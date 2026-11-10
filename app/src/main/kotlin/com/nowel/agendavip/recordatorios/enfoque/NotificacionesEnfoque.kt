package com.nowel.agendavip.recordatorios.enfoque

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Muestra avisos propios de Enfoque, sin convertirlos en tarjetas de Inicio.
 * Las acciones conservan el código y la cuenta para no actuar sobre otra actividad.
 * No abre una pantalla completa sobre el bloqueo: Android administra la notificación.
 */
object NotificacionesEnfoque {

    const val ID_SERVICIO = 2147483645
    private const val CANAL_AVISOS = "enfoque_avisos"
    private const val CANAL_SESION = "enfoque_sesion"
    private const val ID_AVISO = 91

    /** Recibe Android y crea los grupos de avisos; la voz y la música pertenecen al servicio. */
    fun crearCanales(contexto: Context) {
        val gestor = contexto.getSystemService(NotificationManager::class.java)
        val avisos = NotificationChannel(CANAL_AVISOS, "Avisos de Enfoque", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Hora de empezar y pregunta al terminar el tiempo reservado."
            setSound(null, null)
            enableVibration(false)
        }
        val sesion = NotificationChannel(CANAL_SESION, "Sesiones de Enfoque y voz", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tiempo en curso, música y respuestas por voz activadas por la persona."
            setSound(null, null)
            enableVibration(false)
        }
        gestor.createNotificationChannels(listOf(avisos, sesion))
    }

    /**
     * Recibe una actividad y su dueño y muestra estado y acciones si las notificaciones están permitidas.
     * No modifica el tiempo ni interpreta publicar un aviso como que la actividad se completó.
     */
    fun mostrar(contexto: Context, actividad: ActividadEnfoque, propietario: String) {
        if (EspacioPersonal.claveActual(contexto) != propietario) {
            return
        }
        crearCanales(contexto)
        val sistema = contexto.getSystemService(NotificationManager::class.java)
        val sinPermiso = Build.VERSION.SDK_INT >= 33 && contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        if (sinPermiso || !sistema.areNotificationsEnabled()) {
            return
        }
        val canal = if (actividad.estado in setOf(EstadoEnfoque.EN_CURSO, EstadoEnfoque.PAUSADA)) CANAL_SESION else CANAL_AVISOS
        val aviso = construir(contexto, actividad, propietario, canal, false).build()

        try {
            sistema.notify(etiqueta(propietario, actividad.id), ID_AVISO, aviso)
        } catch (_: SecurityException) {
            // Un permiso puede cambiar entre preparar la notificación y entregarla a Android.
        }
    }

    /**
     * El servicio recibe actividad, cuenta y si la persona activó respuestas por voz.
     * Devuelve el aviso visible que Android exige mientras se reproduce música o se usa el micrófono.
     */
    fun construirServicio(
        contexto: Context,
        actividad: ActividadEnfoque,
        propietario: String,
        vozActiva: Boolean = false,
    ): Notification {
        check(EspacioPersonal.claveActual(contexto) == propietario) { "La sesión de Enfoque cambió." }
        crearCanales(contexto)
        return construir(contexto, actividad, propietario, CANAL_SESION, vozActiva)
            .setOngoing(true)
            .build()
    }

    /**
     * El servicio la usa antes de leer datos o esperar al guardado de otra acción.
     * Devuelve una preparación sin datos personales para cumplir el plazo de arranque de Android.
     */
    fun construirArranque(contexto: Context, vozActiva: Boolean = false): Notification {
        crearCanales(contexto)
        val constructor = Notification.Builder(contexto, CANAL_SESION)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentTitle("Agenda.VIP · Enfoque")
            .setContentText(if (vozActiva) "Preparando respuestas por voz…" else "Preparando aviso de Enfoque…")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= 31) {
            constructor.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return constructor.build()
    }

    /** Retira solo el aviso de una actividad de esa cuenta; no borra datos ni detiene otros avisos. */
    fun cancelar(contexto: Context, id: String, propietario: String) {
        contexto.getSystemService(NotificationManager::class.java).cancel(etiqueta(propietario, id), ID_AVISO)
    }

