package com.nowel.agendavip.presentacion.acceso

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.ErrorUsuarioOcupado
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.cuentas.SesionLocal
import com.nowel.agendavip.dominio.acceso.ErrorFormulario
import com.nowel.agendavip.dominio.acceso.ValidadorFormulario
import com.nowel.agendavip.presentacion.agenda.ActividadAgenda
import com.nowel.agendavip.seguridad.AutenticacionDispositivo
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conecta los formularios con las cuentas que se guardan en este teléfono.
 * ValidadorFormulario revisa los campos; RepositorioCuentas comprueba o crea
 * la cuenta y CoordinadorSesion abre su agenda o el espacio invitado separado.
 * Un Executor es una cola de trabajo fuera del hilo que Android usa para dibujar.
 * Aquí evita que la comprobación costosa de contraseñas detenga la pantalla.
 */
class ActividadAcceso : Activity() {

    private enum class Pantalla {
        INICIO_SESION,
        REGISTRO,
    }

    // Solo recuerda posiciones, nunca el texto del campo ni la contraseña.
    private data class SeleccionEnEspera(
        val campoId: Int,
        val inicio: Int,
        val fin: Int,
    )

    /**
     * Transporta solo el resultado público del trabajador, nunca su contraseña.
     * Entrada indica qué propietario debe seguir activo antes de abrir la agenda.
     * Problema identifica un texto español y, cuando procede, un campo por corregir.
     */
    private sealed class ResultadoAcceso {
        data class Entrada(
            val cuentaId: String?,
            val invitado: Boolean,
        ) : ResultadoAcceso()

        data class Problema(
            val mensaje: Int,
            val campo: Int? = null,
        ) : ResultadoAcceso()

        // El alta pudo confirmarse aunque todavía no se completaran copia, perfil o sesión.
        data class RegistroGuardadoSinEntrada(val usuario: String) : ResultadoAcceso()

        // Solo contiene usuarios de acceso, tras verificar el teléfono.
        data class UsuariosGuardados(val usuarios: List<String>) : ResultadoAcceso()

        object SinEntrada : ResultadoAcceso()
    }

    /**
     * Agrupa las copias temporales de las claves de una operación.
     * AtomicBoolean permite avisar al trabajador de que la pantalla canceló su labor.
     * Tanto la cancelación como el bloque finally deben vaciar estas copias.
     */
    private class TrabajoAcceso(val secretos: List<CharArray>) {
        val cancelado = AtomicBoolean(false)

        /**
         * Sustituye cada carácter temporal por cero. Se puede llamar varias veces
         * para limpiar también operaciones que nunca llegaron a salir de la cola.
         */
        fun borrarSecretos() {
            secretos.forEach { secreto ->
                secreto.fill('\u0000')
            }
        }
    }

    private var pantallaActual = Pantalla.INICIO_SESION
    private val ejecutor = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private var futuro: Future<*>? = null
    private var seleccionEnEspera: SeleccionEnEspera? = null
    private lateinit var verificacionUsuario: AutenticacionDispositivo
    private var dialogoAyuda: AlertDialog? = null
    private var consultaAutorizada = false

    @Volatile
    private var visible = false

    @Volatile
    private var trabajoActual: TrabajoAcceso? = null

    // Su constructor también lee preferencias: se prepara en la cola y aquí solo se leen sus getters.
    @Volatile
    private var sesionConsultada: SesionLocal? = null

