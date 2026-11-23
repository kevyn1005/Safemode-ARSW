# Compila los documentos LaTeX del Release 2 con Docker (no hace falta instalar TeX Live):
#   powershell -ExecutionPolicy Bypass -File docs\arquitectura\compilar.ps1                      # el documento de arquitectura
#   powershell -ExecutionPolicy Bypass -File docs\arquitectura\compilar.ps1 -Documento articulo  # el articulo IEEE (docs\articulo)
# La primera vez construye la imagen safemode-latex (TeX Live + IEEEtran, unos minutos); despues tarda segundos.
param([ValidateSet('arquitectura', 'articulo')][string]$Documento = 'arquitectura')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$carpeta = Join-Path $repo "docs\$Documento"
$principal = if ($Documento -eq 'articulo') { 'articulo.tex' } else { 'safemode-arquitectura.tex' }
if (-not (docker images -q safemode-latex)) {
    docker build -t safemode-latex -f (Join-Path $PSScriptRoot 'Dockerfile.latex') $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo construir la imagen safemode-latex' }
}
# la version del codigo que describe el documento (la demo y los documentos deben reflejar la MISMA version)
# (el ultimo commit que toco algo FUERA de docs/: el documento no puede llevar su propio commit)
$hash = (git -C $repo log -1 --format=%h -- . ':(exclude)docs' ':(exclude)README.md' ':(exclude).gitignore').Trim()
Set-Content -Path (Join-Path $carpeta 'version.tex') -Value "\newcommand{\versionrepo}{$hash}" -Encoding ASCII
# se monta la carpeta docs completa: el documento incluye graficas de docs\rendimiento
$docs = Join-Path $repo 'docs'
docker run --rm -v "${docs}:/work" -w "/work/$Documento" safemode-latex latexmk -pdf -interaction=nonstopmode -halt-on-error $principal
if ($LASTEXITCODE -ne 0) { throw "latexmk fallo: revisa $carpeta\$([IO.Path]::GetFileNameWithoutExtension($principal)).log" }
Write-Host "PDF: $carpeta\$([IO.Path]::GetFileNameWithoutExtension($principal)).pdf"
