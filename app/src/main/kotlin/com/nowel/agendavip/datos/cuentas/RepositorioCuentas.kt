package com.nowel.agendavip.datos.cuentas

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Crea y comprueba cuentas que existen únicamente en este teléfono.
 * Recibe el contexto de Android y devuelve datos públicos de CuentaLocal, sin contraseñas.
 * Las pantallas de acceso lo llaman desde un hilo de fondo: disco y el cálculo de seguridad
 * tardan y no deben detener los controles. Cada llamada borra el CharArray recibido al acabar.
 * CharArray es un conjunto de caracteres modificable que permite limpiar la contraseña.
 */
class RepositorioCuentas(context: Context) {

    private val contexto = context.applicationContext
    private val azar = SecureRandom()

    /**
     * Recibe nombre inicial, usuario y contraseña; devuelve la cuenta creada en este dispositivo.
     * El botón Crear cuenta la llama después del formulario. Rechaza campos inválidos y usuarios
     * ocupados con IllegalArgumentException; los errores de disco son ErrorDatosCuentas.
     * El nombre solo prepara el perfil inicial; no es el usuario ni identifica archivos.
     */
    fun crear(
        nombre: String,
        usuario: String,
        contrasena: CharArray,
    ): CuentaLocal {
        try {
            val nombreInicial = validarNombre(nombre)
            val usuarioLegible = validarUsuarioLegible(usuario)
            val claveUsuario = normalizarUsuario(usuarioLegible)
            validarContrasena(contrasena)

            return synchronized(AlmacenCuentas.cerrojo) {
                AlmacenCuentas.comprobarCancelacion()

                operarConDatos {
                    val base = AlmacenCuentas.abrir(contexto)

                    if (existeUsuario(base, claveUsuario)) {
                        throw ErrorUsuarioOcupado()
                    }

                    val sal = ByteArray(TAMANO_SAL)
                    azar.nextBytes(sal)
                    var resumen: ByteArray? = null

                    try {
                        resumen = derivar(contrasena, sal)
                        AlmacenCuentas.comprobarCancelacion()

                        val cuenta = CuentaLocal(
                            id = UUID.randomUUID().toString(),
                            usuario = usuarioLegible,
                            nombreInicial = nombreInicial,
                            creadaEn = System.currentTimeMillis(),
                        )

                        val valores = ContentValues().apply {
                            put("id", cuenta.id)
                            put("usuario", cuenta.usuario)
                            put("clave_usuario", claveUsuario)
                            put("nombre_inicial", cuenta.nombreInicial)
                            put("creada_en", cuenta.creadaEn)
                            put("sal", sal)
                            put("resumen", resumen)
                            put("iteraciones", ITERACIONES)
                            put("fallos", 0)
                            put("espera_hasta", 0L)
                        }

                        AlmacenCuentas.cambiar(base) {
                            try {
                                base.insertOrThrow("cuentas", null, valores)
                            } catch (error: SQLiteConstraintException) {
                                if (existeUsuario(base, claveUsuario)) {
                                    throw ErrorUsuarioOcupado()
                                }

                                throw error
                            }
                        }

                        cuenta
                    } finally {
                        resumen?.fill(0)
                        sal.fill(0)
                    }
                }
            }
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * Recibe usuario y contraseña; devuelve la cuenta correcta o null si no permite entrar.
     * El botón Iniciar sesión la llama y muestra el mismo error para usuario o contraseña
     * incorrectos y espera temporal. Tras cinco fallos se esperan 60 segundos, sin bloqueo
     * permanente; una cuenta inexistente también realiza el cálculo costoso de seguridad.
     */
    fun autenticar(
        usuario: String,
        contrasena: CharArray,
    ): CuentaLocal? {
        try {
            // Los límites se revisan antes del cálculo para impedir un trabajo desmedido.
            val claveUsuario = try {
                normalizarUsuario(validarUsuarioLegible(usuario))
            } catch (_: IllegalArgumentException) {
                return null
            }

            if (!contrasenaDentroDeLimites(contrasena)) {
                return null
            }

            return synchronized(AlmacenCuentas.cerrojo) {
                AlmacenCuentas.comprobarCancelacion()

                operarConDatos {
                    val base = AlmacenCuentas.abrir(contexto)
                    val registro = leerRegistro(base, claveUsuario)

                    if (registro == null) {
                        calcularSinCuenta(contrasena)

                        return@operarConDatos null
                    }

                    try {
                        val resumenCalculado = derivar(contrasena, registro.sal)

                        val coincide = try {
                            // isEqual compara todos los bytes sin terminar en la primera diferencia.
                            MessageDigest.isEqual(registro.resumen, resumenCalculado)
                        } finally {
                            resumenCalculado.fill(0)
                        }

                        AlmacenCuentas.comprobarCancelacion()
                        val ahora = System.currentTimeMillis()

                        if (registro.esperaHasta > ahora ||
                            EliminadorCuenta.estaPendiente(contexto, registro.cuenta.id)
                        ) {
                            return@operarConDatos null
                        }

                        if (coincide) {
                            guardarIntentos(base, registro.cuenta.id, 0, 0L)

                            return@operarConDatos registro.cuenta
                        }

                        val fallos = if (registro.esperaHasta > 0L) {
                            1
                        } else {
                            (registro.fallos + 1).coerceAtMost(MAXIMO_FALLOS)
                        }

                        val esperaHasta = if (fallos >= MAXIMO_FALLOS) {
                            ahora + ESPERA_MILISEGUNDOS
                        } else {
                            0L
                        }

                        guardarIntentos(base, registro.cuenta.id, fallos, esperaHasta)
                        null
                    } finally {
                        registro.limpiar()
                    }
                }
            }
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * Recibe el UUID de una cuenta y devuelve sus datos públicos o null si no existe.
     * La coordinación de sesión la usa para recuperar una entrada recordada, sin pedir secretos.
     * Un código inválido se rechaza y una base dañada informa del error en vez de parecer vacía.
     */
    fun obtener(id: String): CuentaLocal? {
        require(AlmacenCuentas.esIdCanonico(id)) {
            "El identificador de la cuenta no es válido."
        }

        return synchronized(AlmacenCuentas.cerrojo) {
            AlmacenCuentas.comprobarCancelacion()

            if (EliminadorCuenta.estaPendiente(contexto, id)) {
                return@synchronized null
            }

            operarConDatos {
                val base = AlmacenCuentas.abrir(contexto)

                base.query(
                    "cuentas",
                    COLUMNAS_PUBLICAS,
                    "id = ?",
                    arrayOf(id),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        leerCuenta(cursor)
                    } else {
                        null
                    }
                }
            }
        }
    }

    /**
     * Devuelve los nombres de usuario locales ordenados. La ayuda de acceso la llama
     * en segundo plano después de que Android confirme la identidad de la persona.
     * Usa las mismas reglas de lectura que obtener y omite las cuentas pendientes de borrar.
     */
    fun usuariosGuardados(): List<String> = synchronized(AlmacenCuentas.cerrojo) {
        AlmacenCuentas.comprobarCancelacion()

        operarConDatos {
            val base = AlmacenCuentas.abrir(contexto)
            base.query(
                "cuentas",
                COLUMNAS_PUBLICAS,
                null,
                null,
                null,
                null,
                "clave_usuario ASC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        AlmacenCuentas.comprobarCancelacion()
                        val cuenta = leerCuenta(cursor)

                        if (!EliminadorCuenta.estaPendiente(contexto, cuenta.id)) {
                            add(cuenta.usuario)
                        }
                    }
                }
            }
        }
    }

    /**
     * Recibe el dueño y su contraseña actual; devuelve si corresponde a esa cuenta.
     * Seguridad y Desbloqueo la llaman. Reutiliza la misma espera por intentos del acceso normal.
     * Los caracteres recibidos se borran incluso si la cuenta ya no existe.
     */
    fun confirmarContrasena(id: String, contrasena: CharArray): Boolean {
        try {
            return synchronized(AlmacenCuentas.cerrojo) {
                val cuenta = obtener(id) ?: return@synchronized false
                autenticar(cuenta.usuario, contrasena)?.id == id
            }
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * Recibe el dueño, la contraseña actual y la nueva; guarda una protección completamente nueva.
     * Seguridad la llama fuera de la pantalla. Primero comprueba la actual y luego calcula una sal
     * aleatoria y su resumen con las mismas reglas del registro. Nunca guarda el texto de la clave.
     */
    fun cambiarContrasena(id: String, actual: CharArray, nueva: CharArray) {
        try {
            validarContrasena(nueva)
            synchronized(AlmacenCuentas.cerrojo) {
                check(confirmarContrasena(id, actual)) {
                    "La contraseña actual no es correcta o debes esperar un minuto antes de otro intento."
                }
                val sal = ByteArray(TAMANO_SAL)
                azar.nextBytes(sal)
                var resumen: ByteArray? = null

                try {
                    resumen = derivar(nueva, sal)
                    operarConDatos {
                        val base = AlmacenCuentas.abrir(contexto)
                        val valores = ContentValues().apply {
                            put("sal", sal)
                            put("resumen", resumen)
                            put("iteraciones", ITERACIONES)
                            put("fallos", 0)
                            put("espera_hasta", 0L)
                        }
                        AlmacenCuentas.cambiar(base) {
                            check(base.update("cuentas", valores, "id = ?", arrayOf(id)) == 1) {
                                "No se pudo guardar la nueva contraseña. Inténtalo de nuevo."
                            }
                        }
                    }
                } finally {
                    resumen?.fill(0)
                    sal.fill(0)
                }
            }
        } finally {
            actual.fill('\u0000')
            nueva.fill('\u0000')
        }
    }

    /**
     * Recibe un dueño cuyo borrado ya quedó autorizado y retira exclusivamente su fila de cuenta.
     * EliminadorCuenta la llama después de cancelar avisos y limpiar sus datos. Repetirla es seguro:
     * una fila que ya no existe significa que esa parte del borrado había terminado.
     */
    internal fun eliminarConfirmada(id: String) {
        require(AlmacenCuentas.esIdCanonico(id) && EliminadorCuenta.estaPendiente(contexto, id)) {
            "La eliminación de la cuenta no está autorizada."
        }
        synchronized(AlmacenCuentas.cerrojo) {
            operarConDatos {
                val base = AlmacenCuentas.abrir(contexto)
                AlmacenCuentas.cambiar(base) {
                    base.delete("cuentas", "id = ?", arrayOf(id))
                }
            }
        }
    }

    /**
     * No recibe datos y devuelve si existe al menos una cuenta en este teléfono.
     * La pantalla de acceso puede usarlo para orientar la entrada; cualquier error de lectura
     * se comunica como ErrorDatosCuentas y nunca se convierte en un resultado falso.
     */
    fun hayCuentas(): Boolean {
        return synchronized(AlmacenCuentas.cerrojo) {
            AlmacenCuentas.comprobarCancelacion()

            operarConDatos {
                val base = AlmacenCuentas.abrir(contexto)

                base.query(
                    "cuentas",
                    arrayOf("id"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }
    }

    /**
     * Recibe el nombre inicial del formulario y devuelve su contenido sin espacios exteriores.
     * crear la llama antes de tocar disco; exige de 1 a 120 caracteres para limitar los datos.
     * Cuenta caracteres Unicode completos, incluidos los que ocupan dos posiciones de texto.
     */
    private fun validarNombre(nombre: String): String {
        require(nombre.length <= 480) {
            "El nombre debe tener entre 1 y 120 caracteres."
        }

        val limpio = nombre.trim()

        require(limpio.isNotBlank() && limpio.codePointCount(0, limpio.length) <= 120) {
            "El nombre debe tener entre 1 y 120 caracteres."
        }

        return limpio
    }

    /**
     * Recibe el usuario escrito y devuelve su forma legible sin espacios de los extremos.
     * crear y autenticar la llaman antes de normalizar; limita primero el texto recibido
     * para que una entrada enorme no provoque un cálculo o una copia sin límite.
     */
    private fun validarUsuarioLegible(usuario: String): String {
        require(usuario.length <= 160) {
            "El usuario debe tener de 3 a 40 letras, números, puntos, guiones o guiones bajos."
        }

        return usuario.trim()
    }

    /**
     * Recibe un usuario legible y devuelve la clave usada al buscar y evitar duplicados.
     * NFKC unifica formas Unicode equivalentes; Locale.ROOT convierte a minúsculas sin
     * depender del idioma del teléfono. crear, autenticar y la lectura de datos la llaman.
     * Acepta de 3 a 40 letras o números Unicode, además de punto, guion y guion bajo.
     */
    private fun normalizarUsuario(usuario: String): String {
        val clave = Normalizer.normalize(usuario, Normalizer.Form.NFKC)
            .trim()
            .lowercase(Locale.ROOT)

        require(clave.length <= 80 && clave.codePointCount(0, clave.length) in 3..40) {
            "El usuario debe tener de 3 a 40 letras, números, puntos, guiones o guiones bajos."
        }

        var posicion = 0

        while (posicion < clave.length) {
            val caracter = clave.codePointAt(posicion)

            require(
                Character.isLetterOrDigit(caracter) ||
                    caracter == '.'.code ||
                    caracter == '-'.code ||
                    caracter == '_'.code,
            ) {
                "El usuario debe tener de 3 a 40 letras, números, puntos, guiones o guiones bajos."
            }

            posicion += Character.charCount(caracter)
        }

        return clave
    }

    /**
     * Recibe la contraseña sin convertirla en String y exige entre 15 y 128 caracteres.
     * crear la llama; no devuelve un dato. Conserva Unicode y todos los espacios exactamente
     * como se escribieron, sin recortar extremos ni cambiar composiciones Unicode.
     */
    private fun validarContrasena(contrasena: CharArray) {
        require(contrasenaDentroDeLimites(contrasena)) {
            "La contraseña debe tener entre 15 y 128 caracteres. Los espacios cuentan."
        }
    }

    /**
     * Recibe caracteres privados y devuelve si su longitud Unicode permite calcular la clave.
     * crear y autenticar la llaman; revisa antes el tamaño físico y rechaza pares Unicode rotos.
     * No realiza copias de la contraseña y no impone cambios al texto ni reglas de composición.
     */
    private fun contrasenaDentroDeLimites(contrasena: CharArray): Boolean {
        if (contrasena.size !in 15..256) {
            return false
        }

        var posicion = 0
        var cantidad = 0

        while (posicion < contrasena.size) {
            val caracter = contrasena[posicion]

            if (Character.isHighSurrogate(caracter)) {
                if (
                    posicion + 1 >= contrasena.size ||
                    !Character.isLowSurrogate(contrasena[posicion + 1])
                ) {
                    return false
                }

                posicion += 2
            } else {
                if (Character.isLowSurrogate(caracter)) {
                    return false
                }

                posicion += 1
            }

            cantidad += 1

            if (cantidad > 128) {
                return false
            }
        }

        return cantidad >= 15
    }

    /**
     * Recibe contraseña y sal aleatoria; devuelve 32 bytes calculados con PBKDF2 y SHA-256.
     * PBKDF2 repite un cálculo para dificultar probar contraseñas; la sal distingue resultados
     * de personas que eligieron el mismo texto. crear y autenticar usan 600000 iteraciones,
     * según la política acordada. El llamador debe borrar los bytes devueltos.
     * PBEKeySpec mantiene una copia temporal que se borra incluso cuando el cálculo falla.
     */
    private fun derivar(
        contrasena: CharArray,
        sal: ByteArray,
    ): ByteArray {
        AlmacenCuentas.comprobarCancelacion()
        val especificacion = PBEKeySpec(contrasena, sal, ITERACIONES, TAMANO_RESUMEN * 8)

        try {
            val fabrica = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val resultado = fabrica.generateSecret(especificacion).encoded

            try {
                AlmacenCuentas.comprobarCancelacion()

                if (resultado.size != TAMANO_RESUMEN) {
                    throw ErrorDatosCuentas("No se pudo proteger la contraseña. Inténtalo de nuevo.")
                }

                return resultado
            } catch (error: RuntimeException) {
                resultado.fill(0)

                throw error
            }
        } catch (error: GeneralSecurityException) {
            throw ErrorDatosCuentas("No se pudo proteger la contraseña. Inténtalo de nuevo.", error)
        } finally {
            especificacion.clearPassword()
        }
    }

    /**
     * Recibe una contraseña y realiza la misma derivación cuando el usuario no existe.
     * autenticar la llama para reducir diferencias de tiempo que revelarían cuentas.
     * No devuelve datos; borra la sal y el resultado temporal antes de terminar.
     */
    private fun calcularSinCuenta(contrasena: CharArray) {
        val sal = ByteArray(TAMANO_SAL)
        azar.nextBytes(sal)
        var resumen: ByteArray? = null

        try {
            resumen = derivar(contrasena, sal)
        } finally {
            resumen?.fill(0)
            sal.fill(0)
        }
    }

    /**
     * Recibe la base y el usuario normalizado; devuelve si su clave ya está ocupada.
     * crear la llama antes de calcular la contraseña y al resolver un conflicto de escritura.
     * El signo ? separa el dato del texto SQL y evita interpretarlo como una instrucción.
     */
    private fun existeUsuario(
        base: SQLiteDatabase,
        clave: String,
    ): Boolean {
        base.query(
            "cuentas",
            arrayOf("id"),
            "clave_usuario = ?",
            arrayOf(clave),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    /**
     * Recibe una fila situada en una cuenta y devuelve solo su información pública válida.
     * obtener y leerRegistro la llaman; un dato inválido significa error de lectura y se
     * conserva en disco para impedir que una corrupción se interprete como cuenta inexistente.
     */
    private fun leerCuenta(cursor: Cursor): CuentaLocal {
        for (columna in 0..3) {
            val tipoEsperado = if (columna == 3) {
                Cursor.FIELD_TYPE_INTEGER
            } else {
                Cursor.FIELD_TYPE_STRING
            }

            if (cursor.isNull(columna) || cursor.getType(columna) != tipoEsperado) {
                throw ErrorDatosCuentas("No se pueden leer las cuentas guardadas. Los datos se conservaron.")
            }
        }

        val cuenta = CuentaLocal(
            id = cursor.getString(0),
            usuario = cursor.getString(1),
            nombreInicial = cursor.getString(2),
            creadaEn = cursor.getLong(3),
        )

        try {
            require(AlmacenCuentas.esIdCanonico(cuenta.id))
            require(cuenta.creadaEn >= 0L)
            require(validarNombre(cuenta.nombreInicial) == cuenta.nombreInicial)
            require(validarUsuarioLegible(cuenta.usuario) == cuenta.usuario)
            normalizarUsuario(cuenta.usuario)
        } catch (error: IllegalArgumentException) {
            throw ErrorDatosCuentas(
                "No se pueden leer las cuentas guardadas. Los datos se conservaron.",
                error,
            )
        }

        return cuenta
    }

    /**
     * Recibe base y clave normalizada; devuelve los datos necesarios para verificar el acceso.
     * autenticar la llama; null significa usuario inexistente y los datos dañados producen error.
     * Comprueba el coste almacenado antes de usarlo para que una alteración no lo haga ilimitado.
     */
    private fun leerRegistro(
        base: SQLiteDatabase,
        clave: String,
    ): RegistroAutenticacion? {
        val columnas = COLUMNAS_PUBLICAS + arrayOf(
            "sal",
            "resumen",
            "iteraciones",
            "fallos",
            "espera_hasta",
            "clave_usuario",
        )

        base.query(
            "cuentas",
            columnas,
            "clave_usuario = ?",
            arrayOf(clave),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }

            val cuenta = leerCuenta(cursor)

            for (columna in 4..9) {
                if (cursor.isNull(columna)) {
                    throw ErrorDatosCuentas("No se pueden leer las cuentas guardadas. Los datos se conservaron.")
                }
            }

            if (
                cursor.getType(4) != Cursor.FIELD_TYPE_BLOB ||
                cursor.getType(5) != Cursor.FIELD_TYPE_BLOB
            ) {
                throw ErrorDatosCuentas("No se pueden leer las cuentas guardadas. Los datos se conservaron.")
            }

            val sal = cursor.getBlob(4)
            var resumen: ByteArray? = null

            try {
                val resumenLeido = cursor.getBlob(5)
                resumen = resumenLeido
                val iteraciones = cursor.getLong(6)
                val fallos = cursor.getLong(7)
                val esperaHasta = cursor.getLong(8)

                if (
                    cursor.getType(6) != Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getType(7) != Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getType(8) != Cursor.FIELD_TYPE_INTEGER ||
                    sal.size != TAMANO_SAL ||
                    resumenLeido.size != TAMANO_RESUMEN ||
                    iteraciones != ITERACIONES.toLong() ||
                    fallos !in 0L..MAXIMO_FALLOS.toLong() ||
                    esperaHasta < 0L ||
                    cursor.getString(9) != normalizarUsuario(cuenta.usuario) ||
                    (esperaHasta > 0L && fallos != MAXIMO_FALLOS.toLong())
                ) {
                    throw ErrorDatosCuentas("No se pueden leer las cuentas guardadas. Los datos se conservaron.")
                }

                return RegistroAutenticacion(cuenta, sal, resumenLeido, fallos.toInt(), esperaHasta)
            } catch (error: RuntimeException) {
                sal.fill(0)
                resumen?.fill(0)

                throw error
            }
        }
    }

    /**
     * Recibe base, UUID, contador y fin de espera; guarda juntos los intentos de esa cuenta.
     * autenticar la llama para registrar un fallo o limpiar la espera tras un acceso correcto.
     * No devuelve datos; si la fila desaparece o no se puede confirmar, informa del error.
     */
    private fun guardarIntentos(
        base: SQLiteDatabase,
        id: String,
        fallos: Int,
        esperaHasta: Long,
    ) {
        val valores = ContentValues().apply {
            put("fallos", fallos)
            put("espera_hasta", esperaHasta)
        }

        AlmacenCuentas.cambiar(base) {
            val filas = base.update("cuentas", valores, "id = ?", arrayOf(id))

            if (filas != 1) {
                throw ErrorDatosCuentas("No se pudo guardar el intento de acceso. Inténtalo de nuevo.")
            }
        }
    }

    /**
     * Recibe una operación de lectura o escritura y devuelve su resultado sin cambiarlo.
     * Los métodos públicos la llaman para traducir fallos de SQLite a un mensaje sencillo.
     * No convierte errores en listas vacías ni borra información para recuperar el acceso.
     */
    private fun <T> operarConDatos(operacion: () -> T): T {
        try {
            return operacion()
        } catch (error: SQLiteException) {
            throw ErrorDatosCuentas(
                "No se pudieron abrir o guardar las cuentas. Los datos se conservaron.",
                error,
            )
        }
    }

    /**
     * Agrupa temporalmente una cuenta, su sal, su resumen y sus contadores de acceso.
     * leerRegistro la construye y autenticar la usa; no genera un toString con estos campos.
     * Un resumen es el resultado irreversible del cálculo, nunca una contraseña recuperable.
     */
    private class RegistroAutenticacion(
        val cuenta: CuentaLocal,
        val sal: ByteArray,
        val resumen: ByteArray,
        val fallos: Int,
        val esperaHasta: Long,
    ) {

        /**
         * No recibe datos ni devuelve un resultado: borra los bytes temporales del registro.
         * autenticar la llama en finally, el bloque que se ejecuta aun si hay error o cancelación.
         */
        fun limpiar() {
            sal.fill(0)
            resumen.fill(0)
        }
    }

    private companion object {
        const val ITERACIONES = 600_000
        const val TAMANO_SAL = 32
        const val TAMANO_RESUMEN = 32
        const val MAXIMO_FALLOS = 5
        const val ESPERA_MILISEGUNDOS = 60_000L

        val COLUMNAS_PUBLICAS = arrayOf("id", "usuario", "nombre_inicial", "creada_en")
    }
}
