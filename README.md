# Agenda.VIP — Android + API local

**Aplicación de productividad para Android con panel de administración desde Windows.**

Agenda.VIP permite organizar tareas y rutinas, configurar recordatorios hablados y administrar actividades desde una computadora conectada a la misma red. Es un proyecto personal de **Nowel Báez**, desarrollado para resolver necesidades cotidianas de planificación.

## Tecnologías
- **Android:** Kotlin, Android SDK, almacenamiento local, alarmas y autenticación biométrica.
- **Backend local:** Python, FastAPI y SQLite.
- **Panel web:** HTML, CSS y JavaScript.
- **Integraciones:** vinculación mediante QR, intercambio XML y transcripción local opcional con whisper.cpp.

## Funcionalidades principales
- Tareas únicas y recurrentes, seguimiento e historial.
- Recordatorios con voz y opciones de posponer o completar.
- Sesiones de enfoque, avisos de uso y rutina de descanso.
- Cuentas locales, biometría y exportación cifrada de respaldos.
- Panel para Windows: consultar tareas, importar cambios XML y solicitar respaldos.

## Arquitectura resumida
```text
Android (Kotlin)
  ├─ Tareas, alarmas, rutinas y almacenamiento local
  ├─ Autenticación y respaldos
  └─ Vinculación QR / sincronización local
            ↕ red local
Servidor (Python + FastAPI + SQLite)
            ↕
Panel web (HTML + CSS + JavaScript)
```

## Ejecución
**Android:** abrir el repositorio en Android Studio. Se utilizan JDK 17, Android SDK 36, NDK 28.2.13676358 y CMake 3.22.1. Sincronizar Gradle y ejecutar el módulo `app` en un dispositivo o emulador compatible (API 26 o posterior).

**Panel Windows:** con Python 3.12 o posterior, ejecutar en PowerShell desde la raíz:
```powershell
.\servidor\Instalar-Servidor.ps1
.\servidor\Iniciar-Servidor.ps1
```
Abrir la dirección HTTPS que muestre la consola y vincular desde **Configuración → Mi computadora** en Android.

## Estado del proyecto
Proyecto en desarrollo. Las alarmas en pantalla bloqueada, el reinicio del dispositivo y algunas mejoras recientes necesitan validación adicional en equipos físicos. La transcripción requiere importar un modelo multilingüe compatible desde la aplicación.

## Para reclutadores
Este proyecto muestra trabajo en **desarrollo Android, APIs REST locales, persistencia de datos, integración entre dispositivos y diseño de funcionalidades orientadas al usuario**. El repositorio contiene el código fuente y las instrucciones para ejecutarlo.

**Autor:** Nowel Báez · [Portafolio](https://nowelbaez2017-ux.github.io/) · [GitHub](https://github.com/nowelbaez2017-ux)

## Créditos
Se utilizan [whisper.cpp (MIT)](app/src/main/assets/licencias/whisper.txt) y [ZXing Android Embedded (Apache 2.0)](app/src/main/assets/licencias/zxing_android_embedded.txt). Las dependencias Python están en [requirements.txt](servidor/requirements.txt).
