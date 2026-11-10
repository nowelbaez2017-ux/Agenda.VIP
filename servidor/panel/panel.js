"use strict";

/*
 * Este archivo conecta los botones con el servidor de la PC.
 * La sesión se conserva en una cookie protegida y la clave escrita se borra al entrar.
 * Una petición es un mensaje que el navegador envía al servidor para consultar o guardar.
 */
const estado = {
    sesion: null,
    vinculos: [],
    seleccionado: null,
    detalle: null,
    actualizando: false,
    turnoDetalle: 0,
    edicion: null,
    confirmacion: null,
    respaldoElegido: null,
    solicitudTarea: null,
    firmaSolicitudTarea: null,
    enviandoTarea: false,
    turnoVinculacion: 0,
    venceVinculacion: null,
    xml: null
};

/** Recibe un identificador del HTML y devuelve el elemento para conectar o actualizarlo. */
function elemento(id) {
    return document.getElementById(id);
}

/**
 * Recibe etiqueta, clase y texto opcionales. Devuelve un elemento nuevo.
 * textContent muestra los datos como texto y evita que se interpreten como código.
 */
function crear(etiqueta, clase, texto) {
    const nodo = document.createElement(etiqueta);
    if (clase) {
        nodo.className = clase;
    }
    if (texto !== undefined && texto !== null) {
        nodo.textContent = texto;
    }
    return nodo;
}

/** Recibe el destino y un mensaje. Muestra el aviso o retira su espacio cuando está vacío. */
function mostrarMensaje(id, texto, error = false) {
    const aviso = elemento(id);
    aviso.textContent = texto;
    aviso.hidden = !texto;
    aviso.classList.toggle("error", error);
}

/**
 * Recibe una ruta del mismo servidor y opciones. Devuelve datos o el archivo solicitado.
 * La marca CSRF confirma que el cambio procede de este panel. El límite de espera permite
 * volver a intentarlo si la conexión se corta.
 */
async function peticion(ruta, opciones = {}) {
    const controlador = new AbortController();
    const limite = setTimeout(() => controlador.abort(), opciones.archivo ? 60000 : 15000);
    const metodo = opciones.metodo || "GET";
    const cabeceras = { "Accept": opciones.texto ? "application/xml" : (opciones.archivo ? "application/octet-stream" : "application/json") };
    if (metodo !== "GET") {
        cabeceras["Content-Type"] = "application/json";
        if (estado.sesion) {
            cabeceras["X-AgendaVIP-CSRF"] = estado.sesion.csrf;
        }
    }

    try {
        const respuesta = await fetch(ruta, {
            method: metodo,
            headers: cabeceras,
            body: metodo === "GET" ? undefined : JSON.stringify(opciones.cuerpo || {}),
            credentials: "same-origin",
            cache: "no-store",
            signal: controlador.signal
        });
        if (!respuesta.ok) {
            const problema = await respuesta.json().catch(() => ({}));
            if (respuesta.status === 401 && estado.sesion) {
                mostrarAcceso();
            }
            throw new Error(problema.detalle || "El servidor no pudo completar la solicitud.");
        }
        if (opciones.archivo) {
            return await respuesta.blob();
        }
        if (opciones.texto) {
            return await respuesta.text();
        }
        return respuesta.status === 204 ? {} : await respuesta.json();
    } catch (error) {
        if (error.name === "AbortError") {
            throw new Error("El servidor tardó en responder. Comprueba la conexión y vuelve a intentarlo.");
        }
        if (error instanceof TypeError) {
            throw new Error("No hay conexión con el servidor. Revisa que siga encendido.");
        }
        throw error;
    } finally {
        clearTimeout(limite);
    }
}

/**
 * Recibe un formulario y una acción. Evita envíos simultáneos y anuncia la espera.
 * La función que pidió el trabajo muestra su mensaje de error.
 */
async function ocuparFormulario(formulario, accion) {
    if (formulario.dataset.ocupado === "true") {
        return;
    }
    formulario.dataset.ocupado = "true";
    formulario.setAttribute("aria-busy", "true");
    const botones = Array.from(formulario.querySelectorAll("button"));
    botones.forEach((boton) => {
        boton.disabled = true;
    });
    try {
        await accion();
    } finally {
        formulario.dataset.ocupado = "false";
        formulario.removeAttribute("aria-busy");
        botones.forEach((boton) => {
            boton.disabled = false;
        });
    }
}

/** Cerrar sesión o una sesión caducada la llaman para retirar los datos privados del panel. */
function mostrarAcceso() {
    estado.sesion = null;
    estado.vinculos = [];
    estado.seleccionado = null;
    estado.detalle = null;
    estado.turnoDetalle += 1;
    estado.edicion = null;
    estado.confirmacion = null;
    limpiarEditorXML();
    limpiarVinculacion();
    ["cuentas", "lista-tareas", "lista-actividad", "lista-respaldos"].forEach((id) => {
        elemento(id).replaceChildren();
    });
    elemento("cuenta-seleccionada").hidden = true;
    elemento("bienvenida").hidden = false;
    document.querySelectorAll("dialog[open]").forEach((dialogo) => dialogo.close());
    elemento("aplicacion").hidden = true;
    elemento("acceso").hidden = false;
    elemento("clave-administrador").value = "";
    elemento("recordar-acceso").checked = false;
    elemento("contrasena-respaldo").value = "";
    elemento("confirmar-contrasena-respaldo").value = "";
}

/** Recibe la sesión comprobada y muestra el panel. Entrar y el inicio comparten este paso. */
async function mostrarAplicacion(sesion) {
    estado.sesion = sesion;
    elemento("nombre-servidor").textContent = sesion.nombre_servidor || "Servidor local";
    elemento("acceso").hidden = true;
    elemento("aplicacion").hidden = false;
    mostrarMensaje("error-acceso", "");
    await actualizarTodo();
}

/** El botón Entrar envía la clave y la duración elegida. Luego borra la clave y muestra el panel. */
async function entrar(evento) {
    evento.preventDefault();
    mostrarMensaje("error-acceso", "");
    await ocuparFormulario(evento.currentTarget, async () => {
        try {
            const sesion = await peticion("/api/admin/entrar", {
                metodo: "POST",
                cuerpo: {
                    clave: elemento("clave-administrador").value,
                    recordar: elemento("recordar-acceso").checked
                }
            });
            elemento("clave-administrador").value = "";
            await mostrarAplicacion(sesion);
        } catch (error) {
            mostrarMensaje("error-acceso", error.message, true);
        }
    });
}

/** Cerrar sesión pide invalidar la cookie. Si falla la red, conserva la vista para reintentar. */
async function salir() {
    const boton = elemento("boton-salir");
    boton.disabled = true;
    try {
        await peticion("/api/admin/salir", { metodo: "POST" });
        mostrarAcceso();
    } catch (error) {
        mostrarMensaje("mensaje-global", error.message, true);
    } finally {
        boton.disabled = false;
    }
}

