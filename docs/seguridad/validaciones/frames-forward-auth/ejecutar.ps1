# Validacion ejecutable del escenario ES-3 (integridad del canal /frames): Caddy 2.8 con forward_auth delante del receptor REAL de
# frames del tracker (BrowserFrameSource). Todo en loopback; no toca ningun servidor remoto. Ver README.md de esta carpeta.
#
#   powershell -ExecutionPolicy Bypass -File docs\seguridad\validaciones\frames-forward-auth\ejecutar.ps1
#
# Requisitos: Docker en marcha (imagen caddy:2.8-alpine; se descarga sola), Java 17+ y Node en el PATH, y Maven con las dependencias en ~/.m2.
param([string]$Salida = '')
# 'Continue': en PowerShell 5.1 un comando nativo que escribe en stderr (p. ej. "docker rm" de un contenedor que no existe) se volveria
# un error fatal con 'Stop'; los fallos reales se detectan con las comprobaciones y los throw de abajo.
$ErrorActionPreference = 'Continue'
$aqui = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $aqui '..\..\..\..')).Path
$trabajo = Join-Path $env:TEMP 'safemode-frames-forward-auth'
New-Item -ItemType Directory -Force $trabajo | Out-Null
$contenedor = 'safemode-caddy-frames-test'
$procesos = @()

function Puerto-Libre([int]$p) { -not (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue) }
foreach ($p in 18080, 18081, 18082, 18091) { if (-not (Puerto-Libre $p)) { throw "El puerto $p esta ocupado; liberalo y repite." } }

# 1) receptor de frames real (camera-feed-service compilado) y sus dependencias
$clases = Join-Path $repo 'camera-feed-service\target\classes'
if (-not (Test-Path (Join-Path $clases 'com\safemode\camerafeed\BrowserFrameSource.class'))) {
    Write-Host 'Compilando camera-feed-service...'
    Push-Location $repo; try { mvn -o -q -pl camera-feed-service -am compile | Out-Null } finally { Pop-Location }
}
$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$jars = @(
    (Join-Path $m2 'org\java-websocket\Java-WebSocket\1.5.6\Java-WebSocket-1.5.6.jar'),
    (Get-ChildItem (Join-Path $m2 'org\slf4j\slf4j-api') -Recurse -Filter 'slf4j-api-*.jar' | Sort-Object FullName | Select-Object -Last 1).FullName,
    (Join-Path $m2 'org\slf4j\slf4j-nop\2.0.13\slf4j-nop-2.0.13.jar'))
foreach ($j in $jars) { if (-not (Test-Path $j)) { throw "Falta $j en ~/.m2 (corre 'mvn -pl camera-feed-service -am compile' con red una vez)." } }
$cp = (@($clases) + $jars) -join ';'   # @(...) + arreglo: una lista plana (un arreglo anidado se uniria con espacios)

