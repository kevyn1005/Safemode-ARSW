# Verificacion del despliegue publico de Safemode: solo peticiones de LECTURA, como las de cualquier visitante (no inicia sesion, no manda datos,
# no prueba claves, no genera carga). Sirve para comprobar que todo quedo bien tras un despliegue o tras encender la instancia.
#
#   powershell -ExecutionPolicy Bypass -File docs\despliegue\verificar-despliegue.ps1
#   powershell -ExecutionPolicy Bypass -File docs\despliegue\verificar-despliegue.ps1 -Dominio otro.dominio.org
#
# Cada linea dice OK o FALLA y por que importa. Termina con codigo 1 si algo falla.
param([string]$Dominio = 'safemode-arsw.duckdns.org')
$ErrorActionPreference = 'Continue'
$fallas = 0

function Peticion([string]$ruta) {
    # HttpWebRequest en vez de Invoke-WebRequest: con las redirecciones desactivadas devuelve el codigo real (302), no una excepcion sin respuesta
    try {
        $req = [System.Net.HttpWebRequest]::Create("https://$Dominio$ruta")
        $req.AllowAutoRedirect = $false
        $req.Timeout = 20000
        $req.UserAgent = 'safemode-verificar/1.0'
        try { $resp = $req.GetResponse() } catch [System.Net.WebException] { $resp = $_.Exception.Response; if (-not $resp) { throw } }
        $resultado = [pscustomobject]@{ Codigo = [int]$resp.StatusCode; Cabeceras = $resp.Headers; Destino = $resp.Headers['Location'] }
        $resp.Close()
        return $resultado
    } catch {
        return [pscustomobject]@{ Codigo = 0; Cabeceras = @{}; Destino = $_.Exception.Message }
    }
}

function Comprobar([string]$nombre, [bool]$ok, [string]$detalle, [string]$porQue) {
    if ($ok) { Write-Host ("  [OK]    {0} -> {1}" -f $nombre, $detalle) }
    else { Write-Host ("  [FALLA] {0} -> {1}   ({2})" -f $nombre, $detalle, $porQue); $script:fallas++ }
}

Write-Host "Verificando https://$Dominio  ($(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))"

# 1) DNS: a que IP apunta y si es una instancia EC2 (tras reiniciar la instancia la IP publica cambia y DuckDNS debe actualizarse)
try {
    $ip = ([System.Net.Dns]::GetHostAddresses($Dominio) | Where-Object { $_.AddressFamily -eq 'InterNetwork' } | Select-Object -First 1).IPAddressToString
    $inverso = try { ([System.Net.Dns]::GetHostEntry($ip)).HostName } catch { '(sin nombre inverso)' }
    Comprobar 'DNS resuelve' ($null -ne $ip) "$ip  $inverso" 'el dominio no resuelve: ¿DuckDNS apunta a la IP actual de la instancia?'
} catch { Comprobar 'DNS resuelve' $false $_.Exception.Message 'el dominio no resuelve' }

# 2) El sitio responde por HTTPS con Caddy y el certificado es valido (Invoke-WebRequest falla si no lo es)
$raiz = Peticion '/'
Comprobar 'La pagina del panel responde (200)' ($raiz.Codigo -eq 200) "HTTP $($raiz.Codigo)" 'el gateway o Caddy no estan sirviendo el panel'
Comprobar 'Quien responde es Caddy' ("$($raiz.Cabeceras['Server'])" -eq 'Caddy') "Server: $($raiz.Cabeceras['Server'])" 'otro servidor responde en ese dominio'

# 3) La sesion se exige donde debe: sin cookie, 401 (si dieran 200 o redirigieran, la proteccion no esta activa)
$sesion = Peticion '/auth/me'
Comprobar '/auth/me sin sesion da 401' ($sesion.Codigo -eq 401) "HTTP $($sesion.Codigo)" 'el login de Google no esta protegiendo la API'
$reporte = Peticion '/report'
Comprobar '/report sin sesion no entrega datos' ($reporte.Codigo -in 401, 403) "HTTP $($reporte.Codigo)" 'el reporte de eventos esta abierto'
$grafana = Peticion '/grafana/'
Comprobar '/grafana/ sin sesion da 401 (regla forward_auth de Caddy)' ($grafana.Codigo -eq 401) ("HTTP $($grafana.Codigo)" + $(if ($grafana.Destino) { " -> $($grafana.Destino)" } else { '' })) `
    'si da 302 a /grafana/login, Caddy sigue con la configuracion anterior: ejecutar "docker compose restart caddy" en la instancia'
$frames = Peticion '/frames'   # sin sesion, la regla forward_auth responde 401 antes de llegar al receptor (con o sin cabeceras de WebSocket)
Comprobar '/frames sin sesion da 401 (nadie puede inyectar imagenes al tracker)' ($frames.Codigo -eq 401) "HTTP $($frames.Codigo)" `
    'el canal de frames esta abierto: revisar el bloque /frames del Caddyfile y reiniciar Caddy'

# 4) Cabeceras de seguridad basicas del panel
Comprobar 'X-Content-Type-Options: nosniff' ("$($raiz.Cabeceras['X-Content-Type-Options'])" -eq 'nosniff') "$($raiz.Cabeceras['X-Content-Type-Options'])" 'falta la cabecera'

if ($fallas -eq 0) { Write-Host "`nRESULTADO: OK, todo lo comprobable sin iniciar sesion esta bien." }
else { Write-Host "`nRESULTADO: FALLA ($fallas comprobacion(es))."; exit 1 }
Write-Host "Falta comprobar A MANO (requiere sesion): entrar con Google, ver el panel, recibir una alerta, abrir Grafana con usuario y clave."
