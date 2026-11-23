"""Grafica del articulo: estres con 2 replicas ANTES y DESPUES de la correccion, a partir de las tablas por escalon que genero performance/graficar.py
(docs/rendimiento/resultados/estres-2replicas*/estres-2replicas*-escalones.md). No inventa datos: lee esas tablas.

    python docs/articulo/graficar-comparacion.py
Salida: docs/articulo/figuras/estres-comparacion.pdf (vectorial, a ancho de columna IEEE)"""
import os
import re

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RESULTADOS = os.path.join(RAIZ, 'docs', 'rendimiento', 'resultados')
SALIDA = os.path.join(RAIZ, 'docs', 'articulo', 'figuras')


def leer(carpeta, nombre):
    ruta = os.path.join(RESULTADOS, carpeta, f'{nombre}-escalones.md')
    filas = []
    with open(ruta, encoding='utf-8') as fh:
        for linea in fh:
            celdas = [c.strip() for c in linea.strip().strip('|').split('|')]
            if celdas and re.fullmatch(r'\d+', celdas[0]):
                filas.append(celdas)
    return filas


def numero(texto):
    m = re.match(r'-?\d+(?:\.\d+)?', texto)
    return float(m.group(0)) if m else None


antes = leer('estres-2replicas', 'estres-2replicas')
despues = leer('estres-2replicas-despues', 'estres-2replicas-despues')
operadores = [int(f[0]) for f in antes]
# columnas de la tabla: 0 escalon, 1 req/s k6, 2 req/s servidor, 3 latencia media k6, ... 9 alertas entregadas/s (solo en las tablas nuevas)
rps_antes = [numero(f[1]) for f in antes]
rps_despues = [numero(f[1]) for f in despues]
lat_antes = [numero(f[3]) / 1000 for f in antes]
lat_despues = [numero(f[3]) / 1000 for f in despues]
alertas_antes = [numero(f[9]) for f in antes]
alertas_despues = [numero(f[9]) for f in despues]

plt.rcParams.update({'font.size': 7, 'axes.linewidth': 0.6, 'lines.linewidth': 1.2, 'lines.markersize': 3.2})
fig, ejes = plt.subplots(3, 1, figsize=(3.45, 4.6), sharex=True)
paneles = [
    (ejes[0], rps_antes, rps_despues, 'Solicitudes HTTP (req/s)'),
    (ejes[1], alertas_antes, alertas_despues, 'Alertas entregadas (/s)'),
    (ejes[2], lat_antes, lat_despues, 'Latencia media del cliente (s)'),
]
for ax, a, d, etiqueta in paneles:
    posiciones = list(range(len(operadores)))
    ax.plot(posiciones, a, 'o--', color='#b03a2e', label='antes')
    ax.plot(posiciones, d, 's-', color='#1f618d', label='después')
    ax.set_ylabel(etiqueta)
    ax.grid(alpha=.3, linewidth=.4)
    ax.set_xticks(posiciones)
    ax.set_xticklabels([str(o) for o in operadores])
ejes[0].legend(loc='upper left', frameon=False)
ejes[2].set_xlabel('operadores simultáneos (escalón de estrés)')
fig.tight_layout(pad=0.4)
os.makedirs(SALIDA, exist_ok=True)
destino = os.path.join(SALIDA, 'estres-comparacion.pdf')
fig.savefig(destino)
print('listo:', destino)
print('antes  req/s', rps_antes, '| alertas/s', alertas_antes)
print('despues req/s', rps_despues, '| alertas/s', alertas_despues)
