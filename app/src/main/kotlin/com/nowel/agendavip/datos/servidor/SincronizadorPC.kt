package com.nowel.agendavip.datos.servidor

import android.content.Context
import android.content.Intent
import android.os.Build
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.cuentas.SesionLocal
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.dominio.agenda.EstadoOcurrencia
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intercambia tareas con una computadora que la persona ya autorizó.
 * El teléfono mantiene la agenda y sus alarmas. El servidor entrega órdenes, y la app
 * confirma cuáles pudo aplicar. Las peticiones de red se hacen fuera del turno de la sesión.
 */
object SincronizadorPC {

    const val ACCION_ACTUALIZACION = "com.nowel.agendavip.ACTUALIZACION_PC"

    // Una cola evita que los toques y el trabajo de Android compitan por la misma conexión.
    private val trabajador = Executors.newSingleThreadExecutor()
    private val solicitada = AtomicBoolean(false)
    private val turnoConexion = Any()

    /**
     * Recibe Android y solicita una sincronización sin detener la pantalla.
     * El ciclo visible y los cambios de sesión la usan. Agrupa solicitudes repetidas.
     */
    fun solicitar(contexto: Context) {
        if (!solicitada.compareAndSet(false, true)) {
            return
        }

        val aplicacion = contexto.applicationContext
        trabajador.execute {
            try {
                sincronizar(aplicacion)
            } catch (_: Exception) {
                // El resultado se conserva en Mi computadora. Las tareas locales siguen guardadas.
            } finally {
                solicitada.set(false)
            }
        }
    }

    /**
     * Recibe la dirección y el código que aparece en el panel. La pantalla de conexión la
     * ejecuta en segundo plano tras confirmar el permiso. Guarda el vínculo y envía la agenda.
     */
    fun vincular(
        contexto: Context,
        direccion: String,
        codigoCompleto: String,
        propietarioEsperado: String? = null,
    ): Unit = synchronized(turnoConexion) {
        val aplicacion = contexto.applicationContext
        val repositorio = synchronized(EspacioPersonal.cerrojoSesion) {
            check(propietarioEsperado == null || EspacioPersonal.claveActual(aplicacion) == propietarioEsperado) {
                "La cuenta cambió mientras leías el QR. Vuelve a abrir Mi computadora."
            }
            RepositorioConexionPC(aplicacion).also {
                check(it.leer() == null) { "Desconecta primero la computadora que ya está vinculada." }
            }
        }
        val servidor = ClientePC.validarDireccion(direccion)
        val partes = codigoCompleto.trim().split('.')
        require(
            partes.size == 3 && partes[0] == "AVIP1" &&
                partes[1].matches(Regex("[0-9a-fA-F]{64}")) &&
                partes[2].matches(Regex("[A-Za-z0-9_-]{32,128}")),
        ) { "Copia el código completo de vinculación que muestra el panel." }

        val huella = partes[1].lowercase()
        val dispositivo = RepositorioConexionPC.identidadDispositivo(aplicacion)
        val peticion = synchronized(EspacioPersonal.cerrojoSesion) {
            repositorio.exigirCuenta()
            val espacio = EspacioPersonal(aplicacion)
            val perfil = PerfilLocal(aplicacion)
            val usuario = SesionLocal(aplicacion).cuentaIdActiva?.let {
                RepositorioCuentas(aplicacion).obtener(it)?.usuario
            } ?: "Agenda sin cuenta"

            JSONObject()
                .put("codigo", partes[2])
                .put("dispositivo_id", dispositivo)
                .put("cuenta_local_id", espacio.clave)
                .put("usuario", usuario)
                .put("nombre", perfil.nombre)
                .put("dispositivo_nombre", Build.MODEL.take(100))
        }
        val respuesta = ClientePC.post(servidor, huella, "/api/vincular", null, peticion, aplicacion)

        synchronized(EspacioPersonal.cerrojoSesion) {
            repositorio.exigirCuenta()
            check(repositorio.leer() == null) { "La conexión cambió mientras vinculabas la computadora." }
            val id = respuesta.getString("vinculo_id")
            val servidorId = respuesta.getString("servidor_id")
            require(UUID.fromString(id).toString() == id && UUID.fromString(servidorId).toString() == servidorId) {
                "La computadora devolvió una identificación incorrecta."
            }
            val token = respuesta.getString("token")
            require(token.length in 32..256) { "La computadora no devolvió una autorización válida." }
            repositorio.guardar(
                EnlacePC(
                    servidor, huella, token, id, dispositivo, servidorId,
                    respuesta.getString("nombre_servidor").take(120),
                ),
            )
        }

        ProgramadorConexionPC.preparar(aplicacion)
        sincronizar(aplicacion)
    }

