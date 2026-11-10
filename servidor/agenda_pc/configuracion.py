"""Prepara los archivos privados, la identidad HTTPS y el anuncio en la red local."""

from __future__ import annotations

import base64
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import threading
import time
from datetime import datetime, timedelta, timezone
from uuid import uuid4

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


PUERTO = 8443
TIPO_SERVICIO = "_agendavip._tcp.local."


def ahora() -> str:
    """Devuelve la hora UTC en texto. Base de datos y panel la usan para ordenar cambios."""
    return datetime.now(timezone.utc).isoformat()


def ipv4_privada(valor: str) -> bool:
    """Recibe una dirección y dice si pertenece a una red privada IPv4 o a este equipo."""
    try:
        direccion = ipaddress.ip_address(valor)
    except ValueError:
        return False
    redes = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
    return direccion.version == 4 and any(direccion in ipaddress.ip_network(red) for red in redes)


def direcciones_locales() -> list[str]:
    """Devuelve las IPv4 privadas que todavía pertenecen a un adaptador de esta PC.

    Inicio y el panel las usan para presentar la dirección actual. La ruta multicast
    permite encontrar el adaptador activo sin enviar paquetes a un servicio de Internet.
    Una dirección antigua del nombre de Windows se descarta si ya no se puede usar.
    """
    encontradas: set[str] = set()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as salida:
            salida.connect(("224.0.0.251", 5353))
            encontradas.add(salida.getsockname()[0])
    except OSError:
        pass

    try:
        resultados = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
        encontradas.update(resultado[4][0] for resultado in resultados)
    except OSError:
        pass

    actuales = []
    for valor in sorted(encontradas):
        if not ipv4_privada(valor) or valor.startswith("127."):
            continue
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as comprobacion:
                comprobacion.bind((valor, 0))
            actuales.append(valor)
        except OSError:
            # DHCP puede haber retirado esta dirección mientras la red cambiaba.
            continue
    return actuales


