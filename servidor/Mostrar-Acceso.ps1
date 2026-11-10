# Abre una ventana local para consultar el acceso del panel sin publicar la clave en el chat.
# La clave pertenece al servidor de esta PC y se lee desde su carpeta privada de Windows.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$rutaConfiguracion = Join-Path $env:LOCALAPPDATA 'AgendaVIPServidor\configuracion.json'
if (-not (Test-Path -LiteralPath $rutaConfiguracion -PathType Leaf)) {
    [void][Windows.Forms.MessageBox]::Show(
        'Primero inicia el servidor para crear su acceso.',
        'Agenda.VIP'
    )
    exit
}

$datosAcceso = [IO.File]::ReadAllText($rutaConfiguracion) | ConvertFrom-Json
$ventana = New-Object Windows.Forms.Form
$ventana.Text = 'Agenda.VIP - Acceso al panel de tu PC'
$ventana.ClientSize = New-Object Drawing.Size(590, 280)
$ventana.StartPosition = 'CenterScreen'
$ventana.FormBorderStyle = 'FixedDialog'
$ventana.MaximizeBox = $false
$ventana.Font = New-Object Drawing.Font('Segoe UI', 10)
$ventana.BackColor = [Drawing.Color]::FromArgb(250, 247, 240)

$titulo = New-Object Windows.Forms.Label
$titulo.Text = 'Esta es la clave de administrador del panel'
$titulo.Location = New-Object Drawing.Point(20, 20)
$titulo.Size = New-Object Drawing.Size(550, 28)
$titulo.Font = New-Object Drawing.Font('Segoe UI', 12, [Drawing.FontStyle]::Bold)
$ventana.Controls.Add($titulo)

$ayuda = New-Object Windows.Forms.Label
$ayuda.Text = 'Abre el panel en esta PC. Usa la clave de abajo para entrar.'
$ayuda.Location = New-Object Drawing.Point(20, 56)
$ayuda.Size = New-Object Drawing.Size(550, 25)
$ventana.Controls.Add($ayuda)

$campoClave = New-Object Windows.Forms.TextBox
$campoClave.Text = [string]$datosAcceso.clave_administrador
$campoClave.ReadOnly = $true
$campoClave.UseSystemPasswordChar = $true
$campoClave.Location = New-Object Drawing.Point(20, 95)
$campoClave.Size = New-Object Drawing.Size(430, 28)
$ventana.Controls.Add($campoClave)

# El botón solo copia la clave cuando la persona lo decide desde esta ventana.
$copiar = New-Object Windows.Forms.Button
$copiar.Text = 'Copiar clave'
$copiar.Location = New-Object Drawing.Point(460, 91)
$copiar.Size = New-Object Drawing.Size(110, 34)
$copiar.Add_Click({
    [Windows.Forms.Clipboard]::SetText($campoClave.Text)
    $estado.Text = 'Clave copiada. Pégala en el inicio de sesión del panel.'
})
$ventana.Controls.Add($copiar)

# La casilla cambia la visibilidad del texto. La app nunca manda esta clave al celular.
$mostrar = New-Object Windows.Forms.CheckBox
$mostrar.Text = 'Mostrar clave'
$mostrar.Location = New-Object Drawing.Point(20, 135)
$mostrar.Size = New-Object Drawing.Size(160, 25)
$mostrar.Add_CheckedChanged({
    $campoClave.UseSystemPasswordChar = -not $mostrar.Checked
})
$ventana.Controls.Add($mostrar)

$abrir = New-Object Windows.Forms.Button
$abrir.Text = 'Abrir panel'
$abrir.Location = New-Object Drawing.Point(20, 175)
$abrir.Size = New-Object Drawing.Size(160, 36)
$abrir.Add_Click({
    Start-Process 'https://localhost:8443/'
})
$ventana.Controls.Add($abrir)

$estado = New-Object Windows.Forms.Label
$estado.Text = 'La clave se mantiene guardada en esta computadora.'
$estado.Location = New-Object Drawing.Point(20, 230)
$estado.Size = New-Object Drawing.Size(550, 40)
$ventana.Controls.Add($estado)

try {
    [void]$ventana.ShowDialog()
} finally {
    $campoClave.Clear()
    $ventana.Dispose()
}
