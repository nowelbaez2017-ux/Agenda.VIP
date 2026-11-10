package com.nowel.agendavip.datos.agenda

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.RegistroHistorialTarea
import com.nowel.agendavip.dominio.agenda.mismaVersionDeAgenda
import com.nowel.agendavip.dominio.agenda.Tarea
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Representa un fallo al leer o guardar la agenda. Recibe el mensaje y, si existe, el error original.
 * El repositorio lo lanza y las pantallas pueden mostrar su mensaje sin borrar lo que estaba guardado.
 */
class ErrorDatosAgenda(
    mensaje: String,
    causa: Throwable? = null
) : IllegalStateException(mensaje, causa)

/**
 * Contiene lo que se leyó de la agenda en un solo momento. Sus estados están agrupados
 * por id de tarea, y el historial solo incluye los días que pidió la pantalla.
 */
internal data class LecturaAgenda(
    val tareas: List<Tarea>,
    val estadosPorTarea: Map<String, List<EstadoOcurrencia>>,
    val historial: List<RegistroHistorialTarea>,
)

/**
 * Lee y guarda las tareas en un archivo privado de este teléfono, mediante el acceso a Android recibido.
 * El archivo usa JSON: texto organizado en campos y listas para guardar los datos y leerlos después.
 * Las pantallas leen aquí su agenda y el gestor de recordatorios guarda tareas y cambios de cada día.
 * Antes de escribir se lee lo anterior; si está dañado se informa del error y se conserva ese contenido.
 */
class RepositorioTareas(context: Context) {

    private val espacio = EspacioPersonal(context)

    // La propiedad consulta el archivo de la misma cuenta y rechaza una sesión antigua.
    private val preferencias
        get() = espacio.preferencias("agenda_local")

    /** Mantiene juntas la lista de tareas y las decisiones guardadas para cada fecha concreta. */
    private data class Documento(
        val tareas: List<Tarea>,
        val estados: List<EstadoOcurrencia>,
        val historial: List<RegistroHistorialTarea> = emptyList()
    )

    /**
     * No recibe datos y devuelve las tareas guardadas, ordenadas por fecha, hora y título.
     * El calendario la llama para mostrar la agenda y el gestor para recuperar sus recordatorios.
     * synchronized permite que una lectura espere si otra operación está usando estos mismos datos.
     */
    fun listar(): List<Tarea> = synchronized(cerrojo) {
        leer().tareas.sortedWith(ordenDeTareas)
    }

    /**
     * Inicio, Agenda y la conexión con la PC llaman esta función para leer el archivo una vez.
     * Devuelve las tareas ordenadas y sus estados agrupados por id, de la misma lectura.
     * Si recibe desde y hasta, incluye los registros de ese período. No cambia el archivo.
     */
    internal fun leerParaAgenda(
        desde: LocalDate? = null,
        hasta: LocalDate? = null,
    ): LecturaAgenda {
        val documento = synchronized(cerrojo) { leer() }
        val registros = if (desde != null && hasta != null) {
            filtrarHistorial(documento.historial, desde, hasta)
        } else {
            emptyList()
        }

        return LecturaAgenda(
            tareas = documento.tareas.sortedWith(ordenDeTareas),
            estadosPorTarea = documento.estados.groupBy { it.tareaId },
            historial = registros,
        )
    }

    /**
     * Detalles llama esta función con el id de la tarea y el día abierto. Devuelve
     * la tarea y su estado de ese día, o null si la tarea ya no existe. Los dos datos
     * salen de la misma lectura para que una edición no se mezcle con un estado anterior.
     */
    internal fun leerDetalle(id: String, fecha: LocalDate): Pair<Tarea, EstadoOcurrencia>? =
        synchronized(cerrojo) {
            val documento = leer()
            val tarea = documento.tareas.firstOrNull { it.id == id } ?: return@synchronized null
            val estado = documento.estados.firstOrNull { it.tareaId == id && it.fecha == fecha }
                ?: EstadoOcurrencia(id, fecha)
            tarea to estado
        }