class Configuracion:
    """Conserva la identidad del servidor y las rutas de sus archivos privados.

    Inicio crea una instancia. Las rutas apuntan al perfil de Windows y quedan fuera
    del código que se subirá a GitHub. Una configuración dañada produce un error.
    """

    def __init__(self) -> None:
        """Carga las claves existentes o las crea únicamente durante el primer arranque."""
        base = os.environ.get("LOCALAPPDATA")
        if not base:
            raise RuntimeError("Este servidor necesita la carpeta LOCALAPPDATA de Windows.")
        self.carpeta = Path(base) / "AgendaVIPServidor"
        self.carpeta.mkdir(mode=0o700, parents=True, exist_ok=True)
        ruta = self.carpeta / "configuracion.json"
        if ruta.exists():
            datos = json.loads(ruta.read_text(encoding="utf-8"))
        else:
            datos = {
                "servidor_id": str(uuid4()),
                "nombre_servidor": f"Agenda.VIP · {socket.gethostname()}",
                "clave_administrador": secrets.token_urlsafe(32),
                "clave_respaldos": base64.b64encode(secrets.token_bytes(32)).decode("ascii"),
            }
            # El modo x evita sustituir las claves de un arranque simultáneo.
            with ruta.open("x", encoding="utf-8") as archivo:
                json.dump(datos, archivo, indent=2, ensure_ascii=False)
        self.servidor_id = str(datos["servidor_id"])
        self.nombre = str(datos["nombre_servidor"])
        self.clave_administrador = str(datos["clave_administrador"])
        self.clave_respaldos = base64.b64decode(datos["clave_respaldos"], validate=True)
        if len(self.clave_respaldos) != 32 or len(self.clave_administrador) < 32:
            raise RuntimeError("Las claves del servidor no son válidas. Conserva la carpeta de datos.")
        self.certificado = self.carpeta / "servidor.crt"
        self.clave_https = self.carpeta / "servidor.key"
        self.base = self.carpeta / "agenda.sqlite3"
        self.preparar_certificado()
        certificado = x509.load_pem_x509_certificate(self.certificado.read_bytes())
        self.huella = certificado.fingerprint(hashes.SHA256()).hex()

    def preparar_certificado(self) -> None:
        """Crea la identidad HTTPS una vez. El celular verifica su huella al vincularse.

        Si falta solo uno de los archivos, se detiene en lugar de cambiar la identidad
        sin avisar. El archivo .key nunca debe compartirse con el teléfono ni el panel.
        """
        if self.certificado.exists() and self.clave_https.exists():
            return
        if self.certificado.exists() or self.clave_https.exists():
            raise RuntimeError("Falta una parte de la identidad HTTPS. Conserva los archivos existentes.")
        clave = ec.generate_private_key(ec.SECP256R1())
        nombre = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "AgendaVIP local")])
        hoy = datetime.now(timezone.utc)
        alternativas = [x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
        alternativas.extend(x509.IPAddress(ipaddress.ip_address(ip)) for ip in direcciones_locales())
        certificado = (
            x509.CertificateBuilder()
            .subject_name(nombre)
            .issuer_name(nombre)
            .public_key(clave.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(hoy - timedelta(days=1))
            .not_valid_after(hoy + timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName(alternativas), critical=False)
            .sign(clave, hashes.SHA256())
        )
        self.clave_https.write_bytes(clave.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
        self.certificado.write_bytes(certificado.public_bytes(serialization.Encoding.PEM))

    def metadatos(self) -> dict:
        """Devuelve la información pública que necesita el panel para vincular un celular."""
        return {
            "nombre_servidor": self.nombre,
            "servidor_id": self.servidor_id,
            "huella": self.huella,
            "direcciones": [f"https://{ip}:{PUERTO}" for ip in direcciones_locales()],
        }


def anunciar(configuracion: Configuracion, direcciones: list[str] | None = None):
    """Publica esta PC y devuelve el publicador y su servicio.

    Recibe las direcciones comprobadas por el vigilante, o las consulta si se llama sola.
    El anuncio conserva la identidad del servidor aunque el router le asigne otra dirección.
    """
    from zeroconf import IPVersion, ServiceInfo, Zeroconf

    actuales = direcciones_locales() if direcciones is None else direcciones
    if not actuales:
        return None
    servicio = ServiceInfo(
        TIPO_SERVICIO,
        f"AgendaVIP-{configuracion.servidor_id[:8]}.{TIPO_SERVICIO}",
        addresses=[socket.inet_aton(direccion) for direccion in actuales],
        port=PUERTO,
        properties={
            "version": "1",
            "nombre": configuracion.nombre,
            "servidor_id": configuracion.servidor_id,
            "huella": configuracion.huella,
        },
        server=f"agendavip-{configuracion.servidor_id[:8]}.local.",
    )
    publicador = Zeroconf(interfaces=actuales, ip_version=IPVersion.V4Only)
    try:
        publicador.register_service(servicio, allow_name_change=True)
    except Exception:
        publicador.close()
        raise
    return publicador, servicio


class AnuncioRedLocal:
    """Mantiene visible el servidor al cambiar de Wi-Fi o despertar la computadora.

    El trabajador revisa la dirección sin tocar el certificado, las cuentas o las tareas.
    La apertura HTTPS funciona por separado aunque Windows retrase el descubrimiento.
    """

    INTERVALO_REVISION = 10
    INTERVALO_RENOVACION = 60

    def __init__(self, configuracion: Configuracion) -> None:
        """Recibe la identidad existente y prepara la señal con la que Inicio pedirá cerrar."""
        self.configuracion = configuracion
        self.terminado = threading.Event()
        self.trabajador: threading.Thread | None = None

    def iniciar(self) -> None:
        """Inicio la llama una vez para comenzar la vigilancia sin esperar a mDNS."""
        if self.trabajador is not None and self.trabajador.is_alive():
            return
        self.terminado.clear()
        self.trabajador = threading.Thread(
            target=self._vigilar,
            name="AgendaVIP-descubrimiento",
            daemon=True,
        )
        self.trabajador.start()

    def cerrar(self) -> None:
        """Pide retirar el anuncio y espera como máximo un segundo al cerrar el servidor.

        El trabajador conserva la responsabilidad de cerrar sus conexiones. Si una llamada
        de Windows tarda, la salida de HTTPS continúa y el hilo auxiliar no retiene el proceso.
        """
        self.terminado.set()
        if self.trabajador is not None:
            self.trabajador.join(timeout=1)

    @staticmethod
    def _retirar(anuncio) -> None:
        """Recibe un anuncio anterior y libera sus conexiones, incluso si la red desapareció."""
        if anuncio is None:
            return
        publicador, servicio = anuncio
        try:
            publicador.unregister_service(servicio)
        except Exception:
            # Al perder Wi-Fi puede no ser posible enviar el mensaje de despedida.
            pass
        finally:
            publicador.close()

    def _vigilar(self) -> None:
        """Renueva el anuncio tras un cambio de dirección, una suspensión o un minuto de uso.

        Reabrir solo mDNS recupera también adaptadores que vuelven con la misma dirección.
        La identidad HTTPS se conserva y las conexiones de sincronización siguen su propio curso.
        """
        anuncio = None
        direcciones_publicadas: tuple[str, ...] = ()
        ultima_publicacion = 0.0
        ultimo_ciclo = time.monotonic()
        ultimo_aviso = -300.0

        try:
            while not self.terminado.is_set():
                try:
                    direcciones = tuple(direcciones_locales())
                    instante = time.monotonic()
                    reanudada = instante - ultimo_ciclo > self.INTERVALO_REVISION * 3
                    renovar = (
                        direcciones != direcciones_publicadas
                        or reanudada
                        or instante - ultima_publicacion >= self.INTERVALO_RENOVACION
                    )

                    if anuncio is not None and renovar:
                        anterior = anuncio
                        anuncio = None
                        self._retirar(anterior)

                    if anuncio is None and direcciones and not self.terminado.is_set():
                        anuncio = anunciar(self.configuracion, list(direcciones))
                        direcciones_publicadas = direcciones
                        ultima_publicacion = time.monotonic()

                except Exception as error:
                    anterior = anuncio
                    anuncio = None
                    try:
                        self._retirar(anterior)
                    except Exception:
                        pass
                    instante = time.monotonic()
                    if instante - ultimo_aviso >= 300:
                        print(
                            f"Descubrimiento local pendiente ({type(error).__name__}). "
                            "El panel sigue disponible por su dirección HTTPS.",
                            flush=True,
                        )
                        ultimo_aviso = instante

                ultimo_ciclo = time.monotonic()
                self.terminado.wait(self.INTERVALO_REVISION)
        finally:
            try:
                self._retirar(anuncio)
            except Exception:
                # Cerrar la red auxiliar no debe impedir finalizar el servidor.
                pass
