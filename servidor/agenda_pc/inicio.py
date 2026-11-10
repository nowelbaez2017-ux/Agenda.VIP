"""Arranque que Nowel ejecuta desde Iniciar-Servidor.ps1 en su computadora."""

from __future__ import annotations

import logging
import sys

import uvicorn

from .aplicacion import crear_aplicacion
from .configuracion import AnuncioRedLocal, Configuracion, PUERTO


def iniciar() -> None:
    """Carga las claves, anuncia el servidor y lo mantiene abierto hasta pulsar Ctrl+C.

    Muestra la clave del administrador solo en esta consola local. El registro HTTP
    se desactiva para evitar guardar direcciones, códigos o datos personales en archivos.
    """
    configuracion = Configuracion()
    aplicacion = crear_aplicacion(configuracion)
    print("\nAgenda.VIP · Servidor de Nowel Baez\n")
    print(f"Panel en esta PC       https://localhost:{PUERTO}")
    print(f"Clave del panel        {configuracion.clave_administrador}")
    print(f"Huella HTTPS           {configuracion.huella}")
    for direccion in configuracion.metadatos()["direcciones"]:
        print(f"Dirección para celular {direccion}")
    print(f"\nDatos y copias         {configuracion.carpeta}")
    print("La primera vez, el navegador pide aceptar el certificado local de esta PC.")
    print("Conserva privada esta clave. El código para vincular celulares se crea dentro del panel.")
    print("Deja esta consola abierta. Ctrl+C detiene el servidor.\n")
    anuncio = AnuncioRedLocal(configuracion)
    anuncio.iniciar()
    try:
        uvicorn.run(
            aplicacion,
            host="0.0.0.0",
            port=PUERTO,
            ssl_keyfile=str(configuracion.clave_https),
            ssl_certfile=str(configuracion.certificado),
            access_log=False,
            log_level="warning",
            server_header=False,
            proxy_headers=False,
            limit_concurrency=8,
            timeout_keep_alive=5,
        )
    finally:
        anuncio.cerrar()


if __name__ == "__main__":
    try:
        iniciar()
    except KeyboardInterrupt:
        pass
    except Exception as error:
        # El tipo permite localizar el fallo sin imprimir claves ni datos de una cuenta.
        logging.error("No se pudo iniciar Agenda.VIP (%s). Revisa los archivos privados y el puerto 8443.", type(error).__name__)
        sys.exit(1)