try {
    Remove-Item (Join-Path $trabajo '*') -ErrorAction SilentlyContinue
    # 2) servicio que imita /auth/me del gateway (200 solo con la cookie buena) y el receptor real
    $procesos += Start-Process node -ArgumentList "`"$aqui\stub-auth.js`"", "`"$trabajo\stub.log`"" -WindowStyle Hidden -PassThru
    $procesos += Start-Process node -ArgumentList "`"$aqui\open-upstream.js`"", "`"$trabajo\grafana.log`"" -WindowStyle Hidden -PassThru
    $procesos += Start-Process java -ArgumentList '-cp', "`"$cp`"", "`"$aqui\FrameServer.java`"", '90' `
        -RedirectStandardOutput "$trabajo\receptor.out" -RedirectStandardError "$trabajo\receptor.err" -WindowStyle Hidden -PassThru
    for ($i = 0; $i -lt 40; $i++) { if (-not (Puerto-Libre 18091) -and -not (Puerto-Libre 18081)) { break }; Start-Sleep -Seconds 1 }
    if (Puerto-Libre 18091) { throw 'El receptor de frames no arranco; mira ' + "$trabajo\receptor.err" }

    # 3) Caddy 2.8 (la version del despliegue) con la regla forward_auth
    docker rm -f $contenedor 2>&1 | Out-Null
    docker run -d --name $contenedor --add-host host.docker.internal:host-gateway -p 127.0.0.1:18080:18080 `
        -v "$aqui\Caddyfile.prueba:/etc/caddy/Caddyfile:ro" caddy:2.8-alpine | Out-Null
    for ($i = 0; $i -lt 30; $i++) { if (-not (Puerto-Libre 18080)) { break }; Start-Sleep -Seconds 1 }
    if (Puerto-Libre 18080) { throw 'Caddy no arranco: ' + (docker logs $contenedor 2>&1 | Select-Object -Last 3) }

    # 4) escenarios
    $lineas = java "$aqui\CaddyClient.java" 2>&1 | Select-String -Pattern '^[A-K]\)' | ForEach-Object { $_.Line }
    Start-Sleep -Seconds 1
    $recibidos = @(Get-Content "$trabajo\receptor.out" | Select-String 'FRAME RECIBIDO' | ForEach-Object { $_.Line })

    $grafanaLlegaron = @(Get-Content "$trabajo\grafana.log" -ErrorAction SilentlyContinue | ForEach-Object { $_ })

    function Linea([string]$letra) { $lineas | Where-Object { $_ -like "$letra)*" } | Select-Object -First 1 }
    $comprobaciones = @(
        @{ Nombre = 'A  atacante sin Origin ni cookie: bloqueado (401)';            Ok = (Linea 'A') -match 'BLOQUEADO por Caddy, HTTP 401' },
        @{ Nombre = 'B  atacante con Origin falsificado: bloqueado (401)';          Ok = (Linea 'B') -match 'BLOQUEADO por Caddy, HTTP 401' },
        @{ Nombre = 'C  atacante con cookie inventada: bloqueado (401)';            Ok = (Linea 'C') -match 'BLOQUEADO por Caddy, HTTP 401' },
        @{ Nombre = 'D  operador con sesion: conecta (101) y manda frames';         Ok = (Linea 'D') -match 'CONECTO' },
        @{ Nombre = 'E  control sin forward_auth (como estaba): el atacante pasa';  Ok = (Linea 'E') -match 'CONECTO' },
        @{ Nombre = 'F  atacante contra el gateway real como autenticador: 401';    Ok = (Linea 'F') -match 'BLOQUEADO por Caddy, HTTP 401' },
        @{ Nombre = 'G  al receptor llegaron solo frames del operador y del control';
           Ok = (($recibidos -join "`n") -match 'VERDE') -and (($recibidos -join "`n") -match 'AZUL') -and (($recibidos -join "`n") -notmatch 'ROJO') },
        @{ Nombre = 'H  sin cookie no entra a Grafana (401)';                       Ok = (Linea 'H') -match 'HTTP 401' },
        @{ Nombre = 'I  con cookie inventada no entra a Grafana (401)';             Ok = (Linea 'I') -match 'HTTP 401' },
        @{ Nombre = 'J  operador con sesion: llega a Grafana (200)';                Ok = (Linea 'J') -match 'HTTP 200 llego a Grafana' },
        @{ Nombre = 'K  control sin forward_auth (como estaba): el atacante entra'; Ok = (Linea 'K') -match 'HTTP 200 llego a Grafana' },
        @{ Nombre = 'L  a Grafana solo llegaron las peticiones del operador y del control (las bloqueadas no llegaron)';
           Ok = ($grafanaLlegaron.Count -eq 2) -and (($grafanaLlegaron -join "`n") -match '"url":"/grafana/","tieneCookie":true') -and (($grafanaLlegaron -join "`n") -match 'grafana-open') -and (($grafanaLlegaron -join "`n") -notmatch 'api/health') })

    $salidaTexto = @()
    $salidaTexto += "Validacion ES-3 (integridad de /frames) y acceso a /grafana - " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    $salidaTexto += "commit: " + (git -C $repo rev-parse --short HEAD) + "   Caddy: caddy:2.8-alpine   Java: " + ((java -version 2>&1 | Select-Object -First 1))
    $salidaTexto += ''
    $salidaTexto += 'Escenarios (cliente -> Caddy):'
    $salidaTexto += $lineas
    $salidaTexto += ''
    $salidaTexto += 'Frames que llegaron al receptor real del tracker:'
    $salidaTexto += ($recibidos | ForEach-Object { '  ' + $_ })
    $salidaTexto += ''
    $salidaTexto += 'Peticiones que llegaron al "Grafana" de la prueba (las bloqueadas por Caddy no deben aparecer):'
    $salidaTexto += ($grafanaLlegaron | ForEach-Object { '  ' + $_ })
    $salidaTexto += ''
    $salidaTexto += 'Comprobaciones:'
    foreach ($c in $comprobaciones) { $salidaTexto += ('  [{0}] {1}' -f $(if ($c.Ok) { 'OK' } else { 'FALLA' }), $c.Nombre) }
    $fallas = @($comprobaciones | Where-Object { -not $_.Ok }).Count
    $salidaTexto += ''
    $salidaTexto += $(if ($fallas -eq 0) { "RESULTADO: OK ($($comprobaciones.Count)/$($comprobaciones.Count))" } else { "RESULTADO: FALLA ($fallas de $($comprobaciones.Count))" })
    $salidaTexto | ForEach-Object { Write-Host $_ }
    if ($Salida) { $salidaTexto | Set-Content -Path $Salida -Encoding UTF8; Write-Host "`nEvidencia guardada en $Salida" }
    if ($fallas -ne 0) { exit 1 }
}
finally {
    docker rm -f $contenedor 2>&1 | Out-Null
    foreach ($p in $procesos) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
}
