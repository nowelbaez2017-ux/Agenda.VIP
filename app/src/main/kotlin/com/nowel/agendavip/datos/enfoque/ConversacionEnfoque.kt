package com.nowel.agendavip.datos.enfoque

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Un texto conserva quién habló y a qué actividad pertenece; nunca guarda grabaciones. */
data class MensajeVozEnfoque(val rol: String, val texto: String, val momento: Long)

/**
 * Conserva las últimas cien intervenciones de Enfoque en el espacio privado de la cuenta.
 * Recibe Android. El servicio añade frases y la pantalla de respuestas consulta las de su actividad.
 * Los cambios usan el mismo turno que la sesión para impedir cruces con otra cuenta.
 */
class ConversacionEnfoque(contexto: Context) {

    private val espacio = EspacioPersonal(contexto)

    /**
     * Recibe la actividad, quién habló y el texto, y lo guarda sin devolver un resultado.
     * El servicio la llama fuera del trabajo de dibujo. Conserva como máximo 800 caracteres por frase.
     * Si el archivo no puede leerse no lo sustituye por una conversación vacía.
     */
    fun guardar(actividadId: String, rol: String, texto: String) = synchronized(EspacioPersonal.cerrojoSesion) {
        require(rol in setOf("Tú", "Agenda.VIP"))
        val mensajes = leer()
        mensajes.add(JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("actividad", actividadId)
            .put("rol", rol)
            .put("texto", texto.take(800))
            .put("momento", System.currentTimeMillis()))
        val lista = JSONArray()
        mensajes.takeLast(100).forEach { lista.put(it) }
        check(espacio.preferencias("conversacion_enfoque").edit()
            .putString("mensajes", lista.toString()).commit()) {
            "No se pudo guardar el mensaje de voz. Revisa la actividad antes de repetir una decisión."
        }
    }

    /** Recibe una actividad y devuelve únicamente sus mensajes, ordenados como se guardaron. */
    fun listar(actividadId: String): List<MensajeVozEnfoque> = synchronized(EspacioPersonal.cerrojoSesion) {
        leer().filter { it.getString("actividad") == actividadId }.map {
            MensajeVozEnfoque(it.getString("rol"), it.getString("texto"), it.getLong("momento"))
        }
    }

    /**
     * Las dos operaciones anteriores leen aquí la lista. Un texto dañado produce un error explícito.
     * No devuelve una lista vacía cuando había datos que no se pudieron abrir.
     */
    private fun leer(): MutableList<JSONObject> {
        try {
            val texto = espacio.preferencias("conversacion_enfoque").getString("mensajes", null)
                ?: return mutableListOf()
            val lista = JSONArray(texto)
            check(lista.length() <= 100)
            return (0 until lista.length()).map { posicion ->
                lista.getJSONObject(posicion).also {
                    check(it.getString("rol") in setOf("Tú", "Agenda.VIP"))
                    check(it.getString("texto").length <= 800)
                    it.getString("actividad")
                    it.getLong("momento")
                }
            }.toMutableList()
        } catch (error: Exception) {
            throw IllegalStateException("No se pudo leer el historial de voz. Se conservó sin modificar.", error)
        }
    }
}