    /**
     * Recibe [id], el código que distingue una tarea, y devuelve sus datos o null si ya no existe.
     * Los detalles, el formulario de edición y los recordatorios la llaman para recuperar esa tarea.
     * Un documento dañado produce un error, para distinguirlo de una tarea que fue eliminada.
     */
    fun obtener(id: String): Tarea? = synchronized(cerrojo) {
        leer().tareas.firstOrNull { it.id == id }
    }

    /**
     * Recibe una [tarea], revisa sus datos y la añade o sustituye la que tenga el mismo código.
     * El gestor de recordatorios la llama al guardar el formulario; conserva las decisiones
     * de cada fecha, como haber completado ayer. No devuelve un resultado.
     */
    fun guardar(tarea: Tarea) = synchronized(cerrojo) {
        validar(tarea)
        val documento = leer()
        val anterior = documento.tareas.firstOrNull { it.id == tarea.id }
        val historial = if (anterior != null && anterior != tarea) {
            archivarAntesDeCambiar(documento, anterior)
        } else {
            documento.historial
        }
        val tareas = documento.tareas.filterNot { it.id == tarea.id } + tarea
        escribir(documento.copy(tareas = tareas, historial = historial))
    }

    /**
     * Recibe el [id] de una tarea y retira su programación y sus estados activos.
     * Antes conserva el historial que ya corresponde al pasado o a decisiones reales.
     * El gestor la llama después de cancelar alarmas: eliminar una rutina no borra lo que ocurrió.
     * No conserva futuras fechas únicamente programadas ni devuelve un resultado.
     */
    fun eliminar(id: String) = synchronized(cerrojo) {
        val documento = leer()
        val tarea = documento.tareas.firstOrNull { it.id == id }
        val historial = tarea?.let { archivarAntesDeCambiar(documento, it) }
            ?: documento.historial
        escribir(
            documento.copy(
                tareas = documento.tareas.filterNot { it.id == id },
                estados = documento.estados.filterNot { it.tareaId == id },
                historial = historial
            )
        )
    }

    /**
     * Recibe el [id] de una tarea y una [fecha] concreta; devuelve cómo va la tarea ese día.
     * Si aún no se guardó nada para esa fecha, devuelve un estado nuevo con sus valores iniciales.
     * El calendario, los detalles y el gestor la usan para saber si está completada o tiene avisos pendientes.
     */
    fun estado(id: String, fecha: LocalDate): EstadoOcurrencia = synchronized(cerrojo) {
        leer().estados.firstOrNull { it.tareaId == id && it.fecha == fecha }
            ?: EstadoOcurrencia(id, fecha)
    }

    /**
     * Recibe el [estado] de una tarea en una fecha concreta y guarda sus decisiones y horas de aviso.
     * El gestor la llama al completar, posponer, detener o programar un recordatorio.
     * Exige que la tarea exista y que el contador no sea negativo; no devuelve un resultado.
     */
    fun guardarEstado(
        estado: EstadoOcurrencia,
        registrarEnHistorial: Boolean = true
    ) = synchronized(cerrojo) {
        require(estado.avisosEmitidos >= 0) {
            "El número de avisos no puede ser negativo."
        }
        val documento = leer()
        val tarea = documento.tareas.firstOrNull { it.id == estado.tareaId }
        require(tarea != null) {
            "La tarea ya no existe."
        }
        val estados = documento.estados.filterNot {
            it.tareaId == estado.tareaId && it.fecha == estado.fecha
        } + estado
        val anterior = documento.estados.firstOrNull {
            it.tareaId == estado.tareaId && it.fecha == estado.fecha
        } ?: EstadoOcurrencia(estado.tareaId, estado.fecha)
        val cambioDeDecision = estado.completada != anterior.completada ||
            estado.omitida != anterior.omitida ||
            estado.seguimientoDetenido != anterior.seguimientoDetenido ||
            estado.pospuestaHasta != anterior.pospuestaHasta
        val avisoRecibido = estado.avisosEmitidos > anterior.avisosEmitidos && estado.ultimaAlerta != null
        val historial = if (registrarEnHistorial && (cambioDeDecision || avisoRecibido)) {
            conservarRegistro(documento.historial, tarea, estado)
        } else {
            documento.historial
        }

        escribir(documento.copy(estados = estados, historial = historial))
    }

