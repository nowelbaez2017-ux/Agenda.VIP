package com.nowel.agendavip.datos.descanso

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.dominio.descanso.EstadoNocheDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Guarda opciones y hasta sesenta noches en el espacio privado de la persona que está dentro.
 * SharedPreferences es un archivo de Android con campos pequeños; JSON guarda los campos juntos.
 * Configuración modifica las opciones. Las alarmas y el servicio anotan las decisiones de cada noche.
 * Todas las operaciones esperan el mismo turno que un cambio de cuenta para no mezclar personas.
 */
class RepositorioDescanso(contexto: Context) {

    private val espacio = EspacioPersonal(contexto)

    val propietario: String = espacio.clave

    private val preferencias
        get() = espacio.preferencias(NOMBRE_ARCHIVO)

    /**
     * Devuelve las opciones de esta persona, o los valores iniciales si nunca las guardó.
     * Un archivo dañado produce una explicación y se conserva para poder recuperarlo.
     */
    fun configuracion(): ConfiguracionDescanso = synchronized(EspacioPersonal.cerrojoSesion) {
        espacio.exigirVigente()
        val texto = preferencias.getString(CLAVE_CONFIGURACION, null)
            ?: return@synchronized ConfiguracionDescanso()

        try {
            leerConfiguracion(texto)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudo leer la configuración de Descanso. Sus datos se conservaron.",
                error,
            )
        }
    }

    /**
     * Recibe las opciones revisadas por Guardar y confirma su escritura antes de programar avisos.
     * No concede permisos ni borra las decisiones de una noche en curso.
     */
    fun guardarConfiguracion(configuracion: ConfiguracionDescanso) =
        synchronized(EspacioPersonal.cerrojoSesion) {
            espacio.exigirVigente()
            ReglasDescanso.validar(configuracion)
            this.configuracion()
            leerNoches()
            guardarTexto(CLAVE_CONFIGURACION, escribirConfiguracion(configuracion).toString())
        }

    /**
     * Recibe el día de inicio y devuelve sus decisiones. Una noche nueva empieza sin pausas ni avisos.
     * Consultar no crea registros; únicamente una acción real se guarda en el historial.
     */
    fun estado(fecha: LocalDate): EstadoNocheDescanso =
        synchronized(EspacioPersonal.cerrojoSesion) {
            leerNoches().firstOrNull { noche -> noche.fecha == fecha }
                ?: EstadoNocheDescanso(fecha)
        }

    /**
     * Recibe la noche y el instante de la petición; concede como máximo una pausa de cinco minutos.
     * La pausa nunca sobrepasa el final del descanso. Devuelve false si está cancelado, fuera de
     * horario, si la pausa no está permitida o si esa noche ya se utilizó.
     */
    fun pausar(fecha: LocalDate, ahoraMillis: Long): Boolean =
        synchronized(EspacioPersonal.cerrojoSesion) {
            require(ahoraMillis >= 0)
            val opciones = configuracion()
            val ahora = Instant.ofEpochMilli(ahoraMillis).atZone(ZoneId.systemDefault())
            val ventana = ReglasDescanso.ventanaActual(opciones, ahora)
                ?: return@synchronized false
            val noche = estado(fecha)

            if (ventana.fechaNoche != fecha || !opciones.pausaPermitida || noche.cancelada ||
                noche.pausasUsadas >= 1) {
                return@synchronized false
            }

            guardarNoche(
                noche.copy(
                    pausasUsadas = 1,
                    pausaHasta = minOf(ahoraMillis + CINCO_MINUTOS, ventana.fin.toInstant().toEpochMilli()),
                ),
            )
            true
        }

    /**
     * Recibe la fecha del aviso confirmado y suspende únicamente esa noche.
     * Cancelar por esta noche la utiliza; los días futuros siguen conservando su horario.
     */
    fun cancelarNoche(fecha: LocalDate) = synchronized(EspacioPersonal.cerrojoSesion) {
        guardarNoche(estado(fecha).copy(cancelada = true, pausaHasta = 0))
    }

    /**
     * Recibe noche y tipo de aviso; devuelve true solo al registrarlo por primera vez.
     * El receptor guarda antes de hablar para evitar duplicados si Android entrega dos alarmas.
     */
    fun marcarAviso(fecha: LocalDate, previo: Boolean): Boolean =
        synchronized(EspacioPersonal.cerrojoSesion) {
            val noche = estado(fecha)
            val emitido = if (previo) noche.avisoPrevio else noche.avisoInicio

            if (emitido || noche.cancelada) {
                return@synchronized false
            }

            val nueva = if (previo) {
                noche.copy(avisoPrevio = true)
            } else {
                noche.copy(avisoInicio = true)
            }
            guardarNoche(nueva)
            true
        }

    /**
     * Recibe la noche, el instante y la separación mínima entre lecturas de voz.
     * Devuelve true si corresponde hablar; guarda el instante para que abrir repetidamente una
     * aplicación no provoque varias frases seguidas. El valor habitual es una lectura cada cinco minutos.
     */
    fun registrarVoz(
        fecha: LocalDate,
        ahoraMillis: Long,
        intervaloMinimo: Long = CINCO_MINUTOS,
    ): Boolean = synchronized(EspacioPersonal.cerrojoSesion) {
        require(ahoraMillis >= 0 && intervaloMinimo >= 0)
        val noche = estado(fecha)

        if (noche.cancelada || noche.pausaHasta > ahoraMillis ||
            (noche.vozUltima > 0 && ahoraMillis - noche.vozUltima < intervaloMinimo)) {
            return@synchronized false
        }

        guardarNoche(noche.copy(vozUltima = ahoraMillis))
        true
    }

    /**
     * Recibe una cantidad observada de uso de aplicaciones elegidas y la añade a esa noche.
     * El servicio solo entrega tiempo con pantalla activa; este dato no representa horas de sueño.
     * Un límite de dos días protege el archivo de desbordamientos o de tiempos imposibles.
     */
    fun registrarUso(fecha: LocalDate, milisegundos: Long) =
        synchronized(EspacioPersonal.cerrojoSesion) {
            require(milisegundos in 0..MAXIMO_USO)
            if (milisegundos == 0L) {
                return@synchronized
            }

            val noche = estado(fecha)
            guardarNoche(
                noche.copy(
                    milisegundosUso = (noche.milisegundosUso + milisegundos).coerceAtMost(MAXIMO_USO),
                ),
            )
        }

    /** Devuelve las últimas sesenta noches, de la más reciente a la más antigua, para la pantalla. */
    fun historial(): List<EstadoNocheDescanso> = synchronized(EspacioPersonal.cerrojoSesion) {
        leerNoches().sortedByDescending { noche -> noche.fecha }
    }

    /**
     * Recibe una noche actualizada y reemplaza únicamente su registro, conservando las demás.
     * Las operaciones públicas la llaman dentro del turno de sesión; no admite noches duplicadas.
     */
    private fun guardarNoche(noche: EstadoNocheDescanso) {
        validarNoche(noche)
        val noches = (leerNoches().filterNot { anterior -> anterior.fecha == noche.fecha } + noche)
            .sortedByDescending { registrada -> registrada.fecha }
            .take(MAXIMO_NOCHES)
        val lista = JSONArray()
        noches.forEach { registrada ->
            lista.put(escribirNoche(registrada))
        }
        guardarTexto(CLAVE_NOCHES, lista.toString())
    }

    /**
     * Devuelve todos los registros comprobados. Si el archivo está dañado, lo conserva y avisa.
     * La usan las consultas y las actualizaciones antes de reemplazar un único registro.
     */
    private fun leerNoches(): List<EstadoNocheDescanso> {
        espacio.exigirVigente()
        val texto = preferencias.getString(CLAVE_NOCHES, null) ?: return emptyList()

        try {
            return decodificarNoches(texto)
        } catch (error: Exception) {
            throw IllegalStateException(
                "No se pudo leer el historial de Descanso. Sus datos se conservaron.",
                error,
            )
        }
    }

    /**
     * Recibe campo y texto ya comprobados. commit espera la confirmación del guardado.
     * Si Android no confirma, repone el valor anterior en memoria y comunica el problema.
     */
    private fun guardarTexto(clave: String, texto: String) {
        espacio.exigirVigente()
        val anterior = preferencias.getString(clave, null)

        if (!preferencias.edit().putString(clave, texto).commit()) {
            preferencias.edit().putString(clave, anterior).commit()
            error("Android no pudo guardar Descanso. Vuelve a intentarlo.")
        }
    }

    companion object {
        const val NOMBRE_ARCHIVO = "descanso_local"
        private const val CLAVE_CONFIGURACION = "configuracion"
        private const val CLAVE_NOCHES = "noches"
        private const val MAXIMO_NOCHES = 60
        private const val CINCO_MINUTOS = 5 * 60 * 1000L
        private const val MAXIMO_USO = 2 * 24 * 60 * 60 * 1000L

        /**
         * Respaldo entrega los campos de este archivo antes de importar una copia.
         * Comprueba sus nombres, tipos y contenido sin necesitar una cuenta ni modificar datos.
         * Un mapa vacío es válido para una cuenta que todavía no configuró Descanso.
         */
        fun validarCopia(mapa: Map<String, *>) {
            require(mapa.keys.all { clave -> clave == CLAVE_CONFIGURACION || clave == CLAVE_NOCHES }) {
                "El respaldo contiene campos de Descanso no reconocidos."
            }
            mapa[CLAVE_CONFIGURACION]?.let { texto ->
                require(texto is String)
                leerConfiguracion(texto)
            }
            mapa[CLAVE_NOCHES]?.let { texto ->
                require(texto is String)
                decodificarNoches(texto)
            }
            require(mapa.values.none { valor -> valor == null })
        }

        /** Recibe opciones válidas y devuelve su representación con campos explícitos para guardar. */
        private fun escribirConfiguracion(configuracion: ConfiguracionDescanso): JSONObject {
            val dias = JSONArray()
            configuracion.dias.sortedBy { dia -> dia.value }.forEach { dia ->
                dias.put(dia.value)
            }
            val aplicaciones = JSONArray()
            configuracion.aplicaciones.sorted().forEach { paquete ->
                aplicaciones.put(paquete)
            }

            return JSONObject()
                .put("activado", configuracion.activado)
                .put("inicio", configuracion.inicio.toString())
                .put("fin", configuracion.fin.toString())
                .put("dias", dias)
                .put("aplicaciones", aplicaciones)
                .put("mensaje", configuracion.mensaje)
                .put("voz_activa", configuracion.vozActiva)
                .put("restriccion_activa", configuracion.restriccionActiva)
                .put("pausa_permitida", configuracion.pausaPermitida)
        }

        /**
         * Recibe texto JSON y devuelve opciones con tipos y límites comprobados.
         * Se usa tanto al abrir Descanso como al validar una copia de seguridad.
         */
        private fun leerConfiguracion(texto: String): ConfiguracionDescanso {
            val dato = leerJson(texto) as? JSONObject
                ?: error("La configuración de Descanso no contiene un objeto válido.")
            require(listOf("activado", "voz_activa", "restriccion_activa", "pausa_permitida").all { campo ->
                dato.get(campo) is Boolean
            })
            require(listOf("inicio", "fin", "mensaje").all { campo -> dato.get(campo) is String })
            val listaDias = dato.getJSONArray("dias")
            require(listaDias.length() in 1..7)
            val dias = (0 until listaDias.length()).map { indice ->
                val numero = numeroEntero(listaDias.get(indice))
                require(numero in 1L..7L)
                DayOfWeek.of(numero.toInt())
            }
            require(dias.distinct().size == dias.size)
            val listaAplicaciones = dato.getJSONArray("aplicaciones")
            require(listaAplicaciones.length() <= 200)
            val aplicaciones = (0 until listaAplicaciones.length()).map { indice ->
                val paquete = listaAplicaciones.get(indice)
                require(paquete is String)
                paquete
            }
            require(aplicaciones.distinct().size == aplicaciones.size)

            return ConfiguracionDescanso(
                activado = dato.getBoolean("activado"),
                inicio = LocalTime.parse(dato.getString("inicio")),
                fin = LocalTime.parse(dato.getString("fin")),
                dias = dias.toSet(),
                aplicaciones = aplicaciones.toSet(),
                mensaje = dato.getString("mensaje"),
                vozActiva = dato.getBoolean("voz_activa"),
                restriccionActiva = dato.getBoolean("restriccion_activa"),
                pausaPermitida = dato.getBoolean("pausa_permitida"),
            ).also(ReglasDescanso::validar)
        }

        /** Recibe una noche y devuelve sus campos para conservar pausas, avisos y tiempo observado. */
        private fun escribirNoche(noche: EstadoNocheDescanso): JSONObject = JSONObject()
            .put("fecha", noche.fecha.toString())
            .put("pausas_usadas", noche.pausasUsadas)
            .put("pausa_hasta", noche.pausaHasta)
            .put("cancelada", noche.cancelada)
            .put("aviso_previo", noche.avisoPrevio)
            .put("aviso_inicio", noche.avisoInicio)
            .put("voz_ultima", noche.vozUltima)
            .put("milisegundos_uso", noche.milisegundosUso)

        /** Recibe el historial JSON y devuelve noches válidas sin duplicados y con tamaño limitado. */
        private fun decodificarNoches(texto: String): List<EstadoNocheDescanso> {
            val lista = leerJson(texto) as? JSONArray ?: error("El historial no contiene una lista válida.")
            require(lista.length() <= MAXIMO_NOCHES)
            val noches = (0 until lista.length()).map { indice ->
                val dato = lista.getJSONObject(indice)
                require(dato.get("fecha") is String)
                require(listOf("cancelada", "aviso_previo", "aviso_inicio").all { campo ->
                    dato.get(campo) is Boolean
                })
                EstadoNocheDescanso(
                    fecha = LocalDate.parse(dato.getString("fecha")),
                    pausasUsadas = numeroEntero(dato.get("pausas_usadas")).also { cantidad ->
                        require(cantidad in 0L..1L)
                    }.toInt(),
                    pausaHasta = numeroEntero(dato.get("pausa_hasta")),
                    cancelada = dato.getBoolean("cancelada"),
                    avisoPrevio = dato.getBoolean("aviso_previo"),
                    avisoInicio = dato.getBoolean("aviso_inicio"),
                    vozUltima = numeroEntero(dato.get("voz_ultima")),
                    milisegundosUso = numeroEntero(dato.get("milisegundos_uso")),
                ).also(::validarNoche)
            }
            require(noches.map { noche -> noche.fecha }.distinct().size == noches.size)
            return noches
        }

        /** Rechaza pausas y tiempos imposibles antes de aceptar un registro guardado o importado. */
        private fun validarNoche(noche: EstadoNocheDescanso) {
            require(noche.pausasUsadas in 0..1)
            require(noche.pausaHasta >= 0 && noche.vozUltima >= 0)
            require(noche.milisegundosUso in 0..MAXIMO_USO)
            require(noche.pausaHasta == 0L || noche.pausasUsadas == 1)
        }

        /** Recibe texto y devuelve un solo valor JSON; rechaza texto extra al terminar. */
        private fun leerJson(texto: String): Any {
            require(texto.length <= 200_000)
            val lector = JSONTokener(texto)
            val dato = lector.nextValue()
            require(lector.nextClean() == '\u0000')
            return dato
        }

        /** Recibe un valor JSON y devuelve un entero Long, rechazando decimales o desbordamientos. */
        private fun numeroEntero(valor: Any): Long {
            require(valor is Number)
            require(valor is Int || valor is Long) {
                "Un tiempo de Descanso contiene un número no válido."
            }
            return valor.toLong()
        }
    }
}
