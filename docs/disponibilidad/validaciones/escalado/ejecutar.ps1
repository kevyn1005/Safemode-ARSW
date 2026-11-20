# Validacion ejecutable del escalado horizontal del gateway (docs/disponibilidad/): dos replicas detras de un balanceador Caddy, con
# PostgreSQL real. Corre APARTE de tu stack normal (proyecto Compose 'safemode-escalado', puertos 18080/18090, su propia base).
#
#   powershell -ExecutionPolicy Bypass -File docs\disponibilidad\validaciones\escalado\ejecutar.ps1 [-Salida evidencia.txt] [-Mantener]
#
# Que hace: levanta el prototipo (si no esta), crea UNA sesion de prueba en la base del prototipo, corre ScaleProbe.java (reparto,
# sesiones compartidas, alerta a todos los operadores, caida de una replica) y, salvo -Mantener, lo baja y borra su volumen.
# Requisitos: Docker en marcha, Java 17+ en el PATH, Maven con gson en ~/.m2 y los puertos 18080/18090 libres.
param([string]$Salida = '', [switch]$Mantener)
$ErrorActionPreference = 'Continue'   # un comando nativo que escribe en stderr no debe abortar en PowerShell 5.1
$aqui = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $aqui '..\..\..\..')).Path
$proyecto = 'safemode-escalado'
$compose = @('compose', '-p', $proyecto, '-f', 'docker-compose.yml', '-f', 'docker-compose.persistencia.yml', '-f', 'docker-compose.escalado.yml')

function Compose { Push-Location $repo; try { & docker @compose @args } finally { Pop-Location } }
function Puerto-Libre([int]$p) { -not (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue) }

$yaArriba = (docker ps --filter "name=$proyecto-balanceador" --format '{{.Names}}') -ne $null -and (docker ps --filter "name=$proyecto-balanceador" --format '{{.Names}}')
if (-not $yaArriba) {
    foreach ($p in 18080, 18090) { if (-not (Puerto-Libre $p)) { throw "El puerto $p esta ocupado; liberalo y repite." } }
    Write-Host 'Levantando el prototipo (la primera vez construye la imagen y tarda unos minutos)...'
    Compose up -d --build --scale realtime-gateway=2 --wait --wait-timeout 300 postgres realtime-gateway balanceador | Out-Null
}

# Esperar a que el balanceador conozca a las dos replicas (su vigilancia activa corre cada 2 s)
$listo = $false
for ($i = 0; $i -lt 60 -and -not $listo; $i++) {
    $vistas = @{}
    for ($k = 0; $k -lt 6; $k++) {
        try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/auth/me' -TimeoutSec 3 -ErrorAction Stop } catch { $r = $_.Exception.Response }
        if ($r -and $r.Headers) { $vistas[[string]$r.Headers['X-Served-By']] = 1 }
    }
    if ($vistas.Count -ge 2) { $listo = $true } else { Start-Sleep -Seconds 2 }
}
if (-not $listo) { throw 'El balanceador no llego a las dos replicas en 2 minutos; mira: docker logs ' + "$proyecto-balanceador-1" }

# Arranque en frio: las dos replicas arrancan A LA VEZ sobre una base vacia (ambas ejecutan CREATE TABLE IF NOT EXISTS). Si una hubiera
# fallado y Docker la hubiera reiniciado, el contador de reinicios no seria 0: se deja en la evidencia.
$reinicios = (docker inspect -f '{{.Name}} reinicios={{.RestartCount}}' "$proyecto-realtime-gateway-1" "$proyecto-realtime-gateway-2") -join '; '

# Una sesion de prueba en la base DEL PROTOTIPO: la base guarda solo la huella SHA-256 del identificador (JdbcSessionStore)
$bytes = New-Object byte[] 32; (New-Object System.Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
$id = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$huella = ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($id))) -replace '-', '').ToLower()
$sql = "INSERT INTO operator_session (id_hash, email, display_name, expires_at) VALUES ('$huella', 'prueba@example.com', 'Operador de prueba', now() + interval '1 hour')"
docker exec "$proyecto-postgres-1" psql -U safemode -d safemode -q -c $sql | Out-Null

# gson (lo trae el gateway en ~/.m2)
$m2 = Join-Path $env:USERPROFILE '.m2\repository\com\google\code\gson\gson'
$gson = Get-ChildItem $m2 -Recurse -Filter 'gson-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object FullName | Select-Object -Last 1
if (-not $gson) { throw "Falta gson en $m2 (corre 'mvn -pl realtime-gateway -am compile' con red una vez)." }

$salidaSonda = Join-Path $env:TEMP 'safemode-escalado-sonda.txt'
Write-Host 'Corriendo el sondeo (dura ~1 minuto)...'
$inicio = Get-Date
& java -cp $gson.FullName (Join-Path $aqui 'ScaleProbe.java') 'http://localhost:18080' 'ws://localhost:18090' "safemode_session=$id" $proyecto 2>&1 | Tee-Object -FilePath $salidaSonda
$codigo = $LASTEXITCODE

# Errores en los registros de las replicas durante la prueba (honestidad: no solo lo que salio bien)
$errores = @()
foreach ($n in 1, 2) {
    $errores += docker logs "$proyecto-realtime-gateway-$n" --since $inicio.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') 2>&1 |
        Where-Object { $_ -match 'Exception|ERROR|No se pudo|fallo' } | ForEach-Object { "replica-${n}: $_" }
}
$commit = (git -C $repo rev-parse --short HEAD)
$cabecera = @("Validacion del escalado horizontal del gateway - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "commit base: $commit (con cambios sin commitear: $((git -C $repo status --porcelain | Measure-Object).Count) archivos)   Caddy: caddy:2.8-alpine   PostgreSQL: 16.6   Java: $((java -version 2>&1 | Select-Object -First 1))",
    "Arranque en frio de las dos replicas a la vez, antes de las pruebas: $reinicios",
    "Entorno: Docker Desktop en UN solo equipo (16 CPU logicas); las dos replicas comparten host: esto valida el COMPORTAMIENTO, no la separacion fisica.", '')
$pie = @('', "Lineas de error/aviso en los registros de las replicas durante la prueba: $($errores.Count)") + ($errores | Select-Object -First 15)
if ($Salida) { ($cabecera + (Get-Content $salidaSonda) + $pie) | Set-Content -Encoding UTF8 (Join-Path $repo $Salida); Write-Host "Evidencia guardada en $Salida" }
$pie | ForEach-Object { Write-Host $_ }

if (-not $Mantener) {
    Write-Host 'Bajando el prototipo y borrando su volumen...'
    Compose down -v --remove-orphans 2>&1 | Out-Null
} else {
    Write-Host "Prototipo en marcha (-Mantener): http://localhost:18080 ; para bajarlo: docker compose -p $proyecto -f docker-compose.yml -f docker-compose.persistencia.yml -f docker-compose.escalado.yml down -v"
}
exit $codigo