    /**
     * Recibe el [id] de una tarea y devuelve los estados que ya se guardaron para sus distintas fechas.
     * El gestor de recordatorios la usa para recuperar avisos tras reiniciar y para cancelar una rutina.
     * Una lista vacía indica que todavía no se guardaron decisiones para esa tarea.
     */
    internal fun estadosDe(id: String): List<EstadoOcurrencia> = synchronized(cerrojo) {
        leer().estados.filter { it.tareaId == id }
    }

    /**
     * Recibe dos fechas y devuelve los registros guardados dentro de ese período.
     * La comprobación del historial usa esta lectura sin cambiar las tareas de la cuenta.
     */
    fun historialEntre(desde: LocalDate, hasta: LocalDate): List<RegistroHistorialTarea> =
        synchronized(cerrojo) {
            filtrarHistorial(leer().historial, desde, hasta)
        }

    /**
     * Recibe los registros ya leídos y los límites del período. La lectura de Agenda y
     * historialEntre la usan para devolver las mismas filas en el mismo orden.
     */
    private fun filtrarHistorial(
        registros: List<RegistroHistorialTarea>,
        desde: LocalDate,
        hasta: LocalDate,
    ): List<RegistroHistorialTarea> {
        require(!hasta.isBefore(desde)) { "El período del historial no es válido." }
        return registros.filter { it.estado.fecha in desde..hasta }
            .sortedWith(
                compareBy<RegistroHistorialTarea> { it.estado.fecha }
                    .thenBy { it.tarea.hora }
                    .thenBy { it.registradoEn }
            )
    }

    /**
     * Recibe los datos anteriores y la tarea que va a cambiar o eliminarse.
     * Guarda sus días pasados antes de perder el nombre, la hora o los días originales.
     * Para hoy solo conserva lo ya vencido o una decisión real; las alarmas futuras no crean historia.
     * Devuelve la lista nueva. Guardar y eliminar la escriben junto al cambio, en el mismo documento.
     */
    private fun archivarAntesDeCambiar(documento: Documento, tarea: Tarea): List<RegistroHistorialTarea> {
        val ahora = System.currentTimeMillis()
        val hoy = LocalDate.now()
        val estados = documento.estados.filter { it.tareaId == tarea.id }.associateBy { it.fecha }
        val historial = documento.historial.toMutableList()
        val fechasGuardadas = historial.filter { it.tarea.id == tarea.id }
            .mapTo(mutableSetOf()) { it.estado.fecha }

        // Evita recorrer una fecha dañada de millones de años sin advertir el problema.
        require(ChronoUnit.DAYS.between(tarea.fecha, hoy) <= 100_000L) {
            "La fecha inicial está demasiado lejos. Revisa esa tarea antes de cambiarla."
        }

        var fecha = tarea.fecha
        while (fecha.isBefore(hoy)) {
            if (tarea.correspondeA(fecha) && fecha !in fechasGuardadas) {
                val estado = estados[fecha] ?: EstadoOcurrencia(tarea.id, fecha)
                historial.add(RegistroHistorialTarea(tarea, estado.copy(alarmaProgramadaPara = null), ahora))
                fechasGuardadas.add(fecha)
            }
            fecha = fecha.plusDays(1)
        }

        val estadoHoy = estados[hoy] ?: EstadoOcurrencia(tarea.id, hoy)
        val horaHoy = hoy.atTime(tarea.hora).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (tarea.correspondeA(hoy) && (horaHoy <= ahora || tieneHechos(estadoHoy)) &&
            historial.none { mismaVersionDeAgenda(it.tarea, tarea) && it.estado.fecha == hoy }
        ) {
            historial.add(RegistroHistorialTarea(tarea, estadoHoy.copy(alarmaProgramadaPara = null), ahora))
        }

        // Completar una tarea por adelantado sí es una decisión; programarla por adelantado no lo es.
        estados.values.filter { it.fecha.isAfter(hoy) && tieneHechos(it) }.forEach { estado ->
            if (tarea.correspondeA(estado.fecha) && historial.none { mismaVersionDeAgenda(it.tarea, tarea) && it.estado.fecha == estado.fecha }) {
                historial.add(RegistroHistorialTarea(tarea, estado.copy(alarmaProgramadaPara = null), ahora))
            }
        }

        return historial
    }

