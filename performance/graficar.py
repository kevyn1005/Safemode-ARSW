"""Gráficas y tabla de una prueba k6 a partir de lo que Prometheus guardó (k6 manda sus métricas por remote write y Prometheus recoge cada
réplica del gateway). Uso:
    python performance/graficar.py --prom http://localhost:19090 --inicio EPOCH --fin EPOCH --tipo estres --etiqueta 2replicas --salida carpeta
Genera <salida>/<tipo>-<etiqueta>.png (8 paneles con el mismo eje de tiempo), <salida>/<tipo>-<etiqueta>-series.csv y, para estrés, la
tabla por escalón en <salida>/<tipo>-<etiqueta>-escalones.md.

Notas de método (importan para leer bien los números):
 1. k6 exporta sus percentiles y promedios ACUMULADOS desde el inicio de la prueba, no por ventana, y uno por ruta. Por eso aquí NO se grafica
    "el P95 de k6" (sería acumulado y no global): la latencia del cliente se calcula como PROMEDIO por ventana a partir de los contadores de k6
    (suma de duraciones y cantidad de solicitudes, que sí son aditivos). Los percentiles exactos de TODA la prueba salen del resumen de k6.
 2. El P95 del servidor sí es por ventana (histograma del gateway), pero el último bucket de ese histograma es 5 s: por encima, el cuantil se
    queda en 5000 ms ("tope"). Con la media por ventana se ve lo que el tope esconde.
 3. Las series de k6 se filtran por las etiquetas tipo/etiqueta de la prueba: Prometheus arrastra hasta 5 minutos el último valor de una
    corrida anterior y se mezclaría con la actual.
No se inventa ni se rellena ningún dato: los huecos se quedan como huecos."""
import argparse
import csv
import json
import os
import sys
import urllib.parse
import urllib.request

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402

PASO = 5          # s entre puntos de las consultas de rango
VENTANA = 15      # s de la ventana deslizante de los promedios
CLAVES = ('scenario', 'name', 'method', 'status', 'expected_response')   # identifican una serie de k6 dentro de una prueba


def _json(url):
    with urllib.request.urlopen(url, timeout=180) as r:
        datos = json.load(r)
    if datos.get('status') != 'success':
        raise RuntimeError(f'Prometheus rechazó la consulta: {datos}')
    return datos['data']['result']


def rango(prom, expr, inicio, fin):
    """[(metrica, [(t, valor)])] con los NaN descartados."""
    url = f"{prom}/api/v1/query_range?" + urllib.parse.urlencode({'query': expr, 'start': inicio, 'end': fin, 'step': PASO})
    salida = []
    for s in _json(url):
        puntos = []
        for t, v in s['values']:
            try:
                x = float(v)
            except ValueError:
                continue
            if x == x:
                puntos.append((float(t), x))
        salida.append((s['metric'], puntos))
    return salida


def instante(prom, expr, t):
    url = f"{prom}/api/v1/query?" + urllib.parse.urlencode({'query': expr, 'time': t})
    resultado = _json(url)
    if not resultado:
        return None
    try:
        x = float(resultado[0]['value'][1])
    except (ValueError, KeyError):
        return None
    return x if x == x else None


def etiqueta_de(metrica):
    return metrica.get('instance') or metrica.get('route') or 'total'


def clave(metrica):
    return tuple((k, metrica[k]) for k in CLAVES if k in metrica)


def suma_por_instante(lista):
    acumulado = {}
    for _, puntos in lista:
        for t, v in puntos:
            acumulado[t] = acumulado.get(t, 0.0) + v
    return sorted(acumulado.items())


