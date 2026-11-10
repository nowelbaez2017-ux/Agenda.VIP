package com.nowel.agendavip.recordatorios.descanso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.time.LocalDate

/**
 * Publica el estado nocturno y permite silenciar la voz sin abandonar el descanso.
 * Cada notificación pertenece a una cuenta. Su versión pública oculta el mensaje personal
 * para que no se lea desde una pantalla bloqueada.
 */
object NotificacionesDescanso {

    const val ID_VOZ = 2147483637
    private const val CANAL_AVISOS = "descanso_avisos"
    private const val CANAL_VOZ = "descanso_voz"
    private const val ID_NOCHE = 37

    /** Prepara canales separados para el aviso nocturno y para la lectura temporal del mensaje. */
    private fun preparar(contexto: Context) {
        val avisos = NotificationChannel(CANAL_AVISOS, "Descanso nocturno", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Preparación para descansar y estado del horario nocturno elegido."
        }
        val lectura = NotificationChannel(CANAL_VOZ, "Voz de descanso", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Permite silenciar el mensaje que Agenda.VIP está leyendo."
            setSound(null, null)
        }
        contexto.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(avisos, lectura))
    }

    /**
     * Recibe cuenta, fecha y mensaje ya calculado. Deja un aviso de preparación o de descanso.
     * Nunca abre por sí sola una pantalla sobre otra aplicación: Android decide cómo mostrarla.
     */
    fun publicar(contexto: Context, propietario: String, fecha: LocalDate, mensaje: String, previo: Boolean) {
        if (EspacioPersonal.claveActual(contexto) != propietario) {
            return
        }
        preparar(contexto)
        val aviso = base(contexto, propietario, fecha, CANAL_AVISOS, mensaje)
            .setContentTitle(if (previo) "Tu descanso está por comenzar" else "Es hora de descansar")
            .setOnlyAlertOnce(true)
            .setOngoing(!previo)
            .setAutoCancel(previo)
            .build()
        try {
            contexto.getSystemService(NotificationManager::class.java)
                .notify(etiqueta(propietario), ID_NOCHE, aviso)
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones la capa voluntaria puede seguir trabajando.
        }
    }

    /** Devuelve el aviso obligatorio mientras el servicio convierte texto en voz, sin pedir micrófono. */
    fun lectura(contexto: Context, propietario: String, fecha: LocalDate, mensaje: String): Notification {
        preparar(contexto)
        return base(contexto, propietario, fecha, CANAL_VOZ, mensaje)
            .setContentTitle("Agenda.VIP te recuerda descansar")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Recibe los datos visibles y arma las acciones; Silenciar no modifica la rutina de la noche. */
    private fun base(
        contexto: Context,
        propietario: String,
        fecha: LocalDate,
        canal: String,
        mensaje: String,
    ): Notification.Builder {
        val silenciar = PendingIntent.getBroadcast(
            contexto, 0,
            Intent(contexto, ReceptorDescanso::class.java)
                .setAction(GestorDescanso.SILENCIAR)
                .setData(Uri.parse("agendavip://descanso_silencio/$propietario/$fecha"))
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                .putExtra(GestorDescanso.EXTRA_FECHA, fecha.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publica = Notification.Builder(contexto, canal)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentTitle("Descanso de Agenda.VIP")
            .setContentText("Tienes un aviso personal de descanso.")
            .build()
        return Notification.Builder(contexto, canal)
            .setSmallIcon(R.drawable.icono_notificacion)
            .setContentText(mensaje)
            .setStyle(Notification.BigTextStyle().bigText(mensaje))
            .setContentIntent(abrir(contexto, propietario))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publica)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "Silenciar aviso", silenciar).build())
    }

    /** Construye la apertura de Descanso indicando su cuenta para rechazar notificaciones antiguas. */
    private fun abrir(contexto: Context, propietario: String): PendingIntent {
        return PendingIntent.getActivity(
            contexto, 0,
            Intent()
                .setClassName(contexto.packageName, "com.nowel.agendavip.presentacion.descanso.ActividadDescanso")
                .setData(Uri.parse("agendavip://descanso_configuracion/$propietario"))
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, propietario)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** El fin de horario y el cierre de sesión retiran solo el aviso nocturno de esa cuenta. */
    fun cancelar(contexto: Context, propietario: String) {
        contexto.getSystemService(NotificationManager::class.java).cancel(etiqueta(propietario), ID_NOCHE)
    }

    /** Devuelve una etiqueta independiente de los avisos de tareas y de Enfoque. */
    private fun etiqueta(propietario: String): String {
        return "$propietario|descanso|noche"
    }
}
