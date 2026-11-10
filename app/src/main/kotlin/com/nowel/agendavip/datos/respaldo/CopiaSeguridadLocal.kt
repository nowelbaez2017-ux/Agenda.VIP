package com.nowel.agendavip.datos.respaldo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.nowel.agendavip.datos.agenda.RepositorioTareas
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.enfoque.RepositorioEnfoque
import com.nowel.agendavip.datos.uso.RepositorioLimitesUso
import com.nowel.agendavip.recordatorios.GestorRecordatorios
import com.nowel.agendavip.recordatorios.enfoque.GestorEnfoque
import com.nowel.agendavip.recordatorios.uso.ServicioLimitesUso
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exporta y restaura los datos de una sola persona sin usar una cuenta de Internet.
 * Respaldo elige entre una copia portátil con contraseña o una copia ligada a la instalación.
 * En ambos casos AES-GCM protege el contenido y detecta cambios en el archivo.
 * Los datos exportados se limitan a los apartados de la agenda y el perfil.
 */
class CopiaSeguridadLocal(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val espacio = EspacioPersonal(this.contexto)

    /** Mantiene en memoria una copia ya descifrada; aún no cambia ningún dato de la cuenta. */
    class Preparada internal constructor(
        internal val contenido: JSONObject,
        val creadaEn: String,
        val tareas: Int,
        val actividades: Int
    )

    /**
     * Recibe el archivo de destino y la contraseña elegida; escribe una copia cifrada.
     * Respaldo la llama fuera del trabajo que dibuja la pantalla. No devuelve datos y limpia
     * la contraseña recibida al terminar, incluso cuando el destino deja de estar disponible.
     */
    fun exportar(destino: Uri, contrasena: CharArray) {
        try {
            require(contrasena.size in 12..128) {
                "Utiliza al menos 12 caracteres para proteger el respaldo."
            }

            val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val cifrador = prepararCifrado(Cipher.ENCRYPT_MODE, contrasena, sal, nonce)
            val cifrado = cifrarDatos(cifrador)

            escribirCopia(destino, CABECERA, sal + nonce, cifrado)
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * Recibe el archivo y su contraseña, comprueba el contenido y devuelve una vista previa.
     * Restaurar la llama antes de pedir la confirmación final. Conserva el formato portátil
     * anterior para que se puedan seguir usando las copias ya guardadas.
     */
    fun preparar(origen: Uri, contrasena: CharArray): Preparada {
        try {
            espacio.exigirVigente()

            return abrirCopia(origen).use { entrada ->
                require(leerCampo(entrada, CABECERA.size).contentEquals(CABECERA)) {
                    "Este archivo no es un respaldo con contraseña de Agenda.VIP."
                }

                val sal = leerCampo(entrada, 16)
                val nonce = leerCampo(entrada, NONCE_BYTES)
                val descifrador = prepararCifrado(Cipher.DECRYPT_MODE, contrasena, sal, nonce)
                descifrarDatos(entrada, descifrador, "La contraseña no coincide o el respaldo fue modificado.")
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("No se pudo leer el respaldo. La agenda actual sigue intacta.", error)
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * Recibe la dirección de Archivos y devuelve true si requiere la biometría del teléfono.
     * Restaurar usa el resultado para abrir el cuadro de Android o pedir la contraseña.
     * Si no reconoce el archivo, informa antes de pedir una comprobación de identidad.
     */
    fun esCopiaTelefono(origen: Uri): Boolean {
        espacio.exigirVigente()

        val cabecera = abrirCopia(origen).use { entrada ->
            leerCampo(entrada, CABECERA.size)
        }

        return when {
            cabecera.contentEquals(CABECERA_TELEFONO) -> true
            cabecera.contentEquals(CABECERA) -> false
            else -> throw IllegalArgumentException("Este archivo no es un respaldo de Agenda.VIP.")
        }
    }

    /**
     * Devuelve una operación de cifrado nueva, que aún necesita aprobación de Android.
     * Crear con huella la entrega al cuadro del sistema antes de llamar a exportarTelefono.
     */
    fun cifradorParaTelefono(): Cipher {
        espacio.exigirVigente()
        return ClaveRespaldoTelefono.preparar()
    }

    /**
     * Recibe un respaldo del teléfono y devuelve la operación que permite descifrarlo.
     * Restaurar la entrega al cuadro de Android. Lee solo el comienzo, sin abrir los datos.
     */
    fun descifradorParaTelefono(origen: Uri): Cipher {
        espacio.exigirVigente()

        val nonce = abrirCopia(origen).use { entrada ->
            leerNonceTelefono(entrada)
        }

        return ClaveRespaldoTelefono.preparar(nonce)
    }

    /**
     * Recibe el destino y el cifrador aprobado por Android, y guarda el contenido protegido.
     * Crear con huella la llama después de confirmar la identidad. No guarda la huella.
     */
    fun exportarTelefono(destino: Uri, cifrador: Cipher) {
        espacio.exigirVigente()

        val nonce = cifrador.iv
        check(nonce != null && nonce.size == NONCE_BYTES) {
            "No se pudo preparar el cifrado del respaldo."
        }

        // La cabecera identifica el tipo de copia y también queda protegida contra cambios.
        cifrador.updateAAD(CABECERA_TELEFONO)
        val cifrado = cifrarDatos(cifrador)
        escribirCopia(destino, CABECERA_TELEFONO, nonce, cifrado)
    }

    /**
     * Recibe el archivo y la operación que Android aprobó. Devuelve su vista previa.
     * Restaurar la usa para mostrar la confirmación. Los datos vigentes todavía quedan intactos.
     */
    fun prepararTelefono(origen: Uri, descifrador: Cipher): Preparada {
        espacio.exigirVigente()

        return abrirCopia(origen).use { entrada ->
            val nonce = leerNonceTelefono(entrada)
            val nonceAutorizado = descifrador.iv

            require(nonceAutorizado != null && nonce.contentEquals(nonceAutorizado)) {
                "El archivo cambió mientras confirmabas tu identidad. Selecciónalo de nuevo."
            }

            descifrador.updateAAD(CABECERA_TELEFONO)
            descifrarDatos(
                entrada,
                descifrador,
                "La copia fue modificada o pertenece a otra instalación de Agenda.VIP.",
            )
        }
    }

    /**
     * Recibe una dirección de Archivos y devuelve la entrada para leerla por partes.
     * Las funciones de restauración cierran esta entrada al terminar o si hay un error.
     */
    private fun abrirCopia(origen: Uri): DataInputStream {
        val entrada = contexto.contentResolver.openInputStream(origen)
            ?: error("No se pudo abrir ese respaldo.")

        return DataInputStream(entrada.buffered())
    }

    /**
     * Recibe la entrada y la cantidad exacta de bytes que forman un campo de la cabecera.
     * Devuelve el campo completo; si se termina el archivo antes, explica que quedó incompleto.
     */
    private fun leerCampo(entrada: DataInputStream, cantidad: Int): ByteArray {
        val campo = ByteArray(cantidad)

        try {
            entrada.readFully(campo)
        } catch (error: EOFException) {
            throw IllegalArgumentException("El archivo está incompleto.", error)
        }

        return campo
    }

    /**
     * Recibe una entrada recién abierta, comprueba su cabecera y devuelve su número único.
     * Preparar y descifrar la copia del teléfono llaman aquí para verificar el mismo archivo.
     */
    private fun leerNonceTelefono(entrada: DataInputStream): ByteArray {
        require(leerCampo(entrada, CABECERA_TELEFONO.size).contentEquals(CABECERA_TELEFONO)) {
            "Este archivo no es un respaldo protegido por este teléfono."
        }

        return leerCampo(entrada, NONCE_BYTES)
    }

    /**
     * Recibe el cifrador listo y devuelve los datos de la cuenta convertidos en bytes protegidos.
     * Ambos tipos de copia usan el mismo documento que el respaldo autorizado en la PC.
     */
    private fun cifrarDatos(cifrador: Cipher): ByteArray {
        val documento = documentoParaServidor()
        val datos = documento.toString().toByteArray(Charsets.UTF_8)

        try {
            return cifrador.doFinal(datos)
        } finally {
            datos.fill(0)
        }
    }

    /**
     * Devuelve una copia completa y revisada de los apartados permitidos de esta cuenta.
     * La conexión con la computadora la usa solo tras una solicitud de respaldo del panel.
     * Las contraseñas, las llaves de conexión, los modelos y las canciones quedan fuera.
     */
    fun documentoParaServidor(): JSONObject {
        return synchronized(EspacioPersonal.cerrojoSesion) {
            espacio.exigirVigente()
            JSONObject()
                .put("formato", "Agenda.VIP")
                .put("version", 1)
                .put("creada_en", Instant.now().toString())
                .put("archivos", capturar(contexto, espacio.clave))
                .also { incluirFoto(it) }
                .also { documento ->
                    require(documento.toString().toByteArray(Charsets.UTF_8).size <= MAXIMO) {
                        "El respaldo supera el tamaño permitido de 48 MB."
                    }
                }
        }
    }

    /**
     * Recibe el archivo de destino, su marca de formato, la información de cifrado y los datos
     * protegidos. Ambos tipos de copia usan esta función para guardar el archivo en ese orden.
     */
    private fun escribirCopia(destino: Uri, cabecera: ByteArray, datosCifrado: ByteArray, cifrado: ByteArray) {
        espacio.exigirVigente()

        val salida = contexto.contentResolver.openOutputStream(destino, "wt")
            ?: error("No se pudo abrir el archivo elegido para guardar el respaldo.")

        salida.use {
            it.write(cabecera)
            it.write(datosCifrado)
            it.write(cifrado)
            it.flush()
        }
    }

    /**
     * Recibe la entrada después de la cabecera, el descifrador y el mensaje para una copia inválida.
     * Devuelve los datos ya revisados. El descifrador verifica el sello de seguridad antes de leerlos.
     */
    private fun descifrarDatos(entrada: InputStream, descifrador: Cipher, mensaje: String): Preparada {
        val cifrado = leerLimitado(entrada, MAXIMO + SELLO_BYTES)

        require(cifrado.size > SELLO_BYTES) {
            "El archivo está incompleto."
        }

        val datos = try {
            descifrador.doFinal(cifrado)
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException(mensaje, error)
        }

        try {
            return revisarDocumento(JSONObject(datos.toString(Charsets.UTF_8)))
        } finally {
            datos.fill(0)
        }
    }

    /**
     * Recibe el contenido que ya pasó el descifrado. Devuelve la fecha y los recuentos para
     * que la pantalla confirme qué restaurar. Verifica la estructura antes de tocar la agenda.
     */
    private fun revisarDocumento(documento: JSONObject): Preparada {
        require(documento.getString("formato") == "Agenda.VIP" && documento.getInt("version") == 1) {
            "La versión de este respaldo no es compatible."
        }

        val archivos = documento.getJSONObject("archivos")
        validarArchivos(archivos)
        validarDocumentos(archivos)

        documento.optString("foto", "").takeIf { it.isNotEmpty() }?.let {
            validarFoto(Base64.decode(it, Base64.NO_WRAP))
        }

        val tareas = archivos.getJSONObject("agenda_local").optString("documento_v1", "")
            .takeIf { it.isNotEmpty() }?.let { JSONObject(it).getJSONArray("tareas").length() } ?: 0

        val actividades = archivos.getJSONObject("enfoque_local").optString("documento_v1", "")
            .takeIf { it.isNotEmpty() }?.let { JSONObject(it).getJSONArray("actividades").length() } ?: 0

        espacio.exigirVigente()

        val creadaEn = documento.getString("creada_en")
        Instant.parse(creadaEn)
        return Preparada(documento, creadaEn, tareas, actividades)
    }

    /**
     * Recibe la copia que la persona ya revisó y confirmó; reemplaza solo sus datos personales.
     * Guarda antes un diario privado con el contenido anterior para recuperarlo tras una interrupción.
     * Al terminar vuelve a preparar los avisos futuros. No activa la supervisión de uso ni el micrófono.
     */
    fun restaurar(copia: Preparada) = synchronized(EspacioPersonal.cerrojoSesion) {
        espacio.exigirVigente()
        validarArchivos(copia.contenido.getJSONObject("archivos"))
        validarDocumentos(copia.contenido.getJSONObject("archivos"))
        val nuevos = JSONObject(copia.contenido.getJSONObject("archivos").toString())
        normalizarAvisos(nuevos, Instant.parse(copia.creadaEn).toEpochMilli())
        validarDocumentos(nuevos)
        val anterior = capturar(contexto, espacio.clave)
        val diario = diario(contexto, espacio.clave)
        escribirDiario(diario, anterior)
        var fotoNueva: File? = null

        try {
            GestorRecordatorios(contexto).cancelarTodo()
            GestorEnfoque(contexto).cancelarAlarmasActuales()
            com.nowel.agendavip.recordatorios.enfoque.ServicioEnfoque.detenerTodo(contexto)
            ServicioLimitesUso.detener(contexto)
            com.nowel.agendavip.recordatorios.descanso.GestorDescanso.detenerPorCambioSesion(contexto)
            val perfil = nuevos.getJSONObject("perfil_local")
            perfil.remove("foto_uri")
            val foto = copia.contenido.optString("foto", "")

            if (foto.isNotEmpty()) {
                val bytes = Base64.decode(foto, Base64.NO_WRAP)
                validarFoto(bytes)
                val carpeta = File(contexto.filesDir, "fotos_perfil/${espacio.clave}")
                check(carpeta.isDirectory || carpeta.mkdirs()) { "No se pudo preparar la foto del respaldo." }
                fotoNueva = File(carpeta, "${UUID.randomUUID()}.imagen").also { it.writeBytes(bytes) }
                perfil.put("foto_uri", Uri.fromFile(fotoNueva).toString())
            }

            aplicar(contexto, espacio.clave, nuevos)
            diario.delete()
        } catch (error: Exception) {
            // La cancelación de una pantalla no debe impedir que vuelva a guardarse lo anterior.
            val interrumpido = Thread.interrupted()

            try {
                aplicar(contexto, espacio.clave, anterior)
                diario.delete()
                fotoNueva?.delete()
            } catch (recuperacion: Exception) {
                error.addSuppressed(recuperacion)
                com.nowel.agendavip.seguridad.EstadoArranque.problema =
                    "No se pudo recuperar una restauración interrumpida."
                // El diario permanece: AplicacionAgenda reintentará antes de mostrar los datos.
            } finally {
                if (interrumpido) Thread.currentThread().interrupt()
            }

            throw IllegalStateException("No terminó la restauración. Se ha conservado el respaldo privado anterior.", error)
        } finally {
            // Actualiza la capa nocturna después del guardado o de recuperar el estado anterior.
            runCatching {
                com.nowel.agendavip.recordatorios.descanso.GestorDescanso.actualizar(contexto)
            }
            // Recuperar los avisos es un trabajo posterior: no cambia el resultado del guardado.
            try {
                GestorRecordatorios(contexto).reprogramarTodo()
            } catch (error: Exception) {
                android.util.Log.e("AgendaVIP", "Datos conservados; falta recuperar avisos: ${error.javaClass.simpleName}")
            }
            try {
                com.nowel.agendavip.recordatorios.enfoque.RecuperadorEnfoque.solicitar(contexto)
            } catch (error: Exception) {
                android.util.Log.e("AgendaVIP", "Datos conservados; falta recuperar Enfoque: ${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Exportar la llama para añadir una copia de la foto privada, cuando existe.
     * Nunca sigue una dirección guardada fuera del directorio privado de fotos de esta persona.
     */
    private fun incluirFoto(documento: JSONObject) {
        val perfil = documento.getJSONObject("archivos").getJSONObject("perfil_local")
        val referencia = perfil.optString("foto_uri", "")
        perfil.remove("foto_uri")
        if (referencia.isBlank()) return
        val uri = Uri.parse(referencia)
        require(uri.scheme == "file") { "Selecciona otra vez tu foto desde Perfil antes de respaldarla." }
        val carpeta = File(contexto.filesDir, "fotos_perfil/${espacio.clave}").canonicalFile
        val archivo = File(requireNotNull(uri.path)).canonicalFile
        require(archivo.parentFile == carpeta) { "La foto no pertenece a esta cuenta." }
        val datos = archivo.inputStream().use { leerLimitado(it, 32 * 1024 * 1024) }
        validarFoto(datos)
        documento.put("foto", Base64.encodeToString(datos, Base64.NO_WRAP))
    }

    /** Comprueba los documentos de tareas y Enfoque antes de permitir que sustituyan la agenda. */
    private fun validarDocumentos(archivos: JSONObject) {
        archivos.getJSONObject("agenda_local").optString("documento_v1", "")
            .takeIf { it.isNotEmpty() }?.let { RepositorioTareas(contexto).validarCopia(it) }
        archivos.getJSONObject("enfoque_local").optString("documento_v1", "")
            .takeIf { it.isNotEmpty() }?.let { RepositorioEnfoque(contexto).validarCopia(it) }
        archivos.getJSONObject("limites_uso").optString("limites", "")
            .takeIf { it.isNotEmpty() }?.let { RepositorioLimitesUso.validarCopia(it) }
        val descanso = archivos.getJSONObject("descanso_local")
        com.nowel.agendavip.datos.descanso.RepositorioDescanso.validarCopia(
            descanso.keys().asSequence().associateWith { descanso.get(it) },
        )
        archivos.getJSONObject("conversacion_enfoque").optString("mensajes", "")
            .takeIf { it.isNotEmpty() }?.let { texto ->
                val mensajes = JSONArray(texto)
                require(mensajes.length() <= 100) { "El historial de voz supera su tamaño permitido." }
                for (indice in 0 until mensajes.length()) {
                    val mensaje = mensajes.getJSONObject(indice)
                    require(mensaje.getString("texto").length <= 800)
                    require(mensaje.getString("rol") in setOf("Tú", "Agenda.VIP"))
                    mensaje.getString("actividad")
                    require(mensaje.getLong("momento") >= 0L)
                }
            }
    }

    /**
     * Recibe una contraseña, sal y número único de cifrado; devuelve el cifrador preparado.
     * Exportar y Preparar la usan. La sal dificulta reutilizar ataques entre respaldos distintos.
     */
    private fun prepararCifrado(modo: Int, contrasena: CharArray, sal: ByteArray, nonce: ByteArray): Cipher {
        val especificacion = PBEKeySpec(contrasena, sal, 210_000, 256)
        val clave = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(especificacion).encoded
        } finally {
            especificacion.clearPassword()
        }

        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(modo, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(CABECERA)
            }
        } finally {
            clave.fill(0)
        }
    }

    companion object {
        private val CABECERA = "AGENDA.VIP.RESPALDO.1\n".toByteArray(Charsets.US_ASCII)
        private val CABECERA_TELEFONO = "AGENDA.VIP.RESPALDO.B\n".toByteArray(Charsets.US_ASCII)
        private const val NONCE_BYTES = 12
        private const val SELLO_BYTES = 16
        private const val MAXIMO = 48 * 1024 * 1024
        // Esta lista fija también conserva el tipo de cada opción. Por ejemplo, volumen es decimal,
        // aunque JSON escriba 1 en lugar de 1.0; Android debe volver a recibir un Float.
        private val ESQUEMA = mapOf(
            "agenda_local" to mapOf("documento_v1" to "texto"),
            "enfoque_local" to mapOf("documento_v1" to "texto"),
            "perfil_local" to listOf("nombre", "nombres", "apellidos", "correo", "celular", "foto_uri")
                .associateWith { "texto" },
            "preferencias_agenda" to mapOf(
                "seguimiento_predeterminado" to "booleano",
                "intervalo_minutos" to "entero",
                "limite_avisos" to "entero",
                "volumen_avisos" to "decimal",
                "nombre_voz" to "texto",
                "etiqueta_voz" to "texto"
            ),
            "conversacion_enfoque" to mapOf("mensajes" to "texto"),
            "limites_uso" to mapOf("limites" to "texto"),
            "descanso_local" to mapOf("configuracion" to "texto", "noches" to "texto")
        )
        private val ARCHIVOS = ESQUEMA.keys.toList()

        /**
         * AplicacionAgenda la llama al arrancar, antes de mostrar datos de la sesión recordada.
         * Si hubo una restauración interrumpida, repone el contenido anterior y retira el diario.
         * No recupera ni inicia sonido; los gestores preparan las alarmas cuando corresponde.
         */
        fun recuperarPendiente(contexto: Context) = synchronized(EspacioPersonal.cerrojoSesion) {
            val propietario = EspacioPersonal.claveActual(contexto) ?: return@synchronized
            val diario = diario(contexto, propietario)
            if (!diario.baseFile.exists() && !File(diario.baseFile.path + ".bak").exists()) return@synchronized
            val anterior = diario.openRead().use { JSONObject(leerLimitado(it, MAXIMO).toString(Charsets.UTF_8)) }
            validarArchivos(anterior)
            aplicar(contexto, propietario, anterior)
            diario.delete()
            com.nowel.agendavip.seguridad.EstadoArranque.problema = null
        }

        /** Lee como máximo el límite recibido para evitar cargar un archivo desmesurado en memoria. */
        private fun leerLimitado(entrada: InputStream, limite: Int): ByteArray {
            val salida = ByteArrayOutputStream()
            val bloque = ByteArray(16 * 1024)
            while (true) {
                val leidos = entrada.read(bloque)
                if (leidos < 0) break
                require(salida.size() + leidos <= limite) { "El archivo es demasiado grande." }
                salida.write(bloque, 0, leidos)
            }
            return salida.toByteArray()
        }

        /** Reúne solo las preferencias personales permitidas, excluyendo claves y sesiones. */
        private fun capturar(contexto: Context, propietario: String): JSONObject {
            val resultado = JSONObject()
            ARCHIVOS.forEach { nombre ->
                val archivo = contexto.getSharedPreferences(
                    EspacioPersonal.nombrePreferencias(nombre, propietario), Context.MODE_PRIVATE
                )
                val campos = JSONObject()
                val valores = archivo.all
                ESQUEMA.getValue(nombre).keys.forEach { clave ->
                    val valor = valores[clave]
                    if (valor != null) {
                        campos.put(clave, valor)
                    }
                }
                resultado.put(nombre, campos)
            }
            return resultado
        }


        /**
         * Recibe la copia o el diario de recuperación y comprueba sus campos antes de escribir.
         * Acepta versiones anteriores descartando su apartado de chat y completando Descanso.
         */
        private fun validarArchivos(archivos: JSONObject) {
            archivos.remove("chat_local")

            // Las copias de versiones anteriores no tenían Descanso. Se importa apagado
            // mediante un apartado vacío, sin perder tareas ni exigir crear otro respaldo.
            if (!archivos.has("descanso_local")) {
                archivos.put("descanso_local", JSONObject())
            }
            require(archivos.length() == ARCHIVOS.size) { "Faltan apartados del respaldo." }
            ARCHIVOS.forEach { nombre ->
                val campos = archivos.getJSONObject(nombre)
                campos.keys().forEach { clave ->
                    val tipo = ESQUEMA.getValue(nombre)[clave]
                        ?: error("El respaldo contiene una opción desconocida: $clave.")
                    val valor = campos.get(clave)
                    val compatible = when (tipo) {
                        "texto" -> valor is String
                        "booleano" -> valor is Boolean
                        "entero" -> (valor is Int || valor is Long) &&
                            (valor as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                        "decimal" -> valor is Number && valor.toDouble().isFinite()
                        else -> false
                    }
                    require(compatible) {
                        "El respaldo contiene una preferencia incompatible."
                    }
                }
            }
        }

        /** Reemplaza cada archivo privado; solo se llama bajo el turno de sesión y con diario preparado. */
        private fun aplicar(contexto: Context, propietario: String, archivos: JSONObject) {
            ARCHIVOS.forEach { nombre ->
                val archivo = contexto.getSharedPreferences(
                    EspacioPersonal.nombrePreferencias(nombre, propietario), Context.MODE_PRIVATE
                )
                val editor = archivo.edit().clear()
                val campos = archivos.getJSONObject(nombre)
                campos.keys().forEach { clave ->
                    guardarValor(editor, clave, campos.get(clave), ESQUEMA.getValue(nombre).getValue(clave))
                }
                check(editor.commit()) { "Android no pudo confirmar la escritura del respaldo." }
            }
        }

        /** Convierte cada valor JSON al tipo que espera Android, sin ejecutar texto del respaldo. */
        private fun guardarValor(editor: SharedPreferences.Editor, clave: String, valor: Any, tipo: String) {
            when (tipo) {
                "texto" -> editor.putString(clave, valor as String)
                "booleano" -> editor.putBoolean(clave, valor as Boolean)
                "entero" -> editor.putInt(clave, (valor as Number).toInt())
                "decimal" -> editor.putFloat(clave, (valor as Number).toFloat())
                else -> error("No se puede guardar este tipo de preferencia.")
            }
        }

        /** Devuelve la ubicación del diario de esta persona, siempre dentro del almacenamiento privado. */
        private fun diario(contexto: Context, propietario: String): AtomicFile {
            EspacioPersonal.nombrePreferencias("respaldo", propietario)
            val carpeta = File(contexto.filesDir, "restauraciones")
            check(carpeta.isDirectory || carpeta.mkdirs()) { "No se pudo preparar el respaldo privado." }
            return AtomicFile(File(carpeta, "$propietario.json"))
        }

        /** Confirma el diario en disco antes de reemplazar preferencias para poder deshacer una interrupción. */
        private fun escribirDiario(diario: AtomicFile, anterior: JSONObject) {
            val salida = diario.startWrite()
            try {
                salida.write(anterior.toString().toByteArray(Charsets.UTF_8))
                diario.finishWrite(salida)
            } catch (error: Exception) {
                diario.failWrite(salida)
                throw error
            }
        }

        /** Valida únicamente las dimensiones de la imagen; no carga todos sus píxeles en memoria. */
        private fun validarFoto(bytes: ByteArray) {
            require(bytes.size <= 32 * 1024 * 1024) { "La foto del respaldo supera 32 MB." }
            val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opciones)
            require(opciones.outWidth > 0 && opciones.outHeight > 0) { "La foto del respaldo no es legible." }
        }

        /**
         * Retira horas del teléfono anterior. Enfoque en curso vuelve pausado, sin inventar trabajo.
         * Los permisos sobre canciones pertenecen a cada teléfono; si faltan, el aviso usa su respaldo.
         */
        private fun normalizarAvisos(archivos: JSONObject, instanteCopia: Long) {
            // Importar conserva el horario y el historial, pero pide activar de nuevo
            // la restricción en este teléfono. Los permisos del sistema no viajan en la copia.
            val descanso = archivos.getJSONObject("descanso_local")
            descanso.optString("configuracion", "").takeIf { it.isNotEmpty() }?.let { texto ->
                val configuracion = JSONObject(texto).put("activado", false)
                descanso.put("configuracion", configuracion.toString())
            }
            val agenda = archivos.getJSONObject("agenda_local")
            agenda.optString("documento_v1", "").takeIf { it.isNotEmpty() }?.let { texto ->
                val documento = JSONObject(texto)
                val estados = documento.getJSONArray("estados")
                for (indice in 0 until estados.length()) {
                    estados.getJSONObject(indice)
                        .put("alarmaProgramadaPara", JSONObject.NULL)
                        .put("pospuestaHasta", JSONObject.NULL)
                        .put("alarmaEsSeguimiento", false)
                }
                agenda.put("documento_v1", documento.toString())
            }
            val enfoque = archivos.getJSONObject("enfoque_local")
            enfoque.optString("documento_v1", "").takeIf { it.isNotEmpty() }?.let { texto ->
                val documento = JSONObject(texto)
                val actividades = documento.getJSONArray("actividades")
                for (indice in 0 until actividades.length()) {
                    val actividad = actividades.getJSONObject(indice)
                    if (actividad.optString("estado") == "EN_CURSO") {
                        val anterior = actividad.getLong("restanteMs")
                        val restante = (actividad.getLong("finEnEpochMs") - instanteCopia).coerceIn(0L, anterior)
                        actividad.put("estado", if (restante > 0L) "PAUSADA" else "ESPERANDO_RESPUESTA")
                            .put("restanteMs", restante)
                            .put("acumuladoTrabajadoMs", actividad.getLong("acumuladoTrabajadoMs") + anterior - restante)
                            .put("finEnEpochMs", JSONObject.NULL)
                            .put("finEnElapsedMs", JSONObject.NULL)
                    }
                    actividad.put("alarmaProgramadaPara", JSONObject.NULL)
                }
                enfoque.put("documento_v1", documento.toString())
            }
        }
    }
}
