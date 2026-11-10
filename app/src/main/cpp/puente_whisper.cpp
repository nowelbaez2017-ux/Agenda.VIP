#include <jni.h>
#include "whisper.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <exception>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

// Una señal compartida permite cancelar sin esperar que termine todo el cálculo.
// shared_ptr conserva la señal mientras el trabajador la usa, aunque se cierre la pantalla.
struct SenalTrabajo {
    std::atomic<bool> cancelada{false};
    std::atomic<bool> ocupada{false};
};

std::mutex cerrojoSenales;
std::unordered_map<jlong, std::shared_ptr<SenalTrabajo>> senales;
std::atomic<jlong> siguienteSenal{1};

/**
 * Recibe el código privado de una operación y devuelve su señal compartida, o una señal vacía.
 * Las funciones JNI la usan para no interpretar un número recibido como una dirección de memoria.
 * El turno del mapa dura solo durante la búsqueda, nunca durante la transcripción.
 */
std::shared_ptr<SenalTrabajo> buscarSenal(jlong codigo) {
    std::lock_guard<std::mutex> turno(cerrojoSenales);
    const auto encontrada = senales.find(codigo);

    return encontrada == senales.end() ? nullptr : encontrada->second;
}

/**
 * Recibe Android, el tipo de error Java y una explicación breve; entrega el error a Kotlin.
 * No añade audio ni transcripciones a registros. Las funciones JNI la usan ante un fallo.
 */
void informarError(JNIEnv * entorno, const char * tipo, const char * mensaje) {
    if (entorno->ExceptionCheck()) {
        return;
    }

    jclass clase = entorno->FindClass(tipo);

    if (clase != nullptr) {
        entorno->ThrowNew(clase, mensaje);
        entorno->DeleteLocalRef(clase);
    }
}

/**
 * Whisper recibe esta función y pregunta si debe abandonar su cálculo.
 * Recibe la señal asociada al trabajo y devuelve true cuando se cerró o canceló.
 */
bool debeCancelar(void * dato) {
    return static_cast<SenalTrabajo *>(dato)->cancelada.load();
}

/**
 * Whisper llama a este destino en lugar de escribir su información interna en el registro.
 * No recibe ni devuelve datos usados por la app; evita registrar audio, rutas o textos privados.
 */
void descartarRegistro(enum ggml_log_level, const char *, void *) {
}

/**
 * Al salir de una transcripción, incluso por error, permite reutilizar su señal.
 * Recibe la señal ocupada; el destructor funciona como el bloque finally de Kotlin.
 */
struct LiberarTurno {
    std::shared_ptr<SenalTrabajo> senal;

    ~LiberarTurno() {
        senal->ocupada.store(false);
    }
};

} // namespace

