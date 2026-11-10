# Nowel puede ejecutarlo como administrador si Firewall impide conectar el teléfono.
# Las reglas se limitan al puerto del servidor, la red Privada y la subred local.
param(
    [switch]$PermitirDescubrimiento
)

$ErrorActionPreference = 'Stop'
$pythonEntorno = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.venv\Scripts\python.exe'))
if (-not (Test-Path -LiteralPath $pythonEntorno -PathType Leaf)) {
    throw 'Primero ejecuta Instalar-Servidor.ps1.'
}

$reglas = @(@{ Nombre = 'AgendaVIP-Servidor-HTTPS'; Puerto = 8443; Protocolo = 'TCP' })
if ($PermitirDescubrimiento) {
    # Solo esta opción añade UDP 5353 para buscar la PC. Escribir la dirección no lo necesita.
    $reglas += @{ Nombre = 'AgendaVIP-Servidor-Descubrimiento'; Puerto = 5353; Protocolo = 'UDP' }
}

foreach ($regla in $reglas) {
    if (-not (Get-NetFirewallRule -Name $regla.Nombre -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule `
            -Name $regla.Nombre `
            -DisplayName $regla.Nombre `
            -Direction Inbound `
            -Action Allow `
            -Profile Private `
            -RemoteAddress LocalSubnet `
            -Protocol $regla.Protocolo `
            -LocalPort $regla.Puerto | Out-Null
    }
}

Write-Host 'Acceso preparado para la red Privada y la misma subred. El panel conserva su clave.'
