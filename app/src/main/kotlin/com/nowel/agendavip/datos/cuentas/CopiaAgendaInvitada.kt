package com.nowel.agendavip.datos.cuentas

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Conserva una copia de la agenda sin cuenta cuando la persona lo pide al registrarse.
 * Recibe el acceso a Android. CoordinadorSesion la usa antes de abrir una cuenta nueva.
 * La agenda original nunca se borra. Si se interrumpe la copia, la próxima entrada puede terminarla.
 */
internal class CopiaAgendaInvitada(contexto: Context) {

    private val contexto = contexto.applicationContext
    private val archivos = listOf("agenda_local", "perfil_local", "preferencias_agenda")

    /**
     * Recibe el código de la cuenta nueva y prepara una fotografía de los datos sin cuenta.
     * Una fotografía aquí significa guardar los valores tal como están ahora, no una imagen.
     * La llama CoordinadorSesion solo si se marcó Copiar mi agenda sin cuenta.
     * No reemplaza una cuenta que ya tiene datos; tampoco devuelve información.
     */
    fun preparar(cuentaId: String): Unit = synchronized(EspacioPersonal.cerrojoSesion) {
        comprobarInterrupcion()
        val control = control(cuentaId)

        // La copia en memoria también cuenta: un commit fallido pudo cambiar el mapa
        // de SharedPreferences aunque el archivo todavía conserve otro marcador.
        if (pendientesEnMemoria.containsKey(cuentaId) || control.contains("pendiente")) {
            return@synchronized
        }

        if (control.getBoolean("completa", false)) {
            return@synchronized
        }

        check(archivos.all { destino(it, cuentaId).all.isEmpty() }) {
            "Esta cuenta ya tiene datos. No se reemplazaron con la agenda sin cuenta."
        }

        val texto = try {
            val copia = JSONObject()

            archivos.forEach { nombre ->
                val original = contexto.getSharedPreferences(nombre, Context.MODE_PRIVATE)
                val campos = JSONObject()

                original.all.forEach { (clave, valor) ->
                    // Esta elección antigua de entrada no forma parte del perfil de una cuenta.
                    if (nombre != "perfil_local" || clave != "agenda_local") {
                        campos.put(clave, codificar(valor))
                    }
                }

                copia.put(nombre, campos)
            }

            copia.toString() ?: throw IllegalStateException(
                "No se pudo preparar la copia. Los datos originales siguen conservados.",
            )
        } catch (error: JSONException) {
            throw IllegalStateException(
                "No se pudo preparar la copia. Los datos originales siguen conservados.",
                error,
            )
        }

        comprobarInterrupcion()
        pendientesEnMemoria[cuentaId] = texto
        confirmarPendiente(control, texto)
    }

