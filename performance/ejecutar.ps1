# Una prueba de rendimiento de Safemode con k6 (carga, estres o pico) contra el gateway DETRAS DEL BALANCEADOR, con Prometheus y Grafana.
# Corre en el prototipo de escalado (proyecto Compose 'safemode-escalado', aparte de tu stack normal): ver docs/rendimiento/informe-k6.md
#
#   powershell -ExecutionPolicy Bypass -File performance\ejecutar.ps1 -Tipo carga  -Replicas 2 -Etiqueta 2replicas
#   powershell -ExecutionPolicy Bypass -File performance\ejecutar.ps1 -Tipo estres -Replicas 1 -Etiqueta 1replica -Mantener
#   powershell -ExecutionPolicy Bypass -File performance\ejecutar.ps1 -Tipo estres -Replicas 2 -Etiqueta 2replicas-despues -AlertasPrevias 1028 -Mantener
#
# Estado inicial de la base: cada corrida VACIA los eventos y las alertas (y reinicia el gateway) y deja exactamente -AlertasPrevias alertas pendientes.
# Importa porque cada WebSocket nuevo recibe el snapshot con TODAS las alertas pendientes: si una corrida hereda las de la anterior, no son comparables.
# Las corridas de docs/rendimiento/resultados hechas antes de este parametro heredaron las de la anterior (el informe dice cuantas).
#
# Que hace: levanta el prototipo con las replicas pedidas (+ Prometheus y Grafana), crea una sesion de prueba en SU base, lanza el generador
# de retiros (como el tracker) y k6, y al terminar exporta el resumen de k6 y las graficas de lo que Prometheus guardo a docs/rendimiento/resultados.
# Requisitos: Docker en marcha, Python (se crea un entorno con matplotlib en %TEMP%), puertos 18080/18090/19090/13000 libres.
param(
    [ValidateSet('carga', 'estres', 'pico')][string]$Tipo = 'carga',
    [ValidateRange(1, 2)][int]$Replicas = 2,
    [string]$Etiqueta = '',
    [ValidateRange(0, 100000)][int]$AlertasPrevias = 0,   # alertas pendientes (sin revisar) con las que EMPIEZA la prueba; 0 = base vacia
    [switch]$Mantener
)
$ErrorActionPreference = 'Continue'
if (-not $Etiqueta) { $Etiqueta = "${Replicas}replicas" }
$aqui = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $aqui '..')).Path
$proyecto = 'safemode-escalado'
$env:PROMETHEUS_PORT = '19090'
$env:GRAFANA_PORT = '13000'
$compose = @('compose', '-p', $proyecto, '-f', 'docker-compose.yml', '-f', 'docker-compose.persistencia.yml', '-f', 'docker-compose.escalado.yml')
function Compose { Push-Location $repo; try { & docker @compose @args } finally { Pop-Location } }

# Pide a Windows que NO suspenda el equipo mientras corre la prueba (la suspension moderna congela Docker/WSL y deja un hueco en los datos: ya
# invalido dos corridas). Es una peticion de este proceso: se libera sola al terminar y no cambia ninguna configuracion del sistema.
# No evita que cierres la tapa o suspendas a mano.
try {
    Add-Type -Namespace Safemode -Name Energia -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint estado);' -ErrorAction Stop
    [void][Safemode.Energia]::SetThreadExecutionState([uint32]2147483649)   # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
} catch { Write-Host "Aviso: no pude pedir que el equipo no se suspenda ($($_.Exception.Message)). Mantenlo despierto a mano durante la prueba." }

# Duracion (s) de cada perfil: debe coincidir con safemode.js
$duraciones = @{ carga = 240; estres = 450; pico = 230 }
$segundos = $duraciones[$Tipo]
$salida = Join-Path $repo "docs\rendimiento\resultados\$Tipo-$Etiqueta"
New-Item -ItemType Directory -Force $salida | Out-Null