    /** Recibe el estado de un día y devuelve si ya tiene una entrega o una decisión de la persona. */
    private fun tieneHechos(estado: EstadoOcurrencia): Boolean {
        return estado.completada || estado.omitida || estado.avisosEmitidos > 0 ||
            estado.pospuestaHasta != null || estado.seguimientoDetenido
    }

    /**
     * Recibe la lista anterior, la tarea y una decisión o entrega; devuelve su fotografía actualizada.
     * Para una fecha pasada conserva el nombre y horario ya guardados, aunque la rutina hoy sea distinta.
     * Para hoy permite conservar dos versiones si se editó un aviso que ya había ocurrido.
     * Nunca deja una alarma activa dentro del historial: esa responsabilidad pertenece a estados.
     */
    private fun conservarRegistro(
        anteriores: List<RegistroHistorialTarea>,
        tarea: Tarea,
        estado: EstadoOcurrencia
    ): List<RegistroHistorialTarea> {
        if (!tarea.correspondeA(estado.fecha)) {
            return anteriores
        }

        val mismaFecha = anteriores.withIndex().filter {
            it.value.tarea.id == tarea.id && it.value.estado.fecha == estado.fecha
        }
        val existente = if (estado.fecha.isBefore(LocalDate.now())) {
            mismaFecha.maxByOrNull { it.value.registradoEn }
        } else {
            mismaFecha.lastOrNull { mismaVersionDeAgenda(it.value.tarea, tarea) }
        }
        val fotografia = RegistroHistorialTarea(
            tarea = existente?.value?.tarea ?: tarea,
            estado = estado.copy(alarmaProgramadaPara = null),
            registradoEn = System.currentTimeMillis()
        )
        val resultado = anteriores.toMutableList()

        if (existente == null) {
            resultado.add(fotografia)
        } else {
            resultado[existente.index] = fotografia
        }

        return resultado
    }

    /**
     * Recibe una [tarea] y comprueba título, días, intervalo y cantidad de avisos antes de usarla.
     * [guardar] y [leer] la llaman para rechazar datos incompletos o fuera de los límites permitidos.
     * No devuelve un resultado: si encuentra un problema, lanza un error con la explicación.
     */
    private fun validar(tarea: Tarea) {
        require(tarea.id.isNotBlank()) {
            "La tarea necesita una identidad."
        }
        require(tarea.titulo.isNotBlank()) {
            "Escribe el título de la tarea."
        }
        require(tarea.intervaloMinutos in 1..1440) {
            "El intervalo debe estar entre 1 y 1440 minutos."
        }
        require(tarea.limiteAvisos in 1..20) {
            "El máximo total debe estar entre 1 y 20 avisos, incluido el inicial."
        }
        require(tarea.modoMusica != ModoMusica.SELECCIONADA || !tarea.musicaUri.isNullOrBlank()) {
            "Selecciona una canción o cambia la opción musical del recordatorio."
        }
        require(tarea.modoMusica == ModoMusica.SELECCIONADA || tarea.musicaUri.isNullOrBlank()) {
            "La opción aleatoria o Sin música no debe conservar un archivo seleccionado."
        }
        require(tarea.diasSemana.all { it in 1..7 }) {
            "Los días de la semana no son válidos."
        }
        require(tarea.repeticion != Repeticion.DIAS_SEMANA || tarea.diasSemana.isNotEmpty()) {
            "Selecciona al menos un día de la semana."
        }
    }

