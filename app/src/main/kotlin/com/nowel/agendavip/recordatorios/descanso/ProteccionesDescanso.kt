package com.nowel.agendavip.recordatorios.descanso

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Mantiene disponibles los accesos esenciales aunque alguien los marque por equivocación.
 * La pantalla de selección y el servicio comparten las mismas excepciones.
 * No pide permisos de llamadas ni consulta contactos, mensajes o números telefónicos.
 */
object ProteccionesDescanso {

    /**
     * Recibe Android y devuelve las aplicaciones que atienden inicio, llamadas, ajustes y alarmas.
     * Son nombres de paquetes, las direcciones con las que Android identifica cada aplicación.
     */
    @Suppress("DEPRECATION")
    fun paquetesEsenciales(contexto: Context): Set<String> {
        val resultado = mutableSetOf(
            contexto.packageName,
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.emergency",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
        val consultas = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(AlarmClock.ACTION_SET_ALARM),
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
        )
        consultas.forEach { intento ->
            contexto.packageManager.queryIntentActivities(intento, 0).forEach {
                resultado.add(it.activityInfo.packageName)
            }
        }
        runCatching {
            contexto.getSystemService(TelecomManager::class.java).defaultDialerPackage
        }.getOrNull()?.let(resultado::add)
        Telephony.Sms.getDefaultSmsPackage(contexto)?.let(resultado::add)
        return resultado
    }

    /**
     * Devuelve true para una aplicación esencial conocida o un componente protegido de Android.
     * El servicio repite esta comprobación para no depender únicamente del filtro de la pantalla.
     */
    fun esEsencial(contexto: Context, paquete: String): Boolean {
        return esComponenteProtegido(paquete) || paquete in paquetesEsenciales(contexto)
    }

    /** Protege componentes de fabricantes que pueden tener nombres distintos en cada teléfono. */
    fun esComponenteProtegido(paquete: String): Boolean {
        val nombre = paquete.lowercase()
        return listOf(
            ".systemui", ".settings", ".permissioncontroller", ".packageinstaller",
            ".incallui", ".emergency", ".launcher", ".deskclock", ".inputmethod", ".dialer",
        ).any(nombre::contains)
    }
}