/** Recibe una fecha del servidor y devuelve el día y la hora en formato de 12 horas. */
function fechaLegible(valor) {
    if (!valor) {
        return "Sin sincronizar todavía";
    }
    const fecha = new Date(valor);
    if (Number.isNaN(fecha.getTime())) {
        return "Fecha no disponible";
    }
    return new Intl.DateTimeFormat("es-DO", {
        day: "numeric", month: "short", hour: "numeric", minute: "2-digit", hour12: true
    }).format(fecha);
}

/** Recibe la hora de Android, por ejemplo 20:30, y devuelve 8:30 p. m. */
function horaLegible(hora) {
    const partes = String(hora || "00:00").split(":");
    const horas = Number(partes[0]);
    return String(horas % 12 || 12) + ":" + (partes[1] || "00") + (horas >= 12 ? " p. m." : " a. m.");
}

/** Recibe una tarea y describe su repetición con los mismos días que muestra la app. */
function repeticionLegible(tarea) {
    if (tarea.repeticion === "DIARIA") {
        return "Todos los días";
    }
    if (tarea.repeticion === "DIAS_SEMANA") {
        const nombres = ["Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"];
        return (tarea.diasSemana || []).map((dia) => nombres[dia - 1]).join(" · ");
    }
    const partes = String(tarea.fecha || "").split("-");
    return partes.length === 3 ? "Solo el " + partes[2] + "/" + partes[1] + "/" + partes[0] : "Solo una vez";
}

/**
 * Consulta las cuentas y los datos de la seleccionada. El reloj y Actualizar la usan.
 * La espera compartida evita que dos actualizaciones se superpongan.
 */
async function actualizarTodo() {
    if (!estado.sesion || estado.actualizando) {
        return;
    }
    estado.actualizando = true;
    elemento("boton-actualizar").disabled = true;
    try {
        const resultado = await peticion("/api/admin/vinculos");
        if (!estado.sesion) {
            return;
        }
        estado.vinculos = resultado.vinculos || [];
        if (!estado.vinculos.some((cuenta) => cuenta.id === estado.seleccionado)) {
            estado.seleccionado = estado.vinculos.find((cuenta) => cuenta.activo)?.id
                || estado.vinculos[0]?.id || null;
        }
        if (estado.xml && estado.xml.cuentaId !== estado.seleccionado) {
            elemento("dialogo-xml").close();
        }
        dibujarCuentas();
        elemento("bienvenida").hidden = Boolean(estado.seleccionado);
        elemento("cuenta-seleccionada").hidden = !estado.seleccionado
            || estado.detalle?.vinculo?.id !== estado.seleccionado;
        if (estado.seleccionado) {
            await actualizarDetalle();
        }
    } catch (error) {
        if (estado.sesion) {
            mostrarMensaje("mensaje-global", error.message, true);
        }
    } finally {
        estado.actualizando = false;
        elemento("boton-actualizar").disabled = false;
    }
}

/**
 * Reutiliza los botones de las cuentas para conservar el foco del teclado al actualizar.
 * Cada botón contiene el nombre y el usuario de esa cuenta vinculada.
 */
function dibujarCuentas() {
    const lista = elemento("cuentas");
    const existentes = new Map(Array.from(lista.children).map((nodo) => [nodo.dataset.id, nodo]));
    const conservadas = new Set();
    estado.vinculos.forEach((cuenta) => {
        let boton = existentes.get(cuenta.id);
        if (!boton) {
            boton = crear("button", "cuenta");
            boton.type = "button";
            boton.dataset.id = cuenta.id;
            boton.append(crear("span", "iniciales"));
            const textos = crear("span", "cuenta-texto");
            textos.append(crear("strong"), crear("span"));
            boton.append(textos);
            boton.addEventListener("click", () => seleccionarCuenta(cuenta.id));
            lista.append(boton);
        }
        const nombre = cuenta.nombre || cuenta.usuario || "Cuenta vinculada";
        const iniciales = nombre.trim().split(/\s+/).slice(0, 2).map((parte) => parte[0] || "").join("");
        boton.querySelector(".iniciales").textContent = iniciales.toLocaleUpperCase("es");
        boton.querySelector("strong").textContent = nombre;
        boton.querySelector(".cuenta-texto span").textContent = cuenta.activo
            ? "@" + cuenta.usuario : "Vinculación retirada";
        boton.classList.toggle("seleccionada", cuenta.id === estado.seleccionado);
        if (cuenta.id === estado.seleccionado) {
            boton.setAttribute("aria-current", "true");
        } else {
            boton.removeAttribute("aria-current");
        }
        conservadas.add(cuenta.id);
    });
    existentes.forEach((nodo, id) => {
        if (!conservadas.has(id)) {
            nodo.remove();
        }
    });
    elemento("sin-cuentas").hidden = estado.vinculos.length > 0;
}

/** Recibe el código de una cuenta y pide sus datos. Cada formulario recuerda su propia cuenta. */
async function seleccionarCuenta(id) {
    if (!estado.sesion || id === estado.seleccionado) {
        return;
    }
    if (estado.xml) {
        elemento("dialogo-xml").close();
    }
    estado.seleccionado = id;
    estado.detalle = null;
    elemento("cuenta-seleccionada").hidden = true;
    dibujarCuentas();
    try {
        await actualizarDetalle();
    } catch (error) {
        mostrarMensaje("mensaje-global", error.message, true);
    }
}

/** Consulta la cuenta elegida. Descarta una respuesta si entretanto se seleccionó otra cuenta. */
async function actualizarDetalle() {
    const id = estado.seleccionado;
    const turno = ++estado.turnoDetalle;
    const respuesta = await peticion("/api/admin/vinculos/" + encodeURIComponent(id));
    if (!estado.sesion || estado.seleccionado !== id || estado.turnoDetalle !== turno) {
        return;
    }
    estado.detalle = respuesta;
    elemento("cuenta-seleccionada").hidden = false;
    dibujarDetalle();
}

/** Coloca los resúmenes y las listas. Una vinculación retirada conserva la vista sin permitir cambios. */
function dibujarDetalle() {
    const detalle = estado.detalle;
    const cuenta = detalle.vinculo;
    const pendientes = detalle.ordenes.filter((orden) => orden.estado === "pendiente").length;
    elemento("usuario-cuenta").textContent = "CUENTA · @" + cuenta.usuario;
    elemento("nombre-cuenta").textContent = cuenta.nombre || cuenta.usuario;
    elemento("detalle-cuenta").textContent = cuenta.dispositivo_nombre || "Teléfono vinculado";
    elemento("cantidad-tareas").textContent = detalle.tareas.length;
    elemento("cantidad-pendientes").textContent = pendientes;
    elemento("ultima-conexion").textContent = fechaLegible(cuenta.ultima_sincronizacion);
    elemento("estado-conexion").textContent = cuenta.activo
        ? "Los cambios se confirman desde el teléfono." : "La vinculación está retirada.";
    elemento("boton-nueva-tarea").disabled = !cuenta.activo;
    elemento("boton-editar-xml").disabled = !cuenta.activo;
    elemento("boton-exportar-xml").disabled = !cuenta.activo;
    actualizarControlesXML();
    elemento("boton-crear-respaldo").disabled = !cuenta.activo;
    elemento("boton-desvincular").disabled = !cuenta.activo;
    dibujarTareas();
    dibujarActividad();
    dibujarRespaldos();
}
/**
 * Recibe una lista y sus filas nuevas. No reemplaza un botón que tenga el foco del teclado.
 * La próxima consulta actualizará la fila cuando la persona termine de usarla.
 */
