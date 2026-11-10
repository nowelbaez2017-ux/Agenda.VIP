package com.nowel.agendavip.recordatorios

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Crea los avisos que se ven en la barra de notificaciones y sus botones.
 * El gestor de recordatorios y el servicio de sonido la usan para una tarea en una fecha concreta.
 * Esa fecha concreta de una rutina se llama ocurrencia; sus botones solo afectan a ese día.
 */
object NotificacionesAviso {

    private const val CANAL_RECORDATORIOS = "agenda_recordatorios"
    private const val CANAL_RESPALDO = "agenda_recordatorios_respaldo"
    private const val CANAL_REPRODUCCION = "agenda_reproduccion"
    private const val CANAL_REGISTROS = "agenda_registros_avisos"
    const val ID_SERVICIO = 2147483646

    /**
     * Recibe [context] para acceder a Android y prepara los grupos de notificaciones de la app.
     * Un canal es un grupo de avisos que la persona puede configurar en Ajustes del teléfono.
     * Las funciones que muestran avisos y el servicio la llaman para tener esos grupos preparados.
     * No devuelve datos. Los canales normales son silenciosos porque el servicio reproduce el
     * audio. El canal de respaldo deja que Android emita un tono si el audio no pudo comenzar.
     */
    fun crearCanales(context: Context) {
        val gestor = context.getSystemService(NotificationManager::class.java)
        val recordatorios = NotificationChannel(
            CANAL_RECORDATORIOS,
            "Recordatorios de tareas",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Tareas pendientes y acciones para completarlas o posponerlas."
            setSound(null, null)
            enableVibration(false)
        }
        val reproduccion = NotificationChannel(
            CANAL_REPRODUCCION,
            "Avisos hablados y música",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Reproducción temporal de la voz instalada y la música local."
            setSound(null, null)
            enableVibration(false)
        }
        val registros = NotificationChannel(
            CANAL_REGISTROS,
            "Registro de avisos activados",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Conserva la hora del último aviso después de completar o cambiar una tarea."
            setSound(null, null)
            enableVibration(false)
        }

        val respaldo = NotificationChannel(
            CANAL_RESPALDO,
            "Tono de respaldo de recordatorios",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Suena cuando no se puede reproducir la voz o la música automáticamente."
            val atributos = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), atributos)
            enableVibration(false)
        }

        gestor.createNotificationChannels(listOf(recordatorios, reproduccion, registros, respaldo))
    }

    /**
     * El servicio la llama al arrancar, antes de leer tareas o esperar el turno de la cuenta.
     * Devuelve un aviso temporal sin datos personales para cumplir inmediatamente la obligación
     * de Android de mostrar que hay un servicio de sonido activo. Después se sustituye por la tarea.
     */
    fun construirArranque(contexto: Context): Notification {
        crearCanales(contexto)
        val aviso = Notification.Builder(contexto, CANAL_REPRODUCCION)
            .setSmallIcon(com.nowel.agendavip.R.drawable.icono_notificacion)
            .setContentTitle("Agenda.VIP")
            .setContentText("Preparando recordatorio…")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= 31) {
            aviso.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return aviso.build()
    }

    /**
     * Recibe acceso a Android en [context], una [tarea] y su [fecha] para publicar el aviso de ese día.
     * [seguimiento] indica que se usa el mensaje de un recordatorio posterior al primero.
     * [requiereToque] usa el tono de respaldo y ofrece escuchar al tocar si no se pudo iniciar
     * o continuar el audio automático. [momentoRecepcion] es la hora ya leída por el gestor
     * o el servicio; pasarla evita otra lectura de los datos mientras se prepara el sonido.
     * No devuelve datos; si falla la publicación, guarda el problema sin interrumpir el audio.
     */
    fun mostrar(
        context: Context,
        tarea: Tarea,
        fecha: LocalDate,
        seguimiento: Boolean,
        requiereToque: Boolean = false,
        propietario: String? = EspacioPersonal.claveActual(context),
        momentoRecepcion: Long? = null,
    ) {
        if (propietario == null) {
            return
        }

        try {
            if (EspacioPersonal.claveActual(context) != propietario) {
                return
            }

            crearCanales(context)
            val sistema = context.getSystemService(NotificationManager::class.java)
            val sinPermiso = Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

            val canalNormal = sistema.getNotificationChannel(CANAL_RECORDATORIOS)
            if (sinPermiso || !sistema.areNotificationsEnabled() ||
                canalNormal?.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                guardarFalloVisual(
                    context,
                    "Los recordatorios visuales están desactivados. Activa las notificaciones y su canal en Ajustes para ver las tareas y sus acciones.",
                    propietario,
                )
                return
            }

            val canalRespaldo = if (requiereToque) {
                sistema.getNotificationChannel(CANAL_RESPALDO)
            } else {
                null
            }
            val canal = if (canalRespaldo != null &&
                canalRespaldo.importance != NotificationManager.IMPORTANCE_NONE &&
                (canalNormal?.importance ?: NotificationManager.IMPORTANCE_DEFAULT) >= NotificationManager.IMPORTANCE_DEFAULT
            ) {
                CANAL_RESPALDO
            } else {
                CANAL_RECORDATORIOS
            }

            val texto = if (requiereToque) {
                "No se pudo reproducir el audio automático. Toca este aviso para escuchar la voz o la música."
            } else {
                textoAviso(tarea, seguimiento)
            }
            val constructor = constructorBase(context, tarea, fecha, canal, propietario)
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto))
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)

            // La hora pertenece a la alarma recibida, no a una muestra de voz ni a la apertura de la app.
            momentoRecepcion?.let { momento ->
                constructor.setWhen(momento)
                constructor.setSubText("Activado: ${formatearMomento(momento)}")
            }

            if (requiereToque) {
                // Tocar permite escuchar y deja disponibles Completar, Posponer y Detener.
                constructor.setContentIntent(
                    ServicioAviso.pendienteEscuchar(context, tarea.id, fecha, seguimiento, propietario)
                )
            }

            agregarAcciones(context, constructor, tarea.id, fecha, propietario)

            synchronized(EspacioPersonal.cerrojoSesion) {
                if (EspacioPersonal.claveActual(context) == propietario) {
                    sistema.notify("$propietario|${tarea.id}|$fecha", 1, constructor.build())
                }
            }
        } catch (error: RuntimeException) {
            Log.w("AgendaVIP", "No se pudo publicar el recordatorio: ${error.javaClass.simpleName}")
            guardarFalloVisual(
                context,
                "No se pudo mostrar la notificación del recordatorio. Revisa sus permisos en Ajustes.",
                propietario,
            )
        }
    }

    /**
     * [mostrar] entrega Android, el [mensaje] y la cuenta [propietario] si falla la publicación.
     * Guarda el diagnóstico en Permisos sin generar otro sonido ni frenar el servicio.
     */
    private fun guardarFalloVisual(contexto: Context, mensaje: String, propietario: String) {
        try {
            GestorRecordatorios.registrarProblema(contexto, mensaje, propietario, notificar = false)
        } catch (error: RuntimeException) {
            Log.w("AgendaVIP", "No se pudo guardar el diagnóstico visual: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Recibe [context], la [tarea], su [fecha] y si el aviso es de [seguimiento].
     * Devuelve una notificación preparada, con texto y botones, para que el servicio pueda usarla.
     * [ServicioAviso] la llama antes de reproducir: Android exige un aviso visible mientras
     * ese servicio mantiene el audio funcionando sin una pantalla abierta.
     */
    fun construirServicio(
        context: Context,
        tarea: Tarea,
        fecha: LocalDate,
        seguimiento: Boolean,
        propietario: String,
    ): Notification {
        check(EspacioPersonal.claveActual(context) == propietario) {
            "La cuenta de este aviso ya no está activa."
        }

        crearCanales(context)
        val constructor = constructorBase(context, tarea, fecha, CANAL_REPRODUCCION, propietario)
            .setContentText(textoAviso(tarea, seguimiento))
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= 31) {
            constructor.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        agregarAcciones(context, constructor, tarea.id, fecha, propietario)
        return constructor.build()
    }

    /**
     * Recibe una [tarea], su [fecha], la hora [momento] del último aviso y su [propietario].
     * El gestor la llama al completar, posponer, detener o editar un aviso que ya se activó.
     * Sustituye su notificación por un registro sin sonido ni botones antiguos; tocarlo abre Detalles.
     * Usa la misma identificación para no añadir una segunda notificación de esa tarea y fecha.
     * No devuelve datos y respeta los avisos que la persona ya retiró deslizando con el dedo.
     */
    fun conservarRegistro(
        contexto: Context,
        tarea: Tarea,
        fecha: LocalDate,
        momento: Long,
        propietario: String
    ) = synchronized(EspacioPersonal.cerrojoSesion) {
        if (EspacioPersonal.claveActual(contexto) != propietario) {
            return@synchronized
        }

        crearCanales(contexto)
        val sistema = contexto.getSystemService(NotificationManager::class.java)
        val etiqueta = "$propietario|${tarea.id}|$fecha"
        val sigueVisible = sistema.activeNotifications.any {
            it.tag == etiqueta && it.id == 1
        }

        if (!sigueVisible) {
            return@synchronized
        }

        // Retira el aviso con acciones antes de sustituirlo por el registro silencioso.
        sistema.cancel(etiqueta, 1)
        val sinPermiso = Build.VERSION.SDK_INT >= 33 && contexto.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED

        if (sinPermiso || !sistema.areNotificationsEnabled() ||
            sistema.getNotificationChannel(CANAL_REGISTROS)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return@synchronized
        }

        val texto = "Activado: ${formatearMomento(momento)}. Toca para ver el recordatorio."
        val registro = constructorBase(contexto, tarea, fecha, CANAL_REGISTROS, propietario)
            .setContentTitle("Aviso activado · ${tarea.titulo}")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_STATUS)
            .setWhen(momento)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()

        try {
            sistema.notify(etiqueta, 1, registro)
        } catch (_: SecurityException) {
            // Si el permiso cambia, el momento del aviso sigue guardado en los detalles de la tarea.
        }
    }

    /**
     * Recibe una hora [momento] en milisegundos y devuelve fecha y hora en formato de doce horas.
     * [mostrar] y [conservarRegistro] la usan para explicar cuándo Android activó el aviso.
     * El resultado sigue la zona horaria del teléfono y no cambia la hora programada.
     */
    private fun formatearMomento(momento: Long): String {
        val local = Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())
        val formato = DateTimeFormatter.ofPattern("dd/MM/yyyy · h:mm a", Locale("es", "DO"))

        return formato.format(local)
    }

    /**
     * Recibe [context], el código [id] y la [fecha] para quitar el aviso visible de ese día.
     * El gestor la llama al eliminar esa fecha o cuando todavía no hay un aviso activado que conservar.
     * No devuelve datos; el gestor detiene el sonido por separado.
     */
    fun cancelar(
        context: Context,
        id: String,
        fecha: LocalDate,
        propietario: String? = EspacioPersonal.claveActual(context),
    ) {
        if (propietario == null) {
            return
        }

        val sistema = context.getSystemService(NotificationManager::class.java)
        sistema.cancel("$propietario|$id|$fecha", 1)

        if (propietario == EspacioPersonal.INVITADO) {
            // Retira también los avisos que se crearon antes de introducir las cuentas.
            sistema.cancel("$id|$fecha", 1)
        }
    }

    /**
     * Recibe Android y el dueño que sale; retira únicamente sus avisos visibles.
     * El gestor la llama al cerrar sesión. No borra tareas ni notificaciones de otras aplicaciones.
     */
    fun cancelarTodo(contexto: Context, propietario: String) {
        val sistema = contexto.getSystemService(NotificationManager::class.java)

        sistema.activeNotifications.forEach { aviso ->
            val etiqueta = aviso.tag.orEmpty()
            val anteriorSinCuenta = propietario == EspacioPersonal.INVITADO &&
                (etiqueta == "estado_recordatorios" || etiqueta.count { it == '|' } == 1)

            if (etiqueta.startsWith("$propietario|") || anteriorSinCuenta) {
                sistema.cancel(aviso.tag, aviso.id)
            }
        }
    }

    /**
     * Recibe el [id] y la [fecha] y devuelve un número calculado a partir de ambos.
     * [constructorBase] y [pendienteAccion] lo usan como código al pedir a Android acciones guardadas.
     * Así las acciones distinguen las fechas además de distinguir la tarea.
     */
    private fun identificador(id: String, fecha: LocalDate): Int =
        (("$id|$fecha".hashCode() and 0x3fffffff) + 100)

    /**
     * Recibe [context], una [tarea], su [fecha] y el [canal] donde se mostrará el aviso.
     * Devuelve un constructor, un objeto al que todavía se le pueden añadir texto y botones.
     * [mostrar] y [construirServicio] lo usan para compartir título, icono y apertura de la agenda.
     * Intent es el mensaje que indica a Android qué abrir; PendingIntent guarda esa petición
     * para ejecutarla después, cuando la persona toque la notificación.
     */
    private fun constructorBase(
        context: Context,
        tarea: Tarea,
        fecha: LocalDate,
        canal: String,
        propietario: String,
    ): Notification.Builder {
        val abrir = Intent("abrir_detalles")
            .setClassName(context.packageName, "com.nowel.agendavip.presentacion.agenda.ActividadAgenda")
            .setData(
                android.net.Uri.parse("agendavip://tarea/$propietario/${android.net.Uri.encode(tarea.id)}/$fecha")
            )
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra("tarea_id", tarea.id)
            .putExtra("fecha_ocurrencia", fecha.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendiente = PendingIntent.getActivity(
            context,
            identificador(tarea.id, fecha),
            abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, canal)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(tarea.titulo)
            .setContentIntent(pendiente)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setShowWhen(true)
    }

    /**
     * Recibe [context], el [constructor] del aviso y el [id] y la [fecha] de su tarea.
     * Añade los botones Completar, Posponer y Detener, conectados al receptor de recordatorios.
     * [mostrar] y [construirServicio] la llaman para ofrecer las mismas acciones.
     * No devuelve datos: añade los botones al constructor recibido.
     */
    private fun agregarAcciones(
        context: Context,
        constructor: Notification.Builder,
        id: String,
        fecha: LocalDate,
        propietario: String,
    ) {
        constructor.addAction(
            Notification.Action.Builder(
                android.R.drawable.checkbox_on_background,
                "Completar",
                pendienteAccion(context, ReceptorRecordatorios.ACCION_COMPLETAR, id, fecha, propietario)
            ).build()
        )
        constructor.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_recent_history,
                "Posponer 10 min",
                pendienteAccion(context, ReceptorRecordatorios.ACCION_POSPONER, id, fecha, propietario)
            ).build()
        )
        constructor.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                "Detener",
                pendienteAccion(context, ReceptorRecordatorios.ACCION_DETENER, id, fecha, propietario)
            ).build()
        )
    }

    /**
     * Recibe [context], la [accion] del botón y el [id] y la [fecha] de la tarea afectada.
     * Devuelve un PendingIntent, la petición que Android conserva hasta que se pulse el botón.
     * [agregarAcciones] la usa para que el receptor sepa qué hacer y sobre qué fecha hacerlo.
     * La dirección añadida al mensaje combina tarea, día y acción para que no se confundan.
     */
    private fun pendienteAccion(
        context: Context,
        accion: String,
        id: String,
        fecha: LocalDate,
        propietario: String,
    ): PendingIntent {
        val intent = Intent(context, ReceptorRecordatorios::class.java)
            .setAction(accion)
            .setData(
                android.net.Uri.parse("agendavip://accion/$propietario/${android.net.Uri.encode(id)}/$fecha/${android.net.Uri.encode(accion)}")
            )
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra("tarea_id", id)
            .putExtra("fecha_ocurrencia", fecha.toString())
        return PendingIntent.getBroadcast(
            context,
            identificador(id, fecha),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Recibe una [tarea] y si se trata de un aviso de [seguimiento]; devuelve el mensaje elegido.
     * [mostrar] y [construirServicio] la llaman para que ambos avisos usen el mismo texto
     * que también se leerá en voz alta, incluida la frase inicial si el campo está vacío.
     */
    private fun textoAviso(tarea: Tarea, seguimiento: Boolean): String =
        tarea.mensajeParaAviso(seguimiento)
}