    /**
     * No recibe datos: lee el texto JSON guardado y devuelve las listas de tareas y estados.
     * Las operaciones públicas del repositorio la usan antes de consultar o cambiar la agenda.
     * Si no hay documento devuelve listas vacías; si está dañado lanza un error para evitar
     * que una escritura posterior sustituya datos reales por una agenda vacía.
     */
    private fun leer(): Documento {
        val contenido = preferencias.getString(CLAVE_DOCUMENTO, null)
            ?: return Documento(emptyList(), emptyList())

        return leerDocumento(contenido)
    }

    /**
     * Recibe el texto de una copia y comprueba tareas, estados e historial sin tocar los datos actuales.
     * La restauración la llama antes de escribir. Devuelve Unit si es válida o lanza un error si no lo es.
     * Una lista historial ausente corresponde a la versión anterior y se acepta como lista vacía.
     */
    fun validarCopia(texto: String) {
        leerDocumento(texto)
    }

    /**
     * Recibe el JSON guardado y devuelve sus listas después de revisar tipos y relaciones.
     * Leer y validarCopia comparten estas comprobaciones; no escribe ni programa recordatorios.
     * El historial lleva sus propias tareas porque puede contener fotografías de tareas eliminadas.
     */
    private fun leerDocumento(contenido: String): Documento {
        try {
            val lector = org.json.JSONTokener(contenido)
            val raiz = lector.nextValue() as? JSONObject
                ?: throw ErrorDatosAgenda("La agenda no contiene un documento válido.")
            require(lector.nextClean() == '\u0000') { "Hay texto sobrante en la agenda." }

            if (entero(raiz, "version", 0) != 1) {
                throw ErrorDatosAgenda("Esta versión de los datos no es compatible. Se conservó la agenda sin modificar.")
            }

            val listaTareas = raiz.getJSONArray("tareas")
            val listaEstados = raiz.getJSONArray("estados")
            val listaHistorial = if (raiz.has("historial")) raiz.getJSONArray("historial") else JSONArray()
            val tareas = (0 until listaTareas.length()).map { leerTarea(listaTareas.getJSONObject(it)) }
            val estados = (0 until listaEstados.length()).map { leerEstado(listaEstados.getJSONObject(it)) }
            val historial = (0 until listaHistorial.length()).map { leerRegistro(listaHistorial.getJSONObject(it)) }

            // Un conjunto guarda cada identidad una sola vez y permite encontrarla directamente.
            // Así revisar meses de historial no recorre todas las tareas por cada estado guardado.
            val identidades = tareas.mapTo(HashSet(tareas.size)) { it.id }
            require(identidades.size == tareas.size) { "Hay identidades de tareas duplicadas." }
            require(estados.map { it.tareaId to it.fecha }.distinct().size == estados.size) { "Hay ocurrencias duplicadas." }
            require(estados.all { it.tareaId in identidades }) { "Hay estados sin tarea." }
            require(historial.map { it.tarea to it.estado.fecha }.distinct().size == historial.size) {
                "Hay fotografías repetidas en el historial."
            }
            tareas.forEach { validar(it) }

            return Documento(tareas, estados, historial)
        } catch (error: ErrorDatosAgenda) {
            throw error
        } catch (error: Exception) {
            throw ErrorDatosAgenda(
                "No se pudieron leer los datos locales. Se conservaron sin sobrescribir; no borres los datos de la aplicación.",
                error
            )
        }
    }