function reemplazarLista(id, filas, vacioId) {
    const lista = elemento(id);
    if (lista.contains(document.activeElement)) {
        return;
    }
    lista.replaceChildren(...filas);
    elemento(vacioId).hidden = filas.length > 0;
}

/**
 * Dibuja las tareas confirmadas. Cada botón conserva la versión recibida para detectar
 * si el teléfono cambió esa tarea antes de aplicar una orden.
 */
function dibujarTareas() {
    const detalle = estado.detalle;
    const pendientes = new Set(
        detalle.ordenes.filter((orden) => orden.estado === "pendiente").map((orden) => orden.tarea_id)
    );
    const filas = detalle.tareas.map((entrada) => {
        const tarea = entrada.datos;
        const fila = crear("article", "tarjeta fila-tarea");
        fila.append(crear("div", "hora-tarea", horaLegible(tarea.hora)));
        const datos = crear("div", "datos-tarea");
        datos.append(crear("h3", "", tarea.titulo));
        datos.append(crear("p", "", repeticionLegible(tarea)));
        if (pendientes.has(entrada.id)) {
            datos.append(crear("span", "estado estado-pendiente", "Cambio pendiente"));
        } else if (entrada.estado_hoy?.completada) {
            datos.append(crear("span", "estado estado-aplicada", "Completada en el teléfono"));
        }

        const acciones = crear("div", "acciones-fila");
        const editar = botonFila("Editar", () => abrirTarea(entrada));
        const eliminar = botonFila("Eliminar", () => pedirEliminar(entrada));
        const bloqueada = !detalle.vinculo.activo || pendientes.has(entrada.id);
        editar.disabled = bloqueada;
        eliminar.disabled = bloqueada;
        acciones.append(editar);
        if (permiteCompletarHoy(entrada)) {
            const completar = botonFila(
                entrada.estado_hoy.completada ? "Marcar pendiente" : "Completar hoy",
                () => pedirCompletar(entrada)
            );
            completar.disabled = bloqueada;
            acciones.append(completar);
        }
        acciones.append(eliminar);
        fila.append(datos, acciones);
        return fila;
    });
    reemplazarLista("lista-tareas", filas, "sin-tareas");
}

/**
 * Recibe la tarea confirmada y devuelve si corresponde a la fecha actual del teléfono.
 * La lista oculta Completar hoy para días anteriores al inicio, días omitidos o fechas
 * que quedan fuera de la repetición. El celular comprueba de nuevo estas reglas al recibirla.
 */
function permiteCompletarHoy(entrada) {
    const hoy = entrada.estado_hoy;
    const tarea = entrada.datos;
    if (!hoy?.fecha || hoy.omitida || hoy.fecha < tarea.fecha) {
        return false;
    }

    if (tarea.repeticion === "UNA_VEZ") {
        return hoy.fecha === tarea.fecha;
    }
    if (tarea.repeticion === "DIARIA") {
        return true;
    }
    if (tarea.repeticion === "DIAS_SEMANA") {
        // UTC permite consultar el día de esa fecha sin desplazarla por la zona de la PC.
        const fecha = new Date(hoy.fecha + "T12:00:00Z");
        const diaSemana = fecha.getUTCDay() || 7;
        return (tarea.diasSemana || []).includes(diaSemana);
    }
    return false;
}

/** Recibe texto y acción. Devuelve un botón pequeño listo para añadir a una fila. */
function botonFila(texto, accion) {
    const boton = crear("button", "boton secundario", texto);
    boton.type = "button";
    boton.addEventListener("click", accion);
    return boton;
}

/** Muestra el estado de las órdenes. Aplicado solo aparece cuando lo confirmó el celular. */
function dibujarActividad() {
    const nombres = { guardar: "Guardar recordatorio", eliminar: "Eliminar recordatorio", completar: "Actualizar tarea de hoy" };
    const estados = { pendiente: "Pendiente", aplicada: "Aplicado", conflicto: "Revisar conflicto", error: "Error" };
    const filas = estado.detalle.ordenes.map((orden) => {
        const fila = crear("article", "tarjeta fila-registro");
        const textos = crear("div");
        textos.append(crear("h3", "", orden.datos?.titulo || nombres[orden.accion] || "Cambio de la agenda"));
        textos.append(crear("p", "", (nombres[orden.accion] || "Cambio") + " · " + fechaLegible(orden.creada_en)));
        if (orden.mensaje) {
            textos.append(crear("p", "", orden.mensaje));
        }
        const etiqueta = estados[orden.estado] || "Estado desconocido";
        const claseEstado = Object.hasOwn(estados, orden.estado) ? orden.estado : "error";
        fila.append(textos, crear("span", "estado estado-" + claseEstado, etiqueta));
        return fila;
    });
    reemplazarLista("lista-actividad", filas, "sin-actividad");
}

/** Muestra las copias. Descargar se ofrece después de que el teléfono termine de enviarla. */
function dibujarRespaldos() {
    const etiquetas = { pendiente: "Esperando al celular", listo: "Lista para descargar", error: "No se completó" };
    const filas = estado.detalle.respaldos.map((respaldo) => {
        const fila = crear("article", "tarjeta fila-registro");
        const textos = crear("div");
        textos.append(crear("h3", "", "Copia de " + fechaLegible(respaldo.creada_en)));
        const tamano = respaldo.tamano ? " · " + (respaldo.tamano / (1024 * 1024)).toFixed(1) + " MB" : "";
        textos.append(crear("p", "", (etiquetas[respaldo.estado] || "Estado desconocido") + tamano));
        fila.append(textos);
        if (respaldo.estado === "listo") {
            fila.append(botonFila("Descargar", () => abrirDescarga(respaldo)));
        } else {
            const esError = respaldo.estado === "error";
            fila.append(crear("span", "estado " + (esError ? "estado-error" : "estado-pendiente"), esError ? "Error" : "Pendiente"));
        }
        return fila;
    });
    reemplazarLista("lista-respaldos", filas, "sin-respaldos");
}

/** Recibe la pestaña elegida y muestra su apartado sin cambiar la cuenta ni los formularios. */
function cambiarVista(vista) {
    ["tareas", "actividad", "respaldos"].forEach((nombre) => {
        elemento("vista-" + nombre).hidden = nombre !== vista;
        const boton = elemento("tab-" + nombre);
        boton.classList.toggle("activa", nombre === vista);
        if (nombre === vista) {
            boton.setAttribute("aria-current", "page");
        } else {
            boton.removeAttribute("aria-current");
        }
    });
}

/** Vincular mi celular abre el cuadro y solicita un código temporal del servidor. */
async function abrirVinculacion() {
    elemento("dialogo-vincular").showModal();
    await generarCodigo();
}

/**
 * Retira el permiso temporal de la pantalla y descarta respuestas de peticiones antiguas.
 * El cierre del cuadro y una nueva generación la usan para evitar mostrar un QR anterior.
 */