def incrementos(promedios, cuentas, desde):
    """Aporte de cada instante (t -> [n, suma]) a partir de los contadores acumulados de k6.
    `promedios` y `cuentas` son series acumuladas (promedio en segundos y cantidad); suma = promedio * cantidad. Se trabaja con la diferencia
    entre puntos consecutivos, que sí es aditiva entre rutas y entre ventanas. Antes de `desde` solo se toma la base (una corrida anterior con
    las mismas etiquetas deja su último valor; un contador que baja es un reinicio y empieza de cero)."""
    por_clave = {clave(m): dict(p) for m, p in promedios}
    aporte = {}
    for metrica, puntos in cuentas:
        medias = por_clave.get(clave(metrica), {})
        previo_n = previo_s = None
        for t, n in puntos:
            if t not in medias:
                continue
            s = medias[t] * n
            if t < desde:
                previo_n, previo_s = n, s
                continue
            if previo_n is None:
                previo_n = previo_s = 0.0   # serie nueva: cuenta desde cero
            dn, ds = n - previo_n, s - previo_s
            if dn < 0:
                dn, ds = n, s               # reinicio del contador
            previo_n, previo_s = n, s
            a = aporte.setdefault(t, [0.0, 0.0])
            a[0] += dn
            a[1] += ds
    return aporte


def contador(series, desde):
    """Aporte por instante de un contador de k6 (t -> [n, 0]). Una serie que NACE durante la prueba (el primer error) cuenta desde cero:
    rate() no ve ese primer salto porque no tiene un punto anterior con que compararlo."""
    aporte = {}
    for _, puntos in series:
        previo = None
        for t, n in puntos:
            if t < desde:
                previo = n
                continue
            if previo is None:
                previo = 0.0
            d = n - previo if n >= previo else n   # un contador que baja es un reinicio
            previo = n
            if d:
                aporte.setdefault(t, [0.0, 0.0])[0] += d
    return aporte


def promedio_entre(aporte, a, b):
    """(promedio en ms, cantidad) de lo ocurrido en (a, b]."""
    n = sum(v[0] for t, v in aporte.items() if a < t <= b)
    s = sum(v[1] for t, v in aporte.items() if a < t <= b)
    return (1000.0 * s / n if n > 0 else None), n


def tasa_entre(aporte, a, b):
    n = sum(v[0] for t, v in aporte.items() if a < t <= b)
    return n / (b - a) if b > a else None


def deslizante(aporte, inicio, fin, ancho, minimo=3):
    """Serie (t, media_ms) con ventana de `ancho` s; si en la ventana hay menos de `minimo` muestras no se dibuja (ruido)."""
    puntos = []
    t = inicio + ancho
    while t <= fin:
        media, n = promedio_entre(aporte, t - ancho, t)
        if media is not None and n >= minimo:
            puntos.append((t, media))
        t += PASO
    return puntos


def deslizante_tasa(aporte, inicio, fin, ancho):
    puntos = []
    t = inicio + ancho
    while t <= fin:
        puntos.append((t, tasa_entre(aporte, t - ancho, t) or 0.0))
        t += PASO
    return puntos


def media_serie(puntos, a, b):
    xs = [v for t, v in puntos if a <= t < b]
    return sum(xs) / len(xs) if xs else None