    /**
     * Recibe una fotografía guardada y devuelve su tarea y estado después de comprobarlos.
     * LeerDocumento la llama para cada registro, incluso cuando su tarea activa ya fue eliminada.
     */
    private fun leerRegistro(objeto: JSONObject): RegistroHistorialTarea {
        val tarea = leerTarea(objeto.getJSONObject("tarea"))
        val estado = leerEstado(objeto.getJSONObject("estado"))
        val registradoEn = numeroOpcional(objeto, "registradoEn")
            ?: throw IllegalArgumentException("Falta el momento del registro histórico.")
        validar(tarea)
        require(tarea.id == estado.tareaId && tarea.correspondeA(estado.fecha)) {
            "El historial mezcla una tarea con otra fecha."
        }
        require(registradoEn >= 0L && estado.alarmaProgramadaPara == null) {
            "Un registro histórico contiene datos de programación activos."
        }

        return RegistroHistorialTarea(tarea, estado, registradoEn)
    }

    /**
     * Recibe un [documento] con tareas y estados, los convierte en texto JSON y los guarda juntos.
     * Las funciones que cambian la agenda la llaman para terminar su guardado.
     * Espera a que Android confirme la escritura y conserva el texto válido anterior como respaldo.
     * No devuelve un resultado; lanza un error si Android no consigue guardar.
     */
    private fun escribir(documento: Documento) {
        val anterior = preferencias.getString(CLAVE_DOCUMENTO, null)
        val raiz = JSONObject()
            .put("version", 1)
            .put(
                "tareas",
                JSONArray().apply {
                    documento.tareas.forEach {
                        put(escribirTarea(it))
                    }
                }
            )
            .put(
                "estados",
                JSONArray().apply {
                    documento.estados.forEach {
                        put(escribirEstado(it))
                    }
                }
            )
        raiz.put("historial", JSONArray().apply {
            documento.historial.forEach { registro ->
                put(JSONObject()
                    .put("tarea", escribirTarea(registro.tarea))
                    .put("estado", escribirEstado(registro.estado))
                    .put("registradoEn", registro.registradoEn))
            }
        })
        val editor = preferencias.edit()
            .putString(CLAVE_DOCUMENTO, raiz.toString())

        if (anterior != null) {
            editor.putString("respaldo_ultimo_valido", anterior)
        }

        if (!editor.commit()) {
            throw ErrorDatosAgenda(
                "Android no pudo guardar la agenda. Vuelve a intentarlo antes de cerrar la pantalla."
            )
        }
    }

    /**
     * Recibe un [objeto] con los campos JSON de una tarea y devuelve una [Tarea] que la app puede usar.
     * La lectura local y la conexión con la PC la llaman. Algunos campos pueden faltar en datos
     * de una versión anterior; en esos casos usa los valores iniciales indicados aquí.
     */
    internal fun leerTarea(objeto: JSONObject): Tarea {
        val dias = if (objeto.has("diasSemana")) {
            objeto.getJSONArray("diasSemana")
        } else {
            JSONArray()
        }

        val direccionMusica = textoOpcional(objeto, "musicaUri")

        // Las agendas antiguas no distinguían las opciones: una dirección vacía significaba silencio.
        // Se conserva esa decisión; solo las tareas nuevas comienzan con música aleatoria.
        val modoAnterior = if (direccionMusica.isNullOrBlank()) {
            ModoMusica.SIN_MUSICA
        } else {
            ModoMusica.SELECCIONADA
        }
        val modoMusica = ModoMusica.valueOf(cadena(objeto, "modoMusica", modoAnterior.name))

        return Tarea(
            id = cadena(objeto, "id"),
            titulo = cadena(objeto, "titulo"),
            fecha = LocalDate.parse(cadena(objeto, "fecha")),
            hora = LocalTime.parse(cadena(objeto, "hora")),
            repeticion = Repeticion.valueOf(cadena(objeto, "repeticion", Repeticion.UNA_VEZ.name)),
            diasSemana = (0 until dias.length()).map {
                convertirEntero(dias.get(it), "día de la semana")
            }.toSet(),
            vozActiva = booleano(objeto, "vozActiva", true),
            mensajeVoz = cadena(objeto, "mensajeVoz", ""),
            musicaUri = direccionMusica,
            musicaNombre = cadena(objeto, "musicaNombre", ""),
            seguimientoActivo = booleano(objeto, "seguimientoActivo", false),
            intervaloMinutos = entero(objeto, "intervaloMinutos", 5),
            limiteAvisos = entero(objeto, "limiteAvisos", 3),
            mensajeSeguimiento = cadena(objeto, "mensajeSeguimiento", ""),
            modoMusica = modoMusica
        )
    }

