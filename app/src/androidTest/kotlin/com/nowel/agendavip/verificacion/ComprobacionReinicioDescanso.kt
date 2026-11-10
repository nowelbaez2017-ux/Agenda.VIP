package com.nowel.agendavip.verificacion

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import com.nowel.agendavip.recordatorios.descanso.GestorDescanso
import com.nowel.agendavip.recordatorios.descanso.ReceptorDescanso
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Comprueba un reinicio real en dos ejecuciones separadas del emulador.
 * Preparar deja una cuenta QA activa y guarda la identidad anterior en un diario privado.
 * Root reinicia Android y consulta sus alarmas antes de iniciar la segunda ejecución.
 * Comprobar no programa alarmas: observa la recuperación realizada por Android y después limpia.
 */
class ComprobacionReinicioDescanso(
    private val instrumentacion: Instrumentation,
    private val aplicacion: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
) {
    private val diario = aplicacion.getSharedPreferences("qa_reinicio_descanso", Context.MODE_PRIVATE)

    /**
     * La fase inicial la llama antes de reiniciar; devuelve true si debe conservarse la sesión QA.
     * No utiliza accesibilidad ni voz, y nunca altera una cuenta personal existente.
     */
    fun preparar(): Boolean {
        val preparado = probar("Descanso reinicio: se guarda una semilla QA y la sesión anterior") {
            exigirEmulador()
            check(!diario.contains("qa")) {
                "Hay un reinicio QA pendiente. Ejecuta primero la fase comprobar para recuperar su sesión."
            }
            val anterior = EspacioPersonal.claveActual(aplicacion)
            val sufijo = UUID.randomUUID().toString().take(8)
            val nueva = RepositorioCuentas(aplicacion).crear(
                "Descanso reinicio QA",
                "qa_descanso_boot_$sufijo",
                "Clave exclusiva del reinicio $sufijo 2026".toCharArray(),
            )
            cuentasCreadas += nueva
            check(diario.edit()
                .putString("original", anterior ?: SIN_SESION)
                .putString("qa", nueva.id)
                .putInt("arranque", numeroArranque())
                .putLong("tiempo_encendido", SystemClock.elapsedRealtime())
                .commit())

            CoordinadorSesion(aplicacion).entrar(nueva)
            val ahora = ZonedDateTime.now()
            val configuracion = ConfiguracionDescanso(
                activado = true,
                inicio = ahora.minusMinutes(2).toLocalTime().withSecond(0).withNano(0),
                fin = ahora.plusHours(1).toLocalTime().withSecond(0).withNano(0),
                aplicaciones = setOf("com.android.chrome"),
                mensaje = "Rutina QA después de un reinicio real.",
                vozActiva = false,
            )
            val repositorio = RepositorioDescanso(aplicacion)
            repositorio.guardarConfiguracion(configuracion)
            val ventana = checkNotNull(ReglasDescanso.ventanaActual(configuracion, ahora))
            check(repositorio.pausar(ventana.fechaNoche, ahora.toInstant().toEpochMilli()))
            GestorDescanso.actualizar(aplicacion)
            val direccion = "agendavip://descanso/${nueva.id}/${GestorDescanso.FIN}/${ventana.fechaNoche}"
            check(diario.edit()
                .putString("fecha", ventana.fechaNoche.toString())
                .putString("inicio", configuracion.inicio.toString())
                .putString("fin", configuracion.fin.toString())
                .putLong("pausa_hasta", repositorio.estado(ventana.fechaNoche).pausaHasta)
                .putLong("fin_instante", ventana.fin.toInstant().toEpochMilli())
                .putString("direccion_fin", direccion)
                .commit())
            check(existePendiente(direccion)) {
                "No se creó el aviso de final antes de reiniciar."
            }
        }
        if (!preparado && diario.contains("qa")) {
            probar("Descanso reinicio: una preparación fallida recupera su sesión anterior") {
                limpiarYRestaurar()
            }
        }
        return preparado
    }

    /**
     * La segunda fase la llama después de adb reboot y de la inspección externa de AlarmManager.
     * Los casos no llaman a actualizar ni entrar antes de observar; hacerlo ocultaría un fallo de arranque.
     * Su finally desactiva la rutina QA y devuelve la sesión original incluso si falla un caso.
     */
    fun comprobarYLimpiar() {
        try {
            probar("Descanso reinicio: Android realmente arrancó de nuevo") {
                exigirEmulador()
                check(diario.contains("qa")) { "No existe una preparación de reinicio pendiente." }
                val anterior = diario.getInt("arranque", -1)
                check(anterior >= 0 && numeroArranque() > anterior) {
                    "No cambió BOOT_COUNT; esta comprobación exige apagar y encender realmente el emulador."
                }
            }
            probar("Descanso reinicio: conserva la cuenta activa, el horario y la pausa usada") {
                val identidad = checkNotNull(diario.getString("qa", null))
                check(EspacioPersonal.claveActual(aplicacion) == identidad)
                val repositorio = RepositorioDescanso(aplicacion)
                val configuracion = repositorio.configuracion()
                check(configuracion.activado && !configuracion.vozActiva)
                check(configuracion.inicio.toString() == diario.getString("inicio", null))
                check(configuracion.fin.toString() == diario.getString("fin", null))
                val fecha = LocalDate.parse(diario.getString("fecha", null))
                val estado = repositorio.estado(fecha)
                check(estado.pausasUsadas == 1)
                check(estado.pausaHasta == diario.getLong("pausa_hasta", -1))
            }
            probar("Descanso reinicio: el receptor recuperó el aviso final sin abrir la agenda") {
                check(System.currentTimeMillis() < diario.getLong("fin_instante", 0L)) {
                    "La revisión empezó después del horario QA preparado. Repite ambas fases."
                }
                val direccion = checkNotNull(diario.getString("direccion_fin", null))
                // FLAG_NO_CREATE solo consulta: no puede crear ni reprogramar el aviso que se comprueba.
                check(existePendiente(direccion)) {
                    "No existe el PendingIntent de final después del arranque."
                }
                val direcciones = aplicacion.getSharedPreferences(
                    "descanso_alarmas_sistema",
                    Context.MODE_PRIVATE,
                ).getStringSet("direcciones", emptySet()).orEmpty()
                check(direccion in direcciones)
            }
        } finally {
            probar("Descanso reinicio: se desactiva QA y se restaura la sesión anterior") {
                limpiarYRestaurar()
            }
        }
    }

    /** Recibe la dirección esperada y consulta si su aviso existe sin crear uno nuevo. */
    private fun existePendiente(direccion: String): Boolean {
        val pendiente = PendingIntent.getBroadcast(
            aplicacion,
            0,
            Intent(aplicacion, ReceptorDescanso::class.java)
                .setAction(GestorDescanso.FIN)
                .setData(Uri.parse(direccion)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        return pendiente != null
    }

    /** Devuelve el contador de arranques de Android, que persiste entre procesos y cambia al reiniciar. */
    private fun numeroArranque(): Int {
        return Settings.Global.getInt(aplicacion.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }

    /** Impide ejecutar esta preparación persistente en un teléfono físico. */
    private fun exigirEmulador() {
        check(Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("/sdk_")) {
            "La comprobación persistente de reinicio solo se permite en el emulador."
        }
    }

    /**
     * Desactiva la cuenta QA, cancela sus avisos y repone la identidad guardada en el diario.
     * Si otra identidad inesperada intervino, conserva el diario y evita modificar su sesión.
     */
    private fun limpiarYRestaurar() {
        exigirEmulador()
        val qa = diario.getString("qa", null) ?: return
        val originalGuardada = checkNotNull(diario.getString("original", null))
        val original = originalGuardada.takeUnless { it == SIN_SESION }
        val actual = EspacioPersonal.claveActual(aplicacion)
        check(actual == qa || actual == original) {
            "Cambió la sesión durante la comprobación. Se conservó el diario QA para revisarlo."
        }

        if (actual == qa) {
            val repositorio = RepositorioDescanso(aplicacion)
            repositorio.guardarConfiguracion(repositorio.configuracion().copy(activado = false))
            GestorDescanso.detenerPorCambioSesion(aplicacion)
        }
        if (actual != original) {
            val coordinador = CoordinadorSesion(aplicacion)
            when (original) {
                null -> coordinador.cerrarSesion()
                EspacioPersonal.INVITADO -> coordinador.entrarComoInvitado()
                else -> {
                    val cuenta = checkNotNull(RepositorioCuentas(aplicacion).obtener(original)) {
                        "La cuenta original dejó de estar disponible. El diario QA se conservó."
                    }
                    coordinador.entrar(cuenta)
                }
            }
        }
        check(EspacioPersonal.claveActual(aplicacion) == original)
        check(diario.edit().clear().commit())
    }

    companion object {
        private const val SIN_SESION = "__SIN_SESION__"
    }
}