def maximo_serie(puntos, a, b):
    xs = [v for t, v in puntos if a <= t < b]
    return max(xs) if xs else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--prom', default='http://localhost:19090')
    ap.add_argument('--inicio', type=float, required=True)
    ap.add_argument('--fin', type=float, required=True)
    ap.add_argument('--tipo', required=True)
    ap.add_argument('--etiqueta', required=True)
    ap.add_argument('--salida', required=True)
    a = ap.parse_args()
    os.makedirs(a.salida, exist_ok=True)
    i, f = a.inicio - 15, a.fin + 45
    sel = f'tipo="{a.tipo}",etiqueta="{a.etiqueta}"'

    def pedir(expr):
        try:
            return rango(a.prom, expr, i, f)
        except Exception as e:  # una métrica ausente no debe impedir graficar el resto
            print(f'[aviso] {expr[:60]}: {e}', file=sys.stderr)
            return []

    vus = pedir(f'sum(k6_vus{{{sel}}})')
    t0 = next((t for t, v in (vus[0][1] if vus else []) if v > 0 and t >= a.inicio - 5), a.inicio)

    # --- lo que cuenta k6 (aditivo): solicitudes y duración; alertas entregadas y su demora --------------------------------------------
    http = incrementos(pedir(f'k6_http_req_duration_avg{{{sel}}}'), pedir(f'k6_http_reqs_total{{{sel}}}'), t0 - 1)
    entrega_k6 = incrementos(pedir(f'k6_alerta_entrega_ms_avg{{{sel}}}'), pedir(f'k6_alertas_recibidas_total{{{sel}}}'), t0 - 1)
    t_fin = max([t for t, v in http.items() if v[0] > 0] or [a.fin]) + 25   # lo posterior es el ultimo valor que Prometheus arrastra hasta 5 min
    lat_k6 = deslizante(http, t0, t_fin, VENTANA)
    rps_k6 = deslizante_tasa(http, t0, t_fin, VENTANA)
    ent_k6 = deslizante(entrega_k6, t0, t_fin, 2 * VENTANA)

    # --- lo que mide el servidor (vivo durante toda la prueba) ----------------------------------------------------------------------------
    consultas = {
        'rps_backend': 'sum(rate(safemode_http_request_seconds_count[15s]))',
        'p95_backend_ms': '1000 * histogram_quantile(0.95, sum by (le) (rate(safemode_http_request_seconds_bucket[15s])))',   # tope 5000: último bucket = 5 s
        'media_backend_ms': '1000 * sum(rate(safemode_http_request_seconds_sum[15s])) / sum(rate(safemode_http_request_seconds_count[15s]))',
        'entrega_backend_p95_ms': '1000 * histogram_quantile(0.95, sum by (le) (rate(safemode_alert_delivery_seconds_bucket[30s])))',
        'cpu_nucleos': 'process_cpu_usage{job="gateway"} * on (instance, job) system_cpu_count{job="gateway"}',
        'cpus_maquina': 'max(system_cpu_count{job="gateway"})',
        'sockets': 'safemode_ws_sessions{job="gateway"}',
        'heap_mb': 'sum by (instance) (jvm_memory_used_bytes{job="gateway",area="heap"}) / 1048576',
    }
    datos = {nombre: [(etiqueta_de(m), p) for m, p in pedir(expr)] for nombre, expr in consultas.items()}
    ws_err = contador(pedir(f'k6_ws_errores_total{{{sel}}}'), t0 - 1)
    http_err = contador(pedir(f'k6_http_reqs_total{{{sel},status!~"2.."}}'), t0 - 1)   # solicitudes cuyo estado no es 2xx (0 = sin respuesta)
    datos['errores_ws_s'] = [('WebSocket', deslizante_tasa(ws_err, t0, t_fin, VENTANA))]
    datos['errores_http_s'] = [('HTTP', deslizante_tasa(http_err, t0, t_fin, VENTANA))]
    datos['vus'] = [('total', vus[0][1])] if vus else []
    datos['rps_k6'] = [('k6 (cliente)', rps_k6)]
    datos['latencia_media_k6_ms'] = [('k6', [(t, 1.0 * v) for t, v in lat_k6])]
    datos['entrega_media_k6_ms'] = [('k6', [(t, 1.0 * v) for t, v in ent_k6])]
    cpus = datos['cpus_maquina'][0][1][-1][1] if datos['cpus_maquina'] and datos['cpus_maquina'][0][1] else None

    base = os.path.join(a.salida, f'{a.tipo}-{a.etiqueta}')
    with open(base + '-series.csv', 'w', newline='', encoding='utf-8') as fh:
        w = csv.writer(fh)
        w.writerow(['metrica', 'serie', 't_epoch', 'valor'])
        for nombre, lista in datos.items():
            for etiqueta, puntos in lista:
                for t, v in puntos:
                    w.writerow([nombre, etiqueta, int(t), round(v, 4)])

    fig, ejes = plt.subplots(4, 2, figsize=(14, 15), sharex=True)
    fig.suptitle(f'Safemode · prueba de {a.tipo} · {a.etiqueta}', fontsize=14, fontweight='bold')

    def trazar(ax, titulo, unidad, lista, **kw):
        ax.set_title(titulo, fontsize=10, loc='left')
        ax.set_ylabel(unidad)
        ax.grid(alpha=.3)
        for etiqueta, puntos in lista:
            puntos = [(t, v) for t, v in puntos if t0 <= t <= t_fin]   # el CSV conserva todo; la figura va de que empieza la carga a que termina
            if puntos:
                x, y = [], []
                for k, (t, v) in enumerate(puntos):
                    if k and t - puntos[k - 1][0] > 2.5 * PASO:   # un tramo sin muestras se deja como hueco, no se une con una recta
                        x.append(float('nan'))
                        y.append(float('nan'))
                    x.append(t - t0)
                    y.append(v)
                ax.plot(x, y, label=etiqueta, linewidth=1.6, **kw)
        if len([1 for _, p in lista if p]) > 1:
            ax.legend(fontsize=7, loc='upper left')

    trazar(ejes[0][0], 'Usuarios virtuales (operadores simulados)', 'VUs', datos['vus'])
    trazar(ejes[0][1], 'Solicitudes por segundo (TPS de solicitudes)', 'req/s',
           [('k6 (cliente)', rps_k6), ('backend (gateway)', suma_por_instante(datos['rps_backend']))])
    trazar(ejes[1][0], 'Latencia HTTP: media por ventana de 15 s (k6) y servidor', 'ms',
           [('k6 · media (cliente, incluye la espera en cola)', lat_k6),
            ('servidor · media', datos['media_backend_ms'][0][1] if datos['media_backend_ms'] else []),
            ('servidor · P95 (tope 5000 = último bucket)', datos['p95_backend_ms'][0][1] if datos['p95_backend_ms'] else [])])
    trazar(ejes[1][1], 'Entrega de alertas (evento en la base → socket)', 'ms',
           [('k6 · media por ventana de 30 s (reloj del cliente)', ent_k6),
            ('servidor · P95 (ventana de 30 s)', datos['entrega_backend_p95_ms'][0][1] if datos['entrega_backend_p95_ms'] else [])])
    trazar(ejes[2][0], f'CPU del proceso del gateway, en núcleos (la máquina tiene {int(cpus) if cpus else "?"} CPUs lógicas)', 'núcleos',
           datos['cpu_nucleos'])
    trazar(ejes[2][1], 'WebSockets abiertos (por réplica)', 'sockets', datos['sockets'])
    trazar(ejes[3][0], 'Errores por segundo (cliente)', 'errores/s',
           [('WebSocket (cierres/errores)', datos['errores_ws_s'][0][1] if datos['errores_ws_s'] else []),
            ('HTTP (respuesta que no es 2xx o sin respuesta)', datos['errores_http_s'][0][1] if datos['errores_http_s'] else [])])
    trazar(ejes[3][1], 'Memoria heap de la JVM (por réplica)', 'MB', datos['heap_mb'])
    for ax in ejes[3]:
        ax.set_xlabel('segundos desde el inicio de la prueba')
    for ax in ejes.flat:
        ax.axvline(0, color='gray', linewidth=.6, linestyle=':')
        if a.tipo == 'pico':   # el salto: 60 s de base, 10 s de subida, 60 s en el pico
            ax.axvspan(60, 130, color='tab:red', alpha=.06)
    fig.tight_layout(rect=(0, 0, 1, .98))
    fig.savefig(base + '.png', dpi=110)
    plt.close(fig)

    # --- verificación cruzada contra el resumen de k6 (si está): la derivación debe reproducir su promedio ---------------------------------
    resumen = os.path.join(a.salida, f'k6-{a.tipo}-{a.etiqueta}-completo.json')
    if os.path.exists(resumen):
        try:
            with open(resumen, encoding='utf-8') as fh:
                k6 = json.load(fh)['metrics']
            media_k6 = k6['http_req_duration']['values']['avg']
            media_der, n_der = promedio_entre(http, t0 - 1, f)
            print(f'[verificación] latencia media HTTP: k6 {media_k6:.1f} ms · derivada de Prometheus {media_der:.1f} ms ({int(n_der)} solicitudes; '
                  f'k6 contó {int(k6["http_reqs"]["values"]["count"])})')
            if 'alerta_entrega_ms' in k6:
                e_k6 = k6['alerta_entrega_ms']['values']['avg']
                e_der, e_n = promedio_entre(entrega_k6, t0 - 1, f)
                print(f'[verificación] entrega de alertas, media: k6 {e_k6:.1f} ms · derivada {e_der:.1f} ms ({int(e_n)} entregas; '
                      f'k6 contó {int(k6["alertas_recibidas"]["values"]["count"])})')
            if 'ws_errores' in k6:
                derivados = sum(v[0] for v in ws_err.values())
                print(f'[verificación] errores de WebSocket: k6 {int(k6["ws_errores"]["values"]["count"])} · derivados {int(derivados)}')
        except Exception as e:  # la verificación es informativa
            print(f'[aviso] verificación cruzada: {e}', file=sys.stderr)

    # --- Estrés: tabla por escalón (60 s: 15 de subida + 45 de meseta; se mide solo la meseta) ---------------------------------------------
    if a.tipo == 'estres':
        objetivos = [25, 50, 100, 200, 300, 400, 600]
        filas = ['| Escalón (VUs) | req/s k6 | req/s servidor | Latencia media k6 (ms) | Media servidor (ms) | P95 servidor (ms)* | Errores HTTP | '
                 'Errores WS (/s) | CPU máx. (núcleos) | Alertas entregadas (/s) | Entrega media k6 (ms) | Entrega P95 servidor (ms) |',
                 '|---|---|---|---|---|---|---|---|---|---|---|---|']

        def fmt(x, d=0):
            return '—' if x is None else f'{x:.{d}f}'

        rps_be = suma_por_instante(datos['rps_backend'])
        for n, objetivo in enumerate(objetivos):
            a0 = t0 + n * 60 + 20   # desde 5 s después de llegar al escalón
            b0 = t0 + (n + 1) * 60
            cpu_pico = max([maximo_serie(p, a0, b0) or 0 for _, p in datos['cpu_nucleos']] or [0])
            lat, _ = promedio_entre(http, a0, b0)
            ent, n_ent = promedio_entre(entrega_k6, a0, b0)
            tasa_ent = tasa_entre(entrega_k6, a0, b0)
            total = tasa_entre(http, a0, b0)
            p95 = instante(a.prom, f'1000 * histogram_quantile(0.95, sum by (le) (increase(safemode_http_request_seconds_bucket[{b0 - a0:.0f}s])))', b0)
            ent_srv = instante(a.prom, f'1000 * histogram_quantile(0.95, sum by (le) (increase(safemode_alert_delivery_seconds_bucket[{b0 - a0:.0f}s])))', b0)
            media_srv = instante(a.prom, f'1000 * sum(increase(safemode_http_request_seconds_sum[{b0 - a0:.0f}s])) / '
                                         f'sum(increase(safemode_http_request_seconds_count[{b0 - a0:.0f}s]))', b0)
            errores_http = (tasa_entre(http_err, a0, b0) or 0.0) / total if total else None
            filas.append(f"| {objetivo} | {fmt(total, 1)} | {fmt(media_serie(rps_be, a0, b0), 1)} | {fmt(lat)} | {fmt(media_srv)} | {fmt(p95)} | "
                         f"{fmt(None if errores_http is None else 100 * errores_http, 2)} % | {fmt(tasa_entre(ws_err, a0, b0), 1)} | {fmt(cpu_pico, 1)} | "
                         f"{fmt(tasa_ent, 1)} | {'sin entregas' if n_ent == 0 else fmt(ent)} | {fmt(ent_srv)} |")
        filas += ['',
                  '\\* El histograma del servidor termina en 5 s: un valor de 5000 significa «5 s o más» (la media a su lado muestra lo que el tope esconde). '
                  'La latencia media de k6 es la del cliente (incluye la espera antes de que el servidor atienda). Cada escalón mide solo su meseta '
                  '(se descartan los primeros 5 s tras la subida).']
        with open(base + '-escalones.md', 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(filas) + '\n')
        print('\n'.join(filas))
    print('listo:', base + '.png')


if __name__ == '__main__':
    main()
