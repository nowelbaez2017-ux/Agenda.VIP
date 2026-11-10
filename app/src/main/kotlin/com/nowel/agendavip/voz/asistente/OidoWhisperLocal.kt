package com.nowel.agendavip.voz.asistente

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * Escucha una frase y la convierte en texto español utilizando el modelo local voz.bin.
 * Recibe Android. El servicio de Enfoque inicia y termina la escucha desde su trabajador de voz.
 * Un trabajador es un hilo aparte: hace tareas largas sin detener los botones de la pantalla.
 * El audio solo vive en memoria; este oído no guarda grabaciones ni accede a Internet.
 * La captura termina al detectar una pausa, tras ocho segundos sin voz o al alcanzar treinta segundos.
 */
class OidoWhisperLocal(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val cerrojo = Any()
    private var captura: Captura? = null
    private var transcribiendo = false
    @Volatile
    private var cerrado = false
    private var senalNativa = 0L

    /**
     * Agrupa una grabación, su memoria y las marcas que comparten pantalla y trabajador.
     * Volatile permite que el lector vea una cancelación sin esperar el turno de otro hilo.
     * Solo el hilo de captura escribe los sonidos y su cantidad; terminar espera que ese hilo acabe.
     */
    private class Captura(val grabador: AudioRecord) {
        val muestras = ShortArray(MAXIMO_MUESTRAS)
        lateinit var hilo: Thread

        @Volatile
        var detener = false

        @Volatile
        var cancelada = false

        @Volatile
        var terminada = false

        @Volatile
        var cantidad = 0

        @Volatile
        var fallo: String? = null
    }

    /**
     * Enfoque consulta este valor sin esperar una transcripción.
     * Devuelve true cuando la frase o la espera de voz terminaron y ya puede pedir el texto.
     */
    val terminoCaptura: Boolean
        get() = synchronized(cerrojo) {
            captura?.terminada == true
        }

    /**
     * Abre el micrófono y pone a trabajar un hilo propio de captura; no devuelve datos.
     * Enfoque la llama desde su trabajador después de obtener el permiso de micrófono.
     * Comprueba primero que exista voz.bin y la biblioteca incluida al compilar.
     */
    fun iniciar() {
        exigirTrabajador()

        synchronized(cerrojo) {
            check(!cerrado) { "El lector de voz ya está cerrado. Vuelve a activar la voz en Enfoque." }
            check(!transcribiendo && captura == null) { "Termina la frase anterior antes de empezar otra." }
            comprobarModelo()
            cargarBiblioteca()

            if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("Permite el micrófono para responder por voz en Enfoque.")
            }

            if (senalNativa == 0L) {
                senalNativa = crearSenalNativa()
            }

            check(senalNativa != 0L && reiniciarSenalNativa(senalNativa)) {
                "El motor de voz sigue terminando la frase anterior."
            }

            val minimo = AudioRecord.getMinBufferSize(
                FRECUENCIA,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimo > 0) { "Este teléfono no pudo preparar el micrófono a 16000 muestras por segundo." }
            val grabador = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(FRECUENCIA)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimo, FRECUENCIA * 2))
                .build()

            try {
                check(grabador.state == AudioRecord.STATE_INITIALIZED) {
                    "No se pudo abrir el micrófono. Comprueba si otra aplicación lo está utilizando."
                }
                val nueva = Captura(grabador)
                nueva.hilo = Thread({ recogerSonido(nueva) }, "AgendaVIP-microfono")
                grabador.startRecording()
                check(grabador.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Android no permitió iniciar el micrófono."
                }
                captura = nueva
                nueva.hilo.start()
            } catch (error: Exception) {
                captura = null
                grabador.release()
                throw error
            }
        }
    }

    /**
     * Detiene la captura, espera a que entregue sus muestras y devuelve lo que Whisper entendió.
     * Enfoque la llama en su trabajador cuando termina la captura.
     * Rechaza silencio y fallos; Enfoque interpreta el texto antes de aplicar una decisión.
     * Al acabar borra los sonidos temporales, incluso si se cancela o falla el modelo.
     */
    fun terminarYTranscribir(): String {
        exigirTrabajador()
        val actual = synchronized(cerrojo) {
            check(!cerrado) { "El oído local ya está cerrado." }
            check(!transcribiendo) { "Ya se está transcribiendo una frase." }
            val pendiente = captura ?: throw IllegalStateException("Primero comienza a hablar.")
            transcribiendo = true
            pendiente.detener = true
            pendiente
        }
        var sonidos: FloatArray? = null

        try {
            actual.hilo.join(2_000L)
            if (actual.hilo.isAlive) {
                actual.cancelada = true
                actual.hilo.interrupt()
                throw IllegalStateException("El micrófono todavía está terminando. Vuelve a intentarlo.")
            }
            comprobarCancelacion(actual)
            actual.fallo?.let { throw IllegalStateException(it) }
            check(actual.cantidad >= FRECUENCIA / 5) {
                "No se escuchó una frase. Acerca el teléfono y repite."
            }
            comprobarModelo()
            val audio = FloatArray(actual.cantidad) { indice ->
                actual.muestras[indice] / 32768f
            }
            sonidos = audio
            val senal = synchronized(cerrojo) {
                comprobarCancelacion(actual)
                senalNativa
            }
            val bytes = transcribirNativo(rutaModelo().absolutePath, audio, senal)
            comprobarCancelacion(actual)
            val texto = try {
                bytes.toString(Charsets.UTF_8).trim()
            } finally {
                bytes.fill(0)
            }
            check(texto.isNotBlank()) {
                "No pude distinguir las palabras. Acerca el teléfono y repite la frase."
            }
            return texto
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelar()
            throw CancellationException("Se canceló la transcripción.")
        } finally {
            sonidos?.fill(0f)

            if (!actual.hilo.isAlive) {
                actual.muestras.fill(0)
            }
            synchronized(cerrojo) {
                if (captura === actual && !actual.hilo.isAlive) {
                    captura = null
                }
                transcribiendo = false
            }
        }
    }

    /**
     * Enfoque la llama al desactivar la voz, cambiar de cuenta o cancelar una escucha.
     * Marca el trabajo y despierta al lector; vuelve sin esperar a que termine Whisper.
     * No guarda texto ni modifica tareas, perfiles o recordatorios.
     */
    fun cancelar() {
        synchronized(cerrojo) {
            captura?.let {
                it.cancelada = true
                it.detener = true
                it.hilo.interrupt()

                // La captura puede haber terminado antes de pulsar Cancelar.
                // Si ya no hay lector ni transcripción, permite iniciar otra frase.
                if (!transcribiendo && !it.hilo.isAlive) {
                    it.muestras.fill(0)
                    captura = null
                }
            }

            if (senalNativa != 0L) {
                cancelarSenalNativa(senalNativa)
            }
        }
    }

    /**
     * El servicio de Enfoque la llama cuando se cierra.
     * Cancela la frase y retira la señal; el cálculo que esté acabando conserva su memoria válida.
     * No espera el cálculo desde la pantalla ni vuelve a abrir el micrófono por su cuenta.
     */
    fun cerrar() {
        synchronized(cerrojo) {
            cerrado = true
            cancelar()

            if (senalNativa != 0L) {
                cerrarSenalNativa(senalNativa)
                senalNativa = 0L
            }
        }
    }

    /**
     * Recibe la captura que preparó iniciar y recoge sonidos en bloques pequeños.
     * Para al escuchar una pausa de 1,4 segundos después de voz, o tras ocho segundos sin voz.
     * Las respuestas cortas, como «sí», cuentan desde unos 120 milisegundos de sonido.
     * Este umbral de volumen no distingue perfectamente voz de ruido; Whisper hará otra revisión.
     * Siempre libera el micrófono y publica terminada para que Enfoque pueda pedir la transcripción.
     */
    private fun recogerSonido(actual: Captura) {
        val bloque = ShortArray(320)
        val inicio = SystemClock.elapsedRealtime()
        var primerSonido = 0L
        var ultimoSonido = inicio
        var muestrasConSonido = 0

        try {
            while (!actual.detener && actual.cantidad < MAXIMO_MUESTRAS) {
                val cantidad = actual.grabador.read(
                    bloque,
                    0,
                    minOf(bloque.size, MAXIMO_MUESTRAS - actual.cantidad),
                    AudioRecord.READ_NON_BLOCKING,
                )

                if (cantidad < 0) {
                    throw IllegalStateException("El micrófono dejó de entregar sonido. Vuelve a intentarlo.")
                }

                if (cantidad > 0) {
                    System.arraycopy(bloque, 0, actual.muestras, actual.cantidad, cantidad)
                    actual.cantidad += cantidad
                    val energia = (0 until cantidad).sumOf { indice ->
                        val valor = bloque[indice] / 32768.0
                        valor * valor
                    }
                    val ahora = SystemClock.elapsedRealtime()

                    if (sqrt(energia / cantidad) >= 0.006) {
                        if (primerSonido == 0L) {
                            primerSonido = ahora
                        }
                        ultimoSonido = ahora
                        muestrasConSonido += cantidad
                    }

                    if (primerSonido != 0L && muestrasConSonido >= FRECUENCIA * 12 / 100 &&
                        ahora - primerSonido >= 600L && ahora - ultimoSonido >= 1_400L
                    ) {
                        break
                    }
                } else {
                    Thread.sleep(8L)
                }

                val transcurrido = SystemClock.elapsedRealtime() - inicio

                if ((primerSonido == 0L && transcurrido >= 8_000L) || transcurrido >= 30_000L) {
                    break
                }
            }
        } catch (_: InterruptedException) {
            // cancelar cambió las marcas antes de despertar este hilo.
        } catch (error: Exception) {
            actual.fallo = error.message ?: "No se pudo grabar esta frase."
        } finally {
            bloque.fill(0)

            try {
                if (actual.grabador.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    actual.grabador.stop()
                }
            } catch (_: RuntimeException) {
                // Aunque Android ya cerrara el micrófono, todavía hay que liberar su referencia.
            }
            actual.grabador.release()
            actual.terminada = true

            synchronized(cerrojo) {
                if (actual.cancelada || cerrado) {
                    actual.muestras.fill(0)

                    if (captura === actual && !transcribiendo) {
                        captura = null
                    }
                }
            }
        }
    }

    /**
     * Devuelve el archivo privado común de voz, importado desde la pantalla de modelos.
     * No depende del usuario: las grabaciones y el historial sí se mantienen por separado.
     */
    private fun rutaModelo(): File = File(contexto.filesDir, "modelos_locales/voz.bin")

    /**
     * iniciar y terminarYTranscribir la llaman antes de abrir el modelo.
     * Revisa que exista y tenga la cabecera GGML, la marca del formato que acepta Whisper.
     * La comprobación completa y el idioma se revisan después en la biblioteca nativa.
     */
    private fun comprobarModelo() {
        val archivo = rutaModelo()
        check(archivo.isFile && archivo.length() >= 16L) {
            "Primero importa el modelo de voz Whisper multilingüe en la pantalla de modelos."
        }
        val cabecera = ByteArray(4)
        FileInputStream(archivo).use { entrada ->
            check(entrada.read(cabecera) == cabecera.size) { "El modelo de voz está incompleto." }
        }
        check(ByteBuffer.wrap(cabecera).order(ByteOrder.LITTLE_ENDIAN).int == 0x67676d6c) {
            "Este archivo no es un modelo GGML de Whisper compatible."
        }
    }

    /**
     * Recibe la captura y aborta si se cerró, canceló o interrumpió el trabajo.
     * terminarYTranscribir la usa para no entregar palabras de una escucha que ya se abandonó.
     */
    private fun comprobarCancelacion(actual: Captura) {
        if (actual.cancelada || cerrado || Thread.currentThread().isInterrupted) {
            throw CancellationException("Se canceló la transcripción.")
        }
    }

    /**
     * Las operaciones largas la llaman sin argumentos y exigen estar fuera del hilo de pantalla.
     * Evita que una llamada accidental deje congelados los botones mientras se prepara el modelo.
     */
    private fun exigirTrabajador() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "La voz debe prepararse desde el trabajador de Enfoque."
        }
    }

    /** Cada declaración enlaza con su función del mismo nombre en puente_whisper.cpp. */
    private external fun crearSenalNativa(): Long

    private external fun reiniciarSenalNativa(codigo: Long): Boolean

    private external fun cancelarSenalNativa(codigo: Long)

    private external fun cerrarSenalNativa(codigo: Long)

    private external fun transcribirNativo(modelo: String, muestras: FloatArray, codigo: Long): ByteArray

    companion object {
        private const val FRECUENCIA = 16_000
        private const val MAXIMO_MUESTRAS = FRECUENCIA * 30
        private var bibliotecaCargada = false

        /**
         * iniciar la llama desde el trabajador para cargar el puente incluido al compilar.
         * Si falta el componente nativo, explica el problema sin sustituirlo por reconocimiento online.
         */
        @Synchronized
        private fun cargarBiblioteca() {
            if (bibliotecaCargada) {
                return
            }

            try {
                System.loadLibrary("agenda_voz_local")
                bibliotecaCargada = true
            } catch (error: LinkageError) {
                throw IllegalStateException("No se pudo cargar el motor local de voz. Revisa la integración CMake de Android Studio.", error)
            }
        }
    }
}
