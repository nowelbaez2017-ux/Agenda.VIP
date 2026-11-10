package com.nowel.agendavip.datos.cuentas

import android.content.Context
import com.nowel.agendavip.recordatorios.uso.ServicioLimitesUso
import java.io.File

/**
 * Elimina únicamente la cuenta que confirmó su contraseña y sus datos privados.
 * Seguridad lo utiliza fuera del trabajo que dibuja la pantalla. Un registro pequeño recuerda
 * un borrado autorizado para terminarlo si Android cierra el proceso durante la limpieza.
 */
class EliminadorCuenta(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val control = this.contexto.getSharedPreferences(ARCHIVO_CONTROL, Context.MODE_PRIVATE)

    /**
     * Recibe la contraseña de la cuenta abierta, la comprueba y empieza el borrado confirmado.
     * No borra la agenda sin cuenta ni las cuentas de otras personas. Limpia los caracteres al salir.
     */
    fun eliminarActual(contrasena: CharArray) {
        try {
            synchronized(EspacioPersonal.cerrojoSesion) {
                val propietario = EspacioPersonal.claveActual(contexto)
                require(propietario != null && AlmacenCuentas.esIdCanonico(propietario)) {
                    "Entra en una cuenta para eliminarla. La agenda sin cuenta no se borra desde aquí."
                }
                check(RepositorioCuentas(contexto).confirmarContrasena(propietario, contrasena)) {
                    "La contraseña no es correcta o debes esperar un minuto antes de intentarlo otra vez."
                }

                val pendientes = control.getStringSet(CLAVE_PENDIENTES, emptySet()).orEmpty().toMutableSet()
                pendientes.add(propietario)
                check(control.edit().putStringSet(CLAVE_PENDIENTES, pendientes).commit()) {
                    "No se pudo preparar la eliminación. Tus datos se conservaron."
                }
                terminar(propietario)
            }
        } finally {
            contrasena.fill('\u0000')
        }
    }

    /**
     * La aplicación la llama al arrancar para terminar únicamente borrados que ya se confirmaron.
     * Una limpieza pendiente impide volver a autenticar esa cuenta hasta terminarla.
     * No inicia una eliminación nueva ni recibe contraseñas.
     */
    fun recuperarPendientes() {
        synchronized(EspacioPersonal.cerrojoSesion) {
            val pendientes = control.getStringSet(CLAVE_PENDIENTES, emptySet()).orEmpty().toSet()
            pendientes.forEach { propietario ->
                if (AlmacenCuentas.esIdCanonico(propietario)) {
                    try {
                        terminar(propietario)
                    } catch (error: RuntimeException) {
                        // Conservar el marcador permite reintentar; no habilita la cuenta a medio borrar.
                        android.util.Log.e("AgendaVIP", "No terminó una eliminación local: ${error.javaClass.simpleName}")
                        if (EspacioPersonal.claveActual(contexto) == propietario) {
                            SesionLocal(contexto).cerrar()
                        }
                    }
                }
            }
        }
    }

    /**
     * Recibe el dueño validado y termina la limpieza indicada por el registro autorizado.
     * Cierra primero sus avisos y su sesión; después retira sus archivos y su fila de cuenta.
     * Si se interrumpe, el mismo trabajo se puede repetir sin tocar otro dueño.
     */
    private fun terminar(propietario: String) {
        check(estaPendiente(contexto, propietario)) {
            "La eliminación no tiene una confirmación guardada."
        }

        if (EspacioPersonal.claveActual(contexto) == propietario) {
            ServicioLimitesUso.detener(contexto)
            CoordinadorSesion(contexto).cerrarSesion()
        }

        val preferencias = File(contexto.applicationInfo.dataDir, "shared_prefs")
        val sufijo = "_cuenta_$propietario.xml"
        val archivosPrivados = if (preferencias.exists()) {
            preferencias.listFiles() ?: throw IllegalStateException("No se pudieron leer los archivos de esta cuenta.")
        } else {
            emptyArray()
        }
        val nombres = archivosPrivados.map { it.name.removeSuffix(".bak") }.filter {
            it.endsWith(sufijo) && it.removeSuffix(sufijo).matches(Regex("[a-z_]+"))
        }.map { it.removeSuffix(".xml") }.distinct()

        nombres.forEach { nombre ->
            val archivo = File(preferencias, "$nombre.xml")
            check(contexto.getSharedPreferences(nombre, Context.MODE_PRIVATE).edit().clear().commit()) {
                "No se pudieron limpiar todos los datos de la cuenta. Se reintentará al abrir la app."
            }
            check(contexto.deleteSharedPreferences(nombre) || !archivo.exists()) {
                "No se pudieron retirar todos los archivos de la cuenta. Se reintentará al abrir la app."
            }
        }

        borrarDentroDe(File(contexto.filesDir, "fotos_perfil"), File(contexto.filesDir, "fotos_perfil/$propietario"))
        val restauraciones = File(contexto.filesDir, "restauraciones")
        listOf("$propietario.json", "$propietario.json.bak", "$propietario.json.new").forEach { nombre ->
            borrarDentroDe(restauraciones, File(restauraciones, nombre))
        }
        RepositorioCuentas(contexto).eliminarConfirmada(propietario)

        val pendientes = control.getStringSet(CLAVE_PENDIENTES, emptySet()).orEmpty().toMutableSet()
        pendientes.remove(propietario)
        check(control.edit().putStringSet(CLAVE_PENDIENTES, pendientes).commit()) {
            "La cuenta se eliminó; queda pendiente confirmar la limpieza al abrir la aplicación."
        }
    }

    /**
     * Recibe una carpeta privada permitida y el archivo concreto que pertenece al dueño.
     * Revisa su dirección real antes de borrar para impedir salir de esa carpeta por un enlace.
     * No utiliza nombres personales ni borra fotos originales de la galería o canciones del teléfono.
     */
    private fun borrarDentroDe(carpeta: File, archivo: File) {
        if (!archivo.exists()) {
            return
        }
        val limite = carpeta.canonicalPath + File.separator
        check(archivo.canonicalPath.startsWith(limite)) {
            "No se pudo verificar la carpeta de datos de la cuenta."
        }

        if (archivo.isDirectory) {
            val hijos = archivo.listFiles() ?: throw IllegalStateException("No se pudo leer la carpeta privada de la cuenta.")
            hijos.forEach { hijo -> borrarDentroDe(carpeta, hijo) }
        }
        check(archivo.delete()) {
            "No se pudo eliminar un archivo privado de la cuenta. Se reintentará al abrir la app."
        }
    }

    companion object {
        private const val ARCHIVO_CONTROL = "eliminaciones_autorizadas"
        private const val CLAVE_PENDIENTES = "cuentas_pendientes"

        /** El acceso consulta si un dueño tiene un borrado confirmado que todavía debe terminarse. */
        fun estaPendiente(contexto: Context, propietario: String): Boolean {
            return contexto.applicationContext.getSharedPreferences(ARCHIVO_CONTROL, Context.MODE_PRIVATE)
                .getStringSet(CLAVE_PENDIENTES, emptySet()).orEmpty().contains(propietario)
        }
    }
}
