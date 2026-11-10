# Abre el servidor en esta consola para que su clave de acceso se vea únicamente aquí.
# El entorno se prepara antes con Instalar-Servidor.ps1.
$ErrorActionPreference = 'Stop'
$pythonEntorno = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonEntorno -PathType Leaf)) {
    throw 'Primero ejecuta Instalar-Servidor.ps1 en esta carpeta.'
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & $pythonEntorno -m agenda_pc.inicio
    if ($LASTEXITCODE -ne 0) {
        throw 'El servidor se detuvo por un error. Revisa el mensaje anterior.'
    }
}
finally {
    Pop-Location
}
