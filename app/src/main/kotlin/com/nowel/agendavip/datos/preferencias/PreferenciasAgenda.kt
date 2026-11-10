package com.nowel.agendavip.datos.preferencias

import android.content.Context
import com.nowel.agendavip.datos.cuentas.EspacioPersonal

/**
 * Recuerda las opciones que se eligen en Configuración dentro de Agenda.VIP.
 * SharedPreferences es el archivo pequeño y privado que Android proporciona para opciones.
 * Las pantallas leen aquí los valores por defecto; las tareas ya guardadas conservan sus datos.
 */
class PreferenciasAgenda(contexto: Context) {

    private val espacio = EspacioPersonal(contexto)

    private val opciones
        get() = espacio.preferencias("preferencias_agenda")

    /** Indica si las tareas nuevas empiezan con seguimiento; no cambia las tareas existentes. */
    val seguimientoPredeterminado: Boolean
        get() = opciones.getBoolean("seguimiento_predeterminado", false)

    /** Minutos que se propondrán entre avisos al crear una tarea nueva. */
    val intervaloMinutos: Int
        get() = opciones.getInt("intervalo_minutos", 5).coerceIn(1, 1440)

    /** Cantidad total propuesta, contando el primer aviso, para una tarea nueva. */
    val limiteAvisos: Int
        get() = opciones.getInt("limite_avisos", 3).coerceIn(1, 20)

    /** Volumen propio de la app: 0 es silencio y 1 es el nivel que permita el teléfono. */
    val volumenAvisos: Float
        get() = opciones.getFloat("volumen_avisos", 1f).coerceIn(0f, 1f)

    /** Nombre interno de la voz instalada elegida; null permite elegir automáticamente una española. */
    val nombreVoz: String?
        get() = opciones.getString("nombre_voz", null)

    /** Etiqueta sencilla que se muestra en la fila Voz, sin exponer el nombre interno del programa. */
    val etiquetaVoz: String
        get() = opciones.getString("etiqueta_voz", "Español · Automática").orEmpty()

    /**
     * Recibe el estado del interruptor de Configuración y lo recuerda.
     * No devuelve datos; Nueva tarea consultará la opción cuando se abra un formulario nuevo.
     */
    fun guardarSeguimiento(activado: Boolean) {
        opciones.edit().putBoolean("seguimiento_predeterminado", activado).apply()
    }

    /**
     * Recibe minutos entre 1 y 1440 y los guarda como propuesta para tareas nuevas.
     * La configuración llama aquí después de comprobar lo escrito; no cambia alarmas existentes.
     */
    fun guardarIntervalo(minutos: Int) {
        require(minutos in 1..1440)
        opciones.edit().putInt("intervalo_minutos", minutos).apply()
    }

    /**
     * Recibe entre 1 y 20 avisos en total y recuerda ese límite por defecto.
     * El primer aviso cuenta dentro del límite, igual que en el formulario de cada tarea.
     */
    fun guardarLimite(cantidad: Int) {
        require(cantidad in 1..20)
        opciones.edit().putInt("limite_avisos", cantidad).apply()
    }

    /**
     * Recibe el volumen del control de Configuración, entre 0 y 1.
     * Guarda un nivel propio de Agenda.VIP; no mueve el volumen general de otras aplicaciones.
     */
    fun guardarVolumen(nivel: Float) {
        require(nivel.isFinite() && nivel in 0f..1f)
        opciones.edit().putFloat("volumen_avisos", nivel).apply()
    }

    /**
     * Recibe el nombre interno y la etiqueta visible de una voz instalada elegida por el usuario.
     * El lector de mensajes usa el nombre; Configuración muestra la etiqueta.
     * Recibir null vuelve a la selección automática y no descarga ninguna voz.
     */
    fun guardarVoz(nombre: String?, etiqueta: String) {
        opciones.edit()
            .putString("nombre_voz", nombre)
            .putString("etiqueta_voz", etiqueta)
            .apply()
    }
}