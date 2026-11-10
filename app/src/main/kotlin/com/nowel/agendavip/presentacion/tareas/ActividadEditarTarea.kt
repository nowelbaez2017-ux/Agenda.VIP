package com.nowel.agendavip.presentacion.tareas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.nowel.agendavip.R
import com.nowel.agendavip.presentacion.comun.ProtectorSesion
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.preferencias.PreferenciasAgenda
import com.nowel.agendavip.dominio.agenda.ModoMusica
import com.nowel.agendavip.dominio.agenda.Repeticion
import com.nowel.agendavip.dominio.agenda.Tarea
import com.nowel.agendavip.presentacion.comun.VentanaAgenda
import com.nowel.agendavip.presentacion.comun.VozDeMuestra
import com.nowel.agendavip.presentacion.cuenta.ActividadCuenta
import com.nowel.agendavip.presentacion.musica.ActividadSelectorMusica
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Controla las pantallas Nueva tarea y Editar tarea.
 * Usa el mismo diseño porque ambas tienen los mismos campos. FormularioTarea
 * conserva lo escrito; esta clase abre la música, revisa los datos y pide al
 * gestor de recordatorios que los guarde únicamente cuando pulsas Guardar.
 */
class ActividadEditarTarea : Activity() {

    // Recuerda la cuenta de esta pantalla y bloquea el regreso después de cerrar su sesión.
    private val proteccion = ProtectorSesion(this)

    private lateinit var formulario: FormularioTarea
    private lateinit var voz: VozDeMuestra
    private var original: Tarea? = null
    // Conserva la versión que se abrió, para detectar una edición recibida desde la computadora.
    private var baseEdicion: String? = null
    private var firmaInicial = ""
    private var guardada = false

    // Se pide acceso a las canciones al guardar una tarea aleatoria, nunca al abrir la app.
    // Si se deniega, la tarea se guarda igualmente y conserva sus avisos y su voz.
    private var esperandoPermisoMusica = false
    private var permisoMusicaRevisado = false
    private var permisoTomoFoco = false
    private var abriendoBiblioteca = false

    // Cada acción conserva como máximo una ventana. Los toques rápidos no abren copias.
    private var ventanaMusica: AlertDialog? = null
    private var ventanaGuardada: AlertDialog? = null
    private var ventanaSalida: AlertDialog? = null

