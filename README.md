# Agenda.VIP

Agenda.VIP es una agenda personal para Android creada por **Nowel Baez**. Nació para reunir recordatorios, rutinas y sesiones de concentración en un solo lugar. La agenda funciona con datos locales y puede vincularse a un panel de Windows en la misma red.

## Qué puedes hacer

- **Organizar el día.** Crear tareas únicas o recurrentes y consultar el progreso en Inicio o el historial por día, semana o mes.
- **Configurar recordatorios.** Elegir voz del teléfono, música local y seguimientos automáticos. Puedes posponer, completar o detener los seguimientos de una fecha.
- **Cuidar el tiempo.** Programar sesiones de Enfoque, recibir avisos por límites de uso y configurar una rutina de Descanso. Cada función pide los permisos que utiliza.
- **Proteger la agenda.** Crear cuentas locales, activar el acceso biométrico si el teléfono lo admite y exportar respaldos cifrados.
- **Administrar tareas desde la PC.** Vincular el teléfono por QR, consultar tareas, revisar e importar cambios desde XML y solicitar respaldos. El panel distingue los cambios pendientes de los que confirma el teléfono.

## Abrir la app

1. Abre la carpeta del repositorio en Android Studio.
2. Usa **JDK 17**, Android SDK **36**, NDK **28.2.13676358** y CMake **3.22.1**. El módulo Android admite **API 26** o posterior en ARM64 y x86_64.
3. Sincroniza Gradle y ejecuta el módulo `app` en un teléfono o emulador compatible. La primera sincronización y compilación descargan las dependencias.

Para escuchar los recordatorios hablados, selecciona una voz en español instalada en Android. La transcripción opcional de Enfoque usa Whisper y requiere importar un modelo multilingüe, como `ggml-base.bin`, desde la app.

## Abrir el panel en Windows

Con **Python 3.12 o posterior**, abre PowerShell en la raíz del proyecto y ejecuta estos comandos:

```powershell
.\servidor\Instalar-Servidor.ps1
.\servidor\Iniciar-Servidor.ps1
```

Abre la dirección HTTPS que muestra la consola. Para vincular el teléfono, usa **Configuración → Mi computadora** y escanea el QR desde la misma red. El panel conserva sus datos en `%LOCALAPPDATA%\AgendaVIPServidor`. El instalador prepara el entorno local de Python y el servidor genera sus claves cuando las necesita.

## Estructura del proyecto

| Carpeta | Qué contiene |
| --- | --- |
| `app/src/main/kotlin` | Pantallas, datos locales, reglas de la agenda, alarmas y seguridad. |
| `app/src/main/res` | Diseños XML, textos, iconos y estilos de Android. |
| `app/src/main/cpp` | Puente entre Kotlin y whisper.cpp para transcripción local. |
| `servidor/agenda_pc` | API local con Python, FastAPI y SQLite. |
| `servidor/panel` | Interfaz web en HTML, CSS y JavaScript. |

## Estado y autoría

El código incluye verificaciones para el emulador en [`app/src/androidTest`](app/src/androidTest). La entrega de alarmas con pantalla bloqueada y después de reiniciar depende de los permisos y del ahorro de batería de Android. Queda pendiente comprobar las últimas mejoras de arranque y seguimiento en el teléfono.

**Creada por Nowel Baez.** La transcripción utiliza [whisper.cpp (MIT)](app/src/main/assets/licencias/whisper.txt) y el lector QR utiliza [ZXing Android Embedded (Apache 2.0)](app/src/main/assets/licencias/zxing_android_embedded.txt). Las dependencias del servidor se declaran en [`requirements.txt`](servidor/requirements.txt).