function limpiarVinculacion() {
    estado.turnoVinculacion += 1;
    estado.venceVinculacion = null;
    elemento("codigo-vinculacion").value = "";
    elemento("imagen-vinculacion").hidden = true;
    elemento("imagen-vinculacion").removeAttribute("src");
    elemento("estado-qr").textContent = "";
    elemento("caducidad-codigo").textContent = "";
    elemento("direccion-servidor").textContent = "";
    elemento("boton-copiar-codigo").disabled = true;
    elemento("vinculacion-manual").open = false;
}

/**
 * Generar nuevo QR solicita un permiso temporal y muestra la imagen que prepara la PC.
 * Conserva el código manual como alternativa y solo dibuja la respuesta del cuadro vigente.
 */
async function generarCodigo(evento) {
    evento?.preventDefault();
    const formulario = elemento("formulario-vincular");
    if (formulario.dataset.ocupado === "true") {
        return;
    }
    limpiarVinculacion();
    const turno = estado.turnoVinculacion;
    elemento("estado-qr").textContent = "Preparando el QR…";

    await ocuparFormulario(formulario, async () => {
        mostrarMensaje("error-vincular", "");
        try {
            const datos = await peticion("/api/admin/vinculacion", { metodo: "POST" });
            if (turno !== estado.turnoVinculacion || !elemento("dialogo-vincular").open || !estado.sesion) {
                return;
            }

            elemento("codigo-vinculacion").value = datos.codigo;
            estado.venceVinculacion = Date.parse(datos.vence_en);
            elemento("direccion-servidor").textContent = "Dirección de la PC: " + (datos.direcciones || []).join(" · ");

            const imagen = elemento("imagen-vinculacion");
            if (typeof datos.qr_imagen === "string" && datos.qr_imagen.startsWith("data:image/svg+xml;base64,")) {
                imagen.src = datos.qr_imagen;
                imagen.hidden = false;
                elemento("estado-qr").textContent = "Apunta la cámara del celular al QR.";
            } else {
                elemento("estado-qr").textContent = datos.qr_advertencia || "El QR todavía no está disponible. Puedes usar el código manual.";
                elemento("vinculacion-manual").open = true;
            }
        } catch (error) {
            if (turno === estado.turnoVinculacion && elemento("dialogo-vincular").open) {
                elemento("estado-qr").textContent = "No se pudo preparar el QR.";
                mostrarMensaje("error-vincular", error.message, true);
            }
        }
    });
    actualizarCaducidadVinculacion();
}

/**
 * Actualiza el tiempo visible del permiso cuando el cuadro está abierto.
 * Al vencer retira el dibujo y el texto para que la persona genere un permiso nuevo.
 */
function actualizarCaducidadVinculacion() {
    if (!elemento("dialogo-vincular").open) {
        return;
    }
    if (!Number.isFinite(estado.venceVinculacion)) {
        elemento("boton-copiar-codigo").disabled = true;
        return;
    }

    const segundos = Math.ceil((estado.venceVinculacion - Date.now()) / 1000);
    if (segundos <= 0) {
        estado.venceVinculacion = null;
        elemento("imagen-vinculacion").hidden = true;
        elemento("imagen-vinculacion").removeAttribute("src");
        elemento("codigo-vinculacion").value = "";
        elemento("boton-copiar-codigo").disabled = true;
        elemento("caducidad-codigo").textContent = "Este permiso ya venció.";
        elemento("estado-qr").textContent = "Pulsa Generar nuevo QR para vincular el celular.";
        return;
    }

    const minutos = Math.floor(segundos / 60);
    const restantes = segundos % 60;
    elemento("caducidad-codigo").textContent = "Vence en " + minutos + " min " + restantes + " s.";
    elemento("boton-copiar-codigo").disabled = !elemento("codigo-vinculacion").value ||
        elemento("formulario-vincular").dataset.ocupado === "true";
}

/** Copia el código. Si el navegador no lo permite, selecciona el texto para copiarlo con Ctrl+C. */
async function copiarCodigo() {
    const campo = elemento("codigo-vinculacion");
    if (!campo.value) {
        return;
    }
    try {
        await navigator.clipboard.writeText(campo.value);
        mostrarMensaje("error-vincular", "Código copiado.");
    } catch (_) {
        elemento("vinculacion-manual").open = true;
        campo.focus();
        campo.select();
        mostrarMensaje("error-vincular", "El código está seleccionado. Pulsa Ctrl+C para copiarlo.");
    }
}

/**
 * Recibe una tarea o null para crearla. Guarda una referencia fija de su versión.
 * Las consultas automáticas no sustituyen el borrador del cuadro.
 */
function abrirTarea(entrada = null) {
    if (!estado.detalle || estado.enviandoTarea) {
        return;
    }
    const tarea = entrada?.datos;
    estado.edicion = { cuentaId: estado.seleccionado, entrada };
    estado.solicitudTarea = null;
    estado.firmaSolicitudTarea = null;
    const formulario = elemento("formulario-tarea");
    formulario.reset();
    formulario.querySelector("details").open = false;
    mostrarMensaje("error-tarea", "");
    elemento("titulo-tarea").textContent = tarea ? "Editar tarea" : "Nueva tarea";
    elemento("tarea-titulo").value = tarea?.titulo || "";
    elemento("tarea-fecha").value = tarea?.fecha || fechaHoyLocal();
    const partes = (tarea?.hora || "08:00").split(":");
    elemento("tarea-hora").value = String(Number(partes[0]) % 12 || 12) + ":" + partes[1];
    elemento("tarea-periodo").value = Number(partes[0]) >= 12 ? "PM" : "AM";
    elemento("tarea-repeticion").value = tarea?.repeticion || "UNA_VEZ";
    elemento("tarea-voz").checked = tarea?.vozActiva !== false;
    elemento("tarea-mensaje").value = tarea?.mensajeVoz || "";
    elemento("tarea-seguimiento").checked = tarea?.seguimientoActivo || false;
    elemento("tarea-intervalo").value = tarea?.intervaloMinutos || 5;
    elemento("tarea-limite").value = tarea?.limiteAvisos || 3;
    elemento("tarea-mensaje-seguimiento").value = tarea?.mensajeSeguimiento || "";
    document.querySelectorAll("#dias-semana input").forEach((dia) => {
        dia.checked = tarea?.diasSemana?.includes(Number(dia.value)) || false;
    });

    const musica = elemento("tarea-musica");
    musica.querySelector('option[value="SELECCIONADA"]')?.remove();
    if (tarea?.modoMusica === "SELECCIONADA") {
        const opcion = crear("option", "", "Conservar: " + (tarea.musicaNombre || "canción del celular"));
        opcion.value = "SELECCIONADA";
        musica.append(opcion);
    }
    musica.value = tarea?.modoMusica || "ALEATORIA";
    actualizarDias();
    elemento("dialogo-tarea").showModal();
    elemento("tarea-titulo").focus();
}