Write-Host "== Levantando el prototipo con $Replicas replica(s) del gateway (la primera vez construye la imagen: varios minutos)"
Compose up -d --build --scale "realtime-gateway=$Replicas" --wait --wait-timeout 300 postgres realtime-gateway balanceador blackbox prometheus grafana | Out-Null
# el balanceador conoce 2 destinos fijos; con 1 replica el segundo no existe y su vigilancia activa lo descarta
function Esperar-Replicas {
    $listo = $false
    for ($i = 0; $i -lt 60 -and -not $listo; $i++) {
        $vistas = @{}
        for ($k = 0; $k -lt 8; $k++) {
            try { $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/auth/me' -TimeoutSec 3 -ErrorAction Stop } catch { $r = $_.Exception.Response }
            if ($r -and $r.Headers) { $vistas[[string]$r.Headers['X-Served-By']] = 1 }
        }
        if ($vistas.Count -ge $Replicas) { $listo = $true } else { Start-Sleep -Seconds 2 }
    }
    if (-not $listo) { throw 'El balanceador no llego a las replicas esperadas en 2 minutos.' }
}
Esperar-Replicas

# Estado inicial de la base (ver arriba): vaciar, reiniciar el gateway (su motor de alertas y sus caches parten de cero) y dejar N alertas pendientes
$psql = @('exec', "$proyecto-postgres-1", 'psql', '-U', 'safemode', '-d', 'safemode', '-q', '-t', '-A')
& docker @psql -c 'TRUNCATE alert, object_presence_event RESTART IDENTITY CASCADE' | Out-Null
Compose restart realtime-gateway | Out-Null
Esperar-Replicas
if ($AlertasPrevias -gt 0) {
    Write-Host "== Dejando $AlertasPrevias alertas pendientes como estado inicial (el motor de alertas las crea de verdad a partir de eventos)"
    $semilla = (Get-Content (Join-Path $aqui 'k6\generar-alertas.sql') -Raw).Replace('__CUENTA__', "$AlertasPrevias").Replace('\watch i=0.5', '\watch i=0.05')
    $tmpSemilla = Join-Path $env:TEMP 'safemode-semilla-alertas.sql'
    Set-Content -Path $tmpSemilla -Value $semilla -Encoding ASCII
    docker cp $tmpSemilla "$proyecto-postgres-1:/tmp/semilla-alertas.sql" | Out-Null
    docker exec "$proyecto-postgres-1" psql -U safemode -d safemode -q -f /tmp/semilla-alertas.sql | Out-Null
    $hecho = 0
    for ($i = 0; $i -lt 120 -and $hecho -lt $AlertasPrevias; $i++) {
        $hecho = [int](& docker @psql -c 'SELECT count(*) FROM alert')
        if ($hecho -lt $AlertasPrevias) { Start-Sleep -Seconds 1 }
    }
    if ($hecho -lt $AlertasPrevias) { throw "Solo se crearon $hecho de $AlertasPrevias alertas previas en 2 minutos." }
}
$pendientes = (& docker @psql -c 'SELECT count(*) FROM alert WHERE NOT acknowledged')
Write-Host "== La prueba empieza con $pendientes alertas pendientes en la base"

# Sesion de prueba en la base DEL PROTOTIPO (la base guarda solo la huella SHA-256 del identificador)
$bytes = New-Object byte[] 32; (New-Object System.Security.Cryptography.RNGCryptoServiceProvider).GetBytes($bytes)
$id = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$huella = ([System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($id))) -replace '-', '').ToLower()
docker exec "$proyecto-postgres-1" psql -U safemode -d safemode -q -c "INSERT INTO operator_session (id_hash, email, display_name, expires_at) VALUES ('$huella', 'k6@example.com', 'k6', now() + interval '3 hours')" | Out-Null

# Generador de retiros (lo que hace el tracker): uno cada 0,5 s durante la prueba y un margen
$cuenta = [int](($segundos + 40) / 0.5)
$sql = (Get-Content (Join-Path $aqui 'k6\generar-alertas.sql') -Raw).Replace('__CUENTA__', "$cuenta")
$tmp = Join-Path $env:TEMP 'safemode-generar-alertas.sql'
Set-Content -Path $tmp -Value $sql -Encoding ASCII
docker cp $tmp "$proyecto-postgres-1:/tmp/generar-alertas.sql" | Out-Null
docker exec -d "$proyecto-postgres-1" psql -U safemode -d safemode -q -f /tmp/generar-alertas.sql | Out-Null

