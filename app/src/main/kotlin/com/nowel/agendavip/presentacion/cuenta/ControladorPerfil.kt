package com.nowel.agendavip.presentacion.cuenta

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.cuentas.SesionLocal
import com.nowel.agendavip.datos.perfil.AlmacenFotosPerfil
import com.nowel.agendavip.datos.perfil.PerfilLocal
import com.nowel.agendavip.presentacion.comun.FotoPerfil
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Coordina el formulario de Perfil que ActividadCuenta coloca dentro de su contenido.
 * Recibe la pantalla abierta, su columna de contenido y el estado temporal de Android.
 * Un controlador conecta los elementos visibles con sus acciones y con los datos guardados.
 * Conserva la misma vista al volver del selector para mantener todo lo que se está escribiendo.
 */
class ControladorPerfil(
    private val actividad: Activity,
    private val contenedor: LinearLayout,
    estado: Bundle?,
) {

    private val perfil = PerfilLocal(actividad)

    // Inflar un XML significa convertir su descripción en elementos reales de pantalla.
    private val vista = LayoutInflater.from(actividad).inflate(
        R.layout.perfil_contenido,
        contenedor,
        false,
    )

    private val nombre: EditText = vista.findViewById(R.id.perfil_nombre)
    private val apellido: EditText = vista.findViewById(R.id.perfil_apellido)
    private val correo: EditText = vista.findViewById(R.id.perfil_correo)
    private val celular: EditText = vista.findViewById(R.id.perfil_celular)
    private val foto: ImageView = vista.findViewById(R.id.perfil_foto)
    private val nombreVisible: TextView = vista.findViewById(R.id.perfil_nombre_completo)
    private val usuarioVisible: TextView = vista.findViewById(R.id.perfil_usuario_acceso)
    private val botonCopiar: Button = vista.findViewById(R.id.perfil_copiar_usuario)
    private val cuentaId = SesionLocal(actividad).cuentaIdActiva

    private val almacenFotos = AlmacenFotosPerfil(actividad)
    private val ejecutorPerfil = Executors.newSingleThreadExecutor()
    private var trabajoFoto: Future<*>? = null
    private var turnoFoto = 0
    private var cerrado = false

    private var fotoMostrada: String? = null
    private var fotoPreparada = false
    private var menuFoto: PopupMenu? = null
    private var confirmacionFoto: AlertDialog? = null
    private var confirmacionDatos: AlertDialog? = null

    private var editando = estado?.getBoolean("perfil_editando") ?: false
    private var usuarioAcceso: String? = null

    init {
        // Bundle es el grupo de datos temporales que Android entrega al recrear una pantalla.
        // Cada campo usa primero su borrador; solo si no existe toma el dato guardado.
        nombre.setText(estado?.getString("perfil_borrador_nombre") ?: perfil.nombres)
        apellido.setText(estado?.getString("perfil_borrador_apellido") ?: perfil.apellidos)
        correo.setText(estado?.getString("perfil_borrador_correo") ?: perfil.correo)
        celular.setText(estado?.getString("perfil_borrador_celular") ?: perfil.celular)

        foto.setOnClickListener {
            mostrarMenuFoto(foto)
        }

        foto.setOnLongClickListener {
            mostrarMenuFoto(foto)
            true
        }

        val camara = vista.findViewById<ImageButton>(R.id.perfil_camara)

        camara.setOnClickListener {
            mostrarMenuFoto(camara)
        }

        camara.setOnLongClickListener {
            mostrarMenuFoto(camara)
            true
        }

        vista.findViewById<View>(R.id.perfil_editar).setOnClickListener {
            empezarEdicion()
        }

        vista.findViewById<View>(R.id.perfil_guardar).setOnClickListener {
            pedirGuardarCambios()
        }

        vista.findViewById<View>(R.id.perfil_cancelar_edicion).setOnClickListener {
            terminarEdicion()
        }

        botonCopiar.setOnClickListener {
            copiarUsuario()
        }

        aplicarModoEdicion()
        cargarUsuario()
    }

    /**
     * ActividadCuenta la llama al mostrar Perfil o al regresar de otra pantalla.
     * No recibe argumentos ni devuelve datos: adjunta la vista si todavía no está en la columna.
     * Al conservar sus campos, volver de la foto o del selector no reemplaza el borrador escrito.
     */
    fun mostrar() {
        if (vista.parent !== contenedor) {
            (vista.parent as? ViewGroup)?.removeView(vista)
            contenedor.removeAllViews()
            contenedor.addView(vista)
        }

        actualizarIdentidad()
        actualizarDatosVisibles()
    }

    /**
     * Recibe el Bundle donde Android conserva datos antes de recrear ActividadCuenta.
     * Conserva el modo de edición y su borrador cuando todavía no se ha pulsado Guardar cambios.
     * ActividadCuenta la llama desde onSaveInstanceState; no devuelve un dato.
     */
    fun guardarEstado(estado: Bundle) {
        estado.putBoolean("perfil_editando", editando)
        if (!editando) {
            return
        }

        estado.putString(
            "perfil_borrador_nombre",
            nombre.text.toString(),
        )

        estado.putString(
            "perfil_borrador_apellido",
            apellido.text.toString(),
        )

        estado.putString(
            "perfil_borrador_correo",
            correo.text.toString(),
        )

        estado.putString(
            "perfil_borrador_celular",
            celular.text.toString(),
        )
    }

    /**
     * Compara los cuatro textos escritos con los datos que ya están guardados en este teléfono.
     * No recibe argumentos y devuelve true cuando existe una diferencia pendiente de guardar.
     * ActividadCuenta la llama al volver para decidir si debe preguntar antes de descartar cambios.
     */
    fun hayCambios(): Boolean {
        return editando && (
            nombre.text.toString().trim() != perfil.nombres ||
                apellido.text.toString().trim() != perfil.apellidos ||
                correo.text.toString().trim() != perfil.correo ||
                celular.text.toString().trim() != perfil.celular
            )
    }

    /**
     * Recibe el código de solicitud, el resultado y la imagen elegida en la galería.
     * ActividadCuenta la llama desde onActivityResult. Devuelve true si la solicitud era la 810,
     * incluso cuando se canceló, y false si corresponde a otra función de la aplicación.
     * Copia la foto a los archivos privados de la app; no cambia los textos escritos en el perfil.
     */
    fun recibirResultado(
        solicitud: Int,
        resultado: Int,
        datos: Intent?,
    ): Boolean {
        if (solicitud != SOLICITUD_FOTO) {
            return false
        }

        if (resultado != Activity.RESULT_OK) {
            return true
        }

        val uri = datos?.data

        if (uri == null) {
            informar(R.string.perfil_foto_no_disponible)
            return true
        }

        guardarFotoElegida(uri)

        return true
    }

    /**
     * ActividadCuenta la llama al destruirse para cerrar los menús y avisos propios del perfil.
     * No recibe argumentos ni devuelve datos. Invalida también la imagen pendiente en esa vista.
     * Cancela también la copia de una foto que aún esté en curso y cierra su trabajador.
     * La lectura circular de la imagen ya guardada pertenece al ayudante compartido FotoPerfil.
     */
    fun cerrar() {
        cerrado = true
        cancelarCopiaFoto()
        ejecutorPerfil.shutdownNow()
        menuFoto?.dismiss()
        confirmacionFoto?.dismiss()
        confirmacionDatos?.dismiss()
        foto.tag = null
    }

    /**
     * Actualiza el nombre de la cabecera y la foto desde PerfilLocal, sin reescribir los campos.
     * No recibe argumentos ni devuelve datos. La usan el inicio, mostrar y las acciones de la foto.
     * FotoPerfil prepara la copia circular en segundo plano, es decir, sin bloquear los botones.
     */
    private fun actualizarIdentidad() {
        nombreVisible.text = perfil.nombre.ifBlank {
            actividad.getString(R.string.perfil_nombre_sin_datos)
        }

        val referencia = perfil.fotoUri

        if (!fotoPreparada || fotoMostrada != referencia) {
            FotoPerfil.mostrar(
                actividad,
                referencia,
                foto,
            )

            fotoMostrada = referencia
            fotoPreparada = true
        }

        foto.contentDescription = actividad.getString(
            if (referencia.isNullOrBlank()) {
                R.string.perfil_foto_vacia_descripcion
            } else {
                R.string.perfil_foto_descripcion
            },
        )
    }

    /**
     * Lee el usuario de entrada de la cuenta que abrió Perfil. No recibe ni devuelve datos.
     * El constructor la llama una vez y el trabajo de disco se realiza fuera de la pantalla.
     * El resultado solo se muestra si sigue abierta esa misma cuenta.
     */
    private fun cargarUsuario() {
        val identificador = cuentaId
        if (identificador == null) {
            usuarioVisible.setText(R.string.perfil_usuario_invitado)
            return
        }

        val aplicacion = actividad.applicationContext
        ejecutorPerfil.execute {
            val encontrado = try {
                RepositorioCuentas(aplicacion).obtener(identificador)?.usuario
            } catch (_: RuntimeException) {
                null
            }

            actividad.runOnUiThread {
                if (cerrado || SesionLocal(aplicacion).cuentaIdActiva != identificador) {
                    return@runOnUiThread
                }

                if (encontrado.isNullOrBlank()) {
                    usuarioVisible.setText(R.string.perfil_usuario_no_disponible)
                    return@runOnUiThread
                }

                usuarioAcceso = encontrado
                usuarioVisible.text = encontrado
                botonCopiar.visibility = View.VISIBLE
                vista.findViewById<View>(R.id.perfil_usuario_ayuda).visibility = View.VISIBLE
            }
        }
    }

    /**
     * El botón Copiar llama aquí para llevar al portapapeles el usuario de acceso.
     * El portapapeles permite pegar el texto después. La función no lee ni copia contraseñas.
     * No recibe argumentos ni devuelve datos.
     */
    private fun copiarUsuario() {
        val usuario = usuarioAcceso ?: return
        if (SesionLocal(actividad).cuentaIdActiva != cuentaId) {
            return
        }

        val portapapeles = actividad.getSystemService(ClipboardManager::class.java) ?: return
        portapapeles.setPrimaryClip(
            ClipData.newPlainText(
                actividad.getString(R.string.perfil_usuario_titulo),
                usuario,
            ),
        )

        // Android 13 y posteriores muestran su propio aviso al copiar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            informar(R.string.perfil_usuario_copiado)
        }
    }

    /**
     * Lee los cuatro datos guardados y los coloca en la ficha. No toca los campos de edición.
     * Mostrar y guardarCambios la llaman para reflejar el perfil actual sin alterar un borrador.
     */
    private fun actualizarDatosVisibles() {
        val vacio = actividad.getString(R.string.perfil_dato_pendiente)

        vista.findViewById<TextView>(R.id.perfil_valor_nombre).text =
            perfil.nombres.ifBlank { vacio }

        vista.findViewById<TextView>(R.id.perfil_valor_apellido).text =
            perfil.apellidos.ifBlank { vacio }

        vista.findViewById<TextView>(R.id.perfil_valor_correo).text =
            perfil.correo.ifBlank { vacio }

        vista.findViewById<TextView>(R.id.perfil_valor_celular).text =
            perfil.celular.ifBlank { vacio }
    }

    /**
     * El botón Editar carga los datos guardados y muestra el formulario.
     * No recibe ni devuelve datos. El primer campo queda preparado para escribir.
     */
    private fun empezarEdicion() {
        nombre.setText(perfil.nombres)
        apellido.setText(perfil.apellidos)
        correo.setText(perfil.correo)
        celular.setText(perfil.celular)
        nombre.error = null
        apellido.error = null
        correo.error = null
        celular.error = null

        editando = true
        aplicarModoEdicion()
        nombre.requestFocus()
    }

    /**
     * Al guardar o cancelar, retira el teclado y regresa a la ficha de lectura.
     * No recibe ni devuelve datos. Las ediciones que no se confirmaron quedan descartadas.
     */
    private fun terminarEdicion() {
        val teclado = actividad.getSystemService(InputMethodManager::class.java)
        teclado?.hideSoftInputFromWindow(vista.windowToken, 0)
        editando = false
        aplicarModoEdicion()
        vista.findViewById<View>(R.id.perfil_editar).requestFocus()
    }

    /**
     * El inicio y las acciones de edición la llaman para mostrar solo la ficha o el formulario.
     * No modifica los datos y tampoco crea un formulario nuevo al volver de la galería.
     */
    private fun aplicarModoEdicion() {
        val modoLectura = if (editando) View.GONE else View.VISIBLE
        vista.findViewById<View>(R.id.perfil_datos_lectura).visibility = modoLectura
        vista.findViewById<View>(R.id.perfil_editar).visibility = modoLectura
        vista.findViewById<View>(R.id.perfil_formulario).visibility =
            if (editando) View.VISIBLE else View.GONE
    }

    /**
     * Recibe el elemento tocado para colocar junto a él un menú con las acciones de la foto.
     * La imagen y el botón de cámara la llaman tanto al tocar como al mantener la pulsación.
     * No devuelve datos. Si todavía no hay foto, solo ofrece cambiarla: no hay imagen que ver o quitar.
     */
    private fun mostrarMenuFoto(ancla: View) {
        menuFoto?.dismiss()

        // PopupMenu es el menú flotante que aparece junto al elemento tocado.
        val menu = PopupMenu(
            actividad,
            ancla,
        )
        val tieneFoto = !perfil.fotoUri.isNullOrBlank()

        if (tieneFoto) {
            menu.menu.add(
                0,
                ACCION_VER_FOTO,
                0,
                R.string.perfil_ver_foto,
            )
        }

        menu.menu.add(
            0,
            ACCION_CAMBIAR_FOTO,
            1,
            R.string.perfil_cambiar_foto,
        )

        if (tieneFoto) {
            menu.menu.add(
                0,
                ACCION_QUITAR_FOTO,
                2,
                R.string.perfil_quitar_foto,
            )
        }

        menu.setOnMenuItemClickListener { opcion ->
            when (opcion.itemId) {
                ACCION_VER_FOTO -> verFoto()
                ACCION_CAMBIAR_FOTO -> cambiarFoto()
                ACCION_QUITAR_FOTO -> confirmarQuitarFoto()
            }

            true
        }

        menuFoto = menu
        menu.show()
    }

    /**
     * El menú Ver foto la llama para abrir la imagen completa, sin el recorte circular del avatar.
     * No recibe argumentos ni devuelve datos: entrega la URI guardada a ActividadFotoPerfil.
     * Un Intent es la petición que le indica a Android qué pantalla debe abrir y qué dato llevarle.
     */
    private fun verFoto() {
        val referencia = perfil.fotoUri ?: return

        actividad.startActivity(
            Intent(
                actividad,
                ActividadFotoPerfil::class.java,
            ).putExtra(
                "foto_uri",
                referencia,
            ),
        )
    }

    /**
     * Cambiar foto la llama sin argumentos para abrir las fotos del teléfono.
     * Android 13 o posterior ofrece su selector de imágenes; en versiones anteriores abre la galería.
     * No devuelve una foto directamente: Android la entrega después a recibirResultado, con código 810.
     * No solicita acceso a toda la biblioteca; se usa únicamente la imagen que la persona elige.
     */
    @Suppress("DEPRECATION")
    private fun cambiarFoto() {
        cancelarCopiaFoto()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                actividad.startActivityForResult(
                    Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"),
                    SOLICITUD_FOTO,
                )
                return
            } catch (_: ActivityNotFoundException) {
                // Si este teléfono no ofrece el selector moderno, intenta su galería habitual.
            }
        }

        try {
            actividad.startActivityForResult(
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                SOLICITUD_FOTO,
            )
        } catch (_: ActivityNotFoundException) {
            informar(R.string.perfil_selector_no_disponible)
        }
    }

    /**
     * Recibe la dirección elegida en la galería y pide copiarla sin bloquear la pantalla.
     * recibirResultado la llama. Al terminar guarda la copia en PerfilLocal y actualiza el avatar.
     * Si llega otra selección o se cierra Perfil, descarta solo esta copia nueva sin tocar las anteriores.
     */
    private fun guardarFotoElegida(uri: Uri) {
        cancelarCopiaFoto()
        val turno = turnoFoto
        informar(R.string.perfil_foto_guardando)

        trabajoFoto = ejecutorPerfil.submit {
            try {
                val archivo = almacenFotos.copiar(uri)

                actividad.runOnUiThread {
                    if (cerrado || turno != turnoFoto) {
                        archivo.delete()
                        return@runOnUiThread
                    }

                    try {
                        perfil.guardarFoto(Uri.fromFile(archivo).toString())
                    } catch (_: IllegalStateException) {
                        // La cuenta pudo cambiar después de copiar; no se sustituye su foto.
                        archivo.delete()
                        return@runOnUiThread
                    }

                    try {
                        actualizarIdentidad()
                        informar(R.string.perfil_foto_guardada)
                    } catch (_: IllegalStateException) {
                        // Si acaba de cambiar la sesión, la copia ya guardada sigue perteneciendo
                        // a su perfil. No se borra; el protector de la pantalla gestiona el regreso.
                    }
                }
            } catch (_: Exception) {
                actividad.runOnUiThread {
                    if (!cerrado && turno == turnoFoto) {
                        informar(R.string.perfil_foto_no_disponible)
                    }
                }
            }
        }
    }

    /**
     * El cambio de selección, Quitar foto y cerrar la pantalla la llaman sin argumentos.
     * Avisa al trabajador que pare y cambia el turno para ignorar resultados anteriores.
     * No elimina ninguna foto que ya esté guardada en el perfil.
     */
    private fun cancelarCopiaFoto() {
        turnoFoto += 1
        trabajoFoto?.cancel(true)
        trabajoFoto = null
    }

    /**
     * El menú Quitar foto la llama para pedir confirmación antes de retirar la foto guardada.
     * No recibe argumentos ni devuelve datos. Confirmar actualiza solo la foto y mantiene el borrador.
     * El archivo original permanece en el teléfono; se retira únicamente su referencia del perfil.
     */
    private fun confirmarQuitarFoto() {
        confirmacionFoto = AlertDialog.Builder(actividad)
            .setTitle(R.string.perfil_quitar_foto_titulo)
            .setMessage(R.string.perfil_quitar_foto_ayuda)
            .setPositiveButton(R.string.perfil_quitar_foto) { _, _ ->
                cancelarCopiaFoto()

                // Quita la referencia de este perfil. Las otras cuentas conservan su foto y acceso.
                perfil.guardarFoto(null)
                actualizarIdentidad()
                informar(R.string.perfil_foto_quitada)
            }
            .setNegativeButton(
                R.string.perfil_cancelar,
                null,
            )
            .show()
    }

    /**
     * El botón Guardar cambios revisa los cuatro datos antes de ofrecer la confirmación.
     * No recibe ni devuelve datos. Si el formulario tiene errores, conserva el borrador.
     * Seguir editando cierra el aviso y Confirmar llama a guardarCambios.
     */
    private fun pedirGuardarCambios() {
        if (!editando || !validarCampos()) {
            return
        }

        if (!hayCambios()) {
            terminarEdicion()
            return
        }

        confirmacionDatos?.dismiss()
        confirmacionDatos = AlertDialog.Builder(actividad)
            .setTitle(R.string.perfil_confirmar_titulo)
            .setMessage(R.string.perfil_confirmar_ayuda)
            .setNegativeButton(R.string.perfil_seguir_editando, null)
            .setPositiveButton(R.string.perfil_confirmar) { _, _ ->
                guardarCambios()
            }
            .show()
    }

    /**
     * La confirmación llama aquí para guardar los textos revisados en PerfilLocal.
     * No recibe ni devuelve datos. Actualiza la ficha y cierra la edición tras guardar.
     * Si el guardado no se completa, avisa y deja los campos disponibles.
     */
    private fun guardarCambios() {
        try {
            perfil.guardarDatos(
                nombres = nombre.text.toString(),
                apellidos = apellido.text.toString(),
                correo = correo.text.toString(),
                celular = celular.text.toString(),
            )
        } catch (_: IllegalStateException) {
            informar(R.string.perfil_error_guardar)
            return
        }

        actualizarIdentidad()
        actualizarDatosVisibles()
        terminarEdicion()
        informar(R.string.perfil_datos_guardados)
    }

    /**
     * Revisa el nombre obligatorio y, si se escribieron, el correo y el celular opcionales.
     * No recibe argumentos y devuelve true cuando el formulario puede guardarse.
     * pedirGuardarCambios la llama y deja la edición abierta si hay datos incorrectos.
     * Patterns.EMAIL_ADDRESS es la regla de Android para reconocer el formato básico de un correo.
     */
    private fun validarCampos(): Boolean {
        nombre.error = null
        apellido.error = null
        correo.error = null
        celular.error = null

        var primerError: EditText? = null

        if (nombre.text.toString().isBlank()) {
            nombre.error = actividad.getString(R.string.perfil_error_nombre)
            primerError = nombre
        }

        val correoEscrito = correo.text.toString().trim()

        if (
            correoEscrito.isNotEmpty() &&
            !Patterns.EMAIL_ADDRESS.matcher(correoEscrito).matches()
        ) {
            correo.error = actividad.getString(R.string.perfil_error_correo)

            if (primerError == null) {
                primerError = correo
            }
        }

        val celularEscrito = celular.text.toString().trim()

        if (celularEscrito.isNotEmpty() && !celularValido(celularEscrito)) {
            celular.error = actividad.getString(R.string.perfil_error_celular)

            if (primerError == null) {
                primerError = celular
            }
        }

        primerError?.requestFocus()

        return primerError == null
    }

    /**
     * Recibe el celular sin espacios en sus extremos y devuelve si tiene un formato razonable.
     * Acepta entre 7 y 15 dígitos, espacios, guiones, puntos, paréntesis y un + inicial opcional.
     * validarCampos la llama solo cuando hay un número escrito; no comprueba si esa línea existe.
     */
    private fun celularValido(valor: String): Boolean {
        val cantidadDigitos = valor.count { it in '0'..'9' }
        val caracteresPermitidos = valor.all {
            it in '0'..'9' || it in " +-()."
        }
        val masPermitido = valor.count { it == '+' } <= 1 &&
            (!valor.contains('+') || valor.startsWith('+'))

        return cantidadDigitos in 7..15 && caracteresPermitidos && masPermitido
    }

    /**
     * Recibe el identificador de un texto y lo muestra brevemente mediante Toast, un aviso de Android.
     * Las acciones del perfil la llaman para explicar un guardado o un problema sin borrar el formulario.
     * No devuelve un dato.
     */
    private fun informar(mensaje: Int) {
        Toast.makeText(
            actividad,
            mensaje,
            Toast.LENGTH_LONG,
        ).show()
    }

    // companion object agrupa los números fijos que identifican el selector y cada acción del menú.
    companion object {
        private const val SOLICITUD_FOTO = 810
        private const val ACCION_VER_FOTO = 1
        private const val ACCION_CAMBIAR_FOTO = 2
        private const val ACCION_QUITAR_FOTO = 3
    }
}