/** Devuelve la fecha local de la computadora como año-mes-día para proponer una tarea nueva. */
function fechaHoyLocal() {
    const fecha = new Date();
    return [
        fecha.getFullYear(),
        String(fecha.getMonth() + 1).padStart(2, "0"),
        String(fecha.getDate()).padStart(2, "0")
    ].join("-");
}

/** La elección de repetición muestra los días de semana únicamente cuando hacen falta. */
function actualizarDias() {
    elemento("dias-semana").hidden = elemento("tarea-repeticion").value !== "DIAS_SEMANA";
}

/**
 * Revisa el formulario y devuelve los campos que entiende Agenda.VIP.
 * Al editar permite conservar la canción elegida en el teléfono.
 */
function leerTarea() {
    const original = estado.edicion.entrada?.datos;
    const coincidencia = /^(0?[1-9]|1[0-2]):([0-5][0-9])$/.exec(elemento("tarea-hora").value.trim());
    if (!coincidencia) {
        throw new Error("Escribe la hora como 8:30 y elige a. m. o p. m.");
    }
    let hora = Number(coincidencia[1]) % 12;
    if (elemento("tarea-periodo").value === "PM") {
        hora += 12;
    }
    let horaCompleta = String(hora).padStart(2, "0") + ":" + coincidencia[2];
    if (original?.hora?.slice(0, 5) === horaCompleta) {
        horaCompleta = original.hora;
    }

    const repeticion = elemento("tarea-repeticion").value;
    const diasSemana = Array.from(document.querySelectorAll("#dias-semana input:checked")).map((dia) => Number(dia.value));
    if (repeticion === "DIAS_SEMANA" && diasSemana.length === 0) {
        throw new Error("Selecciona al menos un día de la semana.");
    }
    const titulo = elemento("tarea-titulo").value.trim();
    if (!titulo) {
        throw new Error("Escribe qué hay que hacer.");
    }

    const modoMusica = elemento("tarea-musica").value;
    const datos = {
        titulo,
        fecha: elemento("tarea-fecha").value,
        hora: horaCompleta,
        repeticion,
        diasSemana: repeticion === "DIAS_SEMANA" ? diasSemana : [],
        vozActiva: elemento("tarea-voz").checked,
        mensajeVoz: elemento("tarea-mensaje").value.trim(),
        musicaUri: modoMusica === "SELECCIONADA" ? original.musicaUri : null,
        musicaNombre: modoMusica === "SELECCIONADA" ? original.musicaNombre : "",
        modoMusica,
        seguimientoActivo: elemento("tarea-seguimiento").checked,
        intervaloMinutos: Number(elemento("tarea-intervalo").value),
        limiteAvisos: Number(elemento("tarea-limite").value),
        mensajeSeguimiento: elemento("tarea-mensaje-seguimiento").value.trim()
    };
    if (original) {
        datos.id = original.id;
    }
    return datos;
}
/**
 * Guardar crea una orden pendiente. Reutiliza el mismo código de solicitud si se reintenta
 * el mismo formulario, para no duplicar una tarea tras perder una respuesta de la red.
 */
async function guardarTarea(evento) {
    evento.preventDefault();
    if (!estado.edicion || estado.enviandoTarea) {
        return;
    }
    const formulario = evento.currentTarget;
    mostrarMensaje("error-tarea", "");
    let datos;
    try {
        datos = leerTarea();
    } catch (error) {
        mostrarMensaje("error-tarea", error.message, true);
        return;
    }

    const edicion = estado.edicion;
    const cuerpo = {
        accion: "guardar",
        base_version: edicion.entrada?.version || null,
        datos
    };
    if (edicion.entrada) {
        cuerpo.tarea_id = edicion.entrada.id;
    }

    const firma = JSON.stringify(cuerpo);
    if (estado.firmaSolicitudTarea !== firma) {
        estado.solicitudTarea = crypto.randomUUID();
        estado.firmaSolicitudTarea = firma;
    }
    cuerpo.solicitud_id = estado.solicitudTarea;
    estado.enviandoTarea = true;
    try {
        await ocuparFormulario(formulario, async () => {
            try {
                await peticion("/api/admin/vinculos/" + encodeURIComponent(edicion.cuentaId) + "/ordenes", {
                    metodo: "POST",
                    cuerpo
                });
                elemento("dialogo-tarea").close();
                mostrarMensaje("mensaje-global", "Cambio enviado. Falta que el celular lo reciba y confirme.");
                await actualizarTodo();
            } catch (error) {
                mostrarMensaje("error-tarea", error.message, true);
            }
        });
    } finally {
        estado.enviandoTarea = false;
    }
}

/** Recibe un título, un aviso y una acción. Pide confirmar antes de hacer el cambio. */
function confirmar(titulo, mensaje, etiqueta, accion) {
    estado.confirmacion = accion;
    elemento("titulo-confirmar").textContent = titulo;
    elemento("texto-confirmar").textContent = mensaje;
    elemento("aceptar-confirmacion").textContent = etiqueta;
    mostrarMensaje("error-confirmar", "");
    elemento("dialogo-confirmar").showModal();
}

/** Recibe la tarea elegida y pide eliminar toda su programación cuando llegue al celular. */
function pedirEliminar(entrada) {
    const cuentaId = estado.seleccionado;
    const solicitudId = crypto.randomUUID();
    confirmar(
        "¿Eliminar este recordatorio?",
        "Se eliminará la programación completa de «" + entrada.datos.titulo + "» cuando el teléfono reciba el cambio.",
        "Eliminar recordatorio",
        () => enviarOrden(cuentaId, {
            solicitud_id: solicitudId,
            accion: "eliminar",
            tarea_id: entrada.id,
            base_version: entrada.version
        })
    );
}

/** Recibe la tarea y usa la fecha confirmada por el teléfono para actualizar solo ese día. */
function pedirCompletar(entrada) {
    const cuentaId = estado.seleccionado;
    const solicitudId = crypto.randomUUID();
    const completada = !entrada.estado_hoy.completada;
    confirmar(
        completada ? "¿Completar esta tarea?" : "¿Volver a dejarla pendiente?",
        "Se actualizará «" + entrada.datos.titulo + "» para el día " + entrada.estado_hoy.fecha + " del teléfono.",
        completada ? "Marcar completada" : "Marcar pendiente",
        () => enviarOrden(cuentaId, {
            solicitud_id: solicitudId,
            accion: "completar",
            tarea_id: entrada.id,
            base_version: entrada.version,
            fecha: entrada.estado_hoy.fecha,
            completada
        })
    );
}

/** Recibe la cuenta y la orden confirmada. El celular comprobará su versión antes de aplicarla. */
async function enviarOrden(cuentaId, cuerpo) {
    await peticion("/api/admin/vinculos/" + encodeURIComponent(cuentaId) + "/ordenes", {
        metodo: "POST",
        cuerpo
    });
    mostrarMensaje("mensaje-global", "Solicitud guardada. Esperando confirmación del celular.");
}