    /**
     * Android llama aquí al abrir Nueva tarea o Editar tarea.
     * Recibe lo recordado antes de girar el teléfono, si existe. Crea el diseño y,
     * si se recibió tarea_id, busca esa tarea para editarla; de lo contrario prepara una nueva.
     * Solo una tarea nueva toma el seguimiento de Configuración como punto de partida.
     * Conecta Guardar, Volver y el selector musical sin guardar el borrador todavía.
     * No devuelve datos: prepara esta pantalla.
     */
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)

        if (!proteccion.permitir(estado)) {
            return
        }

        guardada = estado?.getBoolean("tarea_guardada") ?: false
        if (guardada) {
            // La tarea ya se guardó antes del giro. Volver a crear el formulario no la duplica.
            finish()
            return
        }

        esperandoPermisoMusica = estado?.getBoolean("tarea_esperando_permiso_musica") ?: false
        permisoTomoFoco = estado?.getBoolean("tarea_permiso_tomo_foco") ?: false
        permisoMusicaRevisado = estado?.getBoolean("tarea_permiso_musica_revisado") ?: false
        setContentView(R.layout.tarea_pantalla_formulario)
        VentanaAgenda.preparar(this, findViewById(R.id.tarea_raiz))
        voz = VozDeMuestra(this) {
            informar(it)
        }
        val id = intent.getStringExtra("tarea_id")
        try {
            original = id?.let {
                RepositorioTareas(this).obtener(it)
            }
            baseEdicion = estado?.getString("base_edicion")
                ?: original?.let { RepositorioTareas(this).escribirTarea(it).toString() }
            if (id != null && original == null) {
                informar("Esta tarea ya no existe.")
                finish()
                return
            }
        } catch (error: IllegalStateException) {
            mostrarError(error)
            return
        }

        val sugerencia = LocalDateTime.now()
            .plusHours(1)
            .withSecond(0)
            .withNano(0)
        val fecha = runCatching {
            LocalDate.parse(intent.getStringExtra("fecha_ocurrencia"))
        }.getOrDefault(sugerencia.toLocalDate())

        // ?: usa los datos originales si existen. El bloque siguiente se ejecuta
        // únicamente para una tarea nueva, sin cambiar las tareas que ya guardaste.
        val inicial = original ?: run {
            val preferencias = PreferenciasAgenda(this)
            Tarea(
                id = estado?.getString("id_tarea") ?: UUID.randomUUID().toString(),
                titulo = "",
                fecha = fecha,
                hora = sugerencia.toLocalTime(),
                seguimientoActivo = preferencias.seguimientoPredeterminado,
                intervaloMinutos = preferencias.intervaloMinutos,
                limiteAvisos = preferencias.limiteAvisos,
            )
        }

        findViewById<TextView>(R.id.tarea_pantalla_titulo).text = if (original == null) {
            "Nueva tarea"
        } else {
            "Editar tarea"
        }
        formulario = FormularioTarea(
            this,
            inicial,
            estado,
            elegirMusica = {
                abrirMusica()
            },
            escuchar = {
                voz.hablar(it)
            },
        )
        firmaInicial = estado?.getString("firma_inicial") ?: formulario.firma()
        findViewById<View>(R.id.tarea_guardar).setOnClickListener {
            guardar()
        }
        findViewById<View>(R.id.tarea_volver).setOnClickListener {
            volver()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0) {
                volver()
            }
        }
    }

    /** Android la llama al regresar; impide continuar si la cuenta cambió mientras estaba oculta. */
    override fun onResume() {
        super.onResume()

        if (!proteccion.comprobar()) {
            return
        }
        abriendoBiblioteca = false
    }

    /**
     * Android avisa si esta ventana puede recibir toques. Recibe tieneFoco y no devuelve datos.
     * Si la pregunta de permisos cerró sin entregar su respuesta habitual, retoma Guardar una
     * sola vez al recuperar el foco. Haberlo perdido evita adelantarse a la pregunta de Android.
     * Así un giro o una recuperación del formulario no deja Guardar bloqueado para siempre.
     */
    override fun onWindowFocusChanged(tieneFoco: Boolean) {
        super.onWindowFocusChanged(tieneFoco)

        if (!esperandoPermisoMusica) {
            return
        }
        if (!tieneFoco) {
            permisoTomoFoco = true
        } else if (permisoTomoFoco && ::formulario.isInitialized) {
            // La respuesta normal puede llegar antes de este trabajo; entonces ya no hay espera.
            window.decorView.post {
                if (hasWindowFocus() && esperandoPermisoMusica && !isFinishing && !isDestroyed) {
                    terminarEsperaMusical()
                }
            }
        }
    }

    /**
     * Recuerda los campos antes de que Android recree la pantalla, por ejemplo al girar.
     * Recibe estado, el paquete temporal de datos, y pide al formulario que lo llene.
     * También recuerda cómo estaba al principio para poder reconocer cambios pendientes.
     * Android la llama; no devuelve datos ni guarda la tarea en la agenda.
     */
    override fun onSaveInstanceState(estado: Bundle) {
        proteccion.guardarEstado(estado)
        estado.putBoolean("tarea_guardada", guardada)
        estado.putString("base_edicion", baseEdicion)

        if (::formulario.isInitialized) {
            formulario.guardarBorrador(estado)
            estado.putString("firma_inicial", firmaInicial)
            estado.putBoolean("tarea_esperando_permiso_musica", esperandoPermisoMusica)
            estado.putBoolean("tarea_permiso_musica_revisado", permisoMusicaRevisado)
            estado.putBoolean("tarea_permiso_tomo_foco", permisoTomoFoco)
        }
        super.onSaveInstanceState(estado)
    }

    /**
     * FormularioTarea la llama al tocar Música para decidir si habrá canción o no.
     * No recibe ni devuelve datos: abre una ventana con los dos botones pedidos.
     * Sin música cambia el borrador; Seleccionar música abre la biblioteca. Cerrar la
     * ventana con Atrás conserva el modo actual, incluida la elección aleatoria inicial.
     */
    private fun abrirMusica() {
        if (ventanaMusica?.isShowing == true || abriendoBiblioteca || esperandoPermisoMusica || guardada) {
            return
        }

        val borrador = formulario.leer()
        voz.detener()
        val explicacion = when (borrador.modoMusica) {
            ModoMusica.ALEATORIA -> getString(R.string.tarea_dialogo_musica_aleatoria)
            ModoMusica.SIN_MUSICA -> getString(R.string.tarea_dialogo_musica_sin)
            ModoMusica.SELECCIONADA -> getString(
                R.string.tarea_dialogo_musica_seleccionada,
                borrador.descripcionMusica(),
            )
        }

        ventanaMusica = AlertDialog.Builder(this)
            .setTitle(R.string.tarea_dialogo_musica_titulo)
            .setMessage(explicacion)
            .setNegativeButton(R.string.musica_sin) { _, _ ->
                formulario.seleccionarMusica(null, "")
            }
            .setPositiveButton(R.string.tarea_opcion_seleccionar_musica) { _, _ ->
                abrirBibliotecaMusical()
            }
            .setOnDismissListener {
                ventanaMusica = null
            }
            .show()
    }

    /**
     * El botón Seleccionar música de la ventana anterior la llama sin argumentos.
     * Envía título y elección actual al selector; no guarda la tarea ni devuelve datos.
     * Aleatoria no se envía como una elección de silencio: si cancelas la biblioteca,
     * el formulario sigue con su modo anterior y conserva todos los textos escritos.
     */
    @Suppress("DEPRECATION")
    private fun abrirBibliotecaMusical() {
        if (abriendoBiblioteca || esperandoPermisoMusica || guardada) {
            return
        }

        val borrador = formulario.leer()
        val destino = Intent(this, ActividadSelectorMusica::class.java)
            .putExtra("tarea_titulo", borrador.titulo.ifBlank { "Nueva tarea" })

        if (borrador.modoMusica != ModoMusica.ALEATORIA) {
            destino.putExtra("musica_uri", borrador.musicaUri)
            destino.putExtra("musica_nombre", borrador.musicaNombre)
        }

        abriendoBiblioteca = true
        startActivityForResult(destino, 301)
    }

    /**
     * Android llama aquí cuando se cierra otra pantalla abierta para elegir datos.
     * Recibe solicitud, que indica qué selección se abrió; resultado, que indica
     * si se confirmó; y datos, que contiene la canción elegida o Sin música.
     * Solo actualiza el formulario si confirmaste. Cancelar conserva la canción anterior.
     * No devuelve un valor; entrega la elección al formulario.
     */
    @Deprecated("Se conserva la API nativa sin añadir dependencias.")
    override fun onActivityResult(solicitud: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(solicitud, resultado, datos)

        if (!proteccion.comprobar()) {
            return
        }

        if (solicitud == 301 &&
            resultado == RESULT_OK &&
            datos != null &&
            ::formulario.isInitialized
        ) {
            formulario.seleccionarMusica(
                datos.getStringExtra("musica_uri"),
                datos.getStringExtra("musica_nombre").orEmpty(),
            )
        }
    }

    /**
     * Se ejecuta al pulsar Guardar tarea.
     * Primero valida los textos de fecha y hora. Si están incompletos no utiliza
     * los valores anteriores del borrador para guardar otra fecha por accidente.
     * Después revisa título, minutos, límite de avisos, días y momento del aviso.
     * Si algo está mal conserva lo escrito y explica qué corregir.
     * Si está bien pide a GestorRecordatorios guardar la tarea y preparar sus avisos.
     * No recibe ni devuelve datos; al terminar permite abrir los permisos de Android.
     */
    private fun guardar() {
        if (!proteccion.comprobar()) {
            return
        }

        if (guardada || esperandoPermisoMusica) {
            return
        }

        if (!formulario.validarFechaYHora()) {
            return
        }
        val tarea = formulario.leer()

        // when revisa estas condiciones en orden. return termina el guardado si una falla.
        when {
            tarea.titulo.isBlank() -> {
                formulario.error(
                    R.id.tarea_titulo_input,
                    "Escribe qué tienes que hacer.",
                )
                return
            }
            tarea.seguimientoActivo && tarea.intervaloMinutos !in 1..1440 -> {
                formulario.error(
                    R.id.tarea_intervalo_otro_input,
                    "Elige entre 1 y 1440 minutos.",
                )
                return
            }
            tarea.seguimientoActivo && tarea.limiteAvisos !in 1..20 -> {
                formulario.error(
                    R.id.tarea_limite_input,
                    "Elige entre 1 y 20 avisos en total.",
                )
                return
            }
            tarea.repeticion == Repeticion.DIAS_SEMANA && tarea.diasSemana.isEmpty() -> {
                informar("Elige al menos un día de la semana.")
                return
            }
        }

        val fechaHora = tarea.fecha.atTime(tarea.hora)
        val conservaHoraAnterior = original?.let {
            it.fecha == tarea.fecha && it.hora == tarea.hora
        } ?: false
        if (tarea.repeticion == Repeticion.UNA_VEZ &&
            !fechaHora.isAfter(LocalDateTime.now()) &&
            !conservaHoraAnterior
        ) {
            informar("Elige una fecha y hora futuras para este recordatorio.")
            return
        }
        if (original == null && tarea.fecha.isBefore(LocalDate.now())) {
            informar("La fecha de inicio debe ser hoy o un día futuro.")
            return
        }

        // La música aleatoria necesita ver la biblioteca. Se pregunta después de validar,
        // y la respuesta de Android vuelve a guardar sin borrar ni cambiar el borrador.
        if (tarea.modoMusica == ModoMusica.ALEATORIA &&
            !tienePermisoMusical() &&
            !permisoMusicaRevisado
        ) {
            esperandoPermisoMusica = true
            permisoTomoFoco = false
            requestPermissions(arrayOf(permisoMusical()), SOLICITUD_MUSICA_ALEATORIA)
            return
        }

        try {
            val gestor = GestorRecordatorios(this)
            synchronized(com.nowel.agendavip.datos.cuentas.EspacioPersonal.cerrojoSesion) {
                val repositorio = RepositorioTareas(this)
                val actual = repositorio.obtener(tarea.id)?.let { repositorio.escribirTarea(it).toString() }
                check(actual == baseEdicion) {
                    "Esta tarea cambió o fue eliminada mientras la editabas. Vuelve a abrirla para revisar los cambios."
                }
                gestor.guardar(tarea)
            }
            com.nowel.agendavip.datos.servidor.SincronizadorPC.solicitar(this)
            guardada = true
            voz.detener()
            val sinAccesoMusical = tarea.modoMusica == ModoMusica.ALEATORIA && !tienePermisoMusical()
            val mensajeGuardado = if (sinAccesoMusical) {
                getString(R.string.tarea_guardada_sin_permiso_musica)
            } else {
                getString(R.string.tarea_guardada)
            }
            val notificaciones = getSystemService(NotificationManager::class.java)
            if (!gestor.tieneAlarmasExactas() || !notificaciones.areNotificationsEnabled()) {
                ventanaGuardada = AlertDialog.Builder(this)
                    .setTitle("Tarea guardada")
                    .setMessage(
                        getString(R.string.tarea_revisar_permisos) +
                            if (sinAccesoMusical) "\n\n$mensajeGuardado" else "",
                    )
                    .setPositiveButton("Configurar") { _, _ ->
                        val destino = Intent(this, ActividadCuenta::class.java)
                            .putExtra("seccion", "permisos")
                        startActivity(destino)
                        finish()
                    }
                    .setNegativeButton("Después") { _, _ ->
                        finish()
                    }
                    .setOnCancelListener {
                        finish()
                    }
                    .show()
            } else {
                informar(mensajeGuardado)
                finish()
            }
        } catch (error: IllegalStateException) {
            mostrarError(error)
        } catch (error: IllegalArgumentException) {
            informar(error.message ?: "Revisa la configuración de la tarea.")
        }
    }

    /**
     * Guardar la usa para saber qué permiso de música corresponde al Android instalado.
     * No recibe datos y devuelve el nombre del permiso. Desde Android 13 se pide solo
     * audio; las versiones anteriores utilizan el permiso de lectura del almacenamiento.
     */
    private fun permisoMusical(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /**
     * Guardar consulta esta función antes de intentar usar la biblioteca aleatoria.
     * No recibe datos; devuelve true si Android ya permitió leer las canciones.
     * No solicita permisos por sí misma ni abre archivos del teléfono.
     */
    private fun tienePermisoMusical(): Boolean {
        return checkSelfPermission(permisoMusical()) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Android la llama al responder al permiso pedido por Guardar. Recibe el número
     * de solicitud y la respuesta; no devuelve datos. Retoma el guardado con permiso
     * concedido o denegado: denegar música nunca elimina el recordatorio ni su voz.
     * La protección de sesión impide guardar si se cambió de usuario durante la pregunta.
     */
    override fun onRequestPermissionsResult(
        solicitud: Int,
        permisos: Array<out String>,
        resultados: IntArray,
    ) {
        super.onRequestPermissionsResult(solicitud, permisos, resultados)

        if (solicitud != SOLICITUD_MUSICA_ALEATORIA || !esperandoPermisoMusica) {
            return
        }

        terminarEsperaMusical()
    }

    /**
     * La respuesta de permisos o la recuperación de foco la llaman sin argumentos.
     * No devuelve datos: libera la espera y vuelve a validar el borrador antes de guardarlo.
     * Con permiso denegado conserva el recordatorio y explica que no habrá canción aleatoria.
     * Comprobar la sesión evita guardar información si otra cuenta entró durante la pregunta.
     */
    private fun terminarEsperaMusical() {
        esperandoPermisoMusica = false
        permisoTomoFoco = false
        permisoMusicaRevisado = true

        if (::formulario.isInitialized && proteccion.comprobar()) {
            guardar()
        }
    }

    /**
     * Decide qué hacer al tocar la flecha de regreso o el botón Atrás del teléfono.
     * Si no hay cambios, o ya se guardaron, cierra esta pantalla.
     * Si quedan cambios pregunta si quieres descartarlos o seguir editando.
     * No se usa al abrir música: ese recorrido conserva el formulario abierto.
     * No recibe ni devuelve datos; la usan Volver y los controles Atrás de Android.
     */
    private fun volver() {
        if (ventanaSalida?.isShowing == true || esperandoPermisoMusica) {
            return
        }
        if (!::formulario.isInitialized || guardada || formulario.firma() == firmaInicial) {
            finish()
        } else {
            ventanaSalida = AlertDialog.Builder(this)
                .setTitle("¿Salir sin guardar?")
                .setMessage("Los cambios de esta tarea todavía no están guardados.")
                .setPositiveButton("Salir") { _, _ ->
                    finish()
                }
                .setNegativeButton("Seguir editando", null)
                .setOnDismissListener {
                    ventanaSalida = null
                }
                .show()
        }
    }

    /**
     * Recibe el botón Atrás en versiones de Android anteriores a la 13.
     * Llama volver para aplicar la misma pregunta sobre cambios sin guardar.
     * En Android más reciente el gesto se conecta a volver desde onCreate.
     * Android la llama sin argumentos y no espera un resultado.
     */
    @Deprecated("Android 13 utiliza el callback de onCreate.")
    override fun onBackPressed() {
        volver()
    }

    /**
     * Android llama aquí cuando el formulario deja de verse.
     * Detiene únicamente la muestra de voz. Conserva los campos y no toca las alarmas
     * de las tareas que ya se habían guardado antes. No recibe ni devuelve datos.
     */
    override fun onStop() {
        if (::voz.isInitialized) {
            voz.detener()
        }

        super.onStop()
    }

    /**
     * Android llama aquí al retirar este formulario de la memoria.
     * Cierra el programa de voz usado para las muestras, para liberar sus recursos.
     * No borra una tarea ni cambia sus recordatorios. No recibe ni devuelve datos.
     */
    override fun onDestroy() {
        ventanaMusica?.dismiss()
        ventanaGuardada?.dismiss()
        ventanaSalida?.dismiss()

        if (::voz.isInitialized) {
            voz.cerrar()
        }

        super.onDestroy()
    }

    /**
     * Explica un problema al leer o guardar datos sin vaciar el formulario.
     * Recibe error, que contiene el motivo del fallo, y lo muestra en un cuadro.
     * Si la pantalla ni siquiera pudo preparar el formulario, Entendido permite salir.
     * Si ya había campos escritos, los conserva para que puedas volver a intentarlo.
     * La llaman onCreate y guardar; no devuelve datos.
     */
    private fun mostrarError(error: IllegalStateException) {
        AlertDialog.Builder(this)
            .setTitle("No se pudo guardar o leer la tarea")
            .setMessage(error.message ?: "Los datos anteriores se conservaron.")
            .setPositiveButton("Entendido") { _, _ ->
                if (!::formulario.isInitialized) {
                    finish()
                }
            }
            .show()
    }

    /**
     * Muestra una explicación breve sobre la pantalla sin abrir otra ventana completa.
     * Recibe mensaje, el texto que debe leerse. Toast es el aviso pequeño de Android
     * que desaparece por sí solo; aquí sirve para resultados y errores sencillos.
     * La llaman las acciones de esta pantalla y la muestra de voz; no devuelve datos.
     */
    private fun informar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    companion object {
        // Identifica la respuesta de Android al acceso musical de una tarea aleatoria.
        private const val SOLICITUD_MUSICA_ALEATORIA = 302
    }

}
