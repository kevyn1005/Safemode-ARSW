# Analisis de calidad de Safemode con un SonarQube LOCAL (la misma configuracion que usa SonarCloud en el pipeline: sonar-project.properties).
# Sirve para ver las calificaciones (fiabilidad, seguridad, mantenibilidad), el control de calidad y la cobertura SIN esperar al pipeline.
#
# Una sola vez (lo haces tu: son credenciales de TU servidor local):
#   1. docker compose -p safemode-sonar -f docker-compose.sonar.yml up -d      (el primer arranque tarda unos 2 minutos)
#   2. Abrir http://localhost:9000, entrar con admin / admin (pide cambiar la clave) y crear un token:
#      Mi cuenta > Seguridad > Generar token (tipo "Global analysis token" o "User token").
#   3. $env:SONAR_TOKEN = "<el token>"      (solo en esta terminal; el token no se escribe en ningun archivo)
#
# Cada vez:
#   powershell -ExecutionPolicy Bypass -File docs\mantenibilidad\analizar-local.ps1            # usa las coberturas que ya existen
#   powershell -ExecutionPolicy Bypass -File docs\mantenibilidad\analizar-local.ps1 -Medir     # antes corre las pruebas (mvn verify + npm test:coverage)
param(
    [string]$Url = 'http://localhost:9000',
    [string]$Proyecto = 'safemode',
    [switch]$Medir
)
$ErrorActionPreference = 'Stop'
if (-not $env:SONAR_TOKEN) { throw 'Falta $env:SONAR_TOKEN (ver las instrucciones al inicio de este archivo).' }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

if ($Medir) {
    Write-Host '== Pruebas del backend con cobertura (mvn verify)'
    Push-Location $repo; try { mvn -B -q verify; if ($LASTEXITCODE -ne 0) { throw 'mvn verify fallo' } } finally { Pop-Location }
    Write-Host '== Pruebas del panel con cobertura (c8)'
    Push-Location (Join-Path $repo 'security-dashboard'); try { npm run test:coverage; if ($LASTEXITCODE -ne 0) { throw 'las pruebas del panel fallaron' } } finally { Pop-Location }
}
foreach ($f in 'security-dashboard\coverage\lcov.info', 'realtime-gateway\target\site\jacoco\jacoco.xml') {
    if (-not (Test-Path (Join-Path $repo $f))) { throw "No existe ${f}: corre con -Medir para generar la cobertura." }
}

Write-Host "== Analizando con sonar-scanner (el control de calidad decide el codigo de salida)"
# host.docker.internal: el scanner corre en un contenedor y el servidor esta en tu equipo
docker run --rm -e SONAR_TOKEN -e SONAR_HOST_URL="http://host.docker.internal:9000" -v "${repo}:/usr/src" sonarsource/sonar-scanner-cli `
    "-Dsonar.projectKey=$Proyecto" "-Dsonar.qualitygate.wait=true" "-Dsonar.qualitygate.timeout=300"
$codigo = $LASTEXITCODE

Write-Host "`n== Resultado en $Url"
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($env:SONAR_TOKEN):")) }
$estado = (Invoke-RestMethod -Headers $auth -Uri "$Url/api/qualitygates/project_status?projectKey=$Proyecto").projectStatus
"Control de calidad: $($estado.status)"
$estado.conditions | ForEach-Object { "  {0,-34} {1,-6} (valor {2}, umbral {3})" -f $_.metricKey, $_.status, $_.actualValue, $_.errorThreshold }
$metricas = 'coverage,line_coverage,bugs,vulnerabilities,code_smells,security_hotspots,duplicated_lines_density,ncloc,reliability_rating,security_rating,sqale_rating'
$medidas = (Invoke-RestMethod -Headers $auth -Uri "$Url/api/measures/component?component=$Proyecto&metricKeys=$metricas").component.measures
$letras = @{ '1.0' = 'A'; '2.0' = 'B'; '3.0' = 'C'; '4.0' = 'D'; '5.0' = 'E' }
"`nMedidas:"
$medidas | ForEach-Object {
    $v = $_.value
    if ($_.metric -like '*_rating') { $v = $letras[$v] }
    "  {0,-26} {1}" -f $_.metric, $v
}
exit $codigo
