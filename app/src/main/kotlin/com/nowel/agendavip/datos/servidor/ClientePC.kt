package com.nowel.agendavip.datos.servidor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Conserva el código de respuesta para que SincronizadorPC distinga un vínculo revocado. */
class ErrorConexionPC(
    val codigo: Int,
    mensaje: String,
) : IllegalStateException(mensaje)

/** Identifica una conexión de red fallida para buscar la misma PC sin borrar su autorización. */
class ErrorRedPC(mensaje: String, causa: Throwable? = null) : IllegalStateException(mensaje, causa)

/**
 * Envía solicitudes cifradas a la computadora que el usuario vinculó.
 * HTTPS protege los datos durante el viaje. La huella del certificado identifica
 * la misma PC aunque su certificado haya sido creado por ella y no por una empresa.
 * Solo esta conexión utiliza esa huella; la confianza general de Android permanece separada.
 */
object ClientePC {

    private const val MAXIMO_BYTES = 64 * 1024 * 1024

    /**
     * Recibe la dirección escrita o descubierta y devuelve su forma canónica.
     * Vincular la usa para aceptar solo HTTPS y direcciones IPv4 privadas o de este equipo.
     * Una IPv4 tiene cuatro números separados por puntos. La primera versión trabaja en la red local.
     */
    fun validarDireccion(direccion: String): String {
        val uri = try {
            URI(direccion.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Escribe una dirección como https://192.168.1.20:8443.")
        }

        val host = uri.host.orEmpty()
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                esDireccionLocal(host) &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                (uri.port == -1 || uri.port in 1..65535)
        ) {
            "Usa la dirección HTTPS local que muestra el servidor de tu PC."
        }

        val puerto = if (uri.port == -1) 443 else uri.port
        return "https://$host:$puerto"
    }

    /**
     * Recibe cuatro números de dirección y devuelve si pertenecen a una red privada
     * o a la conexión local del equipo. NSD también la usa al mostrar computadoras encontradas.
     */
    fun esDireccionLocal(direccion: String): Boolean {
        val partes = direccion.split('.')
        if (partes.size != 4) {
            return false
        }

        val numeros = partes.map { parte ->
            parte.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == parte }
                ?: return false
        }