    /**
     * Android entrega el estado público guardado al crear esta pantalla.
     * Recupera el formulario que estaba abierto y prepara sus botones.
     * onStart revisará la sesión existente con el repositorio antes de redirigir;
     * el antiguo indicador de PerfilLocal ya no concede acceso a una cuenta.
     */
    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)

        verificacionUsuario = AutenticacionDispositivo(
            actividad = this,
            alAutorizar = {
                consultaAutorizada = pantallaActual == Pantalla.INICIO_SESION
                consultarUsuariosAutorizados()
            },
            alFallar = { mensaje ->
                Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
            },
            titulo = getString(R.string.cuentas_acceso_buscar_usuario),
        )

        val estabaEnRegistro = estadoGuardado?.getString(CLAVE_PANTALLA) == Pantalla.REGISTRO.name

        if (estabaEnRegistro) {
            mostrarRegistro()
        } else {
            mostrarInicioSesion()
        }

        prepararRetrocesoModerno()
    }

    /**
     * Android llama esta función cuando acceso empieza a ser visible.
     * Comprueba en segundo plano si sigue abierta una cuenta válida o el invitado.
     * Volver desde otra aplicación repite la revisión sin conservar contraseñas.
     */
    override fun onStart() {
        super.onStart()
        visible = true
        comprobarEntradaExistente()
    }

    /**
     * Recibe el Bundle, un paquete temporal de Android, antes de recrear la pantalla.
     * Solo añade el nombre del formulario. Android recuerda texto, errores y cursor
     * de los campos públicos; las contraseñas tienen saveEnabled=false en el XML.
     */
    override fun onSaveInstanceState(estado: Bundle) {
        estado.putString(CLAVE_PANTALLA, pantallaActual.name)
        super.onSaveInstanceState(estado)
    }

    /**
     * Prepara el inicio de sesión y conecta Entrar con la autenticación real.
     * Regístrate cambia de formulario y Continuar sin cuenta abre el invitado.
     * No recibe ni devuelve datos; conserva el aspecto definido en el XML.
     */
    private fun mostrarInicioSesion() {
        cambiarPantalla(R.layout.pantalla_inicio_sesion, Pantalla.INICIO_SESION)

        val usuario = findViewById<EditText>(R.id.campo_usuario)
        val contrasena = findViewById<EditText>(R.id.campo_contrasena)
        val visibilidad = findViewById<ImageButton>(R.id.boton_visibilidad_contrasena)

        configurarVisibilidadContrasena(contrasena, visibilidad)
        intent.getStringExtra(EXTRA_USUARIO_SUGERIDO)?.let { sugerido ->
            // Cerrar sesión solo sugiere el texto. Entrar comprueba las credenciales como siempre.
            intent.removeExtra(EXTRA_USUARIO_SUGERIDO)
            if (sugerido.length in 3..80) {
                usuario.setText(sugerido)
                usuario.setSelection(usuario.length())
            }
        }

        findViewById<View>(R.id.enlace_ayuda_acceso).setOnClickListener {
            mostrarAyudaAcceso()
        }

        findViewById<View>(R.id.boton_entrar).setOnClickListener {
            revisarInicioSesion(usuario, contrasena)
        }

        findViewById<View>(R.id.enlace_registro).setOnClickListener {
            mostrarRegistro()
        }

        findViewById<View>(R.id.boton_agenda_local).setOnClickListener {
            entrarComoInvitado()
        }

        contrasena.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_DONE) {
                revisarInicioSesion(usuario, contrasena)
                true
            } else {
                false
            }
        }
    }

    /**
     * El enlace de ayuda abre esta explicación y ofrece comprobar el teléfono.
     * Solo Buscar mi usuario abre la verificación de Android, sin pedir el PIN en la app.
     */
    private fun mostrarAyudaAcceso() {
        if (trabajoActual != null) {
            return
        }

        dialogoAyuda?.dismiss()
        dialogoAyuda = AlertDialog.Builder(this)
            .setTitle(R.string.cuentas_acceso_ayuda_credenciales)
            .setMessage(R.string.cuentas_acceso_ayuda_detalle)
            .setPositiveButton(R.string.cuentas_acceso_buscar_usuario) { _, _ ->
                verificacionUsuario.solicitar()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Android confirma el teléfono y esta función solicita la lista una sola vez.
     * Si al volver del PIN se está comprobando la sesión, el resultado SinEntrada
     * la vuelve a llamar cuando el formulario quede libre.
     */
    private fun consultarUsuariosAutorizados() {
        if (!consultaAutorizada || !visible || trabajoActual != null || isFinishing || isDestroyed) {
            return
        }

        consultaAutorizada = false
        if (pantallaActual != Pantalla.INICIO_SESION) {
            return
        }

        iniciarTrabajo(textoCarga = R.string.cuentas_acceso_buscando_usuario) { trabajo ->
            val usuarios = RepositorioCuentas(applicationContext).usuariosGuardados()
            comprobarTrabajoVigente(trabajo)
            ResultadoAcceso.UsuariosGuardados(usuarios)
        }
    }

    /**
     * Recibe los usuarios locales tras la comprobación del teléfono.
     * Al tocar uno, lo escribe en el campo Usuario y deja el cursor en Contraseña.
     */
    private fun mostrarUsuariosGuardados(usuarios: List<String>) {
        if (usuarios.isEmpty()) {
            mostrarProblema(R.string.cuentas_acceso_sin_usuarios)
            return
        }

        dialogoAyuda?.dismiss()
        dialogoAyuda = AlertDialog.Builder(this)
            .setTitle("Elige tu usuario")
            .setItems(usuarios.toTypedArray()) { _, posicion ->
                val usuario = findViewById<EditText>(R.id.campo_usuario)
                usuario.setText(usuarios[posicion])
                usuario.setSelection(usuario.length())
                findViewById<EditText>(R.id.campo_contrasena).requestFocus()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Android entrega el resultado del PIN o patrón cuando se usó como alternativa
     * a la huella. El comprobador solo atiende su propio código de solicitud.
     */
    @Deprecated("Compatibilidad con el cuadro nativo de credenciales.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)
        verificacionUsuario.recibirResultado(solicitud, resultado)
    }

    /**
     * Prepara los cuatro campos de registro y sus botones para mostrar las claves.
     * Crear cuenta llama revisarRegistro; el enlace inferior vuelve al inicio.
     * La casilla de copia permite pedir la agenda invitada y empieza desmarcada.
     */
    private fun mostrarRegistro() {
        cambiarPantalla(R.layout.pantalla_registro, Pantalla.REGISTRO)

        val nombre = findViewById<EditText>(R.id.campo_nombre)
        val usuario = findViewById<EditText>(R.id.campo_usuario_registro)
        val contrasena = findViewById<EditText>(R.id.campo_contrasena_registro)
        val confirmacion = findViewById<EditText>(R.id.campo_confirmar_contrasena)

        configurarVisibilidadContrasena(
            contrasena,
            findViewById(R.id.boton_visibilidad_contrasena_registro),
        )

        configurarVisibilidadContrasena(
            confirmacion,
            findViewById(R.id.boton_visibilidad_confirmacion),
            esConfirmacion = true,
        )

        findViewById<View>(R.id.boton_crear_cuenta).setOnClickListener {
            revisarRegistro(nombre, usuario, contrasena, confirmacion)
        }

        findViewById<View>(R.id.enlace_inicio_sesion).setOnClickListener {
            mostrarInicioSesion()
        }

        confirmacion.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_DONE) {
                revisarRegistro(nombre, usuario, contrasena, confirmacion)
                true
            } else {
                false
            }
        }
    }

    /**
     * Recibe el diseño XML y el formulario que debe quedar visible.
     * Cancela el trabajo anterior, vacía sus claves y prepara las barras de Android.
     * Los enlaces y el gesto Atrás la usan; cambiar de formulario no inicia sesión.
     */
    private fun cambiarPantalla(diseno: Int, pantalla: Pantalla) {
        cancelarTrabajo()
        ocultarTeclado()
        limpiarContrasenas()

        pantallaActual = pantalla
        setContentView(diseno)
        VentanaAcceso.preparar(this)

        val desplazamiento = findViewById<ScrollView>(R.id.desplazamiento_acceso)
        VentanaAcceso.aplicarMargenes(desplazamiento)
    }

    /**
     * Entrar y la tecla Listo entregan aquí sus campos.
     * Copia la clave sin convertirla en String y pide comprobarla en segundo plano.
     * Un resultado nulo da el mismo mensaje para usuario inexistente, clave
     * incorrecta o espera entre intentos, sin revelar si existe esa cuenta.
     */
    private fun revisarInicioSesion(usuario: EditText, contrasena: EditText) {
        if (trabajoActual != null || !visible) {
            return
        }

        val usuarioEscrito = usuario.text.toString()
        val secreto = copiarContrasena(contrasena)
        var entregadoAlTrabajador = false

        try {
            val errores = ValidadorFormulario.validarInicioSesion(usuarioEscrito, secreto)
            mostrarErrores(errores, camposInicioSesion(usuario, contrasena))

            if (errores.isNotEmpty()) {
                return
            }

            iniciarTrabajo(
                textoCarga = R.string.cuentas_acceso_entrando,
                secretos = listOf(secreto),
            ) { trabajo ->
                val cuenta = RepositorioCuentas(applicationContext).autenticar(
                    usuarioEscrito,
                    secreto,
                )

                comprobarTrabajoVigente(trabajo)

                if (cuenta == null) {
                    ResultadoAcceso.Problema(R.string.cuentas_acceso_credenciales_incorrectas)
                } else {
                    CoordinadorSesion(applicationContext).entrar(cuenta)
                    comprobarTrabajoVigente(trabajo)
                    ResultadoAcceso.Entrada(cuenta.id, invitado = false)
                }
            }

            entregadoAlTrabajador = true
        } finally {
            if (!entregadoAlTrabajador) {
                secreto.fill('\u0000')
            }
        }
    }

    /**
     * Crear cuenta entrega los cuatro campos y recibe las correcciones en pantalla.
     * El trabajador guarda la cuenta y abre su espacio si el formulario sigue activo.
     * Copiar la agenda invitada depende de la casilla, nunca del perfil.
     * Las copias de ambas claves se vacían aunque la revisión o el guardado fallen.
     */
    private fun revisarRegistro(
        nombre: EditText,
        usuario: EditText,
        contrasena: EditText,
        confirmacion: EditText,
    ) {
        if (trabajoActual != null || !visible) {
            return
        }

        val nombreEscrito = nombre.text.toString()
        val usuarioEscrito = usuario.text.toString()
        val secreto = copiarContrasena(contrasena)
        val repeticion = copiarContrasena(confirmacion)
        val copiarAgenda = findViewById<CheckBox>(R.id.casilla_copiar_agenda_invitada).isChecked
        var entregadoAlTrabajador = false

        try {
            val errores = ValidadorFormulario.validarRegistro(
                nombreEscrito,
                usuarioEscrito,
                secreto,
                repeticion,
            )

            val campos = camposInicioSesion(usuario, contrasena).toMutableMap()
            campos[ErrorFormulario.NOMBRE_VACIO] = nombre
            campos[ErrorFormulario.NOMBRE_LARGO] = nombre
            campos[ErrorFormulario.CONFIRMACION_VACIA] = confirmacion
            campos[ErrorFormulario.CONFIRMACION_DIFERENTE] = confirmacion
            mostrarErrores(errores, campos)

            if (errores.isNotEmpty()) {
                return
            }

            iniciarTrabajo(
                textoCarga = R.string.cuentas_acceso_creando,
                secretos = listOf(secreto, repeticion),
            ) { trabajo ->
                val cuenta = RepositorioCuentas(applicationContext).crear(
                    nombreEscrito,
                    usuarioEscrito,
                    secreto,
                )

                comprobarTrabajoVigente(trabajo)

                try {
                    CoordinadorSesion(applicationContext).entrar(cuenta, copiarAgenda)
                    comprobarTrabajoVigente(trabajo)
                    ResultadoAcceso.Entrada(cuenta.id, invitado = false)
                } catch (_: Exception) {
                    // Crear ya confirmó la cuenta. No se debe pedir otro registro:
                    // el siguiente inicio de sesión puede terminar una copia pendiente.
                    comprobarTrabajoVigente(trabajo)
                    ResultadoAcceso.RegistroGuardadoSinEntrada(cuenta.usuario)
                }
            }

            entregadoAlTrabajador = true
        } finally {
            if (!entregadoAlTrabajador) {
                secreto.fill('\u0000')
                repeticion.fill('\u0000')
            }
        }
    }

    /**
     * Continuar sin cuenta abre el espacio invitado separado.
     * El coordinador prepara sus datos fuera del hilo principal y el resultado
     * exige que siga activo ese invitado antes de abrir la agenda.
     */
    private fun entrarComoInvitado() {
        iniciarTrabajo(textoCarga = R.string.cuentas_acceso_abriendo_invitado) { trabajo ->
            comprobarTrabajoVigente(trabajo)
            CoordinadorSesion(applicationContext).entrarComoInvitado()
            comprobarTrabajoVigente(trabajo)
            ResultadoAcceso.Entrada(cuentaId = null, invitado = true)
        }
    }

    /**
     * onStart pide revisar el marcador de sesión y la cuenta guardada.
     * No acepta un identificador sin cuenta: cierra solo ese marcador inválido e
     * informa del problema. Un error de lectura no elimina cuentas, perfil ni tareas.
     */
    private fun comprobarEntradaExistente() {
        iniciarTrabajo(
            textoCarga = R.string.cuentas_acceso_comprobando,
            conservarMensaje = true,
        ) { trabajo ->
            val sesion = sesionConsultada
                ?: throw IllegalStateException("No se pudo preparar la lectura de sesión.")

            if (!sesion.hayEntradaActiva) {
                ResultadoAcceso.SinEntrada
            } else {
                val cuentaId = sesion.cuentaIdActiva

                if (cuentaId != null) {
                    val cuenta = RepositorioCuentas(applicationContext).obtener(cuentaId)
                    comprobarTrabajoVigente(trabajo)

                    if (cuenta == null) {
                        CoordinadorSesion(applicationContext).cerrarSesion()
                        ResultadoAcceso.Problema(R.string.cuentas_acceso_cuenta_no_disponible)
                    } else {
                        ResultadoAcceso.Entrada(cuenta.id, invitado = false)
                    }
                } else if (sesion.invitadoActivo) {
                    ResultadoAcceso.Entrada(cuentaId = null, invitado = true)
                } else {
                    ResultadoAcceso.SinEntrada
                }
            }
        }
    }

    /**
     * Recibe texto de espera, copias de claves y la operación que usa los datos.
     * conservarMensaje mantiene el error visible al revisar la sesión después de volver.
     * Ejecuta la operación en la cola y devuelve solo el resultado público al dibujo.
     * Captura errores previstos sin borrar datos. finally limpia las claves incluso
     * cuando Android interrumpe el trabajo al salir o recrear esta pantalla.
     */
    private fun iniciarTrabajo(
        textoCarga: Int,
        secretos: List<CharArray> = emptyList(),
        conservarMensaje: Boolean = false,
        operacion: (TrabajoAcceso) -> ResultadoAcceso,
    ) {
        if (trabajoActual != null || !visible || isFinishing || isDestroyed) {
            secretos.forEach { secreto ->
                secreto.fill('\u0000')
            }
            return
        }

        val trabajo = TrabajoAcceso(secretos)
        trabajoActual = trabajo
        ocultarTeclado()

        if (!conservarMensaje) {
            limpiarMensaje()
        }

        actualizarCarga(ocupado = true, textoCarga = textoCarga)

        futuro = ejecutor.submit {
            val resultado = try {
                comprobarTrabajoVigente(trabajo)

                if (sesionConsultada == null) {
                    sesionConsultada = SesionLocal(applicationContext)
                }

                comprobarTrabajoVigente(trabajo)
                operacion(trabajo)
            } catch (_: CancellationException) {
                return@submit
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@submit
            } catch (_: ErrorUsuarioOcupado) {
                ResultadoAcceso.Problema(
                    R.string.cuentas_acceso_usuario_duplicado,
                    R.id.campo_usuario_registro,
                )
            } catch (_: IllegalArgumentException) {
                ResultadoAcceso.Problema(R.string.cuentas_acceso_datos_no_validos)
            } catch (_: IllegalStateException) {
                ResultadoAcceso.Problema(R.string.cuentas_acceso_error_guardado)
            } catch (_: Exception) {
                // Otros fallos de lectura, por ejemplo una copia con formato dañado,
                // también deben devolver el control del formulario sin borrar datos.
                ResultadoAcceso.Problema(R.string.cuentas_acceso_error_guardado)
            } finally {
                trabajo.borrarSecretos()
            }

            principal.post {
                if (esTrabajoVigente(trabajo)) {
                    trabajoActual = null
                    futuro = null
                    actualizarCarga(ocupado = false)
                    presentarResultado(resultado)
                }
            }
        }
    }

    /**
     * Recibe el trabajo y aborta si fue cancelado, interrumpido o se salió de acceso.
     * Las operaciones la usan antes y después de tocar la sesión para que una
     * pantalla anterior no vuelva a abrir la agenda al terminar tarde.
     */
    private fun comprobarTrabajoVigente(trabajo: TrabajoAcceso) {
        if (!esTrabajoVigente(trabajo) || Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    /**
     * Devuelve si este trabajo pertenece todavía a la pantalla visible.
     * Comparar su identidad evita confundir una operación cancelada con otra nueva.
     */
    private fun esTrabajoVigente(trabajo: TrabajoAcceso): Boolean {
        return visible &&
            !isFinishing &&
            !isDestroyed &&
            trabajoActual === trabajo &&
            !trabajo.cancelado.get()
    }

    /**
     * Recibe el resultado público de la cola y lo presenta en el hilo de Android.
     * Antes de navegar compara otra vez el propietario activo de la sesión.
     * Un error mantiene los campos y el cursor para permitir corregirlos.
     */
    private fun presentarResultado(resultado: ResultadoAcceso) {
        when (resultado) {
            is ResultadoAcceso.Entrada -> {
                try {
                    val sesion = sesionConsultada
                        ?: throw IllegalStateException("La lectura de sesión no está preparada.")
                    val propietarioCorrecto = if (resultado.invitado) {
                        sesion.invitadoActivo && sesion.cuentaIdActiva == null
                    } else {
                        !sesion.invitadoActivo && sesion.cuentaIdActiva == resultado.cuentaId
                    }

                    if (sesion.hayEntradaActiva && propietarioCorrecto) {
                        abrirAgenda()
                    } else {
                        mostrarProblema(R.string.cuentas_acceso_sesion_cambio)
                    }
                } catch (_: IllegalArgumentException) {
                    mostrarProblema(R.string.cuentas_acceso_error_guardado)
                } catch (_: IllegalStateException) {
                    mostrarProblema(R.string.cuentas_acceso_error_guardado)
                }
            }

            is ResultadoAcceso.Problema -> {
                mostrarProblema(resultado.mensaje, resultado.campo)
            }

            is ResultadoAcceso.RegistroGuardadoSinEntrada -> {
                mostrarInicioSesion()

                val usuario = findViewById<EditText>(R.id.campo_usuario)
                usuario.setText(resultado.usuario)
                usuario.setSelection(usuario.length())
                findViewById<EditText>(R.id.campo_contrasena).requestFocus()
                mostrarProblema(R.string.cuentas_acceso_registro_guardado_sin_entrada)
            }

            is ResultadoAcceso.UsuariosGuardados -> {
                mostrarUsuariosGuardados(resultado.usuarios)
            }

            ResultadoAcceso.SinEntrada -> consultarUsuariosAutorizados()
        }
    }

    /**
     * Recibe un campo de clave y devuelve una copia sin convertirla en String.
     * El trabajador o el bloque finally de revisión vaciarán esa copia temporal.
     */
    private fun copiarContrasena(campo: EditText): CharArray {
        val texto = campo.text
        val copia = CharArray(texto.length)
        texto.getChars(0, texto.length, copia, 0)
        return copia
    }

    /**
     * Relaciona los errores compartidos con los dos campos recibidos.
     * Inicio de sesión y registro reutilizan esta tabla para marcar usuario o clave.
     */
    private fun camposInicioSesion(
        usuario: EditText,
        contrasena: EditText,
    ): Map<ErrorFormulario, EditText> {
        return mapOf(
            ErrorFormulario.USUARIO_VACIO to usuario,
            ErrorFormulario.USUARIO_INVALIDO to usuario,
            ErrorFormulario.CONTRASENA_VACIA to contrasena,
            ErrorFormulario.CONTRASENA_CORTA to contrasena,
            ErrorFormulario.CONTRASENA_LARGA to contrasena,
        )
    }

    /**
     * Recibe los problemas y la tabla que indica dónde presentarlos.
     * Limpia avisos anteriores y enfoca la primera corrección sin reemplazar
     * el texto ni mover la selección que ya tenía ese campo.
     */
    private fun mostrarErrores(
        errores: List<ErrorFormulario>,
        campos: Map<ErrorFormulario, EditText>,
    ) {
        limpiarMensaje()

        campos.values.toSet().forEach { campo ->
            campo.error = null
        }

        errores.forEach { error ->
            campos.getValue(error).error = getString(mensajeDelError(error))
        }

        errores.firstOrNull()?.let { primerError ->
            campos.getValue(primerError).requestFocus()
        }
    }

    /**
     * Recibe un error y devuelve el recurso español que explica cómo corregirlo.
     * R.string identifica textos; R.id identifica controles del diseño XML.
     */
    private fun mensajeDelError(error: ErrorFormulario): Int {
        return when (error) {
            ErrorFormulario.NOMBRE_VACIO -> R.string.error_nombre_vacio
            ErrorFormulario.NOMBRE_LARGO -> R.string.cuentas_acceso_nombre_largo
            ErrorFormulario.USUARIO_VACIO -> R.string.error_usuario_vacio
            ErrorFormulario.USUARIO_INVALIDO -> R.string.cuentas_acceso_usuario_invalido
            ErrorFormulario.CONTRASENA_VACIA -> R.string.error_contrasena_vacia
            ErrorFormulario.CONTRASENA_CORTA -> R.string.cuentas_acceso_contrasena_corta
            ErrorFormulario.CONTRASENA_LARGA -> R.string.cuentas_acceso_contrasena_larga
            ErrorFormulario.CONFIRMACION_VACIA -> R.string.error_confirmacion_vacia
            ErrorFormulario.CONFIRMACION_DIFERENTE -> R.string.error_contrasenas_diferentes
        }
    }

    /**
     * Muestra un texto junto al botón y también señala el campo opcional recibido.
     * La región accesible anuncia el resultado sin abrir un cuadro sobre el formulario.
     */
    private fun mostrarProblema(mensaje: Int, campoId: Int? = null) {
        val estado = findViewById<TextView>(R.id.estado_cuentas_acceso)
        estado.setText(mensaje)
        estado.visibility = View.VISIBLE

        if (campoId != null) {
            findViewById<EditText>(campoId)?.let { campo ->
                campo.error = getString(mensaje)
                campo.requestFocus()
            }
        }
    }

    /**
     * Oculta el mensaje general anterior antes de una revisión nueva.
     * No cambia los campos ni sus selecciones.
     */
    private fun limpiarMensaje() {
        findViewById<TextView>(R.id.estado_cuentas_acceso)?.let { estado ->
            estado.text = ""
            estado.visibility = View.GONE
        }
    }

    /**
     * Recibe si hay trabajo y el texto que explica la espera en el botón.
     * Desactiva los datos mientras se usan sus copias y evita pulsaciones repetidas.
     * Los enlaces entre formularios siguen disponibles para cancelar y volver.
     */
    private fun actualizarCarga(ocupado: Boolean, textoCarga: Int = R.string.accion_entrar) {
        if (ocupado) {
            seleccionEnEspera = (currentFocus as? EditText)?.let { campo ->
                SeleccionEnEspera(
                    campo.id,
                    campo.selectionStart.coerceIn(0, campo.length()),
                    campo.selectionEnd.coerceIn(0, campo.length()),
                )
            }
        }

        val controles = listOf(
            R.id.campo_nombre,
            R.id.campo_usuario,
            R.id.campo_usuario_registro,
            R.id.campo_contrasena,
            R.id.campo_contrasena_registro,
            R.id.campo_confirmar_contrasena,
            R.id.boton_visibilidad_contrasena,
            R.id.boton_visibilidad_contrasena_registro,
            R.id.boton_visibilidad_confirmacion,
            R.id.casilla_copiar_agenda_invitada,
            R.id.boton_entrar,
            R.id.boton_crear_cuenta,
            R.id.boton_agenda_local,
            R.id.enlace_ayuda_acceso,
        )

        controles.forEach { id ->
            findViewById<View>(id)?.isEnabled = !ocupado
        }

        findViewById<Button>(R.id.boton_entrar)?.setText(
            if (ocupado) textoCarga else R.string.accion_entrar,
        )

        findViewById<Button>(R.id.boton_crear_cuenta)?.setText(
            if (ocupado) textoCarga else R.string.accion_crear_cuenta,
        )

        findViewById<Button>(R.id.boton_agenda_local)?.setText(
            if (ocupado && textoCarga == R.string.cuentas_acceso_abriendo_invitado) {
                textoCarga
            } else {
                R.string.agenda_continuar_local
            },
        )

        if (!ocupado) {
            seleccionEnEspera?.let { seleccion ->
                findViewById<EditText>(seleccion.campoId)?.let { campo ->
                    campo.requestFocus()
                    campo.setSelection(
                        seleccion.inicio.coerceIn(0, campo.length()),
                        seleccion.fin.coerceIn(0, campo.length()),
                    )
                }
            }

            seleccionEnEspera = null
        }
    }

    /**
     * Cancela la operación y limpia sus claves incluso si aún estaba en cola.
     * onStop y cambiarPantalla la usan antes de abandonar el formulario.
     * La interrupción permite al repositorio abortar antes de insertar una cuenta.
     */
    private fun cancelarTrabajo() {
        trabajoActual?.let { trabajo ->
            trabajo.cancelado.set(true)
            futuro?.cancel(true)
            trabajo.borrarSecretos()
        }

        trabajoActual = null
        futuro = null
        principal.removeCallbacksAndMessages(null)
        actualizarCarga(ocupado = false)
    }

    /**
     * Oculta el teclado antes de esperar o cambiar de formulario.
     * No recibe ni devuelve datos y termina si no hay un campo enfocado.
     */
    private fun ocultarTeclado() {
        val controlActivo = currentFocus ?: return
        val teclado = getSystemService(InputMethodManager::class.java)
        teclado.hideSoftInputFromWindow(controlActivo.windowToken, 0)
    }

    /**
     * Vacía los tres posibles campos de contraseña y los vuelve a ocultar.
     * Cambiar de formulario o salir no modifica el nombre ni el usuario escrito.
     */
    private fun limpiarContrasenas() {
        limpiarContrasena(R.id.campo_contrasena, R.id.boton_visibilidad_contrasena)
        limpiarContrasena(
            R.id.campo_contrasena_registro,
            R.id.boton_visibilidad_contrasena_registro,
        )
        limpiarContrasena(
            R.id.campo_confirmar_contrasena,
            R.id.boton_visibilidad_confirmacion,
            esConfirmacion = true,
        )
    }

    /**
     * Recibe el identificador del campo y del ojo, y si corresponde a confirmación.
     * Borra solo esa clave y restaura el botón; ignora los campos que no están visibles.
     */
    private fun limpiarContrasena(campoId: Int, botonId: Int, esConfirmacion: Boolean = false) {
        val campo = findViewById<EditText>(campoId) ?: return
        val boton = findViewById<ImageButton>(botonId) ?: return

        campo.text.clear()
        configurarVisibilidadContrasena(campo, boton, esConfirmacion)
    }

    /**
     * Conecta el gesto Atrás moderno con volverDesdeAcceso desde Android 13.
     * Prepara la respuesta sin cambiar ahora el formulario.
     */
    private fun prepararRetrocesoModerno() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) {
                volverDesdeAcceso()
            }
        }
    }

    /**
     * Android anterior a 13 usa esta entrada para el botón Atrás.
     * Reutiliza la misma navegación que el gesto moderno y no devuelve datos.
     */
    @Deprecated("Android 13 o posterior usa prepararRetrocesoModerno.")
    override fun onBackPressed() {
        volverDesdeAcceso()
    }

    /**
     * Cancela el trabajo antes de volver del registro al inicio o cerrar acceso.
     * El gesto no elimina cuentas ni datos de ninguna agenda.
     */
    private fun volverDesdeAcceso() {
        cancelarTrabajo()

        if (pantallaActual == Pantalla.REGISTRO) {
            mostrarInicioSesion()
        } else {
            finish()
        }
    }

    /**
     * Android la llama al dejar de verse acceso, también durante un giro.
     * Invalida resultados tardíos, interrumpe el trabajo y vacía todas las claves.
     * Al volver se podrá iniciar de nuevo el acceso o el registro.
     */
    override fun onStop() {
        visible = false
        consultaAutorizada = false
        dialogoAyuda?.dismiss()
        dialogoAyuda = null
        cancelarTrabajo()
        limpiarContrasenas()
        super.onStop()
    }

    /**
     * Libera la cola cuando Android destruye esta instancia.
     * No reutiliza un trabajador de una pantalla antigua después de girar el teléfono.
     */
    override fun onDestroy() {
        verificacionUsuario.cerrar()
        cancelarTrabajo()
        ejecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Abre la agenda después de comprobar que la sesión esperada sigue activa.
     * CLEAR_TASK y NEW_TASK sustituyen el recorrido de acceso para que Atrás no
     * vuelva a un formulario autenticado. El Intent no transporta contraseñas.
     */
    private fun abrirAgenda() {
        val destino = Intent(this, ActividadAgenda::class.java)
        destino.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(destino)
        finish()
    }

    companion object {
        internal const val EXTRA_USUARIO_SUGERIDO = "usuario_sugerido"
        private const val CLAVE_PANTALLA = "pantalla_acceso"
    }
}