    /**
     * Recibe los datos leídos del QR. Prueba sus direcciones y confirma la identidad
     * antes de utilizar el permiso temporal para crear una vinculación nueva.
     */
    fun vincularDesdeQR(
        contexto: Context,
        direcciones: List<String>,
        codigoCompleto: String,
        servidorId: String,
    ): Unit = synchronized(turnoConexion) {
        val espacio = EspacioPersonal(contexto)
        val partes = codigoCompleto.trim().split('.')
        require(partes.size == 3 && partes[0] == "AVIP1" &&
            partes[1].matches(Regex("[0-9a-fA-F]{64}"))
        ) { "El QR no contiene una autorización válida de Agenda.VIP." }
        val direccion = localizarServidor(contexto, direcciones, partes[1], servidorId)
        espacio.exigirVigente()
        vincular(contexto, direccion, codigoCompleto, espacio.clave)
    }

    /**
     * Recibe un QR de la PC ya vinculada. Solo acepta su misma identidad y certificado.
     * Actualiza la dirección y sincroniza conservando la llave y los cambios pendientes.
     */
    fun actualizarDesdeQR(
        contexto: Context,
        direcciones: List<String>,
        servidorId: String,
        huella: String,
    ): Unit = synchronized(turnoConexion) {
        val conexion = RepositorioConexionPC(contexto)
        val enlace = conexion.leer() ?: error("Primero vincula una computadora.")
        require(enlace.servidorId == servidorId && enlace.huella.equals(huella, ignoreCase = true)) {
            "Este QR pertenece a otra computadora. Abre el panel de la PC que ya vinculaste."
        }
        val direccion = localizarServidor(contexto, direcciones, enlace.huella, enlace.servidorId)
        conexion.actualizarDireccion(enlace, direccion)
        sincronizar(contexto)
    }

    /**
     * Recibe una dirección escrita por la persona para recuperar una PC que cambió de IP.
     * La identidad y el certificado se comprueban antes de guardar esa dirección.
     */
    fun actualizarDireccion(contexto: Context, direccion: String): Unit = synchronized(turnoConexion) {
        val conexion = RepositorioConexionPC(contexto)
        val enlace = conexion.leer() ?: error("Primero vincula una computadora.")
        val confirmada = localizarServidor(contexto, listOf(direccion), enlace.huella, enlace.servidorId)
        conexion.actualizarDireccion(enlace, confirmada)
        sincronizar(contexto)
    }

    /** El botón de recuperación busca la PC conocida y después solicita los cambios pendientes. */
    fun buscarYReconectar(contexto: Context): Unit = synchronized(turnoConexion) {
        val conexion = RepositorioConexionPC(contexto)
        val enlace = conexion.leer() ?: error("Primero vincula una computadora.")
        try {
            localizarServidor(contexto, listOf(enlace.direccion), enlace.huella, enlace.servidorId)
        } catch (_: ErrorRedPC) {
            recuperarDireccion(contexto, conexion, enlace, forzar = true)
        }
        sincronizar(contexto)
    }

    /**
     * Recibe candidatos del QR o de la red y devuelve el primero que confirma certificado e identidad.
     * La consulta pública no lleva la llave de la cuenta. Una PC desconocida nunca recibe esa llave.
     */
    private fun localizarServidor(
        contexto: Context,
        direcciones: List<String>,
        huella: String,
        servidorId: String,
    ): String {
        require(UUID.fromString(servidorId).toString() == servidorId) {
            "La identidad del servidor no es válida."
        }
        for (candidata in direcciones.distinct().take(8)) {
            comprobarInterrupcion()
            try {
                val direccion = ClientePC.validarDireccion(candidata)
                val identidad = ClientePC.consultarServidor(direccion, huella, contexto)
                if (identidad.optString("servidor_id") == servidorId) {
                    return direccion
                }
            } catch (_: ErrorRedPC) {
                // Puede ser una tarjeta virtual de la PC. Se intenta la siguiente dirección del QR.
            } catch (_: ErrorConexionPC) {
                // Responder HTTPS no basta: esta dirección debe ofrecer la API de Agenda.VIP.
            } catch (_: IllegalArgumentException) {
                // Una dirección que dejó de pertenecer a la red local no se utiliza.
            }
        }
        throw ErrorRedPC(
            "Tu PC no está disponible en esta Wi-Fi. Revisa que el servidor esté abierto. La vinculación sigue guardada.",
        )
    }