    /**
     * Recibe la cuenta a la que se va a entrar y termina su copia pendiente, si existe.
     * CoordinadorSesion la llama también al iniciar sesión de nuevo después de una interrupción.
     * Repite los mismos valores guardados hasta confirmar todos los archivos. La cuenta todavía
     * no está abierta durante este paso, por eso no hay cambios nuevos que puedan perderse.
     */
    fun terminarPendiente(cuentaId: String): Unit = synchronized(EspacioPersonal.cerrojoSesion) {
        val control = control(cuentaId)
        val texto = pendientesEnMemoria[cuentaId]
            ?: control.getString("pendiente", null)
            ?: return@synchronized
        pendientesEnMemoria[cuentaId] = texto

        val copia = try {
            JSONObject(texto)
        } catch (error: JSONException) {
            throw IllegalStateException(
                "No se pudo leer la copia pendiente. Los datos originales siguen conservados.",
                error,
            )
        }

        // Un editor prepara cambios en memoria, pero no los aplica hasta commit.
        // Se revisa toda la copia antes de escribir el primer archivo de destino.
        val editores = try {
            archivos.map { nombre ->
                comprobarInterrupcion()
                val campos = copia.getJSONObject(nombre)
                val editor = destino(nombre, cuentaId).edit().clear()
                val claves = campos.keys()

                while (claves.hasNext()) {
                    val clave = claves.next()
                    restaurar(editor, clave, campos.getJSONObject(clave))
                }

                editor
            }
        } catch (error: JSONException) {
            // JSONException significa que faltan campos o tienen un formato incorrecto.
            // Se traduce al error que el formulario sabe mostrar, sin dejarlo esperando.
            throw IllegalStateException(
                "No se pudo leer la copia pendiente. Los datos originales siguen conservados.",
                error,
            )
        }

        // No se modifica ningún destino hasta que este contenido pueda recuperarse
        // también después de cerrar el proceso de la aplicación.
        comprobarInterrupcion()
        confirmarPendiente(control, texto)

        editores.forEach { editor ->
            comprobarInterrupcion()
            check(editor.commit()) {
                "La copia no pudo terminar. Inicia sesión otra vez para continuar sin perder el original."
            }
        }

        comprobarInterrupcion()

        try {
            check(
                control.edit()
                    .remove("pendiente")
                    .putBoolean("completa", true)
                    .commit(),
            ) {
                "No se pudo confirmar la copia. Se volverá a intentar en la próxima entrada."
            }
        } catch (error: RuntimeException) {
            restaurarPendiente(control, texto, error)

            throw error
        }

        // Solo se publica una copia terminada cuando el marcador quedó confirmado.
        pendientesEnMemoria.remove(cuentaId)

        // Unit indica que terminarPendiente no entrega datos al coordinador.
        Unit
    }

    /**
     * Recibe el archivo de control y el contenido pendiente, y exige guardarlo antes de copiar.
     * preparar y terminarPendiente la llaman bajo cerrojoSesion; no devuelve datos.
     * Si commit falla, restaura el marcador pendiente y lanza el error para no abrir la cuenta.
     * El contenido también sigue en pendientesEnMemoria, aunque esa restauración vuelva a fallar.
     */
    private fun confirmarPendiente(control: SharedPreferences, texto: String) {
        comprobarInterrupcion()

        try {
            check(
                control.edit()
                    .putString("pendiente", texto)
                    .putBoolean("completa", false)
                    .commit(),
            ) {
                "No se pudo preparar la copia. Tu agenda sin cuenta sigue conservada."
            }
        } catch (error: RuntimeException) {
            restaurarPendiente(control, texto, error)

            throw error
        }
    }