/** Desvincular pide confirmar antes de retirar el acceso al servidor. Conserva la agenda local. */
function pedirDesvincular() {
    if (!estado.detalle) {
        return;
    }
    const cuentaId = estado.seleccionado;
    const nombre = estado.detalle.vinculo.nombre || estado.detalle.vinculo.usuario;
    confirmar(
        "¿Desvincular esta cuenta?",
        "La cuenta de «" + nombre + "» dejará de compartir cambios con esta computadora. Su agenda permanece en el celular.",
        "Desvincular",
        async () => {
            await peticion("/api/admin/vinculos/" + encodeURIComponent(cuentaId), { metodo: "DELETE" });
            mostrarMensaje("mensaje-global", "Vinculación retirada.");
        }
    );
}

/** El botón del cuadro ejecuta la acción elegida y conserva la opción si falla la conexión. */
async function aceptarConfirmacion(evento) {
    evento.preventDefault();
    if (!estado.confirmacion) {
        return;
    }
    const accion = estado.confirmacion;
    await ocuparFormulario(evento.currentTarget, async () => {
        try {
            await accion();
            elemento("dialogo-confirmar").close();
            estado.confirmacion = null;
            await actualizarTodo();
        } catch (error) {
            mostrarMensaje("error-confirmar", error.message, true);
        }
    });
}

/** Crear copia pide los datos de la cuenta al teléfono en su próxima sincronización. */
async function crearRespaldo() {
    if (!estado.seleccionado) {
        return;
    }
    const boton = elemento("boton-crear-respaldo");
    boton.disabled = true;
    try {
        await peticion("/api/admin/vinculos/" + encodeURIComponent(estado.seleccionado) + "/respaldos", {
            metodo: "POST"
        });
        mostrarMensaje("mensaje-global", "Copia solicitada. Se guardará cuando el teléfono vuelva a sincronizarse.");
        cambiarVista("respaldos");
        await actualizarTodo();
    } catch (error) {
        mostrarMensaje("mensaje-global", error.message, true);
    } finally {
        boton.disabled = estado.detalle?.vinculo?.activo === false;
    }
}

/** Recibe la copia lista y abre el cuadro para proteger la descarga con una contraseña. */
function abrirDescarga(respaldo) {
    estado.respaldoElegido = respaldo.id;
    elemento("formulario-descargar").reset();
    mostrarMensaje("error-descargar", "");
    elemento("dialogo-descargar").showModal();
}

/**
 * Revisa la contraseña repetida, solicita el archivo protegido e inicia su descarga.
 * El archivo temporal del navegador se libera después de empezar a descargar.
 */
async function descargarRespaldo(evento) {
    evento.preventDefault();
    const formulario = evento.currentTarget;
    const contrasena = elemento("contrasena-respaldo").value;
    const repetida = elemento("confirmar-contrasena-respaldo").value;
    if (contrasena.length < 12 || contrasena.length > 128) {
        mostrarMensaje("error-descargar", "Usa entre 12 y 128 caracteres.", true);
        return;
    }
    if (contrasena !== repetida) {
        mostrarMensaje("error-descargar", "Las contraseñas no coinciden.", true);
        return;
    }

    const copiaId = estado.respaldoElegido;
    await ocuparFormulario(formulario, async () => {
        try {
            const archivo = await peticion("/api/admin/respaldos/" + encodeURIComponent(copiaId) + "/descargar", {
                metodo: "POST",
                cuerpo: { contrasena },
                archivo: true
            });
            const direccion = URL.createObjectURL(archivo);
            const enlace = crear("a");
            enlace.href = direccion;
            enlace.download = "AgendaVIP_" + fechaHoyLocal() + ".avip";
            document.body.append(enlace);
            enlace.click();
            enlace.remove();
            setTimeout(() => URL.revokeObjectURL(direccion), 60000);
            elemento("dialogo-descargar").close();
            mostrarMensaje("mensaje-global", "Descarga preparada. Conserva el archivo y la contraseña que elegiste.");
        } catch (error) {
            mostrarMensaje("error-descargar", error.message, true);
        } finally {
            elemento("contrasena-respaldo").value = "";
            elemento("confirmar-contrasena-respaldo").value = "";
        }
    });
}

/**
 * Abre una copia editable del XML de la cuenta seleccionada.
 * La referencia del editor permite descartar respuestas si se cerró el cuadro entretanto.
 */
async function abrirEditorXML() {
    const cuenta = estado.detalle?.vinculo;
    if (!cuenta?.activo || cuenta.id !== estado.seleccionado) {
        return;
    }

    const editor = {
        cuentaId: cuenta.id,
        idLote: crypto.randomUUID(),
        revision: null,
        textoRevisado: null,
        ocupada: true
    };
    estado.xml = editor;
    elemento("cuenta-xml").textContent = cuenta.nombre || cuenta.usuario;
    elemento("contenido-xml").value = "";
    elemento("archivo-xml").value = "";
    elemento("archivo-xml-elegido").textContent = "";
    mostrarMensaje("error-xml", "");
    limpiarVistaPreviaXML();
    elemento("estado-xml").textContent = "Preparando el XML de esta cuenta…";
    actualizarControlesXML();
    elemento("dialogo-xml").showModal();

    try {
        const texto = await peticion(
            "/api/admin/vinculos/" + encodeURIComponent(cuenta.id) + "/recordatorios.xml",
            { texto: true }
        );
        if (estado.xml !== editor || !cuentaXMLDisponible(editor)) {
            return;
        }
        elemento("contenido-xml").value = texto;
        elemento("estado-xml").textContent = "Edita el archivo o abre otro XML. Después pulsa Revisar cambios.";
    } catch (error) {
        if (estado.xml === editor) {
            mostrarMensaje("error-xml", error.message, true);
        }
    } finally {
        if (estado.xml === editor) {
            editor.ocupada = false;
            actualizarControlesXML();
        }
    }
}

/** Recibe el editor y devuelve si su cuenta sigue siendo la seleccionada y autorizada. */
function cuentaXMLDisponible(editor) {
    return Boolean(
        estado.sesion &&
        estado.seleccionado === editor.cuentaId &&
        estado.detalle?.vinculo?.id === editor.cuentaId &&
        estado.detalle.vinculo.activo
    );
}

/**
 * Actualiza los controles del cuadro sin volver a escribir el XML.
 * El reloj del panel puede consultar la agenda mientras la persona conserva su borrador.
 */
function actualizarControlesXML() {
    const editor = estado.xml;
    if (!editor) {
        return;
    }
    const disponible = cuentaXMLDisponible(editor);
    const ocupada = editor.ocupada;
    const formulario = elemento("formulario-xml");
    formulario.setAttribute("aria-busy", String(ocupada));
    elemento("contenido-xml").disabled = ocupada || !disponible;
    elemento("archivo-xml").disabled = ocupada || !disponible;
    elemento("boton-exportar-editor-xml").disabled = ocupada || !disponible;
    elemento("boton-revisar-xml").disabled = ocupada || !disponible
        || !elemento("contenido-xml").value.trim();
    elemento("boton-aplicar-xml").disabled = ocupada || !disponible
        || !editor.revision || totalCambiosXML(editor.revision.resumen) === 0
        || editor.textoRevisado !== elemento("contenido-xml").value;

    if (!disponible) {
        elemento("estado-xml").textContent = "Esta cuenta dejó de estar disponible. Cierra el editor y elige una vinculación activa.";
    }
}

