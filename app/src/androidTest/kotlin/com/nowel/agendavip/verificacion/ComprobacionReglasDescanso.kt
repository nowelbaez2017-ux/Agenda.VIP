package com.nowel.agendavip.verificacion

import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import com.nowel.agendavip.datos.cuentas.CoordinadorSesion
import com.nowel.agendavip.datos.cuentas.EspacioPersonal
import com.nowel.agendavip.datos.cuentas.RepositorioCuentas
import com.nowel.agendavip.datos.descanso.RepositorioDescanso
import com.nowel.agendavip.datos.respaldo.CopiaSeguridadLocal
import com.nowel.agendavip.dominio.cuentas.CuentaLocal
import com.nowel.agendavip.dominio.descanso.ConfiguracionDescanso
import com.nowel.agendavip.dominio.descanso.ReglasDescanso
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Comprueba las cuentas del horario y el guardado real dentro de dos cuentas QA propias.
 * QA identifica datos exclusivos de comprobación. Este archivo no forma parte del APK normal.
 * El runner recibe cada resultado y repone al terminar la sesión que estaba abierta antes.
 * No solicita permisos, no abre el micrófono y no utiliza archivos personales.
 */
class ComprobacionReglasDescanso(
    private val instrumentacion: Instrumentation,
    private val aplicacion: Context,
    private val probar: (String, () -> Unit) -> Boolean,
    private val cuentasCreadas: MutableList<CuentaLocal>,
) {
    private val zona = ZoneId.of("America/Santo_Domingo")
    private val lunes = LocalDate.of(2026, 11, 2)
    private val opciones = ConfiguracionDescanso(activado = true)
    private val propias = mutableListOf<CuentaLocal>()

    /** El runner la llama con solo=reglas_descanso; revisa reglas y datos sin activar restricciones. */
    fun ejecutar() {
        verificarHorarios()
        verificarCambiosDeHora()
        verificarValidacion()
        try {
            if (prepararCuentas()) {
                verificarPersistencia()
                verificarPausas()
                verificarVozYUso()
                verificarAislamiento()
                verificarDatosDanados()
                verificarRespaldo()
                verificarRespaldoCifrado()
                verificarRetencion()
            }
        } finally {
            // La sesión exterior la restaura VerificacionAgenda. Aquí se dejan apagadas las opciones QA.
            if (EspacioPersonal.claveActual(aplicacion) in propias.map { cuenta -> cuenta.id }) {
                runCatching {
                    val repositorio = RepositorioDescanso(aplicacion)
                    repositorio.guardarConfiguracion(repositorio.configuracion().copy(activado = false))
                }
            }
        }
    }

    /** Comprueba inicios, finales y días de inicio; recibe fechas fijas para resultados reproducibles. */
    private fun verificarHorarios() {
        probar("Descanso: una rutina apagada no está vigente") {
            check(ReglasDescanso.ventanaActual(opciones.copy(activado = false), momento(lunes, 22, 0)) == null)
        }
        probar("Descanso: antes del comienzo no hay restricción y se encuentra la noche siguiente") {
            val ahora = momento(lunes, 20, 19)
            check(ReglasDescanso.ventanaActual(opciones, ahora) == null)
            check(ReglasDescanso.proximaVentana(opciones, ahora).inicio == momento(lunes, 20, 30))
        }
        probar("Descanso: comienzo inclusivo y final exclusivo") {
            check(ReglasDescanso.ventanaActual(opciones, momento(lunes, 20, 30)) != null)
            check(ReglasDescanso.ventanaActual(opciones, momento(lunes.plusDays(1), 4, 59)) != null)
            check(ReglasDescanso.ventanaActual(opciones, momento(lunes.plusDays(1), 5, 0)) == null)
        }
        probar("Descanso: medianoche mantiene la identidad de la noche anterior") {
            val antes = checkNotNull(ReglasDescanso.ventanaActual(opciones, momento(lunes, 23, 59)))
            val despues = checkNotNull(ReglasDescanso.ventanaActual(opciones, momento(lunes.plusDays(1), 0, 1)))
            check(antes.fechaNoche == lunes && despues.fechaNoche == lunes)
            check(antes == despues)
        }
        probar("Descanso: el lunes elegido incluye su madrugada del martes") {
            val soloLunes = opciones.copy(dias = setOf(DayOfWeek.MONDAY))
            check(ReglasDescanso.ventanaActual(soloLunes, momento(lunes.plusDays(1), 3, 0)) != null)
            check(ReglasDescanso.ventanaActual(soloLunes, momento(lunes.plusDays(1), 21, 0)) == null)
            check(ReglasDescanso.proximaVentana(soloLunes, momento(lunes.plusDays(1), 12, 0)).fechaNoche ==
                lunes.plusWeeks(1))
        }
        probar("Descanso: un horario diurno termina el mismo día") {
            val diurno = opciones.copy(inicio = LocalTime.of(13, 0), fin = LocalTime.of(14, 0))
            val ventana = checkNotNull(ReglasDescanso.ventanaActual(diurno, momento(lunes, 13, 30)))
            check(ventana.inicio.toLocalDate() == ventana.fin.toLocalDate())
            check(ReglasDescanso.ventanaActual(diurno, momento(lunes, 14, 0)) == null)
        }
        probar("Descanso: la vista previa apagada devuelve el período vigente sin activarlo") {
            val apagada = opciones.copy(activado = false)
            val ahora = momento(lunes.plusDays(1), 1, 0)
            check(ReglasDescanso.proximaVentana(apagada, ahora).fechaNoche == lunes)
            check(ReglasDescanso.ventanaActual(apagada, ahora) == null)
        }
    }

    /** Comprueba la duración real durante los saltos estacionales y el contenido prudente de la voz. */
    private fun verificarCambiosDeHora() {
        probar("Descanso: el cambio de marzo descuenta la hora que desaparece") {
            val inicio = ZonedDateTime.of(2026, 3, 28, 20, 30, 0, 0, ZoneId.of("Europe/Madrid"))
            val ventana = ReglasDescanso.proximaVentana(opciones, inicio)
            check(Duration.between(ventana.inicio, ventana.fin).toMinutes() == 450L)
            val mensaje = ReglasDescanso.mensajeDescanso(opciones, inicio)
            check(mensaje.contains("7 horas y 30 minutos"))
        }
        probar("Descanso: el cambio de octubre cuenta la hora que se repite") {
            val inicio = ZonedDateTime.of(2026, 10, 24, 20, 30, 0, 0, ZoneId.of("Europe/Madrid"))
            val ventana = ReglasDescanso.proximaVentana(opciones, inicio)
            check(Duration.between(ventana.inicio, ventana.fin).toMinutes() == 570L)
        }
        probar("Descanso: la voz calcula tiempo disponible e incluye nombre y mensaje personal") {
            val personal = opciones.copy(mensaje = "Recuerda preparar tu ropa para mañana.")
            val mensaje = ReglasDescanso.mensajeDescanso(personal, momento(lunes, 20, 30), "Nowel")
            check(mensaje.contains("Nowel") && mensaje.contains("8 horas y 30 minutos"))
            check(mensaje.contains("disponibles") && mensaje.contains(personal.mensaje))
            check(!mensaje.contains("vas a dormir", ignoreCase = true))
            check(!mensaje.contains("sin estar agotado", ignoreCase = true))
        }
    }

    /** Entrega opciones incorrectas y comprueba que las reglas las rechazan antes de guardarlas. */
    private fun verificarValidacion() {
        probar("Descanso: se rechazan horas iguales y una lista vacía de días") {
            check(runCatching { ReglasDescanso.validar(opciones.copy(fin = opciones.inicio)) }.isFailure)
            check(runCatching { ReglasDescanso.validar(opciones.copy(dias = emptySet())) }.isFailure)
        }
        probar("Descanso: se rechazan mensajes excesivos y paquetes de aplicaciones inválidos") {
            check(runCatching { ReglasDescanso.validar(opciones.copy(mensaje = "x".repeat(501))) }.isFailure)
            check(runCatching {
                ReglasDescanso.validar(opciones.copy(aplicaciones = setOf("paquete con espacios")))
            }.isFailure)
        }
    }

    /** Crea dos identidades QA propias; devuelve si ambas quedaron disponibles para las pruebas. */
    private fun prepararCuentas(): Boolean {
        return probar("Descanso: se preparan dos cuentas QA sin tocar cuentas personales") {
            repeat(2) { indice ->
                val sufijo = UUID.randomUUID().toString().take(8)
                val cuenta = RepositorioCuentas(aplicacion).crear(
                    "Descanso QA $indice",
                    "qa_descanso_$sufijo",
                    "Clave exclusiva de descanso $sufijo 2026".toCharArray(),
                )
                propias += cuenta
                cuentasCreadas += cuenta
            }
            CoordinadorSesion(aplicacion).entrar(propias.first())
        }
    }

    /** Comprueba valores iniciales y una lectura posterior al guardado mediante otro repositorio. */
    private fun verificarPersistencia() {
        probar("Descanso: una cuenta nueva comienza desactivada y sin aplicaciones elegidas") {
            val repositorio = RepositorioDescanso(aplicacion)
            check(repositorio.configuracion() == ConfiguracionDescanso())
            check(repositorio.historial().isEmpty())
        }
        probar("Descanso: configuración personal conserva horario, días, voz y aplicaciones") {
            val configuracion = opciones.copy(
                dias = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                aplicaciones = setOf("com.agenda.qa.distraccion"),
                mensaje = "Prepara tu descanso.",
                vozActiva = false,
                restriccionActiva = false,
            )
            RepositorioDescanso(aplicacion).guardarConfiguracion(configuracion)
            check(RepositorioDescanso(aplicacion).configuracion() == configuracion)
        }
    }

    /** Comprueba una sola pausa por noche, su persistencia, el límite final y la cancelación explícita. */
    private fun verificarPausas() {
        probar("Descanso: una pausa dura cinco minutos y no vuelve a concederse al recrear repositorio") {
            val ahora = ZonedDateTime.now().withSecond(0).withNano(0)
            val repositorio = configurarAlrededor(ahora)
            val ventana = checkNotNull(ReglasDescanso.ventanaActual(repositorio.configuracion(), ahora))
            val instante = ahora.toInstant().toEpochMilli()
            check(repositorio.pausar(ventana.fechaNoche, instante))
            val guardada = RepositorioDescanso(aplicacion).estado(ventana.fechaNoche)
            check(guardada.pausasUsadas == 1 && guardada.pausaHasta == instante + 300_000L)
            check(!RepositorioDescanso(aplicacion).pausar(ventana.fechaNoche, instante + 360_000L))
        }
        probar("Descanso: una pausa cerca del final no prolonga el horario") {
            val ahora = ZonedDateTime.now().plusDays(2).withSecond(0).withNano(0)
            val repositorio = configurarAlrededor(ahora, minutosFinal = 2)
            val ventana = checkNotNull(ReglasDescanso.ventanaActual(repositorio.configuracion(), ahora))
            check(repositorio.pausar(ventana.fechaNoche, ahora.toInstant().toEpochMilli()))
            check(repositorio.estado(ventana.fechaNoche).pausaHasta == ventana.fin.toInstant().toEpochMilli())
        }
        probar("Descanso: cancelar la noche impide nuevas pausas y avisos sin borrar otros días") {
            val ahora = ZonedDateTime.now().plusDays(3).withSecond(0).withNano(0)
            val repositorio = configurarAlrededor(ahora)
            val fecha = checkNotNull(ReglasDescanso.ventanaActual(repositorio.configuracion(), ahora)).fechaNoche
            repositorio.cancelarNoche(fecha)
            check(repositorio.estado(fecha).cancelada)
            check(!repositorio.pausar(fecha, ahora.toInstant().toEpochMilli()))
            check(!repositorio.marcarAviso(fecha, previo = false))
            check(!repositorio.estado(fecha.plusDays(1)).cancelada)
        }
    }

    /** Comprueba que las marcas impiden avisos repetidos y que los tiempos observados se acumulan. */
    private fun verificarVozYUso() {
        probar("Descanso: previo e inicio se registran una sola vez por noche") {
            val repositorio = RepositorioDescanso(aplicacion)
            val fecha = LocalDate.now().plusDays(5)
            check(repositorio.marcarAviso(fecha, previo = true))
            check(!RepositorioDescanso(aplicacion).marcarAviso(fecha, previo = true))
            check(repositorio.marcarAviso(fecha, previo = false))
            check(!repositorio.marcarAviso(fecha, previo = false))
        }
        probar("Descanso: el límite de voz persiste entre instancias") {
            val repositorio = RepositorioDescanso(aplicacion)
            val fecha = LocalDate.now().plusDays(6)
            val instante = System.currentTimeMillis()
            check(repositorio.registrarVoz(fecha, instante))
            check(!RepositorioDescanso(aplicacion).registrarVoz(fecha, instante + 60_000L))
            check(repositorio.registrarVoz(fecha, instante + 300_000L))
        }
        probar("Descanso: el uso agregado suma sin aceptar cantidades negativas") {
            val repositorio = RepositorioDescanso(aplicacion)
            val fecha = LocalDate.now().plusDays(7)
            repositorio.registrarUso(fecha, 90_000L)
            repositorio.registrarUso(fecha, 30_000L)
            check(repositorio.estado(fecha).milisegundosUso == 120_000L)
            check(runCatching { repositorio.registrarUso(fecha, -1L) }.isFailure)
            check(RepositorioDescanso(aplicacion).estado(fecha).milisegundosUso == 120_000L)
        }
    }

    /** Cambia entre las dos cuentas propias y comprueba que una instancia anterior pierde autorización. */
    private fun verificarAislamiento() {
        probar("Descanso: opciones e historial están aislados por cuenta") {
            val anterior = RepositorioDescanso(aplicacion)
            val guardada = anterior.configuracion()
            CoordinadorSesion(aplicacion).entrar(propias[1])
            check(RepositorioDescanso(aplicacion).configuracion() == ConfiguracionDescanso())
            check(RepositorioDescanso(aplicacion).historial().isEmpty())
            check(runCatching { anterior.configuracion() }.isFailure)
            CoordinadorSesion(aplicacion).entrar(propias[0])
            check(RepositorioDescanso(aplicacion).configuracion() == guardada)
        }
    }

    /** Daña solo campos QA temporalmente y comprueba que un error no reemplace el archivo por vacío. */
    private fun verificarDatosDanados() {
        probar("Descanso: una configuración dañada se conserva al intentar leer o guardar") {
            val preferencias = EspacioPersonal(aplicacion).preferencias(RepositorioDescanso.NOMBRE_ARCHIVO)
            val anterior = preferencias.getString("configuracion", null)
            try {
                check(preferencias.edit().putString("configuracion", "{incompleto").commit())
                val repositorio = RepositorioDescanso(aplicacion)
                check(runCatching { repositorio.configuracion() }.isFailure)
                check(runCatching { repositorio.guardarConfiguracion(opciones) }.isFailure)
                check(preferencias.getString("configuracion", null) == "{incompleto")
            } finally {
                check(preferencias.edit().putString("configuracion", anterior).commit())
            }
        }
        probar("Descanso: un historial dañado no se borra al registrar un aviso") {
            val preferencias = EspacioPersonal(aplicacion).preferencias(RepositorioDescanso.NOMBRE_ARCHIVO)
            val anterior = preferencias.getString("noches", null)
            try {
                check(preferencias.edit().putString("noches", "no es JSON").commit())
                check(runCatching { RepositorioDescanso(aplicacion).marcarAviso(lunes, false) }.isFailure)
                check(preferencias.getString("noches", null) == "no es JSON")
            } finally {
                check(preferencias.edit().putString("noches", anterior).commit())
            }
        }
    }

    /** Valida el formato real del respaldo y rechaza campos desconocidos y enteros fuera de rango. */
    private fun verificarRespaldo() {
        probar("Descanso: el respaldo acepta un archivo vacío o válido y rechaza campos desconocidos") {
            val preferencias = EspacioPersonal(aplicacion).preferencias(RepositorioDescanso.NOMBRE_ARCHIVO)
            RepositorioDescanso.validarCopia(emptyMap<String, Any>())
            RepositorioDescanso.validarCopia(preferencias.all)
            check(runCatching { RepositorioDescanso.validarCopia(mapOf("extra" to true)) }.isFailure)
            val texto = checkNotNull(preferencias.getString("configuracion", null))
            val alterada = JSONObject(texto).put("dias", JSONArray().put(4_294_967_297L))
            check(runCatching {
                RepositorioDescanso.validarCopia(mapOf("configuracion" to alterada.toString()))
            }.isFailure)
        }
    }

    /**
     * Exporta e importa un respaldo cifrado QA real; conserva opciones e historial, pero vuelve apagado.
     * El archivo temporal pertenece a esta prueba y se elimina incluso si falla una comprobación.
     */
    private fun verificarRespaldoCifrado() {
        probar("Descanso: respaldo cifrado conserva horario e historial y restaura desactivado") {
            val repositorio = RepositorioDescanso(aplicacion)
            val original = repositorio.configuracion().copy(activado = true)
            repositorio.guardarConfiguracion(original)
            val historial = repositorio.historial()
            val archivo = File(aplicacion.cacheDir, "descanso_qa_${UUID.randomUUID()}.agendavip")
            val clave = "Respaldo privado exclusivo de Descanso QA 2026"

            try {
                val copia = CopiaSeguridadLocal(aplicacion)
                copia.exportar(Uri.fromFile(archivo), clave.toCharArray())
                check(archivo.length() > 64)
                repositorio.guardarConfiguracion(ConfiguracionDescanso())
                val preparada = copia.preparar(Uri.fromFile(archivo), clave.toCharArray())
                copia.restaurar(preparada)
                val restaurado = RepositorioDescanso(aplicacion)
                check(restaurado.configuracion() == original.copy(activado = false))
                check(restaurado.historial() == historial)
            } finally {
                check(!archivo.exists() || archivo.delete()) {
                    "No se pudo retirar el respaldo temporal de Descanso QA."
                }
            }
        }
    }

    /** Añade noches propias para comprobar el límite de sesenta registros y su orden más reciente. */
    private fun verificarRetencion() {
        probar("Descanso: el historial conserva solo sesenta noches ordenadas") {
            val repositorio = RepositorioDescanso(aplicacion)
            val primera = LocalDate.now().plusDays(100)
            repeat(65) { indice ->
                repositorio.registrarUso(primera.plusDays(indice.toLong()), 1_000L)
            }
            val historial = RepositorioDescanso(aplicacion).historial()
            check(historial.size == 60)
            check(historial.first().fecha == primera.plusDays(64))
            check(historial.last().fecha == primera.plusDays(5))
        }
    }

    /** Devuelve una fecha y hora fija en Santo Domingo para comprobar fronteras de horario. */
    private fun momento(fecha: LocalDate, hora: Int, minuto: Int): ZonedDateTime {
        return fecha.atTime(hora, minuto).atZone(zona)
    }

    /** Prepara en QA un intervalo que contiene el instante recibido; no programa alarmas ni servicios. */
    private fun configurarAlrededor(
        ahora: ZonedDateTime,
        minutosFinal: Long = 60,
    ): RepositorioDescanso {
        val repositorio = RepositorioDescanso(aplicacion)
        repositorio.guardarConfiguracion(
            opciones.copy(
                inicio = ahora.minusMinutes(15).toLocalTime(),
                fin = ahora.plusMinutes(minutosFinal).toLocalTime(),
                vozActiva = false,
                restriccionActiva = false,
            ),
        )
        return repositorio
    }
}