/**
 * Kotlin solicita una señal nueva antes de grabar. Devuelve un código propio de esta biblioteca.
 * La grabación y la transcripción no se guardan aquí: solo su señal de cancelación.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_nowel_agendavip_voz_asistente_OidoWhisperLocal_crearSenalNativa(JNIEnv * entorno, jobject) {
    try {
        const jlong codigo = siguienteSenal.fetch_add(1);
        auto senal = std::make_shared<SenalTrabajo>();
        std::lock_guard<std::mutex> turno(cerrojoSenales);
        senales.emplace(codigo, std::move(senal));
        return codigo;
    } catch (const std::exception &) {
        informarError(entorno, "java/lang/IllegalStateException", "No se pudo preparar el motor local de voz.");
        return 0;
    }
}

/**
 * Kotlin entrega una señal para volver a usarla. Devuelve false si ya no existe o sigue ocupada.
 * No cambia una transcripción activa; iniciar comprueba este resultado antes de abrir el micrófono.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nowel_agendavip_voz_asistente_OidoWhisperLocal_reiniciarSenalNativa(JNIEnv *, jobject, jlong codigo) {
    auto senal = buscarSenal(codigo);

    if (senal == nullptr || senal->ocupada.load()) {
        return JNI_FALSE;
    }

    senal->cancelada.store(false);
    return JNI_TRUE;
}

/**
 * Kotlin entrega la señal que desea cancelar. Solo modifica una marca y vuelve inmediatamente.
 * La transcripción consulta esa marca durante el cálculo; no se libera su memoria por la fuerza.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_nowel_agendavip_voz_asistente_OidoWhisperLocal_cancelarSenalNativa(JNIEnv *, jobject, jlong codigo) {
    auto senal = buscarSenal(codigo);

    if (senal != nullptr) {
        senal->cancelada.store(true);
    }
}

/**
 * Recibe una señal que ya no se usará, la cancela y retira su código del mapa.
 * Si una transcripción sigue acabando, su copia compartida mantiene válida la señal hasta salir.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_nowel_agendavip_voz_asistente_OidoWhisperLocal_cerrarSenalNativa(JNIEnv *, jobject, jlong codigo) {
    std::lock_guard<std::mutex> turno(cerrojoSenales);
    const auto encontrada = senales.find(codigo);

    if (encontrada != senales.end()) {
        encontrada->second->cancelada.store(true);
        senales.erase(encontrada);
    }
}

/**
 * Recibe el modelo privado, hasta treinta segundos de audio y su señal de cancelación.
 * Devuelve los bytes UTF-8 del texto español; Kotlin convierte esos bytes en letras.
 * Carga el modelo solo durante este trabajo y lo libera antes de interpretar la orden.
 * Nunca traduce ni consulta Internet. El texto todavía debe revisarse y confirmarse en el chat.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nowel_agendavip_voz_asistente_OidoWhisperLocal_transcribirNativo(
    JNIEnv * entorno,
    jobject,
    jstring rutaModelo,
    jfloatArray audio,
    jlong codigo
) {
    auto senal = buscarSenal(codigo);

    if (senal == nullptr || senal->cancelada.load()) {
        informarError(entorno, "java/util/concurrent/CancellationException", "Se cancelo la transcripcion.");
        return nullptr;
    }

    bool libre = false;

    if (!senal->ocupada.compare_exchange_strong(libre, true)) {
        informarError(entorno, "java/lang/IllegalStateException", "El motor de voz sigue atendiendo otra frase.");
        return nullptr;
    }

    LiberarTurno liberar{senal};

    try {
        const jsize cantidad = entorno->GetArrayLength(audio);

        if (cantidad < 1600 || cantidad > 16000 * 30) {
            informarError(entorno, "java/lang/IllegalStateException", "La grabacion debe durar entre un instante y treinta segundos.");
            return nullptr;
        }

        std::vector<float> muestras(static_cast<size_t>(cantidad));
        entorno->GetFloatArrayRegion(audio, 0, cantidad, muestras.data());

        if (entorno->ExceptionCheck()) {
            return nullptr;
        }

        double energia = 0.0;

        for (float valor : muestras) {
            if (!std::isfinite(valor) || valor < -1.0f || valor > 1.0f) {
                informarError(entorno, "java/lang/IllegalStateException", "La grabacion no contiene audio valido.");
                return nullptr;
            }

            energia += static_cast<double>(valor) * valor;
        }

        // Esta revisión sencilla rechaza casi silencio. No certifica que el sonido sea una persona.
        if (std::sqrt(energia / cantidad) < 0.002) {
            informarError(entorno, "java/lang/IllegalStateException", "No se escucho una voz suficientemente clara. Acerca el telefono y repite.");
            return nullptr;
        }

        const char * ruta = entorno->GetStringUTFChars(rutaModelo, nullptr);

        if (ruta == nullptr) {
            return nullptr;
        }

        const std::string archivo(ruta);
        entorno->ReleaseStringUTFChars(rutaModelo, ruta);
        whisper_log_set(descartarRegistro, nullptr);
        auto opciones = whisper_context_default_params();
        opciones.use_gpu = false;
        opciones.flash_attn = false;

        std::unique_ptr<whisper_context, decltype(&whisper_free)> modelo(
            whisper_init_from_file_with_params(archivo.c_str(), opciones),
            whisper_free
        );

        if (modelo == nullptr) {
            informarError(entorno, "java/lang/IllegalStateException", "No se pudo abrir voz.bin. Importa un modelo Whisper multilingue compatible.");
            return nullptr;
        }

        if (senal->cancelada.load()) {
            informarError(entorno, "java/util/concurrent/CancellationException", "Se cancelo la transcripcion.");
            return nullptr;
        }

        if (!whisper_is_multilingual(modelo.get())) {
            informarError(entorno, "java/lang/IllegalStateException", "Este modelo solo entiende ingles. Importa un modelo multilingue sin .en en su nombre.");
            return nullptr;
        }

        auto parametros = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        parametros.n_threads = static_cast<int>(std::min(4u, std::max(1u, std::thread::hardware_concurrency())));
        parametros.language = "es";
        parametros.translate = false;
        parametros.detect_language = false;
        parametros.no_context = true;
        parametros.no_timestamps = true;
        parametros.print_realtime = false;
        parametros.print_progress = false;
        parametros.print_timestamps = false;
        parametros.print_special = false;
        parametros.suppress_blank = true;
        parametros.suppress_nst = true;
        parametros.temperature = 0.0f;
        parametros.temperature_inc = 0.0f;
        parametros.max_tokens = 160;
        parametros.no_speech_thold = 0.6f;
        parametros.abort_callback = debeCancelar;
        parametros.abort_callback_user_data = senal.get();

        const int resultado = whisper_full(modelo.get(), parametros, muestras.data(), cantidad);

        if (senal->cancelada.load()) {
            informarError(entorno, "java/util/concurrent/CancellationException", "Se cancelo la transcripcion.");
            return nullptr;
        }

        if (resultado != 0) {
            informarError(entorno, "java/lang/IllegalStateException", "El motor local no pudo transcribir esta grabacion.");
            return nullptr;
        }

        std::string texto;
        const int segmentos = whisper_full_n_segments(modelo.get());

        for (int indice = 0; indice < segmentos; ++indice) {
            if (whisper_full_get_segment_no_speech_prob(modelo.get(), indice) >= 0.65f) {
                continue;
            }

            const char * parte = whisper_full_get_segment_text(modelo.get(), indice);

            if (parte != nullptr) {
                texto.append(parte);
            }

            if (texto.size() > 16000) {
                informarError(entorno, "java/lang/IllegalStateException", "La transcripcion resulto demasiado larga. Divide la peticion en frases mas cortas.");
                return nullptr;
            }
        }

        // Los bytes conservan los acentos y otros caracteres sin conversiones de JNI incompletas.
        jbyteArray salida = entorno->NewByteArray(static_cast<jsize>(texto.size()));

        if (salida != nullptr && !texto.empty()) {
            entorno->SetByteArrayRegion(
                salida,
                0,
                static_cast<jsize>(texto.size()),
                reinterpret_cast<const jbyte *>(texto.data())
            );
        }

        return salida;
    } catch (const std::bad_alloc &) {
        informarError(entorno, "java/lang/IllegalStateException", "No hay memoria suficiente para este modelo de voz. Prueba uno mas pequeno.");
    } catch (const std::exception &) {
        informarError(entorno, "java/lang/IllegalStateException", "No se pudo completar la transcripcion local.");
    }

    return nullptr;
}