Write-Host "== k6: $Tipo, $Etiqueta (~$([math]::Round(($segundos + 40) / 60, 1)) min)"
$inicio = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
Set-Content -Path (Join-Path $salida "estado-inicial-$Tipo-$Etiqueta.txt") -Value "alertas pendientes al iniciar: $pendientes" -Encoding UTF8
docker run --rm --user 0:0 --network "${proyecto}_default" `
    -v "$aqui\k6:/scripts:ro" -v "${salida}:/results" `
    -e HTTP_URL=http://balanceador:8080 -e WS_URL=ws://balanceador:8090 -e "TIPO=$Tipo" -e "ETIQUETA=$Etiqueta" -e "SESION=$id" `
    -e K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write -e 'K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max' -e K6_PROMETHEUS_RW_PUSH_INTERVAL=5s `
    grafana/k6:0.57.0 run -o experimental-prometheus-rw /scripts/safemode.js 2>&1 | Tee-Object -FilePath (Join-Path $salida "k6-$Tipo-$Etiqueta.log")
$codigoK6 = $LASTEXITCODE
$fin = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
# Si el reloj de pared avanzo mucho mas que la duracion del perfil, el equipo se suspendio o se congelo a mitad de la prueba: los datos no valen.
$sobra = ($fin - $inicio) - ($segundos + 40)
if ($sobra -gt 90) {
    Write-Warning ("La prueba tardo $sobra s mas de lo previsto: el equipo se suspendio o se congelo. DESCARTA estos resultados y repite la prueba " +
        "(Windows registra las suspensiones: Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-Kernel-Power'; Id=506,507}).")
}

# parar el generador
docker exec "$proyecto-postgres-1" psql -U safemode -d safemode -q -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE 'INSERT INTO object_presence_event%' AND pid <> pg_backend_pid()" | Out-Null
Start-Sleep -Seconds 12   # Prometheus recoge los ultimos puntos

# alertas de Prometheus que se dispararon durante la prueba (la condicion de degradacion detectada por las reglas)
try {
    $alertas = (Invoke-RestMethod -Uri 'http://localhost:19090/api/v1/query?query=ALERTS%7Balertstate%3D%22firing%22%7D' -TimeoutSec 10).data.result
    $alertasDisparadas = Invoke-RestMethod -Uri "http://localhost:19090/api/v1/query_range?query=count%20by%20(alertname)(ALERTS%7Balertstate%3D%22firing%22%7D)&start=$inicio&end=$fin&step=5" -TimeoutSec 10
    $nombres = @($alertasDisparadas.data.result | ForEach-Object { $_.metric.alertname } | Sort-Object -Unique)
    ("Alertas que se dispararon durante la prueba: " + $(if ($nombres.Count) { $nombres -join ', ' } else { '(ninguna)' })) | Tee-Object -FilePath (Join-Path $salida "alertas-$Tipo-$Etiqueta.txt")
} catch { Write-Host "No se pudieron leer las alertas de Prometheus: $($_.Exception.Message)" }

# graficas y tabla (matplotlib en un entorno propio)
$venv = Join-Path $env:TEMP 'md-venv'
if (-not (Test-Path "$venv\Scripts\python.exe")) { python -m venv $venv | Out-Null }
& "$venv\Scripts\python.exe" -c "import matplotlib" 2>$null
if ($LASTEXITCODE -ne 0) { & "$venv\Scripts\python.exe" -m pip install --quiet matplotlib | Out-Null }
& "$venv\Scripts\python.exe" (Join-Path $aqui 'graficar.py') --prom http://localhost:19090 --inicio $inicio --fin $fin --tipo $Tipo --etiqueta $Etiqueta --salida $salida

if (-not $Mantener) {
    Write-Host 'Bajando el prototipo y borrando sus volumenes...'
    Compose down -v --remove-orphans 2>&1 | Out-Null
} else {
    Write-Host "Prototipo en marcha (-Mantener). Grafana: http://localhost:13000 (admin / lab-grafana, solo en tu equipo) · Prometheus: http://localhost:19090"
}
Write-Host "Resultados en $salida"
exit $codigoK6
