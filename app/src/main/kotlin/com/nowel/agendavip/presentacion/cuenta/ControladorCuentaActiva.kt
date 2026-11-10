package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.cuentas.SesionLocal
import com.nowel.agendavip.presentacion.acceso.ActividadAcceso
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Muestra quién entró y conecta Cerrar sesión con el guardado local.
 * Recibe la pantalla y una acción para actualizar el nombre visible en Configuración.
 * Un ejecutor es un ayudante que hace el trabajo de disco sin detener la pantalla.
 * ActividadCuenta crea este controlador solo al abrir Configuración.
 */
class ControladorCuentaActiva(
    private val actividad: Activity,
    private val actualizarVista: () -> Unit,
) {

    private val ejecutor = Executors.newSingleThreadExecutor()
    private var trabajo: Future<*>? = null
    private var turno = 0
    private var cerrado = false
    private var saliendo = false
    private var dialogo: AlertDialog? = null
    private var usuarioActivo: String? = null

    /** Configuración consulta este texto; el usuario real se lee en segundo plano. */
    var identidad: String = "Cargando cuenta…"
        private set

    /**
     * No recibe datos: consulta el usuario activo y actualiza su etiqueta en Configuración.
     * ActividadCuenta la llama al abrir o volver. El modo sin cuenta se identifica por separado.
     * Si la pantalla ya salió, el número de turno impide dibujar un resultado antiguo.
     */
    fun mostrar() {
        if (cerrado || saliendo) {
            return
        }

        trabajo?.cancel(true)
        val consulta = ++turno
        val sesion = SesionLocal(actividad)
        val propietario = sesion.espacioActivo
        val cuentaId = sesion.cuentaIdActiva

        usuarioActivo = null
        if (cuentaId == null) {
            identidad = "Agenda sin cuenta"
            actualizarVista()
            return
        }

        trabajo = ejecutor.submit {
            val usuario = try {
                RepositorioCuentas(actividad.applicationContext).obtener(cuentaId)?.usuario
            } catch (_: RuntimeException) {
                null
            }

            if (Thread.currentThread().isInterrupted) {
                return@submit
            }

            actividad.runOnUiThread {
                if (!cerrado && turno == consulta && SesionLocal(actividad).espacioActivo == propietario) {
                    usuarioActivo = usuario
                    identidad = if (usuario == null) {
                        "Usuario no disponible"
                    } else {
                        "Usuario: $usuario"
                    }
                    actualizarVista()
                }
            }
        }
    }

    /**
     * El botón Cerrar sesión la llama sin argumentos.
     * Muestra el usuario que se necesitará al volver, junto con una entrada a Seguridad.
     * Confirmar llama a salir; cancelar deja la cuenta y sus avisos activos.
     */
    fun pedirSalida() {
        if (cerrado || saliendo) {
            return
        }

        val esCuenta = SesionLocal(actividad).cuentaIdActiva != null
        val usuario = usuarioActivo
        val mensaje = buildString {
            if (usuario != null) {
                append("Tu usuario de acceso es $usuario. Ten a mano tu contraseña para volver a entrar.\n\n")
            } else if (esCuenta) {
                append("Si olvidaste el usuario, podrás buscarlo desde la ayuda de inicio de sesión.\n\n")
            }

            append("Tus datos seguirán guardados. Los avisos de esta agenda se pausarán hasta que vuelvas a entrar.")
        }

        dialogo?.dismiss()
        val confirmacion = AlertDialog.Builder(actividad)
            .setTitle("¿Cerrar sesión?")
            .setMessage(mensaje)
            .setPositiveButton("Cerrar sesión") { _, _ ->
                salir(usuario)
            }
            .setNegativeButton("Seguir aquí", null)

        if (esCuenta) {
            confirmacion.setNeutralButton("Seguridad") { _, _ ->
                actividad.startActivity(Intent(actividad, ActividadSeguridad::class.java))
            }
        }
        dialogo = confirmacion.show()
    }

    /**
     * Recibe el usuario que mostró el cuadro de confirmación, si pudo leerse.
     * Pide al coordinador cerrar la sesión del dueño actual. El usuario solo se usa para
     * rellenar el formulario al salir y el cuadro temporal evita repetir la operación.
     */
    private fun salir(usuario: String?) {
        saliendo = true
        trabajo?.cancel(true)
        val operacion = ++turno
        val propietario = SesionLocal(actividad).espacioActivo
        dialogo = AlertDialog.Builder(actividad)
            .setMessage("Cerrando sesión…")
            .setCancelable(false)
            .show()

        trabajo = ejecutor.submit {
            val problema = try {
                synchronized(EspacioPersonal.cerrojoSesion) {
                    check(SesionLocal(actividad).espacioActivo == propietario) {
                        "La sesión cambió. Vuelve a abrir Configuración."
                    }

                    CoordinadorSesion(actividad.applicationContext).cerrarSesion()
                }

                null
            } catch (error: RuntimeException) {
                error.message ?: "No se pudo cerrar sesión. Vuelve a intentarlo."
            }

            actividad.runOnUiThread {
                if (cerrado || turno != operacion) {
                    return@runOnUiThread
                }

                dialogo?.dismiss()
                dialogo = null
                saliendo = false

                if (problema == null || !SesionLocal(actividad).hayEntradaActiva) {
                    volverAlAcceso(usuario)
                } else {
                    Toast.makeText(actividad, problema, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Recibe el usuario sugerido y abre el acceso tras cerrar sesión.
     * Retira las pantallas anteriores. El formulario usará el texto como sugerencia editable.
     */
    private fun volverAlAcceso(usuario: String?) {
        val destino = Intent(actividad, ActividadAcceso::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (usuario != null) {
            destino.putExtra(ActividadAcceso.EXTRA_USUARIO_SUGERIDO, usuario)
        }

        actividad.startActivity(destino)
        actividad.finish()
    }

    /**
     * ActividadCuenta la llama al ocultarse: descarta respuestas y cuadros de esa pantalla.
     * Si el cierre todavía no se confirmó, su trabajo recibe la petición de cancelación.
     * Una sesión ya confirmada seguirá cerrada y el protector de pantalla lo detectará al volver.
     */
    fun pausar() {
        turno += 1
        trabajo?.cancel(true)
        trabajo = null
        dialogo?.dismiss()
        dialogo = null
        saliendo = false
    }

    /**
     * ActividadCuenta la llama al destruirse. Cancela el trabajo y libera el ayudante de disco.
     * No recibe datos ni elimina archivos, cuentas, fotos o tareas.
     */
    fun cerrar() {
        cerrado = true
        pausar()
        ejecutor.shutdownNow()
    }
}