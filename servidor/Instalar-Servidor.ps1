# Prepara un entorno propio de Python para las bibliotecas del servidor.
# Nowel ejecuta este archivo una vez. No compila ni abre la aplicación de Android.
$ErrorActionPreference = 'Stop'
$entorno = Join-Path $PSScriptRoot '.venv'
$pythonEntorno = Join-Path $entorno 'Scripts\python.exe'
$dependencias = Join-Path $PSScriptRoot 'requirements.txt'

if (-not (Test-Path -LiteralPath $pythonEntorno -PathType Leaf)) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 -m venv $entorno
    }
    elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python -m venv $entorno
    }
    else {
        throw 'Instala Python 3.12 o posterior y vuelve a ejecutar este archivo.'
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'No se pudo crear el entorno de Python del servidor.'
    }
}

& $pythonEntorno -m pip install -r $dependencias
if ($LASTEXITCODE -ne 0) {
    throw 'No terminó la instalación. Revisa la conexión de Internet y el mensaje anterior.'
}

# La carpeta de claves pertenece al usuario actual de Windows y al sistema.
# Los códigos, contraseñas y copias reales quedan fuera de la carpeta del proyecto.
$carpetaDatos = Join-Path $env:LOCALAPPDATA 'AgendaVIPServidor'
[void][IO.Directory]::CreateDirectory($carpetaDatos)
$identidad = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$permisoUsuario = '*' + $identidad + ':(OI)(CI)F'
& icacls.exe $carpetaDatos '/inheritance:r' '/grant:r' $permisoUsuario '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'No se pudo proteger la carpeta privada del servidor. Revisa sus permisos en Windows.'
}

Write-Host 'Servidor preparado. Ejecuta Iniciar-Servidor.ps1 para abrir el panel.'
