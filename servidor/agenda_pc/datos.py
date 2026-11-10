"""Guarda las vinculaciones, las órdenes pendientes y las copias de cada cuenta."""

from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
import secrets
import sqlite3
import time
from uuid import uuid4

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from .configuracion import Configuracion, ahora


CABECERA_RESPALDO = b"AGENDA.VIP.RESPALDO.1\n"
MAXIMO_RESPALDO = 48 * 1024 * 1024


class ErrorOperacion(Exception):
    """Transporta un mensaje legible y su código HTTP hasta la pantalla del panel."""

    def __init__(self, detalle: str, codigo: int = 400) -> None:
        """Recibe el motivo del rechazo y el estado que debe devolver la API."""
        super().__init__(detalle)
        self.detalle = detalle
        self.codigo = codigo


def resumen(secreto: str) -> str:
    """Devuelve la marca SHA-256 de un secreto aleatorio para evitar guardar su valor."""
    return hashlib.sha256(secreto.encode("utf-8")).hexdigest()


def texto_json(valor) -> str:
    """Convierte un objeto en JSON compacto. SQLite conserva el contenido que confirmó el móvil."""
    return json.dumps(valor, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


class Almacen:
    """Agrupa las operaciones de base de datos que usan los puntos de entrada de la API.

    Cada operación abre su propia transacción. Una transacción confirma todos los cambios
    juntos, para que un corte no deje una orden aplicada sin su respuesta correspondiente.
    """

    def __init__(self, configuracion: Configuracion) -> None:
        """Recibe las rutas privadas y crea las tablas si es el primer arranque."""
        self.configuracion = configuracion
        with self.transaccion() as base:
            base.executescript("""
                CREATE TABLE IF NOT EXISTS codigos (
                    secreto TEXT PRIMARY KEY,
                    vence REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sesiones (
                    secreto TEXT PRIMARY KEY,
                    csrf TEXT NOT NULL,
                    vence REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS vinculos (
                    id TEXT PRIMARY KEY,
                    dispositivo_id TEXT NOT NULL,
                    dispositivo_nombre TEXT NOT NULL DEFAULT '',
                    cuenta_local_id TEXT NOT NULL,
                    usuario TEXT NOT NULL,
                    nombre TEXT NOT NULL,
                    secreto TEXT NOT NULL UNIQUE,
                    activo INTEGER NOT NULL DEFAULT 1,
                    ultima_sincronizacion TEXT,
                    secuencia INTEGER NOT NULL DEFAULT -1,
                    tareas TEXT NOT NULL DEFAULT '[]',
                    perfil TEXT NOT NULL DEFAULT '{}'
                );
                CREATE UNIQUE INDEX IF NOT EXISTS un_vinculo_activo
                    ON vinculos(dispositivo_id, cuenta_local_id) WHERE activo = 1;
                CREATE TABLE IF NOT EXISTS ordenes (
                    id TEXT PRIMARY KEY,
                    vinculo_id TEXT NOT NULL REFERENCES vinculos(id),
                    accion TEXT NOT NULL,
                    tarea_id TEXT NOT NULL,
                    base_version TEXT,
                    datos TEXT,
                    fecha TEXT,
                    completada INTEGER,
                    estado TEXT NOT NULL DEFAULT 'pendiente',
                    mensaje TEXT NOT NULL DEFAULT '',
                    creada_en TEXT NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS una_orden_por_tarea
                    ON ordenes(vinculo_id, tarea_id) WHERE estado = 'pendiente';
                CREATE TABLE IF NOT EXISTS respaldos (
                    id TEXT PRIMARY KEY,
                    vinculo_id TEXT NOT NULL REFERENCES vinculos(id),
                    estado TEXT NOT NULL DEFAULT 'pendiente',
                    creada_en TEXT NOT NULL,
                    tamano INTEGER,
                    contenido BLOB,
                    mensaje TEXT NOT NULL DEFAULT ''
                );
                CREATE UNIQUE INDEX IF NOT EXISTS un_respaldo_pendiente
                    ON respaldos(vinculo_id) WHERE estado = 'pendiente';
            """)
            columnas = {fila[1] for fila in base.execute("PRAGMA table_info(vinculos)")}
            if "dispositivo_nombre" not in columnas:
                # Conserva los datos si se inicia una revisión de la primera versión.
                base.execute("ALTER TABLE vinculos ADD COLUMN dispositivo_nombre TEXT NOT NULL DEFAULT ''")

    @contextmanager
    def transaccion(self):
        """Entrega una conexión y confirma o revierte la operación antes de cerrarla.

        Los métodos de este archivo la usan para serializar las escrituras concurrentes.
        """
        base = sqlite3.connect(self.configuracion.base, timeout=30)
        base.row_factory = sqlite3.Row
        base.execute("PRAGMA foreign_keys = ON")
        try:
            base.execute("BEGIN IMMEDIATE")
            yield base
            base.commit()
        except BaseException:
            base.rollback()
            raise
        finally:
            base.close()

    def crear_sesion(self, duracion_segundos: int = 8 * 3600) -> tuple[str, str]:
        """Recibe la duración elegida por el servidor y devuelve la cookie y su protección CSRF.

        El inicio de sesión la llama tras comprobar la clave. La fecha guardada vence
        al mismo tiempo que la cookie que recibe el navegador.
        """
        secreto = secrets.token_urlsafe(32)
        csrf = secrets.token_urlsafe(32)
        ahora_segundos = time.time()
        with self.transaccion() as base:
            base.execute("DELETE FROM sesiones WHERE vence < ?", (ahora_segundos,))
            base.execute(
                "INSERT INTO sesiones VALUES (?, ?, ?)",
                (resumen(secreto), csrf, ahora_segundos + duracion_segundos),
            )
        return secreto, csrf

    def consultar_sesion(self, secreto: str) -> str:
        """Comprueba la cookie y devuelve su CSRF. Rechaza sesiones ausentes o vencidas."""
        with self.transaccion() as base:
            fila = base.execute("SELECT csrf FROM sesiones WHERE secreto = ? AND vence > ?", (resumen(secreto), time.time())).fetchone()
        if fila is None:
            raise ErrorOperacion("Inicia sesión en el panel.", 401)
        return fila["csrf"]

    def cerrar_sesion(self, secreto: str) -> None:
        """El botón Salir entrega su cookie y elimina únicamente esa sesión del panel."""
        with self.transaccion() as base:
            base.execute("DELETE FROM sesiones WHERE secreto = ?", (resumen(secreto),))

    def crear_codigo(self) -> tuple[str, float]:
        """Crea un código de un solo uso y devuelve el secreto y su vencimiento."""
        secreto = secrets.token_urlsafe(32)
        vence = time.time() + 5 * 60
        with self.transaccion() as base:
            base.execute("DELETE FROM codigos WHERE vence < ?", (time.time(),))
            base.execute("INSERT INTO codigos VALUES (?, ?)", (resumen(secreto), vence))
        return secreto, vence

    def vincular(self, datos: dict) -> dict:
        """Consume el código y vincula una cuenta y un teléfono en la misma transacción.

        Una vinculación nueva del mismo teléfono y cuenta retira el acceso anterior.
        El secreto del móvil se devuelve una vez y su marca se conserva en la base.
        """
        token = secrets.token_urlsafe(32)
        identificador = str(uuid4())
        with self.transaccion() as base:
            codigo = base.execute("SELECT vence FROM codigos WHERE secreto = ?", (resumen(datos["codigo"]),)).fetchone()
            if codigo is None or codigo["vence"] <= time.time():
                raise ErrorOperacion("El código venció o ya fue utilizado. Genera otro desde el panel.", 401)
            base.execute("DELETE FROM codigos WHERE secreto = ?", (resumen(datos["codigo"]),))
            anteriores = base.execute(
                "SELECT id FROM vinculos WHERE dispositivo_id = ? AND cuenta_local_id = ? AND activo = 1",
                (datos["dispositivo_id"], datos["cuenta_local_id"]),
            ).fetchall()
            for anterior in anteriores:
                self.retirar_en_base(base, anterior["id"])
            base.execute(
                "INSERT INTO vinculos(id, dispositivo_id, dispositivo_nombre, cuenta_local_id, usuario, nombre, secreto) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (identificador, datos["dispositivo_id"], datos.get("dispositivo_nombre", ""), datos["cuenta_local_id"], datos["usuario"], datos["nombre"], resumen(token)),
            )
        return {
            "vinculo_id": identificador,
            "token": token,
            "servidor_id": self.configuracion.servidor_id,
            "nombre_servidor": self.configuracion.nombre,
        }

    def id_por_token(self, token: str) -> str:
        """Identifica la cuenta autorizada por el móvil. Nunca acepta el id de otra cuenta."""
        with self.transaccion() as base:
            fila = base.execute("SELECT id FROM vinculos WHERE secreto = ? AND activo = 1", (resumen(token),)).fetchone()
        if fila is None:
            raise ErrorOperacion("La vinculación fue retirada. Conecta el teléfono de nuevo desde Configuración.", 401)
        return fila["id"]

    @staticmethod
    def ficha_vinculo(base, fila) -> dict:
        """Convierte una fila en los datos públicos que usa la lista de usuarios del panel."""
        pendientes = base.execute("SELECT count(*) FROM ordenes WHERE vinculo_id = ? AND estado = 'pendiente'", (fila["id"],)).fetchone()[0]
        return {
            "id": fila["id"],
            "usuario": fila["usuario"],
            "nombre": fila["nombre"],
            "dispositivo_nombre": fila["dispositivo_nombre"] or f"Teléfono {fila['dispositivo_id'][:8]}",
            "ultima_sincronizacion": fila["ultima_sincronizacion"],
            "activo": bool(fila["activo"]),
            "pendientes": pendientes,
        }

    def listar_vinculos(self) -> list[dict]:
        """Devuelve las cuentas que se vincularon con esta PC, con su último contacto."""
        with self.transaccion() as base:
            filas = base.execute("SELECT * FROM vinculos ORDER BY activo DESC, usuario, id").fetchall()
            return [self.ficha_vinculo(base, fila) for fila in filas]

    @staticmethod
    def ficha_orden(fila) -> dict:
        """Entrega al panel o al celular los campos públicos de una orden pendiente o terminada."""
        return {
            "id": fila["id"],
            "accion": fila["accion"],
            "tarea_id": fila["tarea_id"],
            "base_version": fila["base_version"],
            "datos": json.loads(fila["datos"]) if fila["datos"] else None,
            "fecha": fila["fecha"],
            "completada": bool(fila["completada"]) if fila["completada"] is not None else None,
            "estado": fila["estado"],
            "mensaje": fila["mensaje"],
            "creada_en": fila["creada_en"],
        }

    @staticmethod
    def ficha_respaldo(fila) -> dict:
        """Entrega el estado de la copia, sin incluir en la lista sus datos personales."""
        return {clave: fila[clave] for clave in ("id", "estado", "creada_en", "tamano", "mensaje")}

    def detalle_vinculo(self, identificador: str) -> dict:
        """Devuelve la última agenda confirmada, las órdenes y las copias de una vinculación."""
        with self.transaccion() as base:
            fila = base.execute("SELECT * FROM vinculos WHERE id = ?", (identificador,)).fetchone()
            if fila is None:
                raise ErrorOperacion("No se encontró esa vinculación.", 404)
            ordenes = base.execute("SELECT * FROM ordenes WHERE vinculo_id = ? ORDER BY creada_en DESC LIMIT 200", (identificador,)).fetchall()
            respaldos = base.execute("SELECT id, estado, creada_en, tamano, mensaje FROM respaldos WHERE vinculo_id = ? ORDER BY creada_en DESC LIMIT 100", (identificador,)).fetchall()
            return {
                "vinculo": self.ficha_vinculo(base, fila),
                "tareas": json.loads(fila["tareas"]),
                "perfil": json.loads(fila["perfil"]),
                "ordenes": [self.ficha_orden(orden) for orden in ordenes],
                "respaldos": [self.ficha_respaldo(copia) for copia in respaldos],
            }

    def guardar_orden(self, identificador: str, orden: dict) -> dict:
        """Recibe una orden validada del panel y la guarda en su propia transacción.

        La operación compartida también permite importar un lote XML con las mismas
        comprobaciones de cuenta, versión y repetición de una solicitud.
        """
        with self.transaccion() as base:
            return self._guardar_orden_en_base(base, identificador, orden)

    def _guardar_orden_en_base(
        self,
        base,
        identificador: str,
        orden: dict,
        tareas: dict | None = None,
    ) -> dict:
        """Recibe la transacción abierta y añade una orden con las reglas del panel.

        guardar_orden la usa para una tarea. aplicar_lote_xml entrega además la agenda
        ya leída para validar muchas tareas sin volver a interpretar la misma lista.
        Una solicitud repetida conserva su resultado y un contenido diferente se rechaza.
        """
        nueva = orden["solicitud_id"]
        repetida = base.execute("SELECT * FROM ordenes WHERE id = ?", (nueva,)).fetchone()
        if repetida is not None:
            anterior = self.ficha_orden(repetida)
            campos = ("accion", "tarea_id", "base_version", "datos", "fecha", "completada")
            if repetida["vinculo_id"] != identificador or any(anterior[campo] != orden[campo] for campo in campos):
                raise ErrorOperacion("Esta solicitud ya se utilizó para otro cambio. Vuelve a abrir el formulario.", 409)
            return anterior
        if tareas is None:
            fila = base.execute("SELECT * FROM vinculos WHERE id = ? AND activo = 1", (identificador,)).fetchone()
            if fila is None:
                raise ErrorOperacion("La vinculación ya no está activa.", 409)
            tareas = {tarea["id"]: tarea for tarea in json.loads(fila["tareas"])}
        existente = tareas.get(orden["tarea_id"])
        if orden["base_version"] is None:
            if orden["accion"] != "guardar" or existente is not None:
                raise ErrorOperacion("Actualiza la lista antes de cambiar esta tarea.", 409)
        elif existente is None or existente["version"] != orden["base_version"]:
            raise ErrorOperacion("La tarea cambió. Actualiza la lista y revisa su versión nueva.", 409)
        if orden["accion"] == "completar" and existente["estado_hoy"]["fecha"] != orden["fecha"]:
            raise ErrorOperacion("Solo puedes cambiar la fecha que confirmó el teléfono.", 409)
        datos = orden.get("datos")
        if datos and datos["modoMusica"] == "SELECCIONADA":
            anterior = existente["datos"] if existente else {}
            if not anterior.get("musicaUri") or datos["musicaUri"] != anterior["musicaUri"]:
                raise ErrorOperacion("Selecciona esa canción desde el teléfono. Desde la PC puedes conservarla, usar aleatoria o quitar la música.")
        ocupada = base.execute("SELECT 1 FROM ordenes WHERE vinculo_id = ? AND tarea_id = ? AND estado = 'pendiente'", (identificador, orden["tarea_id"])).fetchone()
        if ocupada:
            raise ErrorOperacion("Esta tarea ya tiene un cambio pendiente. Espera la confirmación del teléfono.", 409)
        base.execute(
            "INSERT INTO ordenes(id,vinculo_id,accion,tarea_id,base_version,datos,fecha,completada,creada_en) VALUES (?,?,?,?,?,?,?,?,?)",
            (nueva, identificador, orden["accion"], orden["tarea_id"], orden["base_version"], texto_json(datos) if datos else None, orden.get("fecha"), orden.get("completada"), ahora()),
        )
        return self.ficha_orden(base.execute("SELECT * FROM ordenes WHERE id = ?", (nueva,)).fetchone())

    def revisar_lote_xml(
        self,
        identificador: str,
        contenido: str,
        id_lote: str,
        preparar,
    ) -> dict:
        """Recibe el XML y la función que lo interpreta y devuelve su vista previa.

        La API la llama antes de mostrar los cambios. Mantiene la lectura dentro de una
        transacción para que todas las filas se comparen con la misma agenda confirmada.
        """
        with self.transaccion() as base:
            fila = base.execute(
                "SELECT tareas FROM vinculos WHERE id = ? AND activo = 1",
                (identificador,),
            ).fetchone()
            if fila is None:
                raise ErrorOperacion("La vinculación ya no está activa.", 409)

            tareas = json.loads(fila["tareas"])
            return preparar(contenido, identificador, id_lote, tareas)

    def aplicar_lote_xml(
        self,
        identificador: str,
        contenido: str,
        id_lote: str,
        preparar,
    ) -> dict:
        """Recibe el XML confirmado y deja sus órdenes juntas en una sola transacción.

        Primero interpreta y valida el archivo completo. Después reutiliza las reglas
        de guardar_orden. Si una fila se rechaza, SQLite revierte las órdenes de este lote.
        El teléfono confirma cada cambio cuando consigue conectarse.
        """
        with self.transaccion() as base:
            fila = base.execute(
                "SELECT tareas FROM vinculos WHERE id = ? AND activo = 1",
                (identificador,),
            ).fetchone()
            if fila is None:
                raise ErrorOperacion("La vinculación ya no está activa.", 409)

            confirmadas = json.loads(fila["tareas"])
            plan = preparar(contenido, identificador, id_lote, confirmadas)
            tareas = {tarea["id"]: tarea for tarea in confirmadas}
            ordenes = [
                self._guardar_orden_en_base(base, identificador, orden, tareas)
                for orden in plan["ordenes"]
            ]
            return {
                "id_lote": plan["id_lote"],
                "resumen": plan["resumen"],
                "ordenes": ordenes,
                "mensaje": (
                    "Cambios enviados. El celular confirmará cada recordatorio al sincronizar."
                    if ordenes else
                    "El XML coincide con la agenda confirmada. No hay cambios para enviar."
                ),
            }

    def solicitar_respaldo(self, identificador: str) -> dict:
        """Solicita al celular una copia completa. Repetir el botón conserva la solicitud pendiente."""
        with self.transaccion() as base:
            if not base.execute("SELECT 1 FROM vinculos WHERE id = ? AND activo = 1", (identificador,)).fetchone():
                raise ErrorOperacion("La vinculación ya no está activa.", 409)
            fila = base.execute("SELECT * FROM respaldos WHERE vinculo_id = ? AND estado = 'pendiente'", (identificador,)).fetchone()
            if fila is None:
                nuevo = str(uuid4())
                base.execute("INSERT INTO respaldos(id,vinculo_id,creada_en) VALUES (?,?,?)", (nuevo, identificador, ahora()))
                fila = base.execute("SELECT * FROM respaldos WHERE id = ?", (nuevo,)).fetchone()
            return self.ficha_respaldo(fila)

    def sincronizar(self, identificador: str, datos: dict) -> dict:
        """Recibe la agenda real del móvil y devuelve las órdenes todavía sin confirmar.

        Las confirmaciones repetidas conservan el primer resultado final. Una secuencia
        antigua no puede sustituir una agenda enviada después de un reintento de conexión.
        """
        copia = datos.get("respaldo")
        cifrado = None
        tamano = None
        if copia:
            plano = texto_json(copia["documento"]).encode("utf-8")
            if len(plano) > MAXIMO_RESPALDO:
                raise ErrorOperacion("El respaldo supera el máximo de 48 MB.", 413)
            nonce = secrets.token_bytes(12)
            cifrado = nonce + AESGCM(self.configuracion.clave_respaldos).encrypt(nonce, plano, copia["solicitud_id"].encode("ascii"))
            tamano = len(plano)
        with self.transaccion() as base:
            fila = base.execute("SELECT * FROM vinculos WHERE id = ? AND activo = 1", (identificador,)).fetchone()
            if fila is None:
                raise ErrorOperacion("La vinculación fue retirada.", 401)
            for confirmacion in datos["confirmaciones"]:
                base.execute(
                    "UPDATE ordenes SET estado = ?, mensaje = ? WHERE id = ? AND vinculo_id = ? AND estado = 'pendiente'",
                    (confirmacion["estado"], confirmacion["mensaje"], confirmacion["id"], identificador),
                )
            if datos["secuencia"] > fila["secuencia"]:
                base.execute(
                    "UPDATE vinculos SET tareas = ?, perfil = ?, secuencia = ?, ultima_sincronizacion = ?, nombre = ? WHERE id = ?",
                    (texto_json(datos["tareas"]), texto_json(datos["perfil"]), datos["secuencia"], ahora(), " ".join(filter(None, [datos["perfil"].get("nombres", ""), datos["perfil"].get("apellidos", "")])) or fila["nombre"], identificador),
                )
            if copia:
                base.execute(
                    "UPDATE respaldos SET estado = 'listo', contenido = ?, tamano = ?, mensaje = '' WHERE id = ? AND vinculo_id = ? AND estado = 'pendiente'",
                    (cifrado, tamano, copia["solicitud_id"], identificador),
                )
            error = datos.get("error_respaldo")
            if error:
                base.execute(
                    "UPDATE respaldos SET estado = 'error', mensaje = ? WHERE id = ? AND vinculo_id = ? AND estado = 'pendiente'",
                    (error["mensaje"], error["solicitud_id"], identificador),
                )
            ordenes = base.execute("SELECT * FROM ordenes WHERE vinculo_id = ? AND estado = 'pendiente' ORDER BY creada_en, id LIMIT 30", (identificador,)).fetchall()
            pendiente = base.execute("SELECT id FROM respaldos WHERE vinculo_id = ? AND estado = 'pendiente' ORDER BY creada_en LIMIT 1", (identificador,)).fetchone()
            return {
                "ordenes": [self.ficha_orden(orden) for orden in ordenes],
                "respaldo_pendiente": pendiente["id"] if pendiente else None,
            }

    def descargar_respaldo(self, identificador: str, contrasena: str) -> bytes:
        """Abre la copia privada de la PC y devuelve un archivo .avip compatible con Android.

        La contraseña elegida para descargar prepara otra clave. El servidor no la guarda.
        CopiaSeguridadLocal del teléfono podrá importar este archivo desde Restaurar respaldo.
        """
        with self.transaccion() as base:
            fila = base.execute("SELECT contenido FROM respaldos WHERE id = ? AND estado = 'listo'", (identificador,)).fetchone()
        if fila is None:
            raise ErrorOperacion("La copia todavía no está lista.", 409)
        contenido = bytes(fila["contenido"])
        plano = AESGCM(self.configuracion.clave_respaldos).decrypt(contenido[:12], contenido[12:], identificador.encode("ascii"))
        sal = secrets.token_bytes(16)
        nonce = secrets.token_bytes(12)
        clave = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=sal, iterations=210_000).derive(contrasena.encode("utf-8"))
        return CABECERA_RESPALDO + sal + nonce + AESGCM(clave).encrypt(nonce, plano, CABECERA_RESPALDO)

    @staticmethod
    def retirar_en_base(base, identificador: str) -> None:
        """Retira el acceso del teléfono y cierra las operaciones pendientes de esa vinculación."""
        base.execute("UPDATE vinculos SET activo = 0 WHERE id = ?", (identificador,))
        base.execute("UPDATE ordenes SET estado = 'error', mensaje = 'La vinculación fue retirada.' WHERE vinculo_id = ? AND estado = 'pendiente'", (identificador,))
        base.execute("UPDATE respaldos SET estado = 'error', mensaje = 'La vinculación fue retirada.' WHERE vinculo_id = ? AND estado = 'pendiente'", (identificador,))

    def retirar(self, identificador: str) -> None:
        """El panel entrega la vinculación a retirar. Conserva sus copias y la última agenda recibida."""
        with self.transaccion() as base:
            if not base.execute("SELECT 1 FROM vinculos WHERE id = ?", (identificador,)).fetchone():
                raise ErrorOperacion("No se encontró esa vinculación.", 404)
            self.retirar_en_base(base, identificador)
