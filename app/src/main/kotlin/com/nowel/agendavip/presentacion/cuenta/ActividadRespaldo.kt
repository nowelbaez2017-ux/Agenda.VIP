package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.seguridad.AutenticacionRespaldo
import java.time.LocalDate
import java.util.concurrent.Executors
import javax.crypto.Cipher

/**
 * Permite crear y restaurar copias de la cuenta desde el selector de archivos de Android.
 * El respaldo con huella se abre en esta instalación. La copia para otro teléfono tiene
 * una contraseña propia para que el archivo pueda viajar sin exponer los datos.
 */
class ActividadRespaldo : Activity() {

    private val protector = ProtectorSesion(this)
    private val trabajador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var copia: CopiaSeguridadLocal
    private lateinit var autenticacion: AutenticacionRespaldo
    private lateinit var contenido: LinearLayout
    private lateinit var estado: TextView

    // La pantalla conserva la autorización mientras Archivos está abierto.
    // No se guarda en el estado de Android ni se vuelve a usar para una segunda copia.
    private var cifradorExportacion: Cipher? = null
    private var claveExportacion: CharArray? = null
    private var ocupada = false
    private var destruida = false

    /** Android entrega el estado temporal. Aquí se conectan la pantalla, la cuenta y la huella. */
    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        if (!protector.permitir(guardado)) {
            return
        }

        setContentView(R.layout.pantalla_cuenta_local)
        VentanaAgenda.preparar(this, findViewById(R.id.cuenta_raiz))
        findViewById<TextView>(R.id.cuenta_titulo).text = "Copia de seguridad"
        findViewById<Button>(R.id.cuenta_volver).setOnClickListener {
            finish()
        }