    /**
     * Prepara texto, reloj y acciones para el estado actual; mostrar y construirServicio la comparten.
     * El reloj visual usa la hora final guardada. La alarma real utiliza además el reloj monotónico.
     */
    private fun construir(
        contexto: Context,
        actividad: ActividadEnfoque,
        propietario: String,
        canal: String,
        vozActiva: Boolean,
    ): Notification.Builder {
        val constructor = Notification.Builder(contexto, canal)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentTitle("Enfoque · ${actividad.titulo}")
            .setContentText(texto(actividad))
            .setStyle(Notification.BigTextStyle().bigText(texto(actividad)))
            .setContentIntent(abrirDetalle(contexto, actividad.id, propietario))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(actividad.estado == EstadoEnfoque.EN_CURSO)
            .setOngoing(actividad.estado == EstadoEnfoque.EN_CURSO)
            .setAutoCancel(false)

        if (actividad.estado == EstadoEnfoque.EN_CURSO && actividad.finEnEpochMs != null) {
            constructor.setWhen(actividad.finEnEpochMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        when (actividad.estado) {
            EstadoEnfoque.PENDIENTE, EstadoEnfoque.ESPERANDO_INICIO -> {
                accion(contexto, constructor, "Comenzar", ReceptorEnfoque.ACCION_INICIAR, actividad.id, propietario)
                accion(contexto, constructor, "En 30 min", ReceptorEnfoque.ACCION_POSPONER, actividad.id, propietario)
                if (!vozActiva) {
                    constructor.addAction(Notification.Action.Builder(null, "Más opciones", abrirDetalle(contexto, actividad.id, propietario)).build())
                }
            }
            EstadoEnfoque.EN_CURSO -> {
                accion(contexto, constructor, "Pausar", ReceptorEnfoque.ACCION_PAUSAR, actividad.id, propietario)
                accion(contexto, constructor, "Completada", ReceptorEnfoque.ACCION_COMPLETAR, actividad.id, propietario)
            }
            EstadoEnfoque.PAUSADA -> {
                accion(contexto, constructor, "Retomar", ReceptorEnfoque.ACCION_CONTINUAR, actividad.id, propietario)
                accion(contexto, constructor, "Completada", ReceptorEnfoque.ACCION_COMPLETAR, actividad.id, propietario)
            }
            EstadoEnfoque.ESPERANDO_RESPUESTA -> {
                accion(contexto, constructor, "Ya terminé", ReceptorEnfoque.ACCION_COMPLETAR, actividad.id, propietario)
                constructor.addAction(Notification.Action.Builder(null, "Continuamos", abrirDetalle(contexto, actividad.id, propietario)).build())
            }
            EstadoEnfoque.COMPLETADA, EstadoEnfoque.CANCELADA -> Unit
        }

        if (vozActiva) {
            constructor.setSubText("Respuestas por voz activadas")
            val apagar = PendingIntent.getService(
                contexto,
                0,
                Intent(contexto, ServicioEnfoque::class.java)
                    .setAction("com.nowel.agendavip.ENFOQUE_APAGAR_VOZ")
                    .setData(Uri.parse("agendavip://enfoque_voz/$propietario/apagar"))
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                    .putExtra("enfoque_id", actividad.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            constructor.addAction(Notification.Action.Builder(null, "Desactivar voz", apagar).build())
        }

        if (Build.VERSION.SDK_INT >= 31) {
            constructor.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return constructor
    }

    /** Recibe la actividad y devuelve una explicación de su estado, usando horas de doce horas. */
    private fun texto(actividad: ActividadEnfoque): String = when (actividad.estado) {
        EstadoEnfoque.PENDIENTE -> "Te recordaré comenzar el ${hora(actividad.avisoEnEpochMs)}. El tiempo aún no está corriendo."
        EstadoEnfoque.ESPERANDO_INICIO -> "Es hora de ${actividad.titulo}. ¿Comenzamos o te lo recuerdo después?"
        EstadoEnfoque.EN_CURSO -> "Tiempo reservado en curso. Puedes pausar o marcar la actividad como completada."
        EstadoEnfoque.PAUSADA -> "Sesión pausada. El tiempo pendiente se conserva."
        EstadoEnfoque.ESPERANDO_RESPUESTA -> "Terminó tu tiempo. Dime, ¿ya terminaste o continuamos?"
        EstadoEnfoque.COMPLETADA -> "Actividad marcada como completada por ti."
        EstadoEnfoque.CANCELADA -> "Actividad cancelada. Su historial se conserva."
    }

    /** Recibe milisegundos y devuelve fecha y hora legibles del teléfono. */
    private fun hora(momento: Long): String = Instant.ofEpochMilli(momento).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM · h:mm a", Locale("es")))

    /** Recibe una acción y la añade con cuenta y actividad para que el receptor pueda comprobarlas. */
    private fun accion(contexto: Context, constructor: Notification.Builder, texto: String, accion: String, id: String, propietario: String) {
        val intento = Intent(contexto, ReceptorEnfoque::class.java)
            .setAction(accion)
            .setData(Uri.parse("agendavip://enfoque_accion/$propietario/$id/${Uri.encode(accion)}"))
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra("enfoque_id", id)
            .putExtra("minutos_enfoque", 30)
        val pendiente = PendingIntent.getBroadcast(
            contexto,
            0,
            intento,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        constructor.addAction(Notification.Action.Builder(null, texto, pendiente).build())
    }

    /** Recibe código y dueño; devuelve la apertura de Detalles sin transportar una copia de los datos. */
    private fun abrirDetalle(contexto: Context, id: String, propietario: String): PendingIntent = PendingIntent.getActivity(
        contexto,
        0,
        Intent().setClassName(contexto.packageName, "com.nowel.agendavip.presentacion.enfoque.ActividadDetalleEnfoque")
            .setData(Uri.parse("agendavip://enfoque_detalle/$propietario/$id"))
            .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
            .putExtra("enfoque_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Distingue avisos de actividades y cuentas, aunque compartan el mismo número de notificación. */
    private fun etiqueta(propietario: String, id: String): String = "$propietario|enfoque|$id"
}