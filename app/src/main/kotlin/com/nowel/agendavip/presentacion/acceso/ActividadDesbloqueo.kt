package com.nowel.agendavip.presentacion.acceso

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.seguridad.AutenticacionDispositivo
import com.nowel.agendavip.seguridad.BloqueoLocal
import java.util.concurrent.Executors

/**
 * Cubre la agenda hasta comprobar quién está usando el teléfono.
 * No sustituye la pantalla que había debajo: al autorizar se conservan sus datos sin guardar.
 * AplicacionAgenda la abre únicamente si la persona activó el bloqueo de su espacio.
 */
class ActividadDesbloqueo : Activity() {

    private val ejecutor = Executors.newSingleThreadExecutor()
    private lateinit var comprobacion: AutenticacionDispositivo
    private lateinit var mensaje: TextView
    private var propietario: String? = null
    private var cerrada = false
    private var trabajando = false

    /** Android crea la pantalla protegida; muestra únicamente controles de acceso, sin tareas privadas. */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        propietario = estado?.getString("propietario") ?: EspacioPersonal.claveActual(this)

        if (propietario == null || propietario != EspacioPersonal.claveActual(this)) {
            finish()
            return
        }

        val contenido = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val espacio = ComponentesAgenda.dp(this@ActividadDesbloqueo, 28)
            setPadding(espacio, espacio, espacio, espacio)
            setBackgroundColor(getColor(R.color.marfil))
        }
        val desplazamiento = ScrollView(this).apply {
            isFillViewport = true
            addView(contenido)
        }
        setContentView(desplazamiento)
        VentanaAgenda.preparar(this, desplazamiento)

        contenido.addView(ComponentesAgenda.texto(this, "Agenda.VIP", 36f, titulo = true))
        contenido.addView(ComponentesAgenda.espacio(this, 20))
        contenido.addView(ComponentesAgenda.texto(this, "Tu agenda está protegida", 25f, titulo = true))
        mensaje = ComponentesAgenda.texto(
            this,
            "Confirma tu identidad. Tus recordatorios siguen funcionando mientras la agenda está bloqueada.",
            16f
        )
        contenido.addView(mensaje)
        contenido.addView(ComponentesAgenda.espacio(this, 24))
        contenido.addView(ComponentesAgenda.boton(this, "Desbloquear", true) {
            comprobacion.solicitar()
        })

        if (propietario != EspacioPersonal.INVITADO) {
            contenido.addView(ComponentesAgenda.boton(this, "Usar contraseña de Agenda.VIP") {
                pedirContrasena()
            })
        }
        contenido.addView(ComponentesAgenda.boton(this, "Seguridad del teléfono") {
            try {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (_: RuntimeException) {
                mensaje.text = "Abre los ajustes del teléfono y configura su PIN, patrón o contraseña."
            }
        })
        contenido.addView(ComponentesAgenda.boton(this, "Salir por ahora") {
            moveTaskToBack(true)
        })

        comprobacion = AutenticacionDispositivo(this, { autorizar() }, { mensaje.text = it })

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { moveTaskToBack(true) }
        }
    }

    /** Al volver, una sesión distinta retira esta pantalla; una autorización vigente la termina. */
    override fun onResume() {
        super.onResume()
        if (propietario != EspacioPersonal.claveActual(this) || !BloqueoLocal.necesitaDesbloqueo(this)) {
            finish()
        }
    }

    /** Guarda únicamente el dueño al girar el teléfono; nunca guarda una contraseña escrita. */
    override fun onSaveInstanceState(estado: Bundle) {
        estado.putString("propietario", propietario)
        super.onSaveInstanceState(estado)
    }

    /** Recibe el resultado de Android después de comprobar el PIN, patrón o contraseña del teléfono. */
    @Deprecated("Recibe el contrato nativo de la credencial del dispositivo.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)
        if (::comprobacion.isInitialized) {
            comprobacion.recibirResultado(solicitud, resultado)
        }
    }

    /** Confirma que la misma cuenta sigue dentro y devuelve la pantalla anterior sin crear otra agenda. */
    private fun autorizar() {
        val actual = propietario ?: return
        if (actual == EspacioPersonal.claveActual(this)) {
            BloqueoLocal.autorizar(this, actual)
        }
        finish()
    }

    /**
     * Ofrece la contraseña de la cuenta como alternativa si cambió la huella o el PIN del teléfono.
     * Los caracteres se comprueban fuera de la pantalla y se borran después de usarlos.
     */
    private fun pedirContrasena() {
        if (trabajando || cerrada) {
            return
        }
        val campo = EditText(this).apply {
            hint = "Contraseña de tu cuenta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle("Contraseña de Agenda.VIP")
            .setView(campo)
            .setPositiveButton("Desbloquear", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clave = CharArray(campo.length())
                TextUtils.getChars(campo.text, 0, campo.length(), clave, 0)
                campo.text.clear()
                val cuentaId = propietario ?: return@setOnClickListener
                trabajando = true
                dialogo.dismiss()
                mensaje.text = "Comprobando contraseña…"

                ejecutor.execute {
                    val permitida = try {
                        RepositorioCuentas(applicationContext).confirmarContrasena(cuentaId, clave)
                    } catch (_: RuntimeException) {
                        false
                    } finally {
                        clave.fill('\u0000')
                    }

                    runOnUiThread {
                        trabajando = false
                        if (!cerrada && propietario == EspacioPersonal.claveActual(this)) {
                            if (permitida) {
                                autorizar()
                            } else {
                                mensaje.text = "No se pudo confirmar la contraseña. Revisa el texto o espera un minuto si hubo varios intentos."
                            }
                        }
                    }
                }
            }
        }
        dialogo.show()
    }

    /** Atrás manda la tarea al fondo; no descubre las pantallas protegidas que hay debajo. */
    @Deprecated("Android 13 utiliza la acción registrada en onCreate.")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /** Descarta respuestas tardías y libera el trabajo al retirar esta pantalla. */
    override fun onDestroy() {
        cerrada = true
        if (::comprobacion.isInitialized) {
            comprobacion.cerrar()
        }
        ejecutor.shutdownNow()
        super.onDestroy()
    }
}
