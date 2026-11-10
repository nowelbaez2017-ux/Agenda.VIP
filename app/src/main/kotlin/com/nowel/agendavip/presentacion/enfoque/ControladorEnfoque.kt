package com.nowel.agendavip.presentacion.enfoque

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.dominio.enfoque.ActividadEnfoque
import com.nowel.agendavip.dominio.enfoque.EstadoEnfoque
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.cuenta.ActividadCuenta
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque

/**
 * Coloca las actividades propias de Enfoque dentro de la pestaña principal.
 * Recibe la pantalla y la columna donde dibujar. Inicio no consulta este repositorio.
 * Sus relojes solo dibujan el tiempo: el gestor y Android mantienen la sesión al salir o bloquear.
 */
class ControladorEnfoque(
    private val actividad: Activity,
    private val contenedor: LinearLayout,
) {

    private val repositorio = RepositorioEnfoque(actividad)
    private val gestor = GestorEnfoque(actividad)
    private val principal = Handler(Looper.getMainLooper())
    private var visible = false
    private var cargando = false
    private var contadorSegundos = 0
    private var datos = emptyList<ActividadEnfoque>()
    private val contadores = mutableListOf<Pair<TextView, ActividadEnfoque>>()
    private val trabajo = TrabajadorEnfoque(actividad) { mensaje ->
        cargando = false
        Toast.makeText(actividad, mensaje, Toast.LENGTH_LONG).show()
    }

    private val refrescar = object : Runnable {
        /**
         * Android llama al reloj cada segundo mientras Enfoque está visible. Calcula el texto
         * de los relojes en marcha y solo actualiza los que cambiaron. Cada cinco segundos
         * consulta las actividades para recoger cambios realizados desde una notificación.
         */
        override fun run() {
            if (!visible) {
                return
            }

            contadores.forEach { (vista, dato) ->
                val texto = FormatoEnfoque.contador(gestor.restanteMs(dato))
                if (!TextUtils.equals(vista.text, texto)) {
                    vista.text = texto
                }
            }
            contadorSegundos += 1

            if (contadorSegundos % 5 == 0) {
                consultar()
            }

            principal.postDelayed(this, 1000L)
        }
    }

    /**
     * ActividadAgenda la llama al entrar o volver a Enfoque.
     * Coloca el encabezado y lee las actividades sin congelar los botones.
     */
    fun mostrar() {
        visible = true
        trabajo.activar()
        contadorSegundos = 0
        dibujar()
        consultar()
        principal.removeCallbacks(refrescar)
        principal.postDelayed(refrescar, 1000L)
    }

    /** Lee únicamente actividades propias de la cuenta. Las completadas se consultan en Agenda. */
    private fun consultar() {
        if (!visible || cargando) {
            return
        }

        cargando = true
        trabajo.consultarSincronizado(
            preparar = { gestor.actualizarVencidas() },
            leer = {
                repositorio.listar().filter {
                    it.estado != EstadoEnfoque.COMPLETADA && it.estado != EstadoEnfoque.CANCELADA
                }.sortedWith(
                    compareBy<ActividadEnfoque> { if (it.estado == EstadoEnfoque.EN_CURSO) 0 else 1 }
                        .thenBy { it.avisoEnEpochMs },
                )
            },
            alRecibir = { nuevas ->
                cargando = false

                if (nuevas != datos) {
                    datos = nuevas
                    dibujar()
                }
            },
        )
    }

    /**
     * Dibuja una cabecera breve, Nueva sesión a la derecha y las actividades pendientes.
     * Cada tarjeta abre sus detalles; el historial completo se mantiene en la pestaña Agenda.
     */
    private fun dibujar() {
        contenedor.removeAllViews()
        contadores.clear()
        mostrarPermisosPendientes()
        contenedor.addView(ComponentesAgenda.texto(
            actividad,
            "Decide qué harás y cuánto tiempo le dedicarás. El aviso te pregunta cuándo comenzar.",
            15f,
            R.color.texto_secundario,
        ))
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fila.addView(ComponentesAgenda.texto(actividad, "Tus sesiones", 23f, titulo = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        fila.addView(ComponentesAgenda.boton(actividad, "+ Nueva sesión", true) {
            actividad.startActivity(
                Intent(actividad, ActividadEditarEnfoque::class.java)
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(actividad)),
            )
        }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = ComponentesAgenda.dp(actividad, 12)
                bottomMargin = ComponentesAgenda.dp(actividad, 12)
                marginStart = ComponentesAgenda.dp(actividad, 10)
            }
        })
        contenedor.addView(fila)

        if (datos.isEmpty()) {
            contenedor.addView(ComponentesAgenda.texto(
                actividad,
                "Aún no tienes sesiones pendientes. Crea una actividad, elige cuándo quieres el aviso y los minutos que dedicarás.",
                16f,
            ))
            contenedor.addView(ComponentesAgenda.espacio(actividad, 12))
            contenedor.addView(ComponentesAgenda.texto(
                actividad,
                "Al terminar el tiempo tú decides: Ya terminé o Continuamos. Las sesiones completadas quedan en el historial de Agenda.",
                14f,
                R.color.texto_secundario,
            ))
        }

        datos.forEach { dato ->
            val tarjeta = ComponentesAgenda.tarjeta(actividad, dato.estado == EstadoEnfoque.EN_CURSO)
            tarjeta.addView(ComponentesAgenda.texto(actividad, dato.titulo, 22f, titulo = true))
            tarjeta.addView(ComponentesAgenda.texto(
                actividad,
                FormatoEnfoque.estado(dato.estado),
                14f,
                R.color.texto_secundario,
            ))

            if (dato.estado == EstadoEnfoque.EN_CURSO || dato.estado == EstadoEnfoque.PAUSADA) {
                val contador = ComponentesAgenda.texto(
                    actividad,
                    FormatoEnfoque.contador(gestor.restanteMs(dato)),
                    30f,
                    titulo = true,
                )
                tarjeta.addView(contador)
                if (dato.estado == EstadoEnfoque.EN_CURSO) {
                    contadores.add(contador to dato)
                }
            } else {
                tarjeta.addView(ComponentesAgenda.texto(
                    actividad,
                    "${FormatoEnfoque.fechaHora(dato.avisoEnEpochMs)} · ${FormatoEnfoque.contador(gestor.restanteMs(dato))} de trabajo pendiente",
                    15f,
                ))
            }

            tarjeta.addView(ComponentesAgenda.texto(actividad, "Toca para ver las acciones ›", 13f, R.color.texto_secundario))
            tarjeta.isClickable = true
            tarjeta.isFocusable = true
            tarjeta.contentDescription = "Ver sesión: ${dato.titulo}. ${FormatoEnfoque.estado(dato.estado)}"
            tarjeta.setOnClickListener {
                abrirDetalle(dato.id)
            }
            contenedor.addView(tarjeta)
        }
    }

    /**
     * Revisa los permisos reales de Android y muestra una ayuda breve únicamente si falta alguno.
     * El botón abre Permisos del teléfono; no mezcla estos permisos con las preferencias de la app.
     */
    private fun mostrarPermisosPendientes() {
        val notificaciones = actividad.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val horaExacta = gestor.tieneAlarmasExactas()

        if (notificaciones && horaExacta) {
            return
        }

        val pendientes = buildList {
            if (!notificaciones) {
                add("notificaciones")
            }

            if (!horaExacta) {
                add("avisos a la hora elegida")
            }
        }.joinToString(" y ")
        val ayuda = ComponentesAgenda.tarjeta(actividad)
        ayuda.addView(ComponentesAgenda.texto(
            actividad,
            "Para recibir los avisos, revisa: $pendientes.",
            14f,
        ))
        ayuda.addView(ComponentesAgenda.boton(actividad, "Revisar permisos") {
            actividad.startActivity(
                Intent(actividad, ActividadCuenta::class.java)
                    .putExtra("seccion", "permisos")
                    .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(actividad)),
            )
        })
        contenedor.addView(ayuda)
    }

    /** Recibe el código de actividad y abre sus detalles con la identidad de la cuenta actual. */
    private fun abrirDetalle(id: String) {
        actividad.startActivity(
            Intent(actividad, ActividadDetalleEnfoque::class.java)
                .putExtra("enfoque_id", id)
                .putExtra(EspacioPersonal.EXTRA_ESPACIO, EspacioPersonal.claveActual(actividad)),
        )
    }

    /** Al cambiar de pestaña cancela solo consultas y números visibles; la sesión sigue en Android. */
    fun pausar() {
        visible = false
        cargando = false
        principal.removeCallbacksAndMessages(null)
        trabajo.pausar()
    }

    /** Al destruir ActividadAgenda libera el lector de esta pestaña, sin cancelar sesiones guardadas. */
    fun cerrar() {
        pausar()
        trabajo.cerrar()
    }
}