package com.nowel.agendavip.datos.cuentas

import android.app.NotificationManager
import android.content.Context
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.ServicioAviso
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque
import com.nowel.agendavip.recordatorios.enfoque.RecuperadorEnfoque
import com.nowel.agendavip.recordatorios.enfoque.ServicioEnfoque

/**
 * Coordina el paso entre una cuenta, la agenda sin cuenta y la pantalla de acceso.
 * Recibe el acceso a Android. Los botones de registro, inicio y cierre de sesión lo utilizan.
 * Todas sus operaciones se llaman fuera del hilo de pantalla, que es el encargado de dibujar.
 * Así, guardar datos o preparar avisos no deja los botones congelados.
 */
class CoordinadorSesion(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val sesion = SesionLocal(this.contexto)

    /**
     * Recibe la cuenta comprobada por el registro o el inicio de sesión.
     * copiarAgendaInvitada solo es true cuando la persona marcó esa opción al registrarse.
     * Prepara el perfil, cambia la sesión y recupera los avisos de la cuenta seleccionada.
     * No devuelve datos; si no se puede confirmar la entrada, explica el problema mediante un error.
     */
    fun entrar(cuenta: CuentaLocal, copiarAgendaInvitada: Boolean = false) =
        synchronized(EspacioPersonal.cerrojoSesion) {
            comprobarInterrupcion()
            val guardada = RepositorioCuentas(contexto).obtener(cuenta.id)
                ?: throw IllegalStateException("Esta cuenta ya no está disponible en el teléfono.")
            val copia = CopiaAgendaInvitada(contexto)

            if (copiarAgendaInvitada) {
                copia.preparar(guardada.id)
            }

            copia.terminarPendiente(guardada.id)
            prepararNombre(guardada)
            comprobarInterrupcion()
            detenerEspacioAnterior()

            try {
                // Este último control evita entrar si se abandonó el formulario durante el trabajo.
                comprobarInterrupcion()
                sesion.abrirCuenta(guardada.id)
            com.nowel.agendavip.seguridad.BloqueoLocal.autorizar(contexto, guardada.id)
            } catch (error: RuntimeException) {
                recuperarAvisosTrasFallo(error)
                throw error
            }

            recuperarAvisos()
        }

    /**
     * No recibe datos: abre explícitamente la agenda sin cuenta, conservada por separado.
     * El botón Continuar sin cuenta la llama. No permite ver el perfil ni las tareas de una cuenta.
     * Detiene primero los avisos de la sesión anterior y luego recupera los de esta agenda.
     */
    fun entrarComoInvitado() = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarInterrupcion()
        detenerEspacioAnterior()

        try {
            comprobarInterrupcion()
            sesion.abrirInvitado()
        } catch (error: RuntimeException) {
            recuperarAvisosTrasFallo(error)
            throw error
        }

        recuperarAvisos()
    }

    /**
     * No recibe datos: detiene los avisos y retira la sesión activa, sin borrar cuentas ni tareas.
     * El botón Cerrar sesión en Configuración la llama antes de regresar a la pantalla de acceso.
     * Al terminar no queda una agenda activa; sus recordatorios vuelven cuando se entre de nuevo.
     */
    fun cerrarSesion() = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarInterrupcion()
        detenerEspacioAnterior()

        try {
            comprobarInterrupcion()
            sesion.cerrar()
        } catch (error: RuntimeException) {
            recuperarAvisosTrasFallo(error)
            throw error
        }
    }

    /**
     * Recibe la cuenta y usa su nombre inicial únicamente si su perfil todavía no tiene nombre.
     * entrar la llama antes de activar la cuenta. Respeta el nombre de una copia o de un perfil editado.
     * El usuario de acceso y el nombre del perfil son distintos: editar el perfil no cambia el acceso.
     */
    private fun prepararNombre(cuenta: CuentaLocal) {
        val perfil = contexto.getSharedPreferences(
            EspacioPersonal.nombrePreferencias("perfil_local", cuenta.id),
            Context.MODE_PRIVATE,
        )

        val tieneNombre = listOf("nombres", "apellidos", "nombre").any { campo ->
            !perfil.getString(campo, "").isNullOrBlank()
        }

        if (tieneNombre) {
            return
        }

        check(
            perfil.edit()
                .putString("nombres", cuenta.nombreInicial)
                .putString("nombre", cuenta.nombreInicial)
                .commit(),
        ) {
            "La cuenta está creada, pero no se pudo preparar el perfil. Inicia sesión para intentarlo otra vez."
        }
    }

    /**
     * Cancela alarmas, notificaciones y sonido antes de cambiar de persona.
     * Las tres operaciones públicas la llaman bajo el mismo turno que usan los recordatorios.
     * Si una agenda dañada impide enumerar sus alarmas, no bloquea Cerrar sesión: los receptores
     * rechazan cualquier aviso cuya cuenta ya no esté activa. Los datos dañados no se sobrescriben.
     */
    private fun detenerEspacioAnterior() {
        com.nowel.agendavip.datos.servidor.ProgramadorConexionPC.retirar(contexto)
        // Retira la pantalla nocturna y sus alarmas antes de entregar el teléfono a otra cuenta.
        com.nowel.agendavip.recordatorios.descanso.GestorDescanso.detenerPorCambioSesion(contexto)
        com.nowel.agendavip.seguridad.BloqueoLocal.bloquear()
        com.nowel.agendavip.recordatorios.uso.ServicioLimitesUso.detener(contexto)
        if (sesion.hayEntradaActiva) {
            try {
                GestorRecordatorios(contexto).cancelarTodo()
            } catch (_: RuntimeException) {
                // Salir de una cuenta no debe quedar impedido por un archivo de tareas dañado.
            }

            try {
                GestorEnfoque(contexto).cancelarAlarmasActuales()
            } catch (_: RuntimeException) {
                // Se conserva cualquier archivo dañado. Los receptores comprueban además
                // el dueño de la notificación y rechazan la cuenta que ya salió.
            }
        }

        // La detención no depende de poder leer las actividades: tampoco queda una canción
        // o una escucha de la cuenta anterior si su archivo no se pudo consultar.
        ServicioEnfoque.detenerTodo(contexto)
        ServicioAviso.detenerTodo(contexto)
        contexto.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /**
     * Recupera las alarmas de la agenda activa después de entrar o de un cambio de sesión fallido.
     * Si Android no permite programar, guarda el problema para Configuración sin invalidar el acceso.
     * No intenta recuperar nada cuando ya se cerró la sesión.
     */
    private fun recuperarAvisos() {
        if (!sesion.hayEntradaActiva) {
            return
        }

        // Se pide bajo el turno de sesión; el trabajador de Enfoque verificará que la misma
        // persona siga dentro al ejecutarse. No inicia micrófono ni música al entrar.
        RecuperadorEnfoque.solicitar(contexto)
        com.nowel.agendavip.datos.servidor.ProgramadorConexionPC.preparar(contexto)
        com.nowel.agendavip.datos.servidor.SincronizadorPC.solicitar(contexto)

        // Descanso solo recupera una rutina que esta persona ya había activado.
        runCatching {
            com.nowel.agendavip.recordatorios.descanso.GestorDescanso.actualizar(contexto)
        }


        try {
            GestorRecordatorios(contexto).reprogramarTodo()
        } catch (_: RuntimeException) {
            GestorRecordatorios.registrarProblema(
                contexto,
                "No se pudieron recuperar todos los avisos. Revisa los permisos y el estado de tu agenda.",
            )
        }
    }

    /**
     * Las entradas y la salida la llaman si no pudieron confirmar el cambio de sesión.
     * Recibe el fallo original y repone los avisos de la sesión que sigue activa.
     * Si la recuperación también falla, añade ese detalle al error sin sustituir su causa inicial.
     * Una cancelación del trabajo también detendría la recuperación. Por eso aparta temporalmente
     * esa señal, recupera las alarmas y vuelve a dejar la señal al terminar, incluso si hubo un error.
     */
    private fun recuperarAvisosTrasFallo(fallo: RuntimeException) {
        val estabaInterrumpido = Thread.interrupted()

        try {
            recuperarAvisos()
        } catch (problema: RuntimeException) {
            fallo.addSuppressed(problema)
        } finally {
            if (estabaInterrumpido) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Revisa si Android canceló el trabajo porque se salió del formulario o de Configuración.
     * Se llama antes de confirmar una sesión para evitar que termine una entrada abandonada.
     * No devuelve datos: lanza un error si el trabajo debe detenerse.
     */
    private fun comprobarInterrupcion() {
        check(!Thread.currentThread().isInterrupted) {
            "La operación se interrumpió. Puedes volver a intentarlo."
        }
    }
}