        copia = CopiaSeguridadLocal(this)
        autenticacion = AutenticacionRespaldo(this, ::informar)
        contenido = findViewById(R.id.cuenta_contenido)
        mostrarOpciones()
    }

    /**
     * Agrupa los botones por propósito. La copia con huella queda primero.
     * Restaurar usa un único botón porque el archivo indica cómo está protegido.
     */
    private fun mostrarOpciones() {
        val local = tarjeta(
            "En este teléfono",
            "Protege tu copia con la huella registrada en Android. Se abre en esta instalación de Agenda.VIP."
        )
        local.addView(ComponentesAgenda.boton(this, "Crear copia con huella", principal = true) {
            if (!ocupada) {
                prepararCopiaConHuella()
            }
        })

        val restauracion = tarjeta(
            "Recuperar una copia",
            "Elige tu archivo. Podrás revisar el contenido antes de reemplazar tus datos."
        )
        restauracion.addView(ComponentesAgenda.boton(this, "Elegir archivo de respaldo") {
            if (!ocupada) {
                abrirArchivos(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    IMPORTAR
                )
            }
        })

        val portable = tarjeta(
            "Cambio de teléfono o reinstalación",
            "Elige una contraseña para poder abrir la copia en otra instalación. Es independiente de la contraseña de tu cuenta."
        )
        portable.addView(ComponentesAgenda.boton(this, "Crear copia para otro teléfono") {
            if (!ocupada) {
                pedirClave(confirmar = true) { clave ->
                    claveExportacion?.fill('\u0000')
                    claveExportacion = clave
                    elegirDestino(EXPORTAR_PORTATIL)
                }
            }
        })

        estado = ComponentesAgenda.texto(this, "", 14f, R.color.texto_secundario).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        contenido.addView(estado)
    }

    /**
     * Recibe el título y una explicación corta de una opción. Devuelve su tarjeta para
     * que mostrarOpciones añada el botón correspondiente debajo del texto.
     */
    private fun tarjeta(titulo: String, descripcion: String): LinearLayout {
        val tarjeta = ComponentesAgenda.tarjeta(this)
        tarjeta.addView(ComponentesAgenda.texto(this, titulo, 20f, titulo = true))
        tarjeta.addView(ComponentesAgenda.espacio(this, 4))
        tarjeta.addView(ComponentesAgenda.texto(this, descripcion, 14f, R.color.texto_secundario))
        contenido.addView(tarjeta)
        return tarjeta
    }

    /**
     * Crear copia con huella prepara una llave de Android en segundo plano.
     * Si Android confirma la huella, abre Archivos para elegir dónde guardar la copia.
     */
    private fun prepararCopiaConHuella() {
        val motivo = autenticacion.motivoNoDisponible()
        if (motivo != null) {
            informar(motivo)
            return
        }

        ejecutar("Preparando la copia…", copia::cifradorParaTelefono) { cifrador ->
            cambiarEstado("Confirma tu huella para continuar.")
            autenticacion.solicitar("Crear copia de seguridad", cifrador) { autorizado ->
                if (protector.comprobar()) {
                    cifradorExportacion = autorizado
                    elegirDestino(EXPORTAR_TELEFONO)
                }
            }
        }
    }

    /**
     * Recibe el código de la opción elegida y abre Archivos con un nombre sugerido.
     * La huella y la contraseña usan el mismo selector y formatos de archivo diferenciados.
     */
    private fun elegirDestino(peticion: Int) {
        val prefijo = if (peticion == EXPORTAR_TELEFONO) "AgendaVIP_Telefono" else "AgendaVIP"
        abrirArchivos(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "${prefijo}_${LocalDate.now()}.avip"),
            peticion
        )
    }

    /**
     * Recibe la solicitud y su código para abrir el selector. Si Android no encuentra
     * una aplicación para elegir archivos, libera la autorización y muestra la causa.
     */
    private fun abrirArchivos(solicitud: Intent, peticion: Int) {
        try {
            startActivityForResult(solicitud, peticion)
        } catch (_: ActivityNotFoundException) {
            limpiarExportacion()
            informar("No se encontró un selector de archivos en este teléfono.")
        }
    }

    /** Al volver del selector comprueba que la persona sigue en la misma cuenta. */
    override fun onResume() {
        super.onResume()
        protector.comprobar()
    }

    /** Al girar la pantalla conserva la referencia a la cuenta, sin incluir llaves ni contraseñas. */
    override fun onSaveInstanceState(guardado: Bundle) {
        protector.guardarEstado(guardado)
        super.onSaveInstanceState(guardado)
    }

    /**
     * Archivos devuelve una dirección y el código de la opción que la abrió.
     * La exportación consume su autorización. La importación revisa el formato del archivo.
     */
    @Deprecated("Compatibilidad con el selector de documentos de Android.")
    override fun onActivityResult(peticion: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(peticion, resultado, datos)
        val direccion = datos?.data
        if (resultado != RESULT_OK || direccion == null || !protector.comprobar()) {
            limpiarExportacion()
            if (::estado.isInitialized) {
                cambiarEstado("")
            }
            return
        }

        when (peticion) {
            EXPORTAR_TELEFONO -> {
                val cifrador = cifradorExportacion
                cifradorExportacion = null
                if (cifrador == null) {
                    informar("Vuelve a tocar Crear copia con huella para confirmarla.")
                    return
                }

                ejecutar("Guardando copia…", { copia.exportarTelefono(direccion, cifrador) }) {
                    informar("Copia guardada. Puedes abrirla con tu huella en esta instalación.")
                }
            }

            EXPORTAR_PORTATIL -> {
                val clave = claveExportacion
                claveExportacion = null
                if (clave == null) {
                    informar("Vuelve a crear la copia para elegir su contraseña.")
                    return
                }

                ejecutar("Guardando copia…", { copia.exportar(direccion, clave) }) {
                    informar("Copia guardada. Conserva el archivo y su contraseña.")
                }
            }

            IMPORTAR -> abrirCopiaElegida(direccion)
        }
    }

    /**
     * Recibe el archivo elegido y revisa su encabezado, la marca que indica el tipo de copia.
     * Los archivos con contraseña abren el diálogo antiguo. Los de este teléfono usan la huella.
     */
    private fun abrirCopiaElegida(direccion: Uri) {
        ejecutar("Identificando la copia…", { copia.esCopiaTelefono(direccion) }) { esLocal ->
            if (esLocal) {
                abrirCopiaConHuella(direccion)
            } else {
                cambiarEstado("")
                pedirClave(confirmar = false) { clave ->
                    ejecutar("Comprobando copia…", { copia.preparar(direccion, clave) }, ::confirmarRestauracion)
                }
            }
        }
    }

    /**
     * Recibe el archivo de este teléfono. Prepara la llave que lo abre y solicita la huella.
     * Tras la comprobación muestra una vista previa. Todavía no modifica la agenda.
     */
    private fun abrirCopiaConHuella(direccion: Uri) {
        val motivo = autenticacion.motivoNoDisponible()
        if (motivo != null) {
            informar(motivo)
            return
        }

        ejecutar("Preparando la recuperación…", { copia.descifradorParaTelefono(direccion) }) { descifrador ->
            cambiarEstado("Confirma tu huella para continuar.")
            autenticacion.solicitar("Abrir copia de seguridad", descifrador) { autorizado ->
                if (protector.comprobar()) {
                    ejecutar(
                        "Comprobando copia…",
                        { copia.prepararTelefono(direccion, autorizado) },
                        ::confirmarRestauracion
                    )
                }
            }
        }
    }

    /**
     * Recibe si se necesita crear y repetir la contraseña o usar una existente.
     * Si es válida, entrega los caracteres a la acción que abre o guarda el archivo.
     * La copia con huella sigue un recorrido separado y no pasa por este cuadro.
     */
    private fun pedirClave(confirmar: Boolean, aceptar: (CharArray) -> Unit) {
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val margen = ComponentesAgenda.dp(this@ActividadRespaldo, 20)
            val vertical = ComponentesAgenda.dp(this@ActividadRespaldo, 8)
            setPadding(margen, vertical, margen, vertical)
        }

        val clave = EditText(this).apply {
            hint = "Contraseña de la copia"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val repetida = EditText(this).apply {
            hint = "Repite la contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        columna.addView(clave)
        if (confirmar) {
            columna.addView(repetida)
        }

        val explicacion = if (confirmar) {
            "Crea una contraseña de 12 a 128 caracteres y guárdala para abrir esta copia."
        } else {
            "Escribe la contraseña que elegiste al crear este archivo."
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (confirmar) "Protege tu copia" else "Abrir copia con contraseña")
            .setMessage(explicacion)
            .setView(columna)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar", null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val texto = clave.text
                if (texto.isEmpty()) {
                    clave.error = "Escribe la contraseña."
                } else if (confirmar && texto.length !in 12..128) {
                    clave.error = "Usa entre 12 y 128 caracteres."
                } else if (confirmar && !android.text.TextUtils.equals(texto, repetida.text)) {
                    repetida.error = "Las contraseñas no coinciden."
                } else {
                    val caracteres = CharArray(texto.length) { indice -> texto[indice] }
                    dialogo.dismiss()
                    aceptar(caracteres)
                }
            }
        }

        dialogo.setOnDismissListener {
            clave.text.clear()
            repetida.text.clear()
        }
        dialogo.show()
    }

    /**
     * Recibe una copia ya abierta y explica qué reemplazará en la cuenta actual.
     * Solo el botón Restaurar permite que el repositorio escriba los datos.
     */
    private fun confirmarRestauracion(preparada: CopiaSeguridadLocal.Preparada) {
        cambiarEstado("Copia revisada: ${preparada.tareas} tareas y ${preparada.actividades} actividades de Enfoque.")
        AlertDialog.Builder(this)
            .setTitle("¿Restaurar esta copia?")
            .setMessage(
                "Copia creada: ${preparada.creadaEn}\n\n" +
                    "Reemplazará los datos de esta cuenta. Las otras cuentas se conservan. " +
                    "Las actividades en curso volverán pausadas."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restaurar") { _, _ ->
                ejecutar("Restaurando datos…", { copia.restaurar(preparada) }) {
                    informar("Restauración terminada. Revisa tus canciones y los permisos del teléfono.")
                }
            }
            .show()
    }

    /**
     * Recibe un mensaje de espera, una operación y una acción para su resultado.
     * Leer, cifrar y escribir se hacen en segundo plano para que la pantalla responda a los toques.
     */
    private fun <T> ejecutar(mensaje: String, operacion: () -> T, alTerminar: (T) -> Unit) {
        if (ocupada || destruida) {
            return
        }

        ocupada = true
        cambiarEstado(mensaje)
        trabajador.execute {
            try {
                val resultado = operacion()
                principal.post {
                    ocupada = false
                    if (!destruida && protector.comprobar()) {
                        alTerminar(resultado)
                    }
                }
            } catch (error: Exception) {
                principal.post {
                    ocupada = false
                    if (!destruida && protector.comprobar()) {
                        informar(error.message ?: "No terminó la operación.")
                    }
                }
            }
        }
    }

    /** Recibe un mensaje y actualiza el texto que anuncia el estado de la operación. */
    private fun cambiarEstado(mensaje: String) {
        estado.text = mensaje
        estado.visibility = if (mensaje.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Recibe el resultado de un botón y lo muestra incluso si se desplazó la pantalla. */
    private fun informar(mensaje: String) {
        cambiarEstado(mensaje)
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    /** Archivos y el cierre de pantalla la usan para descartar autorizaciones que quedaron pendientes. */
    private fun limpiarExportacion() {
        cifradorExportacion = null
        claveExportacion?.fill('\u0000')
        claveExportacion = null
    }

    /** Android la llama al cerrar. Cancela el cuadro de huella y deja terminar las escrituras iniciadas. */
    override fun onDestroy() {
        destruida = true
        limpiarExportacion()
        if (::autenticacion.isInitialized) {
            autenticacion.cerrar()
        }
        trabajador.shutdown()
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val EXPORTAR_PORTATIL = 701
        const val IMPORTAR = 702
        const val EXPORTAR_TELEFONO = 703
    }
}