/** Recibe el resumen validado y devuelve cuántas órdenes modificarán la agenda. */
function totalCambiosXML(resumen) {
    return Number(resumen?.nuevos || 0)
        + Number(resumen?.modificados || 0)
        + Number(resumen?.eliminados || 0);
}

/** Limpia la revisión anterior cuando cambia el texto, sin cambiar el contenido del editor. */
function limpiarVistaPreviaXML() {
    elemento("vista-previa-xml").hidden = true;
    elemento("resumen-xml").textContent = "";
    elemento("cambios-xml").replaceChildren();
    elemento("advertencias-xml").replaceChildren();
    elemento("advertencias-xml").hidden = true;
}

/**
 * Un cambio de texto o archivo retira la revisión y prepara otra identidad de importación.
 * Si se reintenta el mismo texto después de un corte, su identidad anterior sigue en el editor.
 */
function cambiarTextoXML() {
    const editor = estado.xml;
    if (!editor || editor.ocupada) {
        return;
    }
    editor.idLote = crypto.randomUUID();
    editor.revision = null;
    editor.textoRevisado = null;
    limpiarVistaPreviaXML();
    mostrarMensaje("error-xml", "");
    elemento("estado-xml").textContent = "Texto modificado. Revisa los cambios antes de enviarlos.";
    actualizarControlesXML();
}

/**
 * Recibe el archivo que eligió la persona y coloca su texto dentro del editor.
 * El límite se comprueba antes de leerlo y también antes de mandarlo al servidor.
 */
async function abrirArchivoXML(evento) {
    const editor = estado.xml;
    const archivo = evento.target.files?.[0];
    if (!editor || editor.ocupada || !archivo || !cuentaXMLDisponible(editor)) {
        return;
    }
    mostrarMensaje("error-xml", "");
    if (archivo.size > 8 * 1024 * 1024) {
        mostrarMensaje("error-xml", "El archivo supera el máximo de 8 MB.", true);
        return;
    }

    editor.ocupada = true;
    elemento("estado-xml").textContent = "Leyendo el archivo elegido…";
    actualizarControlesXML();
    try {
        const texto = await archivo.text();
        if (estado.xml !== editor || !cuentaXMLDisponible(editor)) {
            return;
        }
        if (texto.includes("\u0000")) {
            throw new Error("Guarda el XML con codificación UTF-8 y vuelve a abrirlo.");
        }
        elemento("contenido-xml").value = texto;
        elemento("archivo-xml-elegido").textContent = archivo.name;
        editor.ocupada = false;
        cambiarTextoXML();
    } catch (error) {
        if (estado.xml === editor) {
            mostrarMensaje("error-xml", error.message || "No se pudo leer el archivo elegido.", true);
        }
    } finally {
        if (estado.xml === editor) {
            editor.ocupada = false;
            actualizarControlesXML();
        }
    }
}

/** Descarga el XML confirmado de la cuenta seleccionada para editarlo con otro programa. */
async function exportarXML() {
    const cuenta = estado.detalle?.vinculo;
    if (!cuenta?.activo || cuenta.id !== estado.seleccionado) {
        return;
    }
    const cuentaId = cuenta.id;
    const editor = estado.xml;
    const boton = elemento("boton-exportar-xml");
    boton.disabled = true;
    try {
        const archivo = await peticion(
            "/api/admin/vinculos/" + encodeURIComponent(cuentaId) + "/recordatorios.xml",
            { archivo: true }
        );
        if (!estado.sesion || estado.seleccionado !== cuentaId) {
            return;
        }
        descargarArchivoXML(archivo);
        if (editor && estado.xml === editor) {
            elemento("estado-xml").textContent = "XML actual descargado. El texto que estás editando se conserva.";
        }
    } catch (error) {
        if (editor && estado.xml === editor) {
            mostrarMensaje("error-xml", error.message, true);
        } else if (estado.sesion) {
            mostrarMensaje("mensaje-global", error.message, true);
        }
    } finally {
        boton.disabled = estado.detalle?.vinculo?.activo !== true;
    }
}

/** Recibe el archivo XML y abre su descarga; libera la dirección temporal un minuto después. */
function descargarArchivoXML(archivo) {
    const direccion = URL.createObjectURL(archivo);
    const enlace = crear("a");
    enlace.href = direccion;
    enlace.download = "AgendaVIP_recordatorios.xml";
    document.body.append(enlace);
    enlace.click();
    enlace.remove();
    setTimeout(() => URL.revokeObjectURL(direccion), 60000);
}

/**
 * Comprueba el tamaño del texto y pide al servidor que prepare una vista previa.
 * Esta operación muestra cambios propuestos; Enviar cambios es el paso que los confirma.
 */
async function revisarXML() {
    const editor = estado.xml;
    if (!editor || editor.ocupada || !cuentaXMLDisponible(editor)) {
        return;
    }
    const texto = elemento("contenido-xml").value;
    mostrarMensaje("error-xml", "");
    if (!texto.trim() || new TextEncoder().encode(texto).length > 8 * 1024 * 1024) {
        mostrarMensaje("error-xml", "Abre o escribe un XML de hasta 8 MB.", true);
        return;
    }

    editor.ocupada = true;
    editor.revision = null;
    editor.textoRevisado = null;
    limpiarVistaPreviaXML();
    elemento("estado-xml").textContent = "Comprobando el archivo y preparando los cambios…";
    actualizarControlesXML();
    try {
        const revision = await peticion(
            "/api/admin/vinculos/" + encodeURIComponent(editor.cuentaId) + "/xml/revisar",
            { metodo: "POST", cuerpo: { xml: texto, id_lote: editor.idLote } }
        );
        if (estado.xml !== editor || !cuentaXMLDisponible(editor)) {
            return;
        }
        editor.revision = revision;
        editor.textoRevisado = texto;
        mostrarRevisionXML(revision);
        elemento("estado-xml").textContent = totalCambiosXML(revision.resumen)
            ? "Revisa la lista y pulsa Enviar cambios al celular."
            : "El archivo coincide con los datos actuales. No hay cambios para enviar.";
    } catch (error) {
        if (estado.xml === editor) {
            mostrarMensaje("error-xml", error.message, true);
        }
    } finally {
        if (estado.xml === editor) {
            editor.ocupada = false;
            actualizarControlesXML();
        }
    }
}

/** Recibe la revisión del servidor y dibuja su resumen y cada cambio como texto seguro. */
function mostrarRevisionXML(revision) {
    const resumen = revision.resumen || {};
    elemento("resumen-xml").textContent = descripcionResumenXML(resumen);
    const nombres = {
        nuevo: "Nuevo",
        modificado: "Editar",
        eliminado: "Eliminar",
        sin_cambios: "Sin cambios"
    };
    const fragmento = document.createDocumentFragment();
    (revision.cambios || []).forEach((cambio) => {
        const fila = crear("li", "cambio-xml cambio-xml-" + cambio.tipo);
        fila.append(crear("span", "estado", nombres[cambio.tipo] || "Revisar"));
        const datos = crear("div");
        datos.append(crear("strong", "", cambio.titulo || cambio.id || "Recordatorio"));
        datos.append(crear("p", "nota", "Registro " + cambio.indice + " · " + (cambio.id || "Identidad preparada al importar")));
        fila.append(datos);
        fragmento.append(fila);
    });
    elemento("cambios-xml").replaceChildren(fragmento);
    const avisos = document.createDocumentFragment();
    (revision.advertencias || []).forEach((advertencia) => {
        avisos.append(crear("li", "", advertencia));
    });
    elemento("advertencias-xml").replaceChildren(avisos);
    elemento("advertencias-xml").hidden = !(revision.advertencias || []).length;
    elemento("vista-previa-xml").hidden = false;
}

