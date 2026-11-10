package com.nowel.agendavip.presentacion.descanso

import android.content.Context
import android.content.Intent
import com.nowel.agendavip.recordatorios.descanso.ProteccionesDescanso
import java.util.Locale

/** Identifica una aplicación elegible por su paquete, su nombre único, y por su nombre visible. */
data class AplicacionDescanso(val paquete: String, val nombre: String)

/**
 * Lee únicamente aplicaciones con icono de apertura, sin acceso a todos los paquetes.
 * ActividadDescanso consulta este catálogo en un trabajo separado para no congelar botones.
 * Comparte las excepciones con el servicio: la lista y la restricción protegen las mismas apps.
 */
class CatalogoAplicacionesDescanso(private val contexto: Context) {

    /**
     * Devuelve aplicaciones ordenadas para el selector. Descarta llamadas, SMS, ajustes,
     * inicio, permisos y alarmas mediante las reglas comunes de ProteccionesDescanso.
     * No lee mensajes, contenido de pantallas ni archivos de otras aplicaciones.
     */
    @Suppress("DEPRECATION")
    fun listar(): List<AplicacionDescanso> {
        val gestor = contexto.packageManager
        val protegidas = aplicacionesEsenciales()
        val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return gestor.queryIntentActivities(intento, 0)
            .filter { resultado ->
                val paquete = resultado.activityInfo.packageName
                paquete !in protegidas && !ProteccionesDescanso.esComponenteProtegido(paquete)
            }
            .map { resultado ->
                AplicacionDescanso(
                    paquete = resultado.activityInfo.packageName,
                    nombre = resultado.loadLabel(gestor).toString().take(200),
                )
            }
            .distinctBy { it.paquete }
            .sortedBy { it.nombre.lowercase(Locale.getDefault()) }
    }

    /**
     * Devuelve las excepciones comunes del teléfono. El selector y las comprobaciones
     * utilizan este acceso sin mantener una segunda lista que pudiera quedar desactualizada.
     * No solicita permisos de llamadas ni abre ninguna aplicación.
     */
    fun aplicacionesEsenciales(): Set<String> = ProteccionesDescanso.paquetesEsenciales(contexto)
}