    /**
     * Recibe el vínculo original y busca su dirección actual sin eliminar su autorización.
     * Los reintentos automáticos se limitan a una búsqueda por minuto mientras la PC está ausente.
     */
    private fun recuperarDireccion(
        contexto: Context,
        conexion: RepositorioConexionPC,
        enlace: EnlacePC,
        forzar: Boolean,
    ): EnlacePC {
        synchronized(EspacioPersonal.cerrojoSesion) {
            exigirConexion(conexion, enlace)
            if (!conexion.permitirBusqueda(forzar)) {
                throw ErrorRedPC("La PC sigue sin responder. Puedes pulsar Buscar mi PC de nuevo al regresar.")
            }
            conexion.registrarEstado("Buscando de nuevo la computadora vinculada…")
        }
        val candidatas = BuscadorConexionPC.buscar(contexto, enlace.servidorId)
        val direccion = localizarServidor(contexto, candidatas, enlace.huella, enlace.servidorId)
        return conexion.actualizarDireccion(enlace, direccion)
    }

    /**
     * La pantalla y Android la ejecutan fuera del dibujo. Recibe solo el acceso a la app.
     * Publica la agenda local, recibe órdenes y confirma resultados. Reintentar no duplica
     * una operación: cada orden conserva una identidad y su avance en el diario privado.
     */
    fun sincronizar(contexto: Context) = synchronized(turnoConexion) {
        val aplicacion = contexto.applicationContext
        val sesion = synchronized(EspacioPersonal.cerrojoSesion) {
            if (EspacioPersonal.claveActual(aplicacion) == null) {
                null
            } else {
                val conexion = RepositorioConexionPC(aplicacion)
                conexion.leer()?.let { enlace ->
                    SesionConexion(
                        EspacioPersonal(aplicacion), conexion, enlace,
                        RepositorioTareas(aplicacion), GestorRecordatorios(aplicacion),
                    )
                }
            }
        } ?: return@synchronized

        val espacio = sesion.espacio
        val conexion = sesion.conexion
        var enlace = sesion.enlace
        val tareas = sesion.tareas
        val gestor = sesion.gestor
        var respaldoSolicitado: String? = null

        try {
            // Una vuelta recibe cambios. La siguiente comunica el resultado y entrega el respaldo.
            repeat(3) {
                comprobarInterrupcion()
                val peticion = synchronized(EspacioPersonal.cerrojoSesion) {
                    exigirConexion(conexion, enlace)
                    aplicarPendientes(aplicacion, conexion, tareas, gestor)
                    crearPeticion(aplicacion, conexion, tareas)
                }

                val solicitud = respaldoSolicitado
                if (solicitud != null) {
                    try {
                        val documento = synchronized(EspacioPersonal.cerrojoSesion) {
                            exigirConexion(conexion, enlace)
                            CopiaSeguridadLocal(aplicacion).documentoParaServidor()
                        }
                        peticion.put(
                            "respaldo",
                            JSONObject().put("solicitud_id", solicitud).put("documento", documento),
                        )
                    } catch (_: Exception) {
                        peticion.put(
                            "error_respaldo",
                            JSONObject().put("solicitud_id", solicitud)
                                .put("mensaje", "No se pudo preparar el respaldo en el teléfono."),
                        )
                    }
                }

                synchronized(EspacioPersonal.cerrojoSesion) {
                    exigirConexion(conexion, enlace)
                    comprobarInterrupcion()
                }
                val respuesta = try {
                    ClientePC.post(
                        enlace.direccion, enlace.huella, "/api/sincronizar", enlace.token, peticion, aplicacion,
                    )
                } catch (_: ErrorRedPC) {
                    enlace = recuperarDireccion(aplicacion, conexion, enlace, forzar = false)
                    ClientePC.post(
                        enlace.direccion, enlace.huella, "/api/sincronizar", enlace.token, peticion, aplicacion,
                    )
                }
                val ordenes = respuesta.getJSONArray("ordenes")

                synchronized(EspacioPersonal.cerrojoSesion) {
                    exigirConexion(conexion, enlace)
                    conexion.confirmarRecibidas(peticion.getJSONArray("confirmaciones"))
                    conexion.recibir(ordenes)
                    aplicarPendientes(aplicacion, conexion, tareas, gestor)
                    conexion.registrarEstado(
                        if (conexion.confirmaciones().length() == 0) {
                            "Agenda sincronizada con ${enlace.nombreServidor}."
                        } else {
                            "Cambios revisados en el teléfono. Confirmando el resultado con la PC…"
                        },
                        correcta = true,
                    )
                }

                respaldoSolicitado = respuesta.optString("respaldo_pendiente")
                    .takeUnless { it.isNullOrBlank() || it == "null" }
                if (ordenes.length() == 0 && respaldoSolicitado == null) {
                    return@synchronized
                }
            }
        } catch (error: Exception) {
            synchronized(EspacioPersonal.cerrojoSesion) {
                if (espacio.vigente() && conexion.vigente(enlace)) {
                    val mensaje = if (error is ErrorConexionPC && error.codigo == 401) {
                        "La PC retiró esta autorización. Desconecta y vincula de nuevo si quieres continuar."
                    } else {
                        error.message ?: "No se pudo conectar. Los datos locales permanecen guardados."
                    }
                    conexion.registrarEstado(mensaje.take(300))
                }
            }
            throw error
        }
    }

