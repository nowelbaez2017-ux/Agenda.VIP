package com.nowel.agendavip.recordatorios.enfoque

import android.content.Context
import android.util.Log
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.recordatorios.ProteccionEntregaAviso
import java.util.concurrent.Executors

/** Recupera Enfoque sin depender del trabajo cancelable que dibuja una pantalla. */
object RecuperadorEnfoque {

    private class Solicitud(var reinicioPendiente: Boolean)
    private val ejecutor = Executors.newSingleThreadExecutor()
    private val cerrojo = Any()
    private val pendientes = mutableMapOf<String, Solicitud>()

    /**
     * Recibe Android y si se confirmó un reinicio. El arranque, los permisos y la entrada a la app
     * la llaman. Guarda solo el contexto de aplicación; salir de una pantalla no cancela el encargo.
     * Los encargos iguales se reúnen; un reinicio recibido durante la recuperación se conserva.
     */
    fun solicitar(contexto: Context, reinicio: Boolean = false) {
        val aplicacion = contexto.applicationContext
        val propietario = EspacioPersonal.claveActual(aplicacion) ?: return
        val solicitud = synchronized(cerrojo) {
            val actual = pendientes[propietario]

            if (actual != null) {
                actual.reinicioPendiente = actual.reinicioPendiente || reinicio
                null
            } else {
                Solicitud(reinicio).also {
                    pendientes[propietario] = it
                }
            }
        } ?: return
        val proteccion = ProteccionEntregaAviso.tomar(aplicacion)

        try {
            ejecutor.execute {
                try {
                    var continuar = true

                    while (continuar) {
                        val fueReinicio = synchronized(cerrojo) {
                            val valor = solicitud.reinicioPendiente
                            solicitud.reinicioPendiente = false
                            valor
                        }
                        synchronized(EspacioPersonal.cerrojoSesion) {
                            if (EspacioPersonal.claveActual(aplicacion) == propietario) {
                                GestorEnfoque(aplicacion).reprogramarTodo(fueReinicio)
                            }
                        }
                        continuar = synchronized(cerrojo) {
                            if (solicitud.reinicioPendiente) {
                                true
                            } else {
                                if (pendientes[propietario] === solicitud) {
                                    pendientes.remove(propietario)
                                }
                                false
                            }
                        }
                    }
                } catch (error: Exception) {
                    try {
                        if (EspacioPersonal.claveActual(aplicacion) == propietario) {
                            EspacioPersonal(aplicacion).preferencias("estado_enfoque").edit()
                                .putString("ultimo_error", error.message ?: "No se pudieron recuperar los avisos de Enfoque.")
                                .apply()
                        }
                    } catch (_: RuntimeException) {
                        // Los datos se conservan si también falla el diagnóstico.
                    }
                    Log.e("AgendaVIP", "Fallo al recuperar Enfoque: ${error.javaClass.simpleName}")
                } finally {
                    retirar(propietario, solicitud)
                    ProteccionEntregaAviso.liberar(proteccion)
                }
            }
        } catch (_: RuntimeException) {
            retirar(propietario, solicitud)
            ProteccionEntregaAviso.liberar(proteccion)
        }
    }

    /**
     * Recibe cuenta y encargo terminado y retira solo su propia marca.
     * Un encargo nuevo pudo llegar después del anterior: la comparación conserva ese nuevo trabajo.
     */
    private fun retirar(propietario: String, solicitud: Solicitud) {
        synchronized(cerrojo) {
            if (pendientes[propietario] === solicitud) {
                pendientes.remove(propietario)
            }
        }
    }
}