/** Recibe las cantidades de la revisión y devuelve una explicación que también usa la confirmación. */
function descripcionResumenXML(resumen) {
    return Number(resumen.nuevos || 0) + " nuevos · "
        + Number(resumen.modificados || 0) + " para editar · "
        + Number(resumen.eliminados || 0) + " para eliminar · "
        + Number(resumen.sin_cambios || 0) + " sin cambios";
}

/** El envío abre una confirmación con las cantidades revisadas antes de guardar las órdenes. */
function confirmarImportacionXML(evento) {
    evento.preventDefault();
    const editor = estado.xml;
    if (!editor || editor.ocupada || !editor.revision || !cuentaXMLDisponible(editor)
        || editor.textoRevisado !== elemento("contenido-xml").value
        || totalCambiosXML(editor.revision.resumen) === 0) {
        return;
    }
    const texto = editor.textoRevisado;
    confirmar(
        "¿Enviar estos cambios?",
        descripcionResumenXML(editor.revision.resumen)
            + ". El panel guardará las órdenes y el celular confirmará cada una al sincronizar.",
        "Enviar cambios",
        async () => aplicarXML(editor, texto)
    );
}

/**
 * Recibe el editor y el texto que la persona revisó y confirmó.
 * Reutiliza el mismo lote al reintentar para que perder una respuesta no duplique recordatorios.
 */
async function aplicarXML(editor, texto) {
    if (estado.xml !== editor || editor.ocupada || !cuentaXMLDisponible(editor)
        || editor.textoRevisado !== texto || elemento("contenido-xml").value !== texto) {
        throw new Error("La revisión cambió. Cierra esta confirmación y vuelve a revisar el XML.");
    }

    editor.ocupada = true;
    elemento("estado-xml").textContent = "Enviando las órdenes revisadas…";
    actualizarControlesXML();
    try {
        const resultado = await peticion(
            "/api/admin/vinculos/" + encodeURIComponent(editor.cuentaId) + "/xml/aplicar",
            { metodo: "POST", cuerpo: { xml: texto, id_lote: editor.idLote } }
        );
        if (estado.xml === editor && estado.sesion) {
            elemento("dialogo-xml").close();
            mostrarMensaje(
                "mensaje-global",
                resultado.mensaje || "Importación guardada. Esperando la confirmación del celular."
            );
            cambiarVista("actividad");
        }
    } finally {
        if (estado.xml === editor) {
            editor.ocupada = false;
            actualizarControlesXML();
        }
    }
}

/** El cierre y la salida del panel descartan la vista privada y las respuestas que lleguen después. */
function limpiarEditorXML() {
    estado.xml = null;
    elemento("contenido-xml").value = "";
    elemento("archivo-xml").value = "";
    elemento("archivo-xml-elegido").textContent = "";
    elemento("cuenta-xml").textContent = "";
    elemento("estado-xml").textContent = "";
    mostrarMensaje("error-xml", "");
    limpiarVistaPreviaXML();
}


/** Conecta los controles fijos. Los botones de cada tarea se conectan al dibujar su fila. */
function conectarControles() {
    elemento("formulario-acceso").addEventListener("submit", entrar);
    elemento("boton-salir").addEventListener("click", salir);
    elemento("boton-actualizar").addEventListener("click", actualizarTodo);
    elemento("boton-vincular").addEventListener("click", abrirVinculacion);
    elemento("boton-vincular-bienvenida").addEventListener("click", abrirVinculacion);
    elemento("formulario-vincular").addEventListener("submit", generarCodigo);
    elemento("boton-copiar-codigo").addEventListener("click", copiarCodigo);
    elemento("dialogo-vincular").addEventListener("close", limpiarVinculacion);
    elemento("imagen-vinculacion").addEventListener("error", () => {
        if (!elemento("dialogo-vincular").open || !elemento("imagen-vinculacion").hasAttribute("src")) {
            return;
        }
        elemento("imagen-vinculacion").hidden = true;
        elemento("estado-qr").textContent = "No se pudo mostrar el QR. Puedes usar el código manual.";
        elemento("vinculacion-manual").open = true;
    });
    elemento("boton-nueva-tarea").addEventListener("click", () => abrirTarea());
    elemento("boton-editar-xml").addEventListener("click", abrirEditorXML);
    elemento("boton-exportar-xml").addEventListener("click", exportarXML);
    elemento("boton-exportar-editor-xml").addEventListener("click", exportarXML);
    elemento("archivo-xml").addEventListener("change", abrirArchivoXML);
    elemento("contenido-xml").addEventListener("input", cambiarTextoXML);
    elemento("boton-revisar-xml").addEventListener("click", revisarXML);
    elemento("formulario-xml").addEventListener("submit", confirmarImportacionXML);
    elemento("dialogo-xml").addEventListener("close", limpiarEditorXML);
    elemento("tarea-repeticion").addEventListener("change", actualizarDias);
    elemento("formulario-tarea").addEventListener("submit", guardarTarea);
    elemento("formulario-confirmar").addEventListener("submit", aceptarConfirmacion);
    elemento("formulario-descargar").addEventListener("submit", descargarRespaldo);
    elemento("boton-crear-respaldo").addEventListener("click", crearRespaldo);
    elemento("boton-desvincular").addEventListener("click", pedirDesvincular);

    document.querySelectorAll(".pestana").forEach((boton) => {
        boton.addEventListener("click", () => cambiarVista(boton.dataset.vista));
    });
    document.querySelectorAll(".cerrar-dialogo").forEach((boton) => {
        boton.addEventListener("click", () => boton.closest("dialog").close());
    });
    elemento("dialogo-descargar").addEventListener("close", () => {
        elemento("contrasena-respaldo").value = "";
        elemento("confirmar-contrasena-respaldo").value = "";
    });
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) {
            actualizarTodo();
        }
    });
}

/**
 * Recupera la sesión al abrir y consulta cambios cada seis segundos mientras la pestaña es visible.
 * El panel no ejecuta cambios de tareas automáticamente al consultar.
 */
async function iniciar() {
    conectarControles();
    setInterval(actualizarCaducidadVinculacion, 1000);
    try {
        const sesion = await peticion("/api/admin/sesion");
        await mostrarAplicacion(sesion);
    } catch (_) {
        mostrarAcceso();
    }
    setInterval(() => {
        if (!document.hidden && estado.sesion) {
            actualizarTodo();
        }
    }, 6000);
}

iniciar();
