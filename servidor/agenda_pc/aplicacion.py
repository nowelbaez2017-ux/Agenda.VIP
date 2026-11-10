"""Conecta el panel y el teléfono con las operaciones privadas del servidor."""

from __future__ import annotations

from collections import defaultdict, deque
from datetime import date, datetime, time, timezone
import base64
import hmac
import json
import logging
from pathlib import Path
import threading
import time as reloj
from typing import Annotated, Literal
from urllib.parse import urlsplit
from uuid import UUID, uuid4, uuid5, NAMESPACE_URL

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator
from starlette.concurrency import run_in_threadpool
from starlette.exceptions import HTTPException

from .configuracion import Configuracion, ipv4_privada
from .datos import Almacen, ErrorOperacion
from .recordatorios_xml import exportar_xml, preparar_importacion


Identificador = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")]
TareaId = Annotated[str, StringConstraints(min_length=1, max_length=500)]
Version = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
TextoBreve = Annotated[str, StringConstraints(max_length=200)]


class Modelo(BaseModel):
    """Hace que cada petición use únicamente los campos y los tipos declarados."""
    model_config = ConfigDict(extra="forbid", strict=True)


class TareaModelo(Modelo):
    """Describe los campos de una tarea que Android guarda en RepositorioTareas."""
    id: TareaId
    titulo: Annotated[str, StringConstraints(min_length=1, max_length=5000)]
    fecha: str
    hora: str
    repeticion: Literal["UNA_VEZ", "DIARIA", "DIAS_SEMANA"] = "UNA_VEZ"
    diasSemana: list[Annotated[int, Field(ge=1, le=7)]] = Field(default_factory=list, max_length=7)
    vozActiva: bool = True
    mensajeVoz: Annotated[str, StringConstraints(max_length=10000)] = ""
    musicaUri: Annotated[str, StringConstraints(max_length=4096)] | None = None
    musicaNombre: TextoBreve = ""
    seguimientoActivo: bool = False
    intervaloMinutos: Annotated[int, Field(ge=1, le=1440)] = 5
    limiteAvisos: Annotated[int, Field(ge=1, le=20)] = 3
    mensajeSeguimiento: Annotated[str, StringConstraints(max_length=10000)] = ""
    modoMusica: Literal["ALEATORIA", "SIN_MUSICA", "SELECCIONADA"] = "ALEATORIA"

    @model_validator(mode="after")
    def comprobar_tarea(self):
        """Comprueba fecha, hora, repetición y música antes de guardar una petición."""
        if not self.id.strip():
            raise ValueError("La tarea necesita una identidad.")
        if not self.titulo.strip():
            raise ValueError("Escribe el título de la tarea.")
        if len(self.fecha) != 10 or date.fromisoformat(self.fecha).isoformat() != self.fecha:
            raise ValueError("La fecha debe tener el formato año-mes-día.")
        if len(self.hora) > 18:
            raise ValueError("La hora no es válida.")
        hora = time.fromisoformat(self.hora)
        if hora.tzinfo is not None:
            raise ValueError("Utiliza la hora local del teléfono.")
        if len(set(self.diasSemana)) != len(self.diasSemana):
            raise ValueError("Los días de la semana no pueden repetirse.")
        if self.repeticion == "DIAS_SEMANA" and not self.diasSemana:
            raise ValueError("Elige los días en que se repetirá la tarea.")
        if self.modoMusica == "SELECCIONADA" and not self.musicaUri:
            raise ValueError("Falta la canción seleccionada.")
        if self.modoMusica != "SELECCIONADA" and self.musicaUri:
            raise ValueError("La música aleatoria y Sin música no necesitan una dirección.")
        return self


class EstadoHoy(Modelo):
    """Guarda la decisión de completar la fecha que muestra actualmente el teléfono."""
    fecha: Annotated[str, StringConstraints(pattern=r"^\d{4}-\d{2}-\d{2}$")]
    completada: bool
    omitida: bool = False


