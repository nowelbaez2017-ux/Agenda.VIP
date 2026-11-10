package com.nowel.agendavip.datos.cuentas

import android.content.Context

/**
 * Recuerda qué espacio local está abierto hasta pulsar Cerrar sesión.
 * Recibe el contexto de Android y conserva únicamente un UUID, INVITADO o la ausencia de sesión.
 * SharedPreferences es un pequeño archivo privado; nunca contiene nombres ni contraseñas.
 * La coordinación de acceso cambia este puntero después de guardar datos y cancelar alarmas.
 * Los cambios se realizan en un hilo de fondo; las pantallas leen una instantánea en memoria.
 */
class SesionLocal(context: Context) {

    private val preferencias = context.applicationContext.getSharedPreferences(
        "sesion_cuentas_locales",
        Context.MODE_PRIVATE,
    )

    init {
        // Todas las instancias comparten la misma instantánea para evitar sesiones contradictorias.
        synchronized(cerrojoEscritura) {
            if (!inicializada) {
                espacioPublicado = leerEspacioInicial()
                inicializada = true
            }
        }
    }

    /**
     * No recibe datos y devuelve el UUID recordado, o null al entrar como invitado o salir.
     * Las pantallas la consultan sin leer disco ni esperar una escritura que esté en curso.
     */
    val cuentaIdActiva: String?
        get() {
            val espacio = espacioPublicado

            return if (espacio == ESPACIO_INVITADO) {
                null
            } else {
                espacio
            }
        }

    /**
     * No recibe datos y devuelve si está abierto el espacio INVITADO.
     * La agenda y el acceso la usan para mostrar la entrada actual desde la instantánea.
     */
    val invitadoActivo: Boolean
        get() {
            return espacioPublicado == ESPACIO_INVITADO
        }

    /**
     * No recibe datos y devuelve si existe una entrada de cuenta o de invitado recordada.
     * Las pantallas la consultan para decidir si muestran acceso o agenda al abrir la aplicación.
     */
    val hayEntradaActiva: Boolean
        get() {
            return espacioPublicado != null
        }

    /**
     * No recibe datos y devuelve UUID, INVITADO o null si no hay sesión.
     * El aislamiento de datos y los recordatorios lo usan como espacio seguro; nunca se
     * construyen nombres de archivo a partir del usuario o del nombre personal.
     */
    val espacioActivo: String?
        get() {
            return espacioPublicado
        }

    /**
     * Recibe el UUID canónico de una cuenta autenticada y confirma su entrada recordada.
     * La coordinación de acceso la llama en segundo plano; no devuelve un dato y no modifica
     * tareas, perfil ni alarmas. Los lectores ven el cambio solo después de confirmarlo en disco.
     */
    fun abrirCuenta(id: String) {
        require(AlmacenCuentas.esIdCanonico(id)) {
            "El identificador de la cuenta no es válido."
        }

        guardarEspacio(id)
    }

    /**
     * No recibe datos: confirma la entrada al espacio INVITADO sin crear una cuenta.
     * El botón Continuar como invitado llega aquí mediante la coordinación de acceso.
     * No devuelve un resultado y conserva intactos los datos de los demás espacios.
     */
    fun abrirInvitado() {
        guardarEspacio(ESPACIO_INVITADO)
    }

    /**
     * No recibe datos y elimina únicamente el puntero de entrada recordada.
     * La coordinación de Cerrar sesión la llama después de cancelar avisos y guardar cambios.
     * No devuelve un dato ni elimina cuentas, perfiles, fotos o tareas.
     */
    fun cerrar() {
        guardarEspacio(null)
    }