    /**
     * Recibe una [tarea] y devuelve sus campos organizados en un objeto JSON listo para guardar.
     * El guardado local y la conexión con la PC la llaman. Las fechas y horas se convierten a textos como
     * «2026-10-27» y «09:30» para que la lectura no dependa del idioma del teléfono.
     */
    internal fun escribirTarea(tarea: Tarea): JSONObject = JSONObject()
        .put("id", tarea.id)
        .put("titulo", tarea.titulo)
        .put("fecha", tarea.fecha.toString())
        .put("hora", tarea.hora.toString())
        .put("repeticion", tarea.repeticion.name)
        .put("diasSemana", JSONArray(tarea.diasSemana.sorted()))
        .put("vozActiva", tarea.vozActiva)
        .put("mensajeVoz", tarea.mensajeVoz)
        .put("musicaUri", tarea.musicaUri ?: JSONObject.NULL)
        .put("musicaNombre", tarea.musicaNombre)
        .put("modoMusica", tarea.modoMusica.name)
        .put("seguimientoActivo", tarea.seguimientoActivo)
        .put("intervaloMinutos", tarea.intervaloMinutos)
        .put("limiteAvisos", tarea.limiteAvisos)
        .put("mensajeSeguimiento", tarea.mensajeSeguimiento)

    /**
     * Recibe un [objeto] JSON y devuelve el estado de una tarea en una fecha concreta.
     * [leer] la llama para recuperar si se completó ese día, sus avisos emitidos y sus horas pendientes.
     * Rechaza un contador negativo para que un dato dañado no cambie la cantidad de recordatorios.
     */
    private fun leerEstado(objeto: JSONObject): EstadoOcurrencia {
        val estado = EstadoOcurrencia(
            tareaId = cadena(objeto, "tareaId"),
            fecha = LocalDate.parse(cadena(objeto, "fecha")),
            completada = booleano(objeto, "completada", false),
            omitida = booleano(objeto, "omitida", false),
            seguimientoDetenido = booleano(objeto, "seguimientoDetenido", false),
            pospuestaHasta = numeroOpcional(objeto, "pospuestaHasta"),
            avisosEmitidos = entero(objeto, "avisosEmitidos", 0),
            ultimaAlerta = numeroOpcional(objeto, "ultimaAlerta"),
            alarmaProgramadaPara = numeroOpcional(objeto, "alarmaProgramadaPara"),
            alarmaEsSeguimiento = booleano(objeto, "alarmaEsSeguimiento", false)
        )
        require(estado.avisosEmitidos >= 0) {
            "Un estado contiene un contador inválido."
        }
        return estado
    }

    /**
     * Recibe el [estado] de una fecha y devuelve sus campos en un objeto JSON listo para guardar.
     * [escribir] la llama para conservar las decisiones y el número de avisos aunque se cierre la app.
     * Los campos sin hora usan el valor vacío propio de JSON, llamado null.
     */
    private fun escribirEstado(estado: EstadoOcurrencia): JSONObject = JSONObject()
        .put("tareaId", estado.tareaId)
        .put("fecha", estado.fecha.toString())
        .put("completada", estado.completada)
        .put("omitida", estado.omitida)
        .put("seguimientoDetenido", estado.seguimientoDetenido)
        .put("pospuestaHasta", estado.pospuestaHasta ?: JSONObject.NULL)
        .put("avisosEmitidos", estado.avisosEmitidos)
        .put("ultimaAlerta", estado.ultimaAlerta ?: JSONObject.NULL)
        .put("alarmaProgramadaPara", estado.alarmaProgramadaPara ?: JSONObject.NULL)
        .put("alarmaEsSeguimiento", estado.alarmaEsSeguimiento)

