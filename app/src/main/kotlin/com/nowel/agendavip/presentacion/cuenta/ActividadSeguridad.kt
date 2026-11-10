package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EliminadorCuenta
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.presentacion.acceso.ActividadAcceso
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.seguridad.AplicacionAgenda
import com.nowel.agendavip.seguridad.AutenticacionDispositivo
import com.nowel.agendavip.seguridad.BloqueoLocal
import java.util.concurrent.Executors

/**
 * Reúne el bloqueo de apertura y las acciones privadas de la cuenta local.
 * Configuración abre esta pantalla; cada operación exige una comprobación antes de cambiar datos.
 * No recibe ni envía cuentas a un servidor y nunca solicita el PIN dentro de un formulario propio.
 */
class ActividadSeguridad : Activity() {

    private val protector = ProtectorSesion(this)
    private val ejecutor = Executors.newSingleThreadExecutor()
    private lateinit var contenido: LinearLayout
    private lateinit var comprobacion: AutenticacionDispositivo
    private var propietario = ""
    private var accionPendiente: String? = null
    private var trabajando = false
    private var cerrada = false
    private var cuadro: AlertDialog? = null

    /** Android crea la pantalla, conecta Volver y prepara la comprobación nativa del teléfono. */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (!protector.permitir(estado)) {
            return
        }
        propietario = EspacioPersonal.claveActual(this).orEmpty()
        accionPendiente = estado?.getString("accion_seguridad")
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.pantalla_cuenta_local)
        VentanaAgenda.preparar(this, findViewById(R.id.cuenta_raiz))
        contenido = findViewById(R.id.cuenta_contenido)
        findViewById<TextView>(R.id.cuenta_titulo).text = "Seguridad y privacidad"
        findViewById<View>(R.id.cuenta_volver).setOnClickListener { finish() }
        comprobacion = AutenticacionDispositivo(this, { confirmarCambioBloqueo() }, { explicarFallo(it) })

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { finish() }
        }
    }

    /** Al volver actualiza las opciones si la misma cuenta continúa abierta. */
    override fun onResume() {
        super.onResume()
        if (::contenido.isInitialized && protector.comprobar()) {
            mostrar()
        }
    }

    /** Guarda la operación del teléfono al girar, sin copiar contraseñas a los datos temporales. */
    override fun onSaveInstanceState(estado: Bundle) {
        protector.guardarEstado(estado)
        estado.putString("accion_seguridad", accionPendiente)
        super.onSaveInstanceState(estado)
    }

    /** Recibe el resultado de la pantalla nativa donde Android comprobó el PIN o patrón. */
    @Deprecated("Recibe el contrato nativo de la credencial del dispositivo.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)
        if (::comprobacion.isInitialized && protector.comprobar()) {
            comprobacion.recibirResultado(solicitud, resultado)
        }
    }

    /**
     * Dibuja el estado real y conecta sus botones. El bloqueo se ofrece inicialmente desactivado.
     * Las cuentas reales también pueden cambiar contraseña o pedir su eliminación definitiva.
     */
    private fun mostrar() {
        contenido.removeAllViews()
        contenido.addView(ComponentesAgenda.texto(this, "Protege tu agenda", 27f, titulo = true))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "El bloqueo protege la apertura. Tus avisos siguen funcionando con la pantalla bloqueada.",
            16f
        ))
        val activado = BloqueoLocal.estaActivado(this, propietario)
        contenido.addView(ComponentesAgenda.espacio(this, 18))
        contenido.addView(ComponentesAgenda.texto(
            this,
            if (activado) "Bloqueo de apertura: activado" else "Bloqueo de apertura: desactivado",
            20f,
            titulo = true
        ))
        contenido.addView(ComponentesAgenda.texto(
            this,
            "Al salir de la app se cubre su contenido. Al volver, usa la huella o el PIN, patrón o contraseña del teléfono. Los borradores permanecen abiertos.",
            14f
        ))
        contenido.addView(ComponentesAgenda.boton(this, if (activado) "Desactivar bloqueo" else "Activar bloqueo", true) {
            if (!trabajando) {
                accionPendiente = if (activado) "desactivar" else "activar"
                comprobacion.solicitar()
            }
        })
        contenido.addView(ComponentesAgenda.boton(this, "Configurar seguridad del teléfono") {
            try {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (_: RuntimeException) {
                informar("Abre Ajustes del teléfono y busca Seguridad o Bloqueo de pantalla.")
            }
        })

        if (propietario != EspacioPersonal.INVITADO) {
            contenido.addView(ComponentesAgenda.espacio(this, 28))
            contenido.addView(ComponentesAgenda.texto(this, "Tu cuenta local", 25f, titulo = true))
            contenido.addView(ComponentesAgenda.boton(this, "Cambiar contraseña") { pedirCambioContrasena() })
            contenido.addView(ComponentesAgenda.texto(
                this,
                "La nueva contraseña debe tener entre 15 y 128 caracteres. Puedes usar una frase que recuerdes; los espacios cuentan.",
                14f
            ))
            contenido.addView(ComponentesAgenda.espacio(this, 20))
            contenido.addView(ComponentesAgenda.boton(this, "Eliminar mi cuenta de este teléfono") { pedirEliminacion() })
            contenido.addView(ComponentesAgenda.texto(
                this,
                "Eliminar retira esta cuenta, sus tareas, su historial, su perfil y su foto privada. Las canciones originales y las demás cuentas se conservan. No se puede deshacer.",
                14f
            ))
        } else {
            contenido.addView(ComponentesAgenda.espacio(this, 20))
            contenido.addView(ComponentesAgenda.texto(
                this,
                "Usas la agenda sin cuenta. Para tener una contraseña propia, crea una cuenta desde la pantalla de acceso.",
                14f
            ))
        }
    }

    /**
     * La respuesta correcta del teléfono la llama. Aplica solamente la elección que sigue pendiente.
     * Guardar se hace en segundo plano; la pantalla muestra el resultado cuando vuelve a estar lista.
     */
    private fun confirmarCambioBloqueo() {
        val accion = accionPendiente ?: return
        accionPendiente = null
        BloqueoLocal.autorizar(this, propietario)
        ejecutar("Guardando bloqueo…") {
            BloqueoLocal.guardarActivacion(applicationContext, propietario, accion == "activar")
        }
    }

    /**
     * Muestra tres campos privados: contraseña actual, nueva y confirmación.
     * El botón comprueba que las dos nuevas coincidan antes de pedir al repositorio que la cambie.
     */
    private fun pedirCambioContrasena() {
        if (trabajando || cerrada) {
            return
        }
        val campos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actual = crearCampoContrasena("Contraseña actual")
        val nueva = crearCampoContrasena("Nueva contraseña")
        val confirmar = crearCampoContrasena("Repite la nueva contraseña")
        campos.addView(actual)
        campos.addView(nueva)
        campos.addView(confirmar)
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Cambiar contraseña")
            .setView(campos)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        cuadro = dialogo
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val anterior = leerCaracteres(actual)
                val elegida = leerCaracteres(nueva)
                val repetida = leerCaracteres(confirmar)

                if (!elegida.contentEquals(repetida)) {
                    anterior.fill('\u0000')
                    elegida.fill('\u0000')
                    repetida.fill('\u0000')
                    confirmar.error = "Las contraseñas nuevas no coinciden."
                    return@setOnClickListener
                }
                repetida.fill('\u0000')
                actual.text.clear()
                nueva.text.clear()
                confirmar.text.clear()
                dialogo.dismiss()

                ejecutar("Cambiando contraseña…") {
                    RepositorioCuentas(applicationContext).cambiarContrasena(propietario, anterior, elegida)
                }
            }
        }
        dialogo.show()
    }

    /**
     * Pide una confirmación escrita y la contraseña actual antes de ofrecer Eliminar definitivamente.
     * Nunca ejecuta el borrado por abrir este apartado o tocar accidentalmente un primer botón.
     */
    private fun pedirEliminacion() {
        if (trabajando || cerrada) {
            return
        }
        val campos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        campos.addView(ComponentesAgenda.texto(this, "Esta acción no se puede deshacer. Escribe ELIMINAR y confirma tu contraseña.", 16f))
        val confirmacion = EditText(this).apply {
            hint = "Escribe ELIMINAR"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val contrasena = crearCampoContrasena("Contraseña actual")
        campos.addView(confirmacion)
        campos.addView(contrasena)
        val dialogo = AlertDialog.Builder(this)
            .setTitle("¿Eliminar tu cuenta y sus datos?")
            .setView(campos)
            .setPositiveButton("Eliminar definitivamente", null)
            .setNegativeButton("Conservar mi cuenta", null)
            .create()
        cuadro = dialogo
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmacion.text.toString() != "ELIMINAR") {
                    confirmacion.error = "Escribe ELIMINAR para confirmar."
                    return@setOnClickListener
                }
                val clave = leerCaracteres(contrasena)
                contrasena.text.clear()
                dialogo.dismiss()
                ejecutar("Eliminando únicamente esta cuenta…", salirAlTerminar = true) {
                    EliminadorCuenta(applicationContext).eliminarActual(clave)
                }
            }
        }
        dialogo.show()
    }

    /** Recibe la ayuda del campo y devuelve una entrada que oculta sus caracteres y evita copias automáticas. */
    private fun crearCampoContrasena(ayuda: String): EditText {
        return EditText(this).apply {
            hint = ayuda
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    /** Recibe un campo privado y devuelve caracteres que el repositorio puede borrar después de comprobarlos. */
    private fun leerCaracteres(campo: EditText): CharArray {
        val caracteres = CharArray(campo.length())
        TextUtils.getChars(campo.text, 0, campo.length(), caracteres, 0)
        return caracteres
    }

    /**
     * Recibe un mensaje, la decisión de volver al acceso y una operación de guardado.
     * Hace el trabajo fuera de la pantalla y no aplica respuestas a una cuenta distinta.
     * Eliminar ya autorizado continúa aunque la pantalla se cierre; su registro permite reanudarlo.
     */
    private fun ejecutar(mensaje: String, salirAlTerminar: Boolean = false, operacion: () -> Unit) {
        if (trabajando || cerrada) {
            return
        }
        trabajando = true
        cuadro = AlertDialog.Builder(this).setMessage(mensaje).setCancelable(false).show()

        ejecutor.execute {
            val error = try {
                synchronized(EspacioPersonal.cerrojoSesion) {
                    check(EspacioPersonal.claveActual(applicationContext) == propietario) {
                        "La cuenta cambió. Vuelve a abrir Seguridad."
                    }
                    operacion()
                }
                null
            } catch (fallo: RuntimeException) {
                fallo.message ?: "No se pudo completar la operación. Inténtalo otra vez."
            }

            runOnUiThread {
                trabajando = false
                if (cerrada) {
                    return@runOnUiThread
                }
                cuadro?.dismiss()
                cuadro = null
                (application as? AplicacionAgenda)?.actualizarVentanas()

                if (error == null && salirAlTerminar || EspacioPersonal.claveActual(this) == null) {
                    if (error != null) {
                        informar(error)
                    }
                    startActivity(Intent(this, ActividadAcceso::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                } else if (error == null) {
                    mostrar()
                    informar("Cambio guardado.")
                } else {
                    explicarFallo(error)
                }
            }
        }
    }

    /** Recibe un fallo de comprobación o guardado y lo explica sin activar una protección aparente. */
    private fun explicarFallo(mensaje: String) {
        accionPendiente = null
        if (!cerrada) {
            AlertDialog.Builder(this).setTitle("No se pudo completar").setMessage(mensaje)
                .setPositiveButton("Entendido", null).show()
        }
    }

    /** Recibe un texto breve y lo muestra encima de la pantalla sin cambiar sus datos. */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    /** Libera los cuadros y la biometría; los guardados ya confirmados terminan en su ayudante de trabajo. */
    override fun onDestroy() {
        cerrada = true
        cuadro?.dismiss()
        if (::comprobacion.isInitialized) {
            comprobacion.cerrar()
        }
        ejecutor.shutdown()
        super.onDestroy()
    }
}