class TareaConfirmada(Modelo):
    """Une la tarea con su versión y el estado de hoy para detectar cambios simultáneos."""
    id: TareaId
    version: Version
    datos: TareaModelo
    estado_hoy: EstadoHoy

    @model_validator(mode="after")
    def comprobar_identidad(self):
        """Rechaza un paquete cuya tarea interna pertenece a otro identificador."""
        if self.id != self.datos.id:
            raise ValueError("La identidad de la tarea no coincide.")
        date.fromisoformat(self.estado_hoy.fecha)
        return self


class PerfilModelo(Modelo):
    """Contiene datos públicos opcionales del perfil, sin contraseñas ni fotos."""
    nombres: Annotated[str, StringConstraints(max_length=500)] = ""
    apellidos: Annotated[str, StringConstraints(max_length=500)] = ""
    correo: Annotated[str, StringConstraints(max_length=500)] = ""
    celular: Annotated[str, StringConstraints(max_length=100)] = ""


class VinculacionModelo(Modelo):
    """Recibe el código de un solo uso y la cuenta que aceptó conectarse a esta PC."""
    codigo: Annotated[str, StringConstraints(min_length=32, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")]
    dispositivo_id: Identificador
    dispositivo_nombre: TextoBreve = ""
    cuenta_local_id: str
    usuario: TextoBreve
    nombre: Annotated[str, StringConstraints(max_length=1000)]

    @model_validator(mode="after")
    def comprobar_cuenta(self):
        """Acepta una identidad UUID local o el espacio separado INVITADO."""
        if self.cuenta_local_id != "INVITADO" and str(UUID(self.cuenta_local_id)) != self.cuenta_local_id:
            raise ValueError("La cuenta local no es válida.")
        return self


class ConfirmacionModelo(Modelo):
    """El celular comunica el primer resultado final de una orden que recibió."""
    id: Identificador
    estado: Literal["aplicada", "conflicto", "error"]
    mensaje: Annotated[str, StringConstraints(max_length=1000)] = ""


class DocumentoRespaldo(Modelo):
    """Valida la envoltura de una copia que también puede leer la app Android."""
    formato: Literal["Agenda.VIP"]
    version: Literal[1]
    creada_en: Annotated[str, StringConstraints(max_length=80)]
    archivos: dict
    foto: str | None = None

    @model_validator(mode="after")
    def comprobar_apartados(self):
        """Impide guardar como respaldo campos de acceso o permisos ajenos a la agenda."""
        esperados = {
            "agenda_local", "enfoque_local", "perfil_local", "preferencias_agenda",
            "conversacion_enfoque", "limites_uso", "descanso_local",
        }
        if set(self.archivos) != esperados or not all(isinstance(valor, dict) for valor in self.archivos.values()):
            raise ValueError("Los apartados del respaldo no son compatibles.")
        datetime.fromisoformat(self.creada_en.replace("Z", "+00:00"))
        return self


class RespaldoEnviado(Modelo):
    """Une la solicitud del panel con el documento completo que preparó el teléfono."""
    solicitud_id: Identificador
    documento: DocumentoRespaldo


class ErrorRespaldo(Modelo):
    """Permite terminar una solicitud si el móvil no pudo preparar su copia."""
    solicitud_id: Identificador
    mensaje: Annotated[str, StringConstraints(max_length=1000)]


class SincronizacionModelo(Modelo):
    """Recibe la última agenda local y los resultados de las órdenes anteriores."""
    secuencia: Annotated[int, Field(ge=0, le=9_223_372_036_854_775_807)] = 0
    tareas: list[TareaConfirmada] = Field(max_length=10000)
    perfil: PerfilModelo
    confirmaciones: list[ConfirmacionModelo] = Field(default_factory=list, max_length=500)
    respaldo: RespaldoEnviado | None = None
    error_respaldo: ErrorRespaldo | None = None

    @model_validator(mode="after")
    def comprobar_lista(self):
        """Rechaza identidades repetidas para que una tarea no pueda ocultar a otra."""
        if len({tarea.id for tarea in self.tareas}) != len(self.tareas):
            raise ValueError("La lista contiene tareas repetidas.")
        if self.respaldo and self.error_respaldo:
            raise ValueError("La copia no puede enviarse y fallar en la misma petición.")
        return self


class OrdenModelo(Modelo):
    """Recibe la acción elegida en el panel. Una tarea nueva puede llegar sin id."""
    solicitud_id: Identificador | None = None
    accion: Literal["guardar", "eliminar", "completar"]
    tarea_id: TareaId | None = None
    base_version: Version | None = None
    datos: dict | None = None
    fecha: str | None = None
    completada: bool | None = None

    def preparar(self) -> dict:
        """Completa la identidad de una tarea nueva y devuelve una orden ya revisada.

        El id de solicitud permite repetir una petición perdida sin duplicar la tarea.
        """
        solicitud = self.solicitud_id or str(uuid4())
        identificador = self.tarea_id
        datos = self.datos
        if self.accion == "guardar":
            if datos is None:
                raise ErrorOperacion("Faltan los datos de la tarea.")
            if identificador is None:
                if self.base_version is not None:
                    raise ErrorOperacion("Falta la identidad de la tarea que quieres editar.")
                identificador = str(uuid5(NAMESPACE_URL, f"AgendaVIP:tarea:{solicitud}"))
            datos = dict(datos)
            if datos.get("id", identificador) != identificador:
                raise ErrorOperacion("La identidad de la tarea no coincide.")
            datos["id"] = identificador
            datos = TareaModelo.model_validate(datos).model_dump()
        elif identificador is None or self.base_version is None:
            raise ErrorOperacion("Actualiza la lista y selecciona una tarea existente.")
        if self.accion == "completar":
            if self.fecha is None or self.completada is None:
                raise ErrorOperacion("Falta la fecha o el estado de la tarea.")
            if date.fromisoformat(self.fecha).isoformat() != self.fecha:
                raise ErrorOperacion("La fecha de la tarea no es válida.")
        return {
            "solicitud_id": solicitud,
            "accion": self.accion,
            "tarea_id": identificador,
            "base_version": self.base_version,
            "datos": datos if self.accion == "guardar" else None,
            "fecha": self.fecha if self.accion == "completar" else None,
            "completada": self.completada if self.accion == "completar" else None,
        }


class ImportacionXMLModelo(Modelo):
    """Recibe el XML que se revisó en el panel y la identidad estable de ese intento.

    El identificador del lote permite reintentar una respuesta perdida sin duplicar
    los recordatorios nuevos. El lector comprueba también el tamaño real en bytes.
    """
    xml: Annotated[str, StringConstraints(min_length=1, max_length=8 * 1024 * 1024)]
    id_lote: Identificador


def preparar_lote_xml(contenido: str, vinculo_id: str, id_lote: str, tareas: list[dict]) -> dict:
    """Recibe el archivo y la agenda confirmada y devuelve los cambios revisados.

    Almacen la llama dentro de la transacción. La normalización reutiliza el mismo
    modelo que los botones de Nueva tarea, Editar y Eliminar del panel.
    """
    def normalizar(orden: dict) -> dict:
        """Comprueba una orden del XML con las reglas que también usa el formulario."""
        try:
            return OrdenModelo.model_validate(orden).preparar()
        except ValueError as error:
            raise ValueError(
                "Revisa el título, la fecha, la hora, los días y los límites del recordatorio."
            ) from error

    return preparar_importacion(contenido, vinculo_id, id_lote, tareas, normalizar)


class EntradaModelo(Modelo):
    """Recibe la clave del panel y si el navegador debe conservar la sesión por más tiempo."""
    clave: Annotated[str, StringConstraints(min_length=1, max_length=200)]
    recordar: bool = False


class DescargaModelo(Modelo):
    """Recibe una contraseña temporal para la copia portátil que se va a descargar."""
    contrasena: Annotated[str, StringConstraints(min_length=12, max_length=128)]


class ProteccionesHTTP:
    """Limita el tamaño de las peticiones antes de que FastAPI interprete su contenido."""

    def __init__(self, app) -> None:
        """FastAPI entrega la aplicación a envolver con el límite de cuerpo de 64 MB."""
        self.app = app

    async def __call__(self, scope, receive, send):
        """Recibe los mensajes HTTP, limita sus bytes y entrega el cuerpo a la aplicación."""
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        mensajes = []
        total = 0
        ruta = scope.get("path", "")
        es_importacion_xml = ruta.startswith("/api/admin/vinculos/") and (
            ruta.endswith("/xml/revisar") or ruta.endswith("/xml/aplicar")
        )
        if ruta == "/api/sincronizar":
            maximo = 64 * 1024 * 1024
        elif es_importacion_xml:
            # El JSON puede necesitar escapar caracteres de un XML de hasta 8 MB.
            maximo = 20 * 1024 * 1024
        else:
            maximo = 64 * 1024
        while True:
            mensaje = await receive()
            if mensaje["type"] == "http.disconnect":
                return
            total += len(mensaje.get("body", b""))
            if total > maximo:
                respuesta = JSONResponse({"detalle": "La petición supera el tamaño permitido."}, status_code=413)
                await respuesta(scope, receive, send)
                return
            mensajes.append(mensaje)
            if not mensaje.get("more_body", False):
                break
        indice = 0

        async def recibir_guardado():
            """Devuelve cada bloque ya limitado y después espera la desconexión real."""
            nonlocal indice
            if indice < len(mensajes):
                mensaje = mensajes[indice]
                indice += 1
                return mensaje
            return await receive()

        await self.app(scope, recibir_guardado, send)


def crear_qr_vinculacion(codigo: str, direcciones: list[str], servidor_id: str) -> str | None:
    """Devuelve una imagen SVG del permiso temporal, preparada en esta computadora.

    SVG describe el dibujo con líneas. El panel la muestra como imagen sin ejecutar
    código. Si falta la biblioteca QR, la vinculación manual sigue disponible.
    """
    if not direcciones:
        return None
    try:
        import qrcode
        from qrcode.image.svg import SvgPathFillImage
    except ImportError:
        return None

    contenido = {
        "tipo": "AgendaVIP",
        "version": 1,
        "servidor_id": servidor_id,
        "codigo": codigo,
        "direcciones": direcciones[:8],
    }
    datos = json.dumps(contenido, ensure_ascii=False, separators=(",", ":"))
    generador = qrcode.QRCode(
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=8,
        border=4,
    )
    generador.add_data(datos)
    generador.make(fit=True)
    imagen = generador.make_image(image_factory=SvgPathFillImage)
    dibujo = imagen.to_string()
    return "data:image/svg+xml;base64," + base64.b64encode(dibujo).decode("ascii")


def crear_aplicacion(configuracion: Configuracion) -> FastAPI:
    """Recibe la configuración y devuelve la API con sus rutas y el panel local."""
    almacen = Almacen(configuracion)
    app = FastAPI(title="Agenda.VIP · Servidor local", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(ProteccionesHTTP)
    intentos: dict[str, deque] = defaultdict(deque)
    cerrojo_intentos = threading.Lock()

    def limitar(clave: str, maximo: int, segundos: int = 60) -> None:
        """Recibe una clave de operación e IP y frena repeticiones rápidas de acceso."""
        instante = reloj.monotonic()
        with cerrojo_intentos:
            if len(intentos) > 2048:
                for antigua in list(intentos):
                    if not intentos[antigua] or intentos[antigua][-1] < instante - 300:
                        del intentos[antigua]
                if len(intentos) > 2048:
                    raise ErrorOperacion("Hay demasiadas solicitudes. Espera un momento.", 429)
            cola = intentos[clave]
            while cola and cola[0] <= instante - segundos:
                cola.popleft()
            if len(cola) >= maximo:
                raise ErrorOperacion("Espera un minuto antes de intentarlo otra vez.", 429)
            cola.append(instante)

    @app.exception_handler(ErrorOperacion)
    async def error_operacion(peticion: Request, error: ErrorOperacion):
        """Convierte un rechazo esperado en un mensaje que el panel y el móvil pueden leer."""
        return JSONResponse({"detalle": error.detalle}, status_code=error.codigo)

    @app.exception_handler(RequestValidationError)
    async def error_campos(peticion: Request, error: RequestValidationError):
        """Explica un formato inválido sin devolver contraseñas ni el contenido enviado."""
        return JSONResponse({"detalle": "Revisa los datos enviados. Algún campo tiene un formato incompatible."}, status_code=422)

    @app.exception_handler(HTTPException)
    async def error_http(peticion: Request, error: HTTPException):
        """Devuelve el mismo formato de error si la ruta no existe o el método no corresponde."""
        return JSONResponse({"detalle": "No se encontró esa operación."}, status_code=error.status_code)

    @app.middleware("http")
    async def proteger_red(peticion: Request, continuar):
        """Acepta clientes de la red privada y protege las operaciones del navegador.

        La cookie identifica al administrador. CSRF comprueba que una acción viene del
        panel abierto, mediante un código de sesión y el origen de la página.
        """
        try:
            direccion = peticion.client.host if peticion.client else ""
            if not ipv4_privada(direccion):
                raise ErrorOperacion("El servidor solo acepta la red local privada.", 403)
            host = peticion.url.hostname or ""
            if host != "localhost" and not ipv4_privada(host):
                raise ErrorOperacion("Utiliza la dirección local de esta computadora.", 403)
            if peticion.method in {"POST", "PUT", "PATCH", "DELETE"}:
                if peticion.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
                    raise ErrorOperacion("La petición debe enviarse como JSON.", 415)
            if peticion.url.path == "/api/vincular":
                limitar(f"vincular:{direccion}", 20)
            if peticion.url.path == "/api/sincronizar":
                encabezado = peticion.headers.get("authorization", "")
                if not encabezado.startswith("Bearer ") or len(encabezado) > 200:
                    raise ErrorOperacion("Falta la autorización del teléfono.", 401)
                identificador = await run_in_threadpool(almacen.id_por_token, encabezado[7:])
                limitar(f"sincronizar:{identificador}", 120)
                peticion.state.vinculo_id = identificador
            if peticion.url.path.startswith("/api/admin/"):
                cambiar = peticion.method not in {"GET", "HEAD", "OPTIONS"}
                if cambiar:
                    origen = urlsplit(peticion.headers.get("origin", ""))
                    if origen.scheme != "https" or origen.netloc != peticion.headers.get("host", ""):
                        raise ErrorOperacion("Abre el panel desde su propia dirección HTTPS.", 403)
                if peticion.url.path != "/api/admin/entrar":
                    csrf = await run_in_threadpool(almacen.consultar_sesion, peticion.cookies.get("agenda_admin", ""))
                    if cambiar and not hmac.compare_digest(csrf, peticion.headers.get("x-agendavip-csrf", "")):
                        raise ErrorOperacion("La sesión cambió. Actualiza el panel.", 403)
            respuesta = await continuar(peticion)
        except ErrorOperacion as error:
            respuesta = JSONResponse({"detalle": error.detalle}, status_code=error.codigo)
        except Exception as error:
            logging.error("No terminó la operación del servidor (%s).", type(error).__name__)
            respuesta = JSONResponse({"detalle": "No se pudo guardar la operación. Conserva tus datos y vuelve a intentarlo."}, status_code=500)
        respuesta.headers["Cache-Control"] = "no-store"
        respuesta.headers["X-Content-Type-Options"] = "nosniff"
        respuesta.headers["Referrer-Policy"] = "no-referrer"
        respuesta.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        return respuesta

    @app.get("/api/salud")
    def salud():
        """Devuelve la identidad pública mínima para reconocer el servidor de Agenda.VIP."""
        return {"nombre_servidor": configuracion.nombre, "servidor_id": configuracion.servidor_id, "version": 1}

    @app.post("/api/vincular")
    def vincular(datos: VinculacionModelo, peticion: Request):
        """Comprueba el código que la persona copió del panel y devuelve su acceso al móvil."""
        return almacen.vincular(datos.model_dump())

    @app.post("/api/sincronizar")
    def sincronizar(datos: SincronizacionModelo, peticion: Request):
        """Recibe una agenda autorizada y devuelve sus cambios pendientes y la solicitud de copia."""
        identificador = peticion.state.vinculo_id
        return almacen.sincronizar(identificador, datos.model_dump(exclude_none=True))

    @app.post("/api/admin/entrar")
    def entrar(datos: EntradaModelo, peticion: Request, respuesta: Response):
        """Verifica la clave y crea una sesión privada de ocho horas o treinta días."""
        limitar(f"entrar:{peticion.client.host}", 10)
        if not hmac.compare_digest(datos.clave.encode("utf-8"), configuracion.clave_administrador.encode("utf-8")):
            raise ErrorOperacion("La clave del panel no coincide.", 401)
        duracion = 30 * 24 * 3600 if datos.recordar else 8 * 3600
        secreto, csrf = almacen.crear_sesion(duracion)
        respuesta.set_cookie(
            "agenda_admin",
            secreto,
            max_age=duracion,
            httponly=True,
            secure=True,
            samesite="strict",
            path="/api/admin",
        )
        return {**configuracion.metadatos(), "csrf": csrf}

    @app.get("/api/admin/sesion")
    def sesion(peticion: Request):
        """Recupera la sesión de un panel recargado sin pedir de nuevo la clave de la consola."""
        csrf = almacen.consultar_sesion(peticion.cookies.get("agenda_admin", ""))
        return {**configuracion.metadatos(), "csrf": csrf}

    @app.post("/api/admin/salir")
    def salir(peticion: Request, respuesta: Response):
        """Elimina la sesión de este navegador y su cookie al tocar Salir en el panel."""
        almacen.cerrar_sesion(peticion.cookies.get("agenda_admin", ""))
        respuesta.delete_cookie("agenda_admin", path="/api/admin", secure=True, httponly=True, samesite="strict")
        return {"mensaje": "Sesión del panel cerrada."}

    @app.get("/api/admin/vinculos")
    def vinculos():
        """Entrega las cuentas y los teléfonos que aceptaron vincularse al servidor."""
        return {"vinculos": almacen.listar_vinculos()}

    @app.get("/api/admin/vinculos/{identificador}")
    def detalle(identificador: Identificador):
        """Entrega la agenda confirmada y la actividad de la cuenta elegida en el panel."""
        return almacen.detalle_vinculo(identificador)

    @app.get("/api/admin/vinculos/{identificador}/recordatorios.xml")
    def descargar_xml(identificador: Identificador):
        """Descarga los recordatorios confirmados de la cuenta elegida como un XML editable."""
        detalle = almacen.detalle_vinculo(identificador)
        contenido = exportar_xml(identificador, detalle["tareas"])
        return Response(
            contenido,
            media_type="application/xml; charset=utf-8",
            headers={
                "Content-Disposition": 'attachment; filename="AgendaVIP_recordatorios.xml"',
            },
        )

    @app.post("/api/admin/vinculos/{identificador}/xml/revisar")
    def revisar_xml(identificador: Identificador, datos: ImportacionXMLModelo):
        """Valida el archivo completo y devuelve los cambios que el panel debe confirmar."""
        try:
            plan = almacen.revisar_lote_xml(
                identificador,
                datos.xml,
                datos.id_lote,
                preparar_lote_xml,
            )
        except ValueError as error:
            raise ErrorOperacion(str(error), 400) from error

        return {
            "id_lote": plan["id_lote"],
            "resumen": plan["resumen"],
            "cambios": plan["cambios"],
            "advertencias": plan.get("advertencias", []),
        }

    @app.post("/api/admin/vinculos/{identificador}/xml/aplicar")
    def aplicar_xml(identificador: Identificador, datos: ImportacionXMLModelo):
        """Vuelve a revisar el XML confirmado y encola sus cambios juntos en la PC.

        La vista previa puede haber quedado antigua mientras la persona editaba.
        Almacen comprueba otra vez las versiones antes de guardar la primera orden.
        """
        try:
            return almacen.aplicar_lote_xml(
                identificador,
                datos.xml,
                datos.id_lote,
                preparar_lote_xml,
            )
        except ValueError as error:
            raise ErrorOperacion(str(error), 400) from error

    @app.post("/api/admin/vinculacion")
    def codigo():
        """Crea un permiso temporal y un QR para vincular el celular.

        El QR reúne dirección, identidad del servidor y código. La app conserva su
        confirmación antes de enviar la cuenta. El código manual sigue disponible.
        """
        secreto, vence = almacen.crear_codigo()
        codigo_temporal = f"AVIP1.{configuracion.huella}.{secreto}"
        direcciones = configuracion.metadatos()["direcciones"]
        imagen = crear_qr_vinculacion(
            codigo_temporal,
            direcciones,
            configuracion.servidor_id,
        )
        advertencia = ""
        if not direcciones:
            advertencia = "Conecta la computadora a una red local y vuelve a generar el QR."
        elif imagen is None:
            advertencia = "Ejecuta Instalar-Servidor.ps1 y reinicia el servidor para preparar el QR."
        return {
            "codigo": codigo_temporal,
            "vence_en": datetime.fromtimestamp(vence, timezone.utc).isoformat(),
            "direcciones": direcciones,
            "qr_imagen": imagen,
            "qr_advertencia": advertencia,
        }

    @app.post("/api/admin/vinculos/{identificador}/ordenes")
    def ordenar(identificador: Identificador, datos: OrdenModelo):
        """Valida la acción del panel y la deja pendiente hasta que el teléfono la confirme."""
        try:
            orden = datos.preparar()
        except ValueError as error:
            raise ErrorOperacion("Revisa los datos y la fecha de la tarea.") from error
        return {"orden": almacen.guardar_orden(identificador, orden)}

    @app.post("/api/admin/vinculos/{identificador}/respaldos")
    def respaldar(identificador: Identificador):
        """Solicita una copia completa de la cuenta, que el móvil enviará cuando pueda conectarse."""
        return {"respaldo": almacen.solicitar_respaldo(identificador)}

    @app.post("/api/admin/respaldos/{identificador}/descargar")
    def descargar(identificador: Identificador, datos: DescargaModelo):
        """Entrega un .avip protegido con la contraseña elegida en el panel."""
        contenido = almacen.descargar_respaldo(identificador, datos.contrasena)
        return Response(
            contenido,
            media_type="application/octet-stream",
            headers={"Content-Disposition": f'attachment; filename="AgendaVIP_{identificador[:8]}.avip"'},
        )

    @app.delete("/api/admin/vinculos/{identificador}")
    def retirar(identificador: Identificador):
        """Retira la autorización del teléfono seleccionado y conserva sus copias guardadas."""
        almacen.retirar(identificador)
        return {"mensaje": "Vinculación retirada."}

    carpeta_panel = Path(__file__).resolve().parent.parent / "panel"
    app.mount("/panel", StaticFiles(directory=carpeta_panel), name="panel")

    @app.get("/")
    def inicio():
        """Abre la pantalla del panel que se instala junto al servidor."""
        return FileResponse(carpeta_panel / "index.html")

    return app