    /**
     * No recibe datos y devuelve el puntero privado válido al crear la primera instancia.
     * El constructor la llama una sola vez por proceso; distingue un dato inválido de una
     * sesión cerrada para informar del problema sin borrar silenciosamente lo guardado.
     */
    private fun leerEspacioInicial(): String? {
        val espacio = try {
            preferencias.getString(CLAVE_ESPACIO, null)
        } catch (error: ClassCastException) {
            throw ErrorDatosCuentas("No se pudo leer la sesión guardada. Los datos se conservaron.", error)
        }

        if (
            espacio != null &&
            espacio != ESPACIO_INVITADO &&
            !AlmacenCuentas.esIdCanonico(espacio)
        ) {
            throw ErrorDatosCuentas("No se pudo leer la sesión guardada. Los datos se conservaron.")
        }

        return espacio
    }

    /**
     * Recibe UUID, INVITADO o null y guarda el puntero sin publicar una sesión sin confirmar.
     * abrirCuenta, abrirInvitado y cerrar la llaman en un hilo de fondo; no devuelve un dato.
     * commit espera la confirmación del archivo. Si falla, conserva la instantánea anterior
     * y restaura ese puntero antes de lanzar el error, porque Android pudo cambiar su copia
     * en memoria o dejar una escritura en espera al recibir una cancelación del hilo.
     */
    private fun guardarEspacio(espacio: String?) {
        synchronized(cerrojoEscritura) {
            AlmacenCuentas.comprobarCancelacion()
            val espacioAnterior = espacioPublicado
            val editor = preferencias.edit()

            if (espacio == null) {
                editor.remove(CLAVE_ESPACIO)
            } else {
                editor.putString(CLAVE_ESPACIO, espacio)
            }

            val confirmada = try {
                AlmacenCuentas.comprobarCancelacion()
                val guardada = editor.commit()

                if (guardada) {
                    // Si se abandonó el formulario durante commit, el mismo catch
                    // repone la sesión anterior antes de publicar una entrada tardía.
                    AlmacenCuentas.comprobarCancelacion()
                }

                guardada
            } catch (error: RuntimeException) {
                val fallo = ErrorDatosCuentas(
                    "No se pudo guardar la sesión. Inténtalo de nuevo.",
                    error,
                )

                restaurarEspacioAnterior(espacioAnterior, fallo)

                throw fallo
            }

            if (!confirmada) {
                val fallo = ErrorDatosCuentas("No se pudo guardar la sesión. Inténtalo de nuevo.")
                restaurarEspacioAnterior(espacioAnterior, fallo)

                throw fallo
            }

            // Volatile publica el valor completo para los lectores sin bloquearlos durante commit.
            espacioPublicado = espacio
        }
    }

    /**
     * Recibe el puntero anterior y el error original; intenta reponer la sesión tras un fallo.
     * guardarEspacio la llama dentro del cerrojo y no recibe un resultado. Android puede
     * continuar una escritura que devolvió false por interrupción: esta segunda confirmación
     * queda detrás de ella para que el último valor sea el anterior. La cancelación se conserva.
     * Si el disco tampoco permite restaurar, añade esa causa al error sin publicar otro puntero.
     */
    private fun restaurarEspacioAnterior(
        espacioAnterior: String?,
        fallo: ErrorDatosCuentas,
    ) {
        // interrupted consulta y limpia temporalmente la señal para poder esperar commit.
        val estabaInterrumpido = Thread.interrupted()

        try {
            val editor = preferencias.edit()

            if (espacioAnterior == null) {
                editor.remove(CLAVE_ESPACIO)
            } else {
                editor.putString(CLAVE_ESPACIO, espacioAnterior)
            }

            if (!editor.commit()) {
                fallo.addSuppressed(
                    IllegalStateException("Tampoco se pudo confirmar la sesión anterior."),
                )
            }
        } catch (error: RuntimeException) {
            fallo.addSuppressed(error)
        } finally {
            if (estabaInterrumpido) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        const val CLAVE_ESPACIO = "espacio_activo"
        const val ESPACIO_INVITADO = "INVITADO"

        val cerrojoEscritura = Any()

        @Volatile
        var espacioPublicado: String? = null

        var inicializada = false
    }
}
