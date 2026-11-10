package com.nowel.agendavip.recordatorios.uso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nowel.agendavip.R
import com.nowel.agendavip.dominio.uso.LimiteAplicacion
import com.nowel.agendavip.presentacion.uso.ActividadLimitesUso
import com.nowel.agendavip.datos.cuentas.EspacioPersonal

/**
 * Separa el aviso permanente de supervisión de los mensajes al alcanzar un límite.
 * Ninguna notificación abre otra aplicación, la bloquea ni lee lo que contiene.
 */
object NotificacionesLimitesUso {

    const val ID_SERVICIO = 2147483644
    private const val CANAL_SERVICIO = "uso_supervision"
    private const val CANAL_AVISOS = "uso_limites"

    /** Prepara los grupos de notificaciones; Android permite configurarlos por separado. */
    private fun preparar(contexto: Context) {
        val sistema = contexto.getSystemService(NotificationManager::class.java)
        val servicio = NotificationChannel(
            CANAL_SERVICIO,
            "Supervisión de límites",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Indica que elegiste supervisar el tiempo de tus aplicaciones."
            setSound(null, null)
        }
        val avisos = NotificationChannel(
            CANAL_AVISOS,
            "Límites diarios alcanzados",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Un aviso por aplicación y día al superar el tiempo elegido."
        }
        sistema.createNotificationChannels(listOf(servicio, avisos))
    }

    /** Devuelve el aviso visible del servicio con un botón para pausar la supervisión. */
    fun servicio(contexto: Context, propietario: String, estado: String): Notification {
        preparar(contexto)
        val pausar = PendingIntent.getService(
            contexto,
            0,
            Intent(contexto, ServicioLimitesUso::class.java)
                .setAction(ServicioLimitesUso.ACCION_PAUSAR)
                .setData(android.net.Uri.parse("agendavip://uso_pausar/" + propietario))
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(contexto, CANAL_SERVICIO)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentTitle("Límites de uso activados")
            .setContentText(estado)
            .setContentIntent(abrir(contexto, propietario))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pausar", pausar).build())
            .build()
    }

    /** Publica el mensaje personal del límite; solo permite avisar para la cuenta activa. */
    fun limite(contexto: Context, propietario: String, limite: LimiteAplicacion) {
        if (EspacioPersonal.claveActual(contexto) != propietario) {
            return
        }
        preparar(contexto)
        val aviso = Notification.Builder(contexto, CANAL_AVISOS)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentTitle("Límite de " + limite.nombre + " alcanzado")
            .setContentText(limite.textoAviso())
            .setStyle(Notification.BigTextStyle().bigText(limite.textoAviso()))
            .setContentIntent(abrir(contexto, propietario))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        contexto.getSystemService(NotificationManager::class.java)
            .notify(propietario + "|uso|" + limite.paquete, 1, aviso)
    }

    /** Retira los mensajes de esta cuenta al salir; no afecta a recordatorios ni a otras apps. */
    fun cancelarAvisos(contexto: Context, propietario: String) {
        val sistema = contexto.getSystemService(NotificationManager::class.java)
        sistema.activeNotifications.filter {
            it.tag.orEmpty().startsWith(propietario + "|uso|")
        }.forEach {
            sistema.cancel(it.tag, it.id)
        }
    }

    /** Prepara la apertura de esta pantalla con la identidad de su dueño. */
    private fun abrir(contexto: Context, propietario: String): PendingIntent {
        return PendingIntent.getActivity(
            contexto,
            0,
            Intent(contexto, ActividadLimitesUso::class.java)
                .setData(android.net.Uri.parse("agendavip://uso/" + propietario))
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
