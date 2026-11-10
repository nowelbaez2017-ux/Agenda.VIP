package com.nowel.agendavip.presentacion.servidor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.servidor.ClientePC
import com.nowel.agendavip.datos.servidor.ComputadoraPC
import com.nowel.agendavip.datos.servidor.DescubridorPC
import com.nowel.agendavip.datos.servidor.RepositorioConexionPC
import com.nowel.agendavip.datos.servidor.SincronizadorPC
import com.nowel.agendavip.presentacion.comun.ComponentesAgenda
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Conecta la cuenta abierta con el servidor que la persona inició en su computadora.
 * Buscar encuentra equipos de la misma red; Vincular pide permiso antes de compartir datos.
 * El código del panel identifica la PC y autoriza esta cuenta. Se descarta al usarlo.
 */
class ActividadConexionPC : Activity() {

    private val protector = ProtectorSesion(this)
    private val trabajador = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var espacio: EspacioPersonal
    private lateinit var repositorio: RepositorioConexionPC
    private lateinit var descubridor: DescubridorPC

    private lateinit var formulario: LinearLayout
    private lateinit var tarjetaVinculo: LinearLayout
    private lateinit var encontrados: LinearLayout
    private lateinit var direccion: EditText
    private lateinit var codigo: EditText
    private lateinit var estado: TextView
    private lateinit var detalleVinculo: TextView
    private lateinit var ultimaConexion: TextView
    private lateinit var buscar: Button
    private lateinit var escanear: Button
    private lateinit var reconectarQR: Button
    private lateinit var buscarVinculada: Button
    private lateinit var vincular: Button
    private lateinit var sincronizar: Button
    private lateinit var desconectar: Button
    private var confirmacion: AlertDialog? = null
    private var ocupada = false
    private var buscando = false
    private var destruida = false
    private var visible = false

    private val refresco = object : Runnable {

        /** Actualiza el estado que la sincronización escribe mientras esta pantalla está visible. */
        override fun run() {
            if (visible && !destruida && ::repositorio.isInitialized && protector.comprobar()) {
                actualizarVista()
                principal.postDelayed(this, 2_000L)
            }
        }
    }

    /**
     * Android entrega el borrador público. Construye las tarjetas y conecta sus botones.
     * La dirección se puede recuperar tras girar; el código de permiso no se guarda.
     */
    override fun onCreate(guardado: Bundle?) {
        super.onCreate(guardado)
        if (!protector.permitir(guardado)) {
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.pantalla_cuenta_local)
        VentanaAgenda.preparar(this, findViewById(R.id.cuenta_raiz))
        findViewById<TextView>(R.id.cuenta_titulo).setText(R.string.conexion_pc_titulo)
        findViewById<View>(R.id.cuenta_volver).setOnClickListener {
            finish()
        }

        espacio = EspacioPersonal(this)
        repositorio = RepositorioConexionPC(this)
        descubridor = DescubridorPC(
            this,
            ::mostrarComputadora,
        ) { mensaje ->
            if (!destruida) {
                buscando = false
                buscar.isEnabled = !ocupada
                buscar.setText(R.string.conexion_pc_buscar)
                if (mensaje != null) {
                    informar(mensaje)
                }
            }
        }

        construirPantalla(findViewById(R.id.cuenta_contenido))
        direccion.setText(guardado?.getString("direccion_pc_escrita").orEmpty())
        actualizarVista()
    }