    /**
     * Recibe Android y elimina únicamente la autorización de la cuenta actual.
     * Mi computadora pide confirmación antes. Las tareas y los avisos del celular se conservan.
     */
    fun desconectar(contexto: Context) {
        synchronized(EspacioPersonal.cerrojoSesion) {
            RepositorioConexionPC(contexto).desconectar()
            ProgramadorConexionPC.retirar(contexto)
        }
    }

    /**
     * Recibe los repositorios de esta cuenta y construye la foto de su estado actual.
     * La versión permite reconocer una edición local posterior a lo que vio el panel.
     */
    private fun crearPeticion(
        contexto: Context,
        conexion: RepositorioConexionPC,
        tareas: RepositorioTareas,
    ): JSONObject {
        val hoy = LocalDate.now()
        val lectura = tareas.leerParaAgenda()
        val lista = JSONArray()

        lectura.tareas.forEach { tarea ->
            val estado = lectura.estadosPorTarea[tarea.id]?.firstOrNull { it.fecha == hoy }
                ?: EstadoOcurrencia(tarea.id, hoy)
            lista.put(fila(tareas, tarea, estado))
        }

        val perfil = PerfilLocal(contexto)
        return JSONObject()
            .put("secuencia", conexion.siguienteSecuencia())
            .put("tareas", lista)
            .put(
                "perfil",
                JSONObject()
                    .put("nombres", perfil.nombres)
                    .put("apellidos", perfil.apellidos)
                    .put("correo", perfil.correo)
                    .put("celular", perfil.celular),
            )
            .put("confirmaciones", conexion.confirmaciones())
    }

    /**
     * Recibe el repositorio, una tarea y su estado de hoy. Devuelve la fila que lee el panel.
     * crearPeticion le entrega el estado de la lectura conjunta; aplicarOrden le entrega
     * el estado de una sola tarea para comprobar la misma marca antes de cambiarla.
     */
    private fun fila(tareas: RepositorioTareas, tarea: Tarea, estado: EstadoOcurrencia): JSONObject {
        val datos = tareas.escribirTarea(tarea)
        val estadoHoy = JSONObject()
            .put("fecha", estado.fecha.toString())
            .put("completada", estado.completada)
            .put("omitida", estado.omitida)
        val version = resumen(datos.toString() + "\n" + estadoHoy.toString())
        return JSONObject()
            .put("id", tarea.id)
            .put("version", version)
            .put("datos", datos)
            .put("estado_hoy", estadoHoy)
    }

