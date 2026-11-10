"""Convierte recordatorios entre XML legible y las órdenes que ya entiende la app."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import re
from typing import Callable
from uuid import UUID, uuid5, NAMESPACE_URL
import xml.etree.ElementTree as ET


MAXIMO_XML = 8 * 1024 * 1024
MAXIMO_RECORDATORIOS = 10000
REPETICIONES = {"una_vez": "UNA_VEZ", "diaria": "DIARIA", "dias_semana": "DIAS_SEMANA"}
MODOS_MUSICA = {"aleatoria": "ALEATORIA", "sin_musica": "SIN_MUSICA", "seleccionada": "SELECCIONADA"}
DIAS = {"lunes": 1, "martes": 2, "miercoles": 3, "jueves": 4, "viernes": 5, "sabado": 6, "domingo": 7}
CAMPOS = {"titulo", "fecha", "hora", "periodo", "repeticion", "dias_semana", "voz", "musica", "seguimiento"}


def _datos_iniciales(identificador: str) -> dict:
    """Recibe una identidad y devuelve los valores iniciales de una tarea nueva del XML."""
    return {
        "id": identificador,
        "titulo": "",
        "fecha": "",
        "hora": "",
        "repeticion": "UNA_VEZ",
        "diasSemana": [],
        "vozActiva": True,
        "mensajeVoz": "",
        "musicaUri": None,
        "musicaNombre": "",
        "seguimientoActivo": False,
        "intervaloMinutos": 5,
        "limiteAvisos": 3,
        "mensajeSeguimiento": "",
        "modoMusica": "ALEATORIA",
    }


def _texto(padre: ET.Element, nombre: str, defecto: str = "") -> str:
    """Recibe un grupo y un campo y devuelve su texto. Rechaza etiquetas anidadas por error."""
    campo = padre.find(nombre)
    if campo is None:
        return defecto
    if len(campo) or campo.attrib:
        raise ValueError(f"La etiqueta {nombre} debe contener solamente texto.")
    return campo.text or ""


def _grupo(elemento: ET.Element, atributos: set[str], hijos: set[str], repetidos: set[str] | None = None) -> None:
    """Comprueba las etiquetas y atributos de un grupo para informar también de errores de escritura."""
    if set(elemento.attrib) - atributos:
        raise ValueError(f"La etiqueta {elemento.tag} contiene un atributo desconocido.")
    nombres = [hijo.tag for hijo in elemento]
    if set(nombres) - hijos:
        raise ValueError(f"La etiqueta {elemento.tag} contiene un campo desconocido.")
    permitidos = repetidos or set()
    for nombre in nombres:
        if nombre not in permitidos and nombres.count(nombre) > 1:
            raise ValueError(f"La etiqueta {nombre} aparece más de una vez.")


def _booleano(valor: str) -> bool:
    """Recibe true/false o sí/no y devuelve el valor que necesita la tarea de Android."""
    limpio = valor.strip().lower()
    if limpio in {"true", "si", "sí"}:
        return True
    if limpio in {"false", "no"}:
        return False
    raise ValueError("Los valores de activación deben ser true o false.")


def _entero(valor: str, minimo: int, maximo: int, nombre: str) -> int:
    """Comprueba un número escrito en XML y devuelve su valor dentro del rango permitido."""
    try:
        numero = int(valor)
    except ValueError as error:
        raise ValueError(f"{nombre} debe ser un número entero.") from error
    if not minimo <= numero <= maximo:
        raise ValueError(f"{nombre} debe estar entre {minimo} y {maximo}.")
    return numero


def _hora_12(hora: str) -> tuple[str, str]:
    """Recibe una hora interna y devuelve hora de doce horas y periodo, conservando sus segundos."""
    partes = hora.split(":", 1)
    numero = int(partes[0])
    periodo = "AM" if numero < 12 else "PM"
    return f"{numero % 12 or 12:02d}:{partes[1]}", periodo


def _hora_interna(hora: str, periodo: str) -> str:
    """Recibe una hora de doce horas y AM/PM y devuelve el formato que guarda la app."""
    limpia = hora.strip()
    marca = periodo.strip().upper()
    if not re.fullmatch(r"(?:0?[1-9]|1[0-2]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?", limpia):
        raise ValueError("Escribe la hora en doce horas, por ejemplo 08:30, y su periodo AM o PM.")
    if marca not in {"AM", "PM"}:
        raise ValueError("El periodo de la hora debe ser AM o PM.")
    numero, resto = limpia.split(":", 1)
    hora_24 = int(numero) % 12 + (12 if marca == "PM" else 0)
    return f"{hora_24:02d}:{resto}"


def exportar_xml(vinculo_id: str, tareas: list[dict]) -> str:
    """Recibe la cuenta vinculada y sus tareas confirmadas y devuelve un archivo XML editable.

    El panel lo descarga o lo coloca en el editor. La revisión conserva la marca que permite
    detectar si el celular cambió una tarea después de exportarla.
    """
    raiz = ET.Element("agenda", {
        "formato": "1",
        "vinculo": vinculo_id,
        "exportada_en": datetime.now(timezone.utc).isoformat(),
    })
    raiz.append(ET.Comment(
        " Conserva id y revision al editar. Para eliminar utiliza accion=\"eliminar\". "
        "Para crear puedes usar un id nuevo y quitar revision. "
        "Los campos opcionales omitidos conservan su valor en las tareas existentes. "
        "Revisa la vista previa antes de aplicar. "
    ))
    repeticiones = {valor: clave for clave, valor in REPETICIONES.items()}
    modos = {valor: clave for clave, valor in MODOS_MUSICA.items()}
    dias = {valor: clave for clave, valor in DIAS.items()}

    for entrada in tareas:
        tarea = entrada["datos"]
        recordatorio = ET.SubElement(raiz, "recordatorio", {
            "id": tarea["id"],
            "revision": entrada["version"],
            "accion": "guardar",
        })
        ET.SubElement(recordatorio, "titulo").text = tarea["titulo"]
        ET.SubElement(recordatorio, "fecha").text = tarea["fecha"]
        hora, periodo = _hora_12(tarea["hora"])
        ET.SubElement(recordatorio, "hora").text = hora
        ET.SubElement(recordatorio, "periodo").text = periodo
        ET.SubElement(recordatorio, "repeticion").text = repeticiones[tarea["repeticion"]]
        semana = ET.SubElement(recordatorio, "dias_semana")
        for dia in tarea.get("diasSemana", []):
            ET.SubElement(semana, "dia").text = dias[dia]

        voz = ET.SubElement(recordatorio, "voz", {"activa": str(tarea.get("vozActiva", True)).lower()})
        ET.SubElement(voz, "mensaje").text = tarea.get("mensajeVoz", "")
        musica = ET.SubElement(recordatorio, "musica", {"modo": modos[tarea["modoMusica"]]})
        if tarea.get("musicaNombre") or tarea["modoMusica"] == "SELECCIONADA":
            ET.SubElement(musica, "nombre").text = tarea.get("musicaNombre", "")
        if tarea["modoMusica"] == "SELECCIONADA":
            ET.SubElement(musica, "direccion").text = tarea["musicaUri"]

        seguimiento = ET.SubElement(recordatorio, "seguimiento", {
            "activo": str(tarea.get("seguimientoActivo", False)).lower(),
            "intervalo_minutos": str(tarea.get("intervaloMinutos", 5)),
            "limite_avisos": str(tarea.get("limiteAvisos", 3)),
        })
        ET.SubElement(seguimiento, "mensaje").text = tarea.get("mensajeSeguimiento", "")

    ET.indent(raiz, space="    ")
    # XML normaliza los retornos de carro literales. La referencia conserva ese carácter
    # si llegó dentro de un mensaje copiado de otra aplicación.
    documento = ET.tostring(raiz, encoding="unicode").replace("\r", "&#13;")
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + documento


def _leer_datos(elemento: ET.Element, identificador: str, existente: dict | None) -> dict:
    """Convierte un recordatorio XML en los campos normales de la tarea.

    En una edición parte de los datos actuales y cambia solo los campos presentes.
    Una tarea nueva necesita título, fecha y hora, y toma los valores iniciales del resto.
    """
    datos = _datos_iniciales(identificador)
    if existente:
        datos.update(deepcopy(existente["datos"]))

    datos["titulo"] = _texto(elemento, "titulo", datos["titulo"])
    datos["fecha"] = _texto(elemento, "fecha", datos["fecha"]).strip()
    if elemento.find("hora") is not None:
        if elemento.find("periodo") is None:
            raise ValueError("Añade el periodo AM o PM junto a la hora.")
        datos["hora"] = _hora_interna(_texto(elemento, "hora"), _texto(elemento, "periodo"))
    elif elemento.find("periodo") is not None:
        if not datos["hora"]:
            raise ValueError("Añade una hora antes de elegir AM o PM.")
        hora, _ = _hora_12(datos["hora"])
        datos["hora"] = _hora_interna(hora, _texto(elemento, "periodo"))

    if elemento.find("repeticion") is not None:
        repeticion = _texto(elemento, "repeticion").strip().lower()
        if repeticion not in REPETICIONES:
            raise ValueError("La repetición debe ser una_vez, diaria o dias_semana.")
        datos["repeticion"] = REPETICIONES[repeticion]

    semana = elemento.find("dias_semana")
    if semana is not None:
        _grupo(semana, set(), {"dia"}, {"dia"})
        elegidos = []
        for dia in semana:
            if dia.attrib or len(dia):
                raise ValueError("Cada día debe contener solo su nombre.")
            nombre = (dia.text or "").strip().lower().replace("miércoles", "miercoles").replace("sábado", "sabado")
            if nombre not in DIAS or DIAS[nombre] in elegidos:
                raise ValueError("Revisa los días de la semana y evita repetirlos.")
            elegidos.append(DIAS[nombre])
        datos["diasSemana"] = sorted(elegidos)

    voz = elemento.find("voz")
    if voz is not None:
        _grupo(voz, {"activa"}, {"mensaje"})
        if "activa" in voz.attrib:
            datos["vozActiva"] = _booleano(voz.attrib["activa"])
        datos["mensajeVoz"] = _texto(voz, "mensaje", datos["mensajeVoz"])

    musica = elemento.find("musica")
    if musica is not None:
        _grupo(musica, {"modo"}, {"nombre", "direccion"})
        anterior = datos["modoMusica"]
        modo = musica.get("modo", {valor: clave for clave, valor in MODOS_MUSICA.items()}[anterior])
        if modo not in MODOS_MUSICA:
            raise ValueError("La música debe ser aleatoria, sin_musica o seleccionada.")
        datos["modoMusica"] = MODOS_MUSICA[modo]
        if datos["modoMusica"] == "SELECCIONADA":
            direccion = _texto(musica, "direccion", datos.get("musicaUri") or "")
            if not existente or not direccion or direccion != existente["datos"].get("musicaUri"):
                raise ValueError("Elige la canción en el celular. El XML puede conservar la que ya está seleccionada.")
            datos["musicaUri"] = direccion
            datos["musicaNombre"] = _texto(musica, "nombre", datos["musicaNombre"])
        else:
            if _texto(musica, "direccion").strip():
                raise ValueError("La música aleatoria o sin música debe dejar vacía la dirección.")
            datos["musicaUri"] = None
            nombre_anterior = datos["musicaNombre"] if datos["modoMusica"] == anterior else ""
            datos["musicaNombre"] = _texto(musica, "nombre", nombre_anterior)

    seguimiento = elemento.find("seguimiento")
    if seguimiento is not None:
        _grupo(seguimiento, {"activo", "intervalo_minutos", "limite_avisos"}, {"mensaje"})
        if "activo" in seguimiento.attrib:
            datos["seguimientoActivo"] = _booleano(seguimiento.attrib["activo"])
        if "intervalo_minutos" in seguimiento.attrib:
            datos["intervaloMinutos"] = _entero(seguimiento.attrib["intervalo_minutos"], 1, 1440, "El intervalo")
        if "limite_avisos" in seguimiento.attrib:
            datos["limiteAvisos"] = _entero(seguimiento.attrib["limite_avisos"], 1, 20, "El límite de avisos")
        datos["mensajeSeguimiento"] = _texto(seguimiento, "mensaje", datos["mensajeSeguimiento"])

    return datos


def _mismos_datos(actuales: dict, propuestos: dict) -> bool:
    """Compara campos completos aplicando sus valores iniciales para conservar compatibilidad."""
    base = _datos_iniciales(propuestos["id"])
    anteriores = {**base, **actuales}
    nuevos = {**base, **propuestos}
    return anteriores == nuevos


def preparar_importacion(
    contenido: str,
    vinculo_id: str,
    id_lote: str,
    tareas: list[dict],
    normalizar: Callable[[dict], dict],
) -> dict:
    """Valida todo el archivo y devuelve una vista previa y las órdenes propuestas.

    El servidor pasa su validador habitual. Aplicar vuelve a ejecutar esta lectura dentro
    de la transacción que comprueba versiones y guarda todas las órdenes del lote.
    """
    if not isinstance(contenido, str) or len(contenido.encode("utf-8")) > MAXIMO_XML:
        raise ValueError("El archivo XML debe ocupar como máximo 8 MB.")
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", contenido, flags=re.IGNORECASE):
        raise ValueError("Utiliza un XML de recordatorios sin declaraciones DOCTYPE ni ENTITY.")
    try:
        raiz = ET.fromstring(contenido.lstrip("\ufeff"))
    except ET.ParseError as error:
        linea, columna = error.position
        raise ValueError(f"Revisa el XML en la línea {linea}, columna {columna + 1}.") from error

    if raiz.tag != "agenda" or raiz.get("formato") != "1":
        raise ValueError('El archivo debe comenzar con <agenda formato="1">.')
    _grupo(raiz, {"formato", "vinculo", "exportada_en"}, {"recordatorio"}, {"recordatorio"})
    if raiz.get("vinculo") and raiz.get("vinculo") != vinculo_id:
        raise ValueError("Este XML pertenece a otra cuenta vinculada. Elige esa cuenta en el panel.")
    if len(raiz) > MAXIMO_RECORDATORIOS:
        raise ValueError("Divide el archivo en grupos de hasta 10 000 recordatorios.")
    if str(UUID(id_lote)) != id_lote:
        raise ValueError("La importación necesita una identidad nueva. Vuelve a abrir el editor.")

    actuales = {entrada["id"]: entrada for entrada in tareas}
    encontrados = set()
    ordenes = []
    cambios = []
    resumen = {"nuevos": 0, "modificados": 0, "eliminados": 0, "sin_cambios": 0}
    contadores = {"nuevo": "nuevos", "modificado": "modificados", "eliminado": "eliminados", "sin_cambios": "sin_cambios"}

    for indice, elemento in enumerate(raiz, start=1):
        try:
            _grupo(elemento, {"id", "revision", "accion"}, CAMPOS)
            accion = elemento.get("accion", "guardar")
            if accion not in {"guardar", "eliminar"}:
                raise ValueError("La acción debe ser guardar o eliminar.")
            if accion == "eliminar" and not elemento.get("id", "").strip():
                raise ValueError("Para eliminar conserva el id del recordatorio que quieres retirar.")
            identidad_solicitud = str(uuid5(NAMESPACE_URL, f"AgendaVIP:XML:{vinculo_id}:{id_lote}:{indice}"))
            identificador = elemento.get("id") or str(uuid5(NAMESPACE_URL, identidad_solicitud))
            if not identificador.strip() or len(identificador) > 500:
                raise ValueError("El id debe contener entre 1 y 500 caracteres.")
            if identificador in encontrados:
                raise ValueError("Cada recordatorio debe tener un id diferente.")
            encontrados.add(identificador)

            revision = elemento.get("revision", "").strip().lower() or None
            if revision is not None and not re.fullmatch(r"[0-9a-f]{64}", revision):
                raise ValueError("Conserva la revisión del XML exportado, sin modificarla.")
            existente = actuales.get(identificador)
            if accion == "eliminar" and existente is None:
                tipo = "sin_cambios"
                titulo = identificador
            else:
                if accion == "eliminar" and revision != existente["version"]:
                    raise ValueError("Conserva la revisión actual para eliminar este recordatorio.")
                datos = _leer_datos(elemento, identificador, existente) if accion == "guardar" else None
                propuesta = {
                    "solicitud_id": identidad_solicitud,
                    "accion": accion,
                    "tarea_id": identificador,
                    "base_version": revision,
                    "datos": datos,
                }
                # El mismo validador que usa Nueva tarea comprueba títulos, fechas y opciones.
                normalizada = normalizar(propuesta)
                titulo = datos["titulo"] if datos else existente["datos"]["titulo"]
                if accion == "guardar" and existente and _mismos_datos(existente["datos"], normalizada["datos"]):
                    tipo = "sin_cambios"
                else:
                    if existente is None and revision is not None:
                        raise ValueError("Ese id ya no existe. Para crear una tarea nueva utiliza un id nuevo y omite revision.")
                    if existente is not None and revision != existente["version"]:
                        raise ValueError("El celular cambió este recordatorio. Exporta de nuevo antes de editarlo.")
                    tipo = "eliminado" if accion == "eliminar" else ("modificado" if existente else "nuevo")
                    ordenes.append(normalizada)

            resumen[contadores[tipo]] += 1
            cambios.append({"indice": indice, "tipo": tipo, "id": identificador, "titulo": titulo})
        except ValueError as error:
            raise ValueError(f"Recordatorio {indice}: {error}") from error

    return {"id_lote": id_lote, "resumen": resumen, "cambios": cambios, "ordenes": ordenes}
