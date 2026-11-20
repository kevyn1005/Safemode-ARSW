"""Exporta a PNG las vistas ArchiMate del documento de arquitectura (vistas 4 a 16 de docs/diagramas/safemode-arquitectura.archimate)
usando Archi en linea de comandos (no abre la interfaz). Las imagenes quedan en docs/arquitectura/figuras/ con los nombres que usa el LaTeX.

    python docs/arquitectura/exportar-vistas.py                       (Archi en C:\Program Files\Archi\Archi.exe)
    python docs/arquitectura/exportar-vistas.py --archi "D:\Archi\Archi.exe"

Si editas una vista en Archi (guardas el .archimate), vuelve a correr esto y a compilar el PDF (docs/arquitectura/compilar.ps1)."""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
MODELO = os.path.join(RAIZ, 'docs', 'diagramas', 'safemode-arquitectura.archimate')
SALIDA = os.path.join(RAIZ, 'docs', 'arquitectura', 'figuras')
NOMBRES = {4: 'contexto', 5: 'componentes-flujo', 6: 'componentes-puesto', 7: 'clases-deteccion', 8: 'clases-persistencia', 9: 'clases-gateway',
           10: 'entidad-relacion', 11: 'secuencia-alerta', 12: 'secuencia-login', 13: 'despliegue-actual', 14: 'despliegue-objetivo',
           15: 'actividades-retiro', 16: 'actividades-alerta'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--archi', default=r'C:\Program Files\Archi\Archi.exe')
    a = ap.parse_args()
    tmp = tempfile.mkdtemp(prefix='archi-informe-')
    r = subprocess.run([a.archi, '-application', 'com.archimatetool.commandline.app', '-consoleLog', '-nosplash', '--loadModel', MODELO,
                        '--html.createReport', tmp], capture_output=True, text=True, timeout=300)
    if 'Report generated' not in r.stdout:
        print(r.stdout[-1500:], r.stderr[-1500:])
        sys.exit('Archi no pudo generar el informe (revisa la ruta de Archi.exe)')
    carpeta = next(os.path.join(d) for d, _, _ in os.walk(tmp) if os.path.basename(d) == 'images')
    texto = open(MODELO, encoding='utf-8').read()
    os.makedirs(SALIDA, exist_ok=True)
    hechas = 0
    for m in re.finditer(r'<element xsi:type="archimate:ArchimateDiagramModel" name="(\d+) \u00b7 [^"]*" id="(id-[0-9a-f]+)"', texto):
        n = int(m.group(1))
        if n in NOMBRES and os.path.exists(os.path.join(carpeta, m.group(2) + '.png')):
            destino = os.path.join(SALIDA, f'{n:02d}-{NOMBRES[n]}.png')
            shutil.copy(os.path.join(carpeta, m.group(2) + '.png'), destino)
            hechas += 1
    shutil.rmtree(tmp, ignore_errors=True)
    print(hechas, 'vistas exportadas a', SALIDA)


if __name__ == '__main__':
    main()