    /** Recibe texto y devuelve una marca SHA-256. Cambia cuando cambia la tarea o su estado del día. */
    private fun resumen(texto: String): String {
        return MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /**
     * Recibe los ayudantes de la cuenta y revisa sus órdenes guardadas.
     * Un conflicto significa que la tarea cambió después de verla en la computadora.
     * Los fallos de disco conservan el estado interrumpido para recuperarlo en el siguiente intento.
     */
    private fun aplicarPendientes(
        contexto: Context,
        conexion: RepositorioConexionPC,
        tareas: RepositorioTareas,
        gestor: GestorRecordatorios,
    ) {
        conexion.pendientes().forEach { registro ->
            comprobarInterrupcion()
            try {
                aplicarOrden(registro, conexion, tareas, gestor)
                registro.put("estado", "aplicada").put("mensaje", "Confirmada por el teléfono.")
            } catch (error: ConflictoPC) {
                registro.put("estado", "conflicto").put("mensaje", error.message)
            } catch (error: IllegalArgumentException) {
                registro.put("estado", "error").put("mensaje", error.message ?: "La orden no es válida.")
            } catch (_: org.json.JSONException) {
                registro.put("estado", "error").put("mensaje", "La orden tiene campos incompletos.")
            } catch (_: java.time.format.DateTimeParseException) {
                registro.put("estado", "error").put("mensaje", "La fecha u hora de la orden no es válida.")
            }
            conexion.actualizarRegistro(registro)
            contexto.sendBroadcast(Intent(ACCION_ACTUALIZACION).setPackage(contexto.packageName))
        }
    }

    /**
     * Recibe una orden del diario y utiliza los mismos gestores que los botones del teléfono.
     * Primero conserva el estado aplicando. Si hubo un cierre a mitad, reconoce el resultado
     * esperado en la agenda y termina de recuperar sus alarmas antes de confirmarlo.
     */
    private fun aplicarOrden(
        registro: JSONObject,
        conexion: RepositorioConexionPC,
        tareas: RepositorioTareas,
        gestor: GestorRecordatorios,
    ) {
        val orden = registro.getJSONObject("orden")
        val id = orden.getString("tarea_id")
        require(id.isNotBlank() && id.length <= 500) { "La tarea recibida no tiene una identidad válida." }
        val accion = orden.getString("accion")
        require(accion in listOf("guardar", "eliminar", "completar")) { "La acción recibida no está disponible." }
        val actual = tareas.obtener(id)
        val interrumpida = registro.getString("estado") == "aplicando"

        if (accion == "eliminar" && actual == null) {
            return
        }

        val propuesta = if (accion == "guardar") tareas.leerTarea(orden.getJSONObject("datos")) else null
        require(propuesta == null || propuesta.id == id) { "La orden intenta cambiar la identidad de una tarea." }

        if (interrumpida && propuesta != null && actual == propuesta) {
            gestor.reprogramarTodo()
            return
        }
        val fecha = if (accion == "completar") LocalDate.parse(orden.getString("fecha")) else null
        if (interrumpida && fecha != null && actual != null &&
            tareas.estado(id, fecha).completada == orden.getBoolean("completada")
        ) {
            gestor.reprogramarTodo()
            return
        }

        val base = orden.optString("base_version").takeUnless { it.isNullOrBlank() || it == "null" }
        val versionActual = actual?.let { tarea ->
            val estado = tareas.estado(tarea.id, LocalDate.now())
            fila(tareas, tarea, estado).getString("version")
        }
        if (base != versionActual) {
            throw ConflictoPC("Esta tarea cambió en el celular. Actualiza el panel y revisa la operación.")
        }
        if (accion != "guardar" && actual == null) {
            throw ConflictoPC("La tarea ya no está en el celular.")
        }

        if (propuesta?.modoMusica == ModoMusica.SELECCIONADA) {
            require(actual?.musicaUri == propuesta.musicaUri) {
                "La canción debe elegirse en el celular. El panel puede conservar la que ya estaba seleccionada."
            }
        }

        registro.put("estado", "aplicando")
        conexion.actualizarRegistro(registro)
        when (accion) {
            "guardar" -> gestor.guardar(requireNotNull(propuesta))
            "eliminar" -> gestor.eliminar(id, requireNotNull(actual).fecha, serie = true)
            "completar" -> {
                require(fecha == LocalDate.now()) { "Vuelve a consultar el día actual antes de completar." }
                gestor.marcarCompletada(id, requireNotNull(fecha), orden.getBoolean("completada"))
            }
        }
    }

    /** Comprueba el enlace después de esperar una respuesta y antes de tocar la cuenta. */
    private fun exigirConexion(conexion: RepositorioConexionPC, enlace: EnlacePC) {
        check(conexion.vigente(enlace)) { "La cuenta o la conexión cambiaron. Vuelve a abrir Mi computadora." }
    }

    /** Detiene un turno retirado por Android antes de empezar otra petición o cambio local. */
    private fun comprobarInterrupcion() {
        if (Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException("Android pospuso la conexión con la computadora.")
        }
    }

    /** Mantiene juntos los ayudantes creados bajo el mismo turno para una sola cuenta y vínculo. */
    private class SesionConexion(
        val espacio: EspacioPersonal,
        val conexion: RepositorioConexionPC,
        val enlace: EnlacePC,
        val tareas: RepositorioTareas,
        val gestor: GestorRecordatorios,
    )

    /** Distingue una edición simultánea de un fallo de conexión o de disco. */
    private class ConflictoPC(mensaje: String) : IllegalStateException(mensaje)
}