        return numeros[0] == 10 ||
            (numeros[0] == 172 && numeros[1] in 16..31) ||
            (numeros[0] == 192 && numeros[1] == 168) ||
            numeros[0] == 127
    }

    /**
     * Recibe la PC, su huella, una ruta de la API, el permiso del vínculo y los datos.
     * API es el conjunto de direcciones que el servidor atiende para intercambiar información.
     * Devuelve el objeto JSON respondido. SincronizadorPC la llama fuera del hilo de pantalla.
     */
    fun post(
        direccion: String,
        huella: String,
        ruta: String,
        token: String?,
        cuerpo: JSONObject,
        contexto: Context? = null,
    ): JSONObject {
        val base = validarDireccion(direccion)
        require(ruta.matches(Regex("/api/[A-Za-z0-9/_-]+"))) {
            "La ruta de conexión no es válida."
        }

        val esperada = huella.lowercase(Locale.ROOT)
        require(esperada.matches(Regex("[0-9a-f]{64}"))) {
            "El código de vinculación no contiene una huella válida."
        }

        val bytes = cuerpo.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAXIMO_BYTES) {
            "Los datos superan el tamaño permitido para esta conexión."
        }

        val conexion = crearConexion(base + ruta, esperada, contexto)
        conexion.requestMethod = "POST"
        conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conexion.setRequestProperty("Accept", "application/json")
        if (token != null) {
            require(token.length in 20..512 && token.all { it.isLetterOrDigit() || it in "_-." }) {
                "El permiso de conexión guardado no es válido. Vuelve a vincular esta cuenta."
            }
            conexion.setRequestProperty("Authorization", "Bearer $token")
        }
        conexion.doOutput = true
        conexion.setFixedLengthStreamingMode(bytes.size)

        try {
            conexion.outputStream.use { salida ->
                salida.write(bytes)
            }

            val codigo = conexion.responseCode
            val entrada = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
            val respuesta = entrada?.use { leerRespuesta(it) }.orEmpty()

            if (codigo !in 200..299) {
                val detalle = runCatching {
                    val objeto = JSONObject(respuesta)
                    objeto.optString("detalle").ifBlank { objeto.optString("detail") }
                }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it.length <= 300 }
                throw ErrorConexionPC(
                    codigo,
                    detalle ?: "La PC no aceptó la solicitud. Código $codigo.",
                )
            }

            return try {
                JSONObject(respuesta)
            } catch (_: Exception) {
                throw ErrorConexionPC(codigo, "La PC devolvió una respuesta que Agenda.VIP no reconoce.")
            }
        } catch (error: SocketTimeoutException) {
            throw ErrorRedPC("La PC tardó en responder. Revisa que el servidor siga abierto.", error)
        } catch (error: SSLException) {
            throw ErrorRedPC("La dirección encontrada no confirmó el certificado de tu PC.", error)
        } catch (error: IOException) {
            throw ErrorRedPC("La PC no está disponible en esta Wi-Fi. Se conserva tu vinculación.", error)
        } finally {
            conexion.disconnect()
        }
    }

    /**
     * Recibe una dirección candidata y consulta la identidad pública con el certificado
     * ya autorizado. Buscar de nuevo la PC la usa sin enviar la llave de la cuenta.
     */
    fun consultarServidor(direccion: String, huella: String, contexto: Context): JSONObject {
        val conexion = crearConexion(validarDireccion(direccion) + "/api/salud", huella, contexto)
        conexion.requestMethod = "GET"
        conexion.connectTimeout = 2_500
        conexion.readTimeout = 3_000
        try {
            if (conexion.responseCode !in 200..299) {
                throw ErrorConexionPC(conexion.responseCode, "La dirección encontrada no respondió como tu servidor.")
            }
            return conexion.inputStream.use { JSONObject(leerRespuesta(it)) }
        } catch (error: IOException) {
            throw ErrorRedPC("La PC todavía no está disponible en esta red.", error)
        } finally {
            conexion.disconnect()
        }
    }

    /**
     * Recibe una dirección HTTPS y prepara una conexión individual por la red local.
     * El certificado debe coincidir antes de enviar datos. La confianza del resto de Android
     * y la conexión de las otras aplicaciones conservan su funcionamiento habitual.
     */
    private fun crearConexion(url: String, huella: String, contexto: Context?): HttpsURLConnection {
        require(huella.matches(Regex("[0-9a-fA-F]{64}"))) { "La huella de la PC no es válida." }
        val esperada = huella.lowercase(Locale.ROOT)
        val destino = URL(url)
        val conexion = try {
            val red = contexto?.let { elegirRedLocal(it, destino.host) }
            (red?.openConnection(destino) ?: destino.openConnection()) as HttpsURLConnection
        } catch (error: IOException) {
            throw ErrorRedPC("La red local cambió. Vuelve a conectar la Wi-Fi del celular.", error)
        }

        conexion.sslSocketFactory = prepararConfianza(esperada).socketFactory
        conexion.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, sesion ->
            val certificado = sesion.peerCertificates.firstOrNull() as? X509Certificate
            certificado != null && coincideHuella(certificado, esperada)
        }
        conexion.instanceFollowRedirects = false
        conexion.connectTimeout = 4_000
        conexion.readTimeout = 20_000
        conexion.setRequestProperty("Connection", "close")
        return conexion
    }

    /**
     * Recibe la dirección privada de la PC y elige Wi-Fi o Ethernet para llegar a ella.
     * Primero busca su subred. No exige que esa red tenga Internet y evita que los datos
     * móviles o una VPN elegidos como conexión principal desvíen esta solicitud local.
     */
    private fun elegirRedLocal(contexto: Context, host: String): Network {
        val sistema = contexto.getSystemService(ConnectivityManager::class.java)
        val redes = sistema.allNetworks.filter { red ->
            val capacidades = sistema.getNetworkCapabilities(red)
            capacidades != null &&
                !capacidades.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val destino = InetAddress.getByName(host)
        val mismaSubred = redes.firstOrNull { red ->
            sistema.getLinkProperties(red)?.routes?.any {
                !it.isDefaultRoute && it.matches(destino)
            } == true
        }
        return mismaSubred ?: redes.firstOrNull()
            ?: throw ErrorRedPC("Conecta el celular a la misma Wi-Fi que tu computadora.")
    }

    /**
     * Recibe la huella esperada y devuelve la configuración TLS de esta solicitud.
     * TLS es el mecanismo de HTTPS que cifra la conexión y comprueba el certificado.
     * El certificado se admite únicamente si coincide con el que el usuario autorizó.
     */
    private fun prepararConfianza(esperada: String): SSLContext {
        val comprobador = object : X509TrustManager {

            /** La app actúa como cliente y no recibe certificados de otros clientes. */
            override fun checkClientTrusted(cadena: Array<out X509Certificate>?, tipo: String?) {
                throw CertificateException("No se admite autenticar otros clientes desde esta conexión.")
            }

            /** Android entrega la cadena; solo el certificado exacto del servidor vinculado permite continuar. */
            override fun checkServerTrusted(cadena: Array<out X509Certificate>?, tipo: String?) {
                val certificado = cadena?.firstOrNull()
                    ?: throw CertificateException("La PC no entregó un certificado.")
                certificado.checkValidity()
                if (!coincideHuella(certificado, esperada)) {
                    throw CertificateException("La huella de la PC cambió.")
                }
            }

            /** La confianza está limitada a una huella, por eso no se publica una lista de autoridades. */
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(comprobador), SecureRandom())
        }
    }

    /** Recibe el certificado de la PC y compara su resumen SHA-256 con el código autorizado. */
    private fun coincideHuella(certificado: X509Certificate, esperada: String): Boolean {
        val bytes = MessageDigest.getInstance("SHA-256").digest(certificado.encoded)
        val actual = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            esperada.toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * Recibe la respuesta abierta y devuelve su texto respetando un límite de memoria.
     * POST cierra la entrada al terminar. Un servidor que responde demasiado se rechaza.
     */
    private fun leerRespuesta(entrada: java.io.InputStream): String {
        val salida = ByteArrayOutputStream()
        val bloque = ByteArray(16 * 1024)
        while (true) {
            val cantidad = entrada.read(bloque)
            if (cantidad < 0) {
                break
            }
            require(salida.size() + cantidad <= MAXIMO_BYTES) {
                "La respuesta de la PC supera el tamaño permitido."
            }
            salida.write(bloque, 0, cantidad)
        }
        return salida.toString(Charsets.UTF_8.name())
    }
}