    /**
     * Recibe un [objeto] JSON y la [clave], es decir, el nombre del campo que quiere leer.
     * Devuelve su texto o null si falta o está vacío en JSON. [leerTarea] la usa para la música:
     * así la ausencia de archivo no se confunde con la palabra escrita «null».
     */
    private fun textoOpcional(objeto: JSONObject, clave: String): String? =
        if (!objeto.has(clave) || objeto.isNull(clave)) {
            null
        } else {
            cadena(objeto, clave)
        }

    /**
     * Recibe un [objeto] y el nombre [clave] de un campo; devuelve su número entero largo o null.
     * [leerEstado] la usa para horas que pueden no existir todavía, como la de una posposición.
     * Si existe un valor que no es entero, lanza un error para no cambiar la hora sin advertirlo.
     */
    private fun numeroOpcional(objeto: JSONObject, clave: String): Long? {
        if (!objeto.has(clave) || objeto.isNull(clave)) {
            return null
        }

        val numero = objeto.get(clave)
        require(numero is Int || numero is Long) {
            "El campo $clave no contiene un entero."
        }
        return (numero as Number).toLong()
    }

    /**
     * Recibe un [objeto], el nombre [clave] del campo y un posible texto [defecto].
     * Devuelve el texto leído o el valor por defecto si el campo falta y ese valor fue indicado.
     * Los lectores de tareas y estados la usan para impedir que un número u otro tipo de dato
     * se acepte como texto: en ese caso lanza un error.
     */
    private fun cadena(objeto: JSONObject, clave: String, defecto: String? = null): String {
        if (!objeto.has(clave) && defecto != null) {
            return defecto
        }

        return objeto.get(clave) as? String
            ?: throw IllegalArgumentException("El campo $clave no contiene texto.")
    }

    /**
     * Recibe un [objeto], el nombre [clave] y el valor [defecto] que usará si no existe el campo.
     * Devuelve un booleano, que significa uno de dos valores: true (sí) o false (no).
     * Los lectores de tareas y estados la usan para opciones como voz activa o tarea completada.
     * Si el campo existe con otro tipo de dato, lanza un error en lugar de cambiar la opción.
     */
    private fun booleano(objeto: JSONObject, clave: String, defecto: Boolean): Boolean {
        if (!objeto.has(clave)) {
            return defecto
        }

        return objeto.get(clave) as? Boolean
            ?: throw IllegalArgumentException("El campo $clave no contiene un booleano.")
    }

    /**
     * Recibe un [objeto], el nombre [clave] y un número [defecto]; devuelve un entero de ese campo.
     * Si falta, usa el valor por defecto. Los lectores de la agenda la llaman para contadores,
     * intervalos y versión del documento; [convertirEntero] rechaza textos y números con decimales.
     */
    private fun entero(objeto: JSONObject, clave: String, defecto: Int): Int =
        if (!objeto.has(clave)) {
            defecto
        } else {
            convertirEntero(objeto.get(clave), clave)
        }

    /**
     * Recibe un [valor] leído de JSON y el [nombre] del campo para explicar un posible error.
     * Devuelve ese número como Int, el entero de Kotlin que usan días, intervalos y contadores.
     * [entero] y [leerTarea] la llaman; si el valor tiene otro tipo o no cabe en Int, lanza un error
     * para no sustituir el número guardado por otro distinto.
     */
    private fun convertirEntero(valor: Any, nombre: String): Int {
        require(valor is Int || valor is Long) {
            "El campo $nombre no contiene un entero."
        }
        val numero = (valor as Number).toLong()
        require(numero in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "El campo $nombre está fuera de rango."
        }
        return numero.toInt()
    }

    companion object {
        // Todas las instancias esperan aquí su turno para leer o escribir la agenda sin mezclarse.
        private val cerrojo = Any()
        private val ordenDeTareas = compareBy<Tarea> { it.fecha }
            .thenBy { it.hora }
            .thenBy { it.titulo }
        private const val CLAVE_DOCUMENTO = "documento_v1"
    }
}
