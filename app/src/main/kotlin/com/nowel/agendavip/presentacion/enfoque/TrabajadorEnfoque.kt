package com.nowel.agendavip.presentacion.enfoque

import android.app.Activity
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Separa las consultas de pantalla de las acciones que la persona ya confirmó.
 * Recibe la pantalla y una función que explica errores. Un ejecutor es un trabajador
 * que hace lecturas o guardados fuera del trabajo que Android utiliza para dibujar.
 * Las consultas se cancelan al salir; un Guardar, Comenzar o Completar confirmado termina
 * en el trabajador compartido aunque la pantalla se cierre durante ese guardado.
 */
internal class TrabajadorEnfoque(
    private val actividad: Activity,
    private val informarError: (String) -> Unit,
) {

    private val espacio = EspacioPersonal(actividad)
    private val consultas = Executors.newSingleThreadExecutor()
    private var consulta: Future<*>? = null
    private var version = 0
    private var visible = false
    private var cerrado = false

    /** La pantalla llama al volver. Permite entregar resultados de la visita actual. */
    fun activar() {
        visible = true
    }

    /**
     * Recibe una lectura y qué hacer con su resultado. La usan las listas, formularios y detalles.
     * Descarta respuestas antiguas y consultas interrumpidas; no modifica actividades de enfoque.
     */
    fun <T> consultar(leer: () -> T, alRecibir: (T) -> Unit) {
        if (!visible || cerrado) {
            return
        }

        consulta?.cancel(true)
        val turno = ++version
        consulta = consultas.submit {
            try {
                espacio.exigirVigente()
                val resultado = leer()

                actividad.runOnUiThread {
                    if (vigente(turno)) {
                        alRecibir(resultado)
                    }
                }
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    mostrarError(error, turno)
                }
            }
        }
    }

    /**
     * Recibe una sincronización necesaria y después una lectura para dibujar sus resultados.
     * La lista y los detalles la usan para registrar un bloque cuyo tiempo ya terminó.
     * Como esa sincronización puede guardar un final real, no se interrumpe al salir de pantalla.
     * Solo el dibujo se descarta si la visita terminó; el final sigue protegido por el gestor.
     */
    fun <T> consultarSincronizado(preparar: () -> Unit, leer: () -> T, alRecibir: (T) -> Unit) {
        if (!visible || cerrado) {
            return
        }

        consulta?.cancel(true)
        val turno = ++version
        cambios.execute {
            try {
                espacio.exigirVigente()
                preparar()
                val resultado = leer()

                actividad.runOnUiThread {
                    if (vigente(turno)) {
                        alRecibir(resultado)
                    }
                }
            } catch (error: Exception) {
                mostrarError(error, turno)
            }
        }
    }

    /**
     * Recibe una acción confirmada y qué mostrar al terminar. No cancela este trabajo al salir.
     * El gestor vuelve a comprobar la cuenta antes de guardar; una pantalla antigua no puede
     * cambiar la cuenta que se haya abierto después. Solo dibuja el resultado si sigue visible.
     */
    fun <T> cambiar(accion: () -> T, alTerminar: (T) -> Unit) {
        if (!visible || cerrado) {
            return
        }

        val turno = ++version
        consulta?.cancel(true)
        cambios.execute {
            try {
                espacio.exigirVigente()
                val resultado = accion()

                actividad.runOnUiThread {
                    if (vigente(turno)) {
                        alTerminar(resultado)
                    }
                }
            } catch (error: Exception) {
                mostrarError(error, turno)
            }
        }
    }

    /** Recibe el fallo y su visita; lo explica sin mostrar errores de otra pantalla o cuenta. */
    private fun mostrarError(error: Exception, turno: Int) {
        actividad.runOnUiThread {
            if (vigente(turno)) {
                informarError(error.message ?: "No se pudo terminar. Revisa Enfoque antes de repetir la acción.")
            }
        }
    }

    /** Devuelve si el resultado pertenece a la misma visita visible y a la cuenta original. */
    private fun vigente(turno: Int): Boolean =
        !cerrado && visible && version == turno && !actividad.isFinishing &&
            !actividad.isDestroyed && espacio.vigente()

    /** Al salir cancela únicamente la lectura; las alarmas y los cambios confirmados siguen su curso. */
    fun pausar() {
        visible = false
        version += 1
        consulta?.cancel(true)
        consulta = null
    }

    /** Al destruir la pantalla libera su lector, sin cerrar el trabajador compartido de guardados. */
    fun cerrar() {
        cerrado = true
        pausar()
        consultas.shutdownNow()
    }

    private companion object {
        val cambios = Executors.newSingleThreadExecutor()
    }
}