    /**
     * Recibe la columna de la cuenta y coloca el estado, el vínculo y el formulario.
     * Las tarjetas usan los mismos colores y espacios que el resto de Configuración.
     */
    private fun construirPantalla(contenido: LinearLayout) {
        estado = ComponentesAgenda.texto(this, "", 14f, R.color.texto_secundario).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, 0, 0, ComponentesAgenda.dp(this@ActividadConexionPC, 12))
        }
        contenido.addView(estado)

        tarjetaVinculo = ComponentesAgenda.tarjeta(this, destacada = true)
        tarjetaVinculo.addView(ComponentesAgenda.texto(this, getString(R.string.conexion_pc_vinculada), 22f, titulo = true))
        detalleVinculo = ComponentesAgenda.texto(this, "", 16f)
        tarjetaVinculo.addView(detalleVinculo)
        ultimaConexion = ComponentesAgenda.texto(this, "", 14f, R.color.texto_secundario)
        tarjetaVinculo.addView(ultimaConexion)
        sincronizar = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_sincronizar), principal = true) {
            ejecutar(getString(R.string.conexion_pc_sincronizando)) {
                SincronizadorPC.sincronizar(applicationContext)
            }
        }
        tarjetaVinculo.addView(sincronizar)
        reconectarQR = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_reconectar_qr)) {
            abrirEscanerQR()
        }
        tarjetaVinculo.addView(reconectarQR)
        buscarVinculada = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_buscar_vinculada)) {
            ejecutar(getString(R.string.conexion_pc_reconectando)) {
                SincronizadorPC.buscarYReconectar(applicationContext)
            }
        }
        tarjetaVinculo.addView(buscarVinculada)
        desconectar = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_desconectar)) {
            confirmarDesconexion()
        }
        tarjetaVinculo.addView(desconectar)
        contenido.addView(tarjetaVinculo)

        formulario = ComponentesAgenda.tarjeta(this)
        formulario.addView(ComponentesAgenda.texto(this, getString(R.string.conexion_pc_encontrar_titulo), 22f, titulo = true))
        formulario.addView(ComponentesAgenda.texto(this, getString(R.string.conexion_pc_encontrar_detalle), 14f, R.color.texto_secundario))
        escanear = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_escanear), principal = true) {
            abrirEscanerQR()
        }
        formulario.addView(escanear)
        buscar = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_buscar)) {
            buscarComputadoras()
        }
        formulario.addView(buscar)

        encontrados = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        formulario.addView(encontrados)
        direccion = campo(
            formulario,
            R.string.conexion_pc_direccion,
            R.string.conexion_pc_direccion_ayuda,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            120,
        )
        codigo = campo(
            formulario,
            R.string.conexion_pc_codigo,
            R.string.conexion_pc_codigo_ayuda,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            256,
        ).apply {
            // Evita que Android conserve el código temporal al recrear esta pantalla.
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        vincular = ComponentesAgenda.boton(this, getString(R.string.conexion_pc_vincular), principal = true) {
            confirmarVinculacion()
        }
        formulario.addView(vincular)
        contenido.addView(formulario)

        contenido.addView(ComponentesAgenda.texto(
            this,
            getString(R.string.conexion_pc_funcionamiento_detalle),
            14f,
            R.color.texto_secundario,
        ))
    }

    /**
     * Recibe la tarjeta, los textos, el tipo de teclado y la longitud máxima.
     * Devuelve un campo con etiqueta visible para escribir la dirección o pegar el código.
     */
    private fun campo(
        tarjeta: LinearLayout,
        etiqueta: Int,
        ayuda: Int,
        tipo: Int,
        longitud: Int,
    ): EditText {
        tarjeta.addView(ComponentesAgenda.espacio(this, 16))
        val textoEtiqueta = ComponentesAgenda.texto(this, getString(etiqueta), 14f)
        tarjeta.addView(textoEtiqueta)

        val entrada = EditText(this).apply {
            id = View.generateViewId()
            inputType = tipo
            hint = getString(ayuda)
            textSize = 16f
            setTextColor(getColor(R.color.ciruela))
            setHintTextColor(getColor(R.color.texto_secundario))
            minHeight = ComponentesAgenda.dp(this@ActividadConexionPC, 52)
            background = getDrawable(R.drawable.perfil_fondo_campo)
            val margen = ComponentesAgenda.dp(this@ActividadConexionPC, 12)
            setPadding(margen, margen, margen, margen)
            filters = arrayOf(InputFilter.LengthFilter(longitud))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = ComponentesAgenda.dp(this@ActividadConexionPC, 6)
            }
        }
        textoEtiqueta.labelFor = entrada.id
        tarjeta.addView(entrada)
        return entrada
    }

    /** Buscar mi PC empieza una búsqueda corta y mantiene disponible la dirección manual. */
    private fun buscarComputadoras() {
        if (ocupada) {
            return
        }

        encontrados.removeAllViews()
        buscando = true
        buscar.isEnabled = false
        buscar.setText(R.string.conexion_pc_buscando)
        descubridor.buscar()
    }

    /**
     * Recibe un equipo descubierto. La fila escribe su dirección y cierra la búsqueda al tocarla.
     * La persona todavía debe pegar el código de esa PC y confirmar Vincular.
     */
    private fun mostrarComputadora(computadora: ComputadoraPC) {
        if (destruida || !visible) {
            return
        }

        val vinculada = try {
            repositorio.leer() != null
        } catch (_: Exception) {
            actualizarVista()
            return
        }
        if (vinculada) {
            return
        }

        encontrados.addView(ComponentesAgenda.boton(this, "${computadora.nombre}\n${computadora.direccion}") {
            direccion.setText(computadora.direccion)
            descubridor.cancelar()
            buscando = false
            buscar.isEnabled = !ocupada
            buscar.setText(R.string.conexion_pc_buscar)
            codigo.requestFocus()
        })
    }

    /**
     * Recibe las direcciones y la identidad del QR, o datos vacíos para la entrada manual.
     * Vincular ofrece una confirmación con el alcance del permiso antes de comunicar datos.
     * Aceptar entrega el código al trabajador y vacía el campo visible.
     */
    private fun confirmarVinculacion(
        direccionesQR: List<String> = emptyList(),
        servidorQR: String? = null,
    ) {
        if (ocupada) {
            return
        }

        val url = try {
            ClientePC.validarDireccion(direccion.text.toString())
        } catch (error: IllegalArgumentException) {
            direccion.error = error.message
            direccion.requestFocus()
            return
        }

        val permiso = codigo.text.toString().trim()
        if (!permiso.matches(Regex("AVIP1\\.[0-9A-Fa-f]{64}\\.[A-Za-z0-9_-]{32,128}"))) {
            codigo.error = getString(R.string.conexion_pc_codigo_invalido)
            codigo.requestFocus()
            return
        }

        confirmacion?.dismiss()
        confirmacion = AlertDialog.Builder(this)
            .setTitle(R.string.conexion_pc_confirmar_titulo)
            .setMessage(getString(R.string.conexion_pc_confirmar_detalle, url))
            .setNegativeButton(R.string.conexion_pc_cancelar, null)
            .setPositiveButton(R.string.conexion_pc_vincular) { _, _ ->
                codigo.text.clear()
                ejecutar(getString(R.string.conexion_pc_vinculando)) {
                    if (servidorQR != null && direccionesQR.isNotEmpty()) {
                        SincronizadorPC.vincularDesdeQR(applicationContext, direccionesQR, permiso, servidorQR)
                    } else {
                        SincronizadorPC.vincular(applicationContext, url, permiso)
                    }
                }
            }
            .show()
    }

    /**
     * Los botones Escanear y Reconectar con QR abren la cámara del lector incluido en Agenda.VIP.
     * El lector pide su permiso a Android y devuelve solo el texto del QR, sin guardar una foto.
     */
    @Suppress("DEPRECATION")
    private fun abrirEscanerQR() {
        if (ocupada || !protector.comprobar()) {
            return
        }

        descubridor.cancelar()
        buscando = false
        actualizarVista()
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.conexion_pc_escanear_ayuda))
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        } catch (_: RuntimeException) {
            informar(getString(R.string.conexion_pc_qr_sin_camara))
        }
    }

    /**
     * Android entrega el texto leído por la cámara o avisa que se canceló el escaneo.
     * Solo el resultado del lector se interpreta aquí. Los demás códigos quedan para su pantalla.
     */
    @Deprecated("Compatibilidad con el lector QR basado en la Activity nativa de Android.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)
        val lectura = IntentIntegrator.parseActivityResult(solicitud, resultado, datos) ?: return
        if (!protector.comprobar()) {
            return
        }

        val contenido = lectura.contents
        if (contenido == null) {
            val mensaje = if (datos?.getBooleanExtra("MISSING_CAMERA_PERMISSION", false) == true) {
                R.string.conexion_pc_qr_sin_camara
            } else {
                R.string.conexion_pc_qr_cancelado
            }
            informar(getString(mensaje))
            return
        }

        recibirVinculacionQR(contenido)
    }

    /**
     * Recibe el texto completo y comprueba que describa una PC de Agenda.VIP.
     * Una cuenta nueva prepara la confirmación de vinculación. Una ya vinculada puede
     * recuperar la dirección del mismo servidor conservando su autorización y sus pendientes.
     */
    private fun recibirVinculacionQR(contenido: String) {
        try {
            require(contenido.length in 1..8192)
            val documento = JSONObject(contenido)
            require(documento.getString("tipo") == "AgendaVIP" && documento.opt("version") == 1)
            val servidorId = documento.getString("servidor_id")
            require(UUID.fromString(servidorId).toString() == servidorId)

            val permiso = documento.getString("codigo")
            require(permiso.matches(Regex("AVIP1\\.[0-9A-Fa-f]{64}\\.[A-Za-z0-9_-]{32,128}")))
            val huella = permiso.split('.')[1].lowercase(Locale.ROOT)
            val lista = documento.getJSONArray("direcciones")
            require(lista.length() in 1..16)
            val direcciones = (0 until lista.length()).mapNotNull { indice ->
                runCatching { ClientePC.validarDireccion(lista.getString(indice)) }.getOrNull()
            }.distinct()
            require(direcciones.isNotEmpty())

            val enlace = repositorio.leer()
            if (enlace == null) {
                direccion.setText(direcciones.first())
                codigo.setText(permiso)
                confirmarVinculacion(direcciones, servidorId)
                return
            }

            if (enlace.servidorId != servidorId || !enlace.huella.equals(huella, ignoreCase = true)) {
                informar(getString(R.string.conexion_pc_qr_otra_pc))
                return
            }

            ejecutar(getString(R.string.conexion_pc_reconectando)) {
                SincronizadorPC.actualizarDesdeQR(applicationContext, direcciones, servidorId, huella)
            }
        } catch (_: Exception) {
            informar(getString(R.string.conexion_pc_qr_invalido))
        }
    }

    /** Desconectar pide confirmación antes de retirar el permiso de esta cuenta para comunicarse con la PC. */
    private fun confirmarDesconexion() {
        if (ocupada) {
            return
        }

        confirmacion?.dismiss()
        confirmacion = AlertDialog.Builder(this)
            .setTitle(R.string.conexion_pc_desconectar_titulo)
            .setMessage(R.string.conexion_pc_desconectar_detalle)
            .setNegativeButton(R.string.conexion_pc_cancelar, null)
            .setPositiveButton(R.string.conexion_pc_desconectar) { _, _ ->
                ejecutar(getString(R.string.conexion_pc_desconectando)) {
                    SincronizadorPC.desconectar(applicationContext)
                }
            }
            .show()
    }

    /**
     * Recibe el texto de espera y una acción bloqueante para hacerla fuera de la pantalla.
     * Vincular, Sincronizar y Desconectar la comparten. Solo actualiza esta cuenta al terminar.
     */
    private fun ejecutar(mensaje: String, accion: () -> Unit) {
        if (ocupada || destruida || !protector.comprobar()) {
            return
        }

        ocupada = true
        descubridor.cancelar()
        buscando = false
        actualizarVista()
        estado.text = mensaje
        trabajador.execute {
            val problema = try {
                espacio.exigirVigente()
                accion()
                null
            } catch (_: org.json.JSONException) {
                getString(R.string.conexion_pc_datos_incorrectos)
            } catch (_: ClassCastException) {
                getString(R.string.conexion_pc_datos_incorrectos)
            } catch (error: Exception) {
                error.message ?: getString(R.string.conexion_pc_error)
            }

            principal.post {
                ocupada = false
                if (!destruida && protector.comprobar()) {
                    actualizarVista()
                    if (problema != null) {
                        informar(problema)
                    }
                }
            }
        }
    }

    /**
     * Lee el vínculo y el último resultado guardado, sin hacer una solicitud de red.
     * Oculta la autorización cuando ya existe un vínculo y muestra el momento de la última sincronización.
     */
    private fun actualizarVista() {
        try {
            mostrarVinculoGuardado()
        } catch (_: Exception) {
            formulario.visibility = View.GONE
            tarjetaVinculo.visibility = View.VISIBLE
            detalleVinculo.setText(R.string.conexion_pc_datos_incorrectos)
            ultimaConexion.setText(R.string.conexion_pc_recuperar_vinculo)
            estado.setText(R.string.conexion_pc_datos_incorrectos)
            sincronizar.isEnabled = false
            reconectarQR.isEnabled = false
            buscarVinculada.isEnabled = false
            desconectar.isEnabled = !ocupada
        }
    }

    /**
     * Lee los valores privados y actualiza las tarjetas. actualizarVista la protege para
     * explicar un archivo de conexión incorrecto y ofrecer retirarlo con confirmación.
     */
    private fun mostrarVinculoGuardado() {
        val enlace = repositorio.leer()
        formulario.visibility = if (enlace == null) View.VISIBLE else View.GONE
        tarjetaVinculo.visibility = if (enlace == null) View.GONE else View.VISIBLE
        buscar.isEnabled = !ocupada && !buscando
        buscar.setText(if (buscando) R.string.conexion_pc_buscando else R.string.conexion_pc_buscar)
        vincular.isEnabled = !ocupada
        escanear.isEnabled = !ocupada
        reconectarQR.isEnabled = !ocupada
        buscarVinculada.isEnabled = !ocupada
        sincronizar.isEnabled = !ocupada
        desconectar.isEnabled = !ocupada
        direccion.isEnabled = !ocupada
        codigo.isEnabled = !ocupada

        if (!ocupada) {
            estado.text = repositorio.ultimoEstado().ifBlank {
                getString(R.string.conexion_pc_sin_vinculo)
            }
        }

        if (enlace != null) {
            detalleVinculo.text = "${enlace.nombreServidor}\n${enlace.direccion}"
            val instante = repositorio.ultimaSincronizacion()
            ultimaConexion.text = if (instante > 0L) {
                val fecha = SimpleDateFormat("d/M/yyyy h:mm a", Locale("es", "DO")).format(Date(instante))
                getString(R.string.conexion_pc_ultima, fecha)
            } else {
                getString(R.string.conexion_pc_primera_pendiente)
            }
        }
    }

    /** Al mostrar la pantalla comprueba la cuenta y empieza a actualizar su estado visible. */
    override fun onResume() {
        super.onResume()
        visible = true
        if (::repositorio.isInitialized && protector.comprobar()) {
            principal.removeCallbacks(refresco)
            refresco.run()
        }
    }

    /** Guarda únicamente la dirección escrita y la identidad de la cuenta al recrear la pantalla. */
    override fun onSaveInstanceState(guardado: Bundle) {
        protector.guardarEstado(guardado)
        if (::direccion.isInitialized) {
            guardado.putString("direccion_pc_escrita", direccion.text.toString())
        }
        super.onSaveInstanceState(guardado)
    }

    /** Al dejar de verse, retira la búsqueda local y el refresco para ahorrar trabajo y batería. */
    override fun onStop() {
        visible = false
        buscando = false
        principal.removeCallbacks(refresco)
        if (::descubridor.isInitialized) {
            descubridor.cancelar()
        }
        if (::buscar.isInitialized) {
            buscar.isEnabled = !ocupada
            buscar.setText(R.string.conexion_pc_buscar)
        }
        super.onStop()
    }

    /** Cierra diálogos y búsquedas; deja terminar una operación que la persona ya había confirmado. */
    override fun onDestroy() {
        destruida = true
        confirmacion?.dismiss()
        if (::descubridor.isInitialized) {
            descubridor.cancelar()
        }
        trabajador.shutdown()
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Recibe una explicación breve y la muestra junto al estado, sin abandonar la pantalla. */
    private fun informar(mensaje: String) {
        estado.text = mensaje
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}