    /**
     * Recibe control, copia pendiente y error original; repone el marcador tras un fallo.
     * Las confirmaciones anteriores la llaman y no reciben un resultado. Una interrupción
     * puede hacer que commit devuelva false mientras su escritura sigue esperando: la
     * restauración se confirma después de ella y conserva finalmente la señal de cancelación.
     * Si también falla, se añade esa causa al error; la copia en memoria impide saltar el paso
     * pendiente en la siguiente entrada del mismo proceso, aunque get o all vean el mapa nuevo.
     */
    private fun restaurarPendiente(
        control: SharedPreferences,
        texto: String,
        fallo: RuntimeException,
    ) {
        val estabaInterrumpido = Thread.interrupted()

        try {
            check(
                control.edit()
                    .putString("pendiente", texto)
                    .putBoolean("completa", false)
                    .commit(),
            ) {
                "Tampoco se pudo confirmar el marcador de copia pendiente."
            }
        } catch (error: RuntimeException) {
            fallo.addSuppressed(error)
        } finally {
            if (estabaInterrumpido) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Recibe el código de cuenta y devuelve su pequeño archivo de control de copia.
     * preparar y terminarPendiente lo usan para recordar si una copia está pendiente o terminada.
     * nombrePreferencias revisa que el código sea válido antes de formar el nombre del archivo.
     * Revisa los tipos antes de leer campos: un marcador dañado produce un error que la pantalla
     * puede explicar, en lugar de una conversión inesperada que deje el formulario esperando.
     */
    private fun control(cuentaId: String): SharedPreferences {
        val archivo = contexto.getSharedPreferences(
            EspacioPersonal.nombrePreferencias("copia_agenda_invitada", cuentaId),
            Context.MODE_PRIVATE,
        )

        val campos = archivo.all

        check(!campos.containsKey("pendiente") || campos["pendiente"] is String) {
            "No se pudo leer el estado de la copia. Los datos originales siguen conservados."
        }

        check(!campos.containsKey("completa") || campos["completa"] is Boolean) {
            "No se pudo leer el estado de la copia. Los datos originales siguen conservados."
        }

        return archivo
    }

    /**
     * Recibe el nombre de un archivo y el código de cuenta; devuelve el archivo de esa cuenta.
     * Las funciones anteriores lo usan sin cambiar la sesión activa ni abrir datos de otra persona.
     */
    private fun destino(nombre: String, cuentaId: String): SharedPreferences =
        contexto.getSharedPreferences(
            EspacioPersonal.nombrePreferencias(nombre, cuentaId),
            Context.MODE_PRIVATE,
        )

    /**
     * Recibe un valor de las preferencias y devuelve un objeto con su tipo y contenido.
     * preparar la usa para que un número, un texto y una opción sí/no se recuperen sin confundirse.
     * Un tipo inesperado produce un error antes de modificar el destino.
     */
    private fun codificar(valor: Any?): JSONObject {
        val tipo = when (valor) {
            is String -> "texto"
            is Boolean -> "opcion"
            is Int -> "entero"
            is Long -> "entero_largo"
            is Float -> "decimal"
            is Set<*> -> "textos"
            else -> throw IllegalStateException("Hay un dato que no se puede copiar. Se conservó el original.")
        }

        val contenido = if (valor is Set<*>) {
            check(valor.all { it is String }) {
                "La lista de textos no se pudo copiar. Se conservó el original."
            }
            JSONArray(valor.toList())
        } else {
            valor
        }

        return JSONObject()
            .put("tipo", tipo)
            .put("valor", contenido)
    }

    /**
     * Recibe el editor del archivo destino, el nombre del campo y el valor con su tipo.
     * terminarPendiente la usa para preparar cada campo; el guardado se confirma después en conjunto.
     * No escribe en la agenda sin cuenta ni devuelve información.
     */
    private fun restaurar(editor: SharedPreferences.Editor, clave: String, dato: JSONObject) {
        when (dato.getString("tipo")) {
            "texto" -> editor.putString(clave, dato.getString("valor"))
            "opcion" -> editor.putBoolean(clave, dato.getBoolean("valor"))
            "entero" -> editor.putInt(clave, dato.getInt("valor"))
            "entero_largo" -> editor.putLong(clave, dato.getLong("valor"))
            "decimal" -> editor.putFloat(clave, dato.getDouble("valor").toFloat())
            "textos" -> {
                val valores = dato.getJSONArray("valor")
                val textos = (0 until valores.length()).map { valores.getString(it) }.toSet()
                editor.putStringSet(clave, textos)
            }
            else -> throw IllegalStateException("La copia contiene un tipo desconocido. Se conservó el original.")
        }
    }

    /**
     * Comprueba si se salió del formulario mientras se copiaban datos.
     * Las funciones de copia la llaman antes de escribir para parar sin abrir una sesión por sorpresa.
     */
    private fun comprobarInterrupcion() {
        check(!Thread.currentThread().isInterrupted) {
            "La entrada se interrumpió. Puedes volver a intentarlo."
        }
    }

    private companion object {
        // Solo se consulta y modifica bajo EspacioPersonal.cerrojoSesion.
        // Conserva el contenido solicitado hasta confirmar todos los archivos y el marcador.
        val pendientesEnMemoria = mutableMapOf<String, String>()
    }
}
