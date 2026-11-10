package com.nowel.agendavip.datos.cuentas

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Informa de un problema al abrir, leer o confirmar los datos privados de cuentas.
 * Recibe un mensaje sencillo y la causa original, si existe; el acceso muestra ese mensaje.
 * Hereda de IllegalStateException, que significa que la operación no se pudo completar.
 */
class ErrorDatosCuentas(
    mensaje: String,
    causa: Throwable? = null,
) : IllegalStateException(mensaje, causa)

/**
 * Identifica un usuario ocupado sin obligar a comparar el texto de un mensaje.
 * No recibe datos: crear la lanza y el formulario puede marcar el campo Usuario.
 * Hereda de IllegalArgumentException, que indica un dato de entrada que debe corregirse.
 */
class ErrorUsuarioOcupado : IllegalArgumentException("Este usuario ya está ocupado.")

/**
 * Comparte la base privada entre las operaciones de cuentas, sin publicar sus conexiones.
 * SQLite es el almacenamiento de tablas incluido en Android; las transacciones confirman
 * todos los cambios juntos o conservan los datos anteriores si algo falla.
 * Sus operaciones deben llamarse desde un hilo de fondo, fuera de los controles de pantalla.
 */
internal object AlmacenCuentas {

    // El cerrojo hace esperar a otra operación hasta terminar la lectura o escritura actual.
    val cerrojo = Any()

    private var ayudante: BaseCuentas? = null

    /**
     * Recibe el contexto de Android y devuelve la conexión privada compartida.
     * El repositorio la llama dentro de cerrojo; los fallos se propagan.
     * Conserva solo applicationContext para no retener una pantalla que ya se cerró.
     */
    fun abrir(contexto: Context): SQLiteDatabase {
        val base = ayudante ?: BaseCuentas(contexto.applicationContext).also {
            ayudante = it
        }

        return base.writableDatabase
    }

    /**
     * Recibe un identificador y devuelve si es un UUID escrito de forma canónica.
     * Canónico significa los 36 caracteres habituales, con guiones y letras minúsculas.
     * El repositorio y la sesión lo usan antes de emplear el código como espacio personal.
     */
    fun esIdCanonico(id: String): Boolean {
        if (id.length != 36) {
            return false
        }

        return try {
            UUID.fromString(id).toString() == id
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * No recibe datos ni devuelve un resultado: detiene una operación que fue cancelada.
     * El repositorio y la sesión la llaman antes de cambiar disco, por ejemplo si se cerró
     * la pantalla; isInterrupted consulta la cancelación sin borrar esa señal del hilo.
     */
    fun comprobarCancelacion() {
        if (Thread.currentThread().isInterrupted) {
            throw ErrorDatosCuentas("La operación se canceló. Puedes intentarlo de nuevo.")
        }
    }

    /**
     * Recibe la base y un bloque de cambios; devuelve el resultado del bloque al llamador.
     * Las operaciones del repositorio la usan para confirmar juntas sus cambios y detectar fallos
     * de escritura. Una cancelación antes de confirmar conserva los datos anteriores.
     */
    fun <T> cambiar(
        base: SQLiteDatabase,
        bloque: () -> T,
    ): T {
        comprobarCancelacion()
        base.beginTransaction()

        try {
            comprobarCancelacion()
            val resultado = bloque()
            comprobarCancelacion()
            base.setTransactionSuccessful()

            return resultado
        } finally {
            // endTransaction confirma en disco y también puede informar de un fallo.
            base.endTransaction()
        }
    }
}

/**
 * Abre cuentas_locales.db dentro del directorio privado de la aplicación recibida.
 * SQLiteOpenHelper administra la creación y versión del archivo; solo AlmacenCuentas lo usa.
 * El manejador de corrupción informa del problema y evita el borrado automático de la base.
 */
private class BaseCuentas(contexto: Context) : SQLiteOpenHelper(
    contexto,
    "cuentas_locales.db",
    null,
    1,
    DatabaseErrorHandler {
        throw ErrorDatosCuentas(
            "No se pueden leer las cuentas guardadas. Los datos se conservaron.",
        )
    },
) {

    /**
     * Recibe una base recién creada y prepara la tabla de cuentas.
     * Android la llama una sola vez; no devuelve un dato y nunca sustituye una base existente.
     * UNIQUE impide repetir el usuario normalizado; CHECK exige valores válidos al guardar.
     */
    override fun onCreate(base: SQLiteDatabase) {
        base.execSQL(
            """
            CREATE TABLE cuentas (
                id TEXT NOT NULL PRIMARY KEY,
                usuario TEXT NOT NULL,
                clave_usuario TEXT NOT NULL UNIQUE,
                nombre_inicial TEXT NOT NULL,
                creada_en INTEGER NOT NULL CHECK (creada_en >= 0),
                sal BLOB NOT NULL CHECK (length(sal) = 32),
                resumen BLOB NOT NULL CHECK (length(resumen) = 32),
                iteraciones INTEGER NOT NULL CHECK (iteraciones = 600000),
                fallos INTEGER NOT NULL DEFAULT 0 CHECK (fallos BETWEEN 0 AND 5),
                espera_hasta INTEGER NOT NULL DEFAULT 0 CHECK (espera_hasta >= 0)
            )
            """.trimIndent(),
        )
    }

    /**
     * Recibe una base antigua y las versiones de origen y destino; Android la invoca al actualizar.
     * No devuelve un dato: informa de que falta una migración, es decir, una adaptación segura
     * entre versiones. Así no borra cuentas ni interpreta una versión desconocida como vacía.
     */
    override fun onUpgrade(
        base: SQLiteDatabase,
        versionAnterior: Int,
        versionNueva: Int,
    ) {
        throw ErrorDatosCuentas(
            "Esta versión no puede actualizar las cuentas guardadas. Los datos se conservaron.",
        )
    }

    /**
     * Recibe una base más reciente y ambas versiones; Android la llama al instalar una app anterior.
     * No devuelve un dato: rechaza abrir datos de otra versión sin intentar borrarlos o convertirlos.
     */
    override fun onDowngrade(
        base: SQLiteDatabase,
        versionAnterior: Int,
        versionNueva: Int,
    ) {
        throw ErrorDatosCuentas(
            "Esta versión no puede abrir las cuentas guardadas. Los datos se conservaron.",
        )
    }
}
