"""Pruebas de aceptacion del escenario de persistencia de Safemode, con resultados JSON. Solo datos ficticios.

Solo usa la biblioteca estandar de Python (corre en la imagen python:3.12-alpine, sin instalar nada).
Modos: smoke | reset | baseline | acceptance | redis-down | redis-up | authority-down  (ver docs/persistencia/README.md)
"""
import argparse
import json
import os
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

BASE = os.getenv('BASE_URL', 'http://localhost:8088')
KEY = os.getenv('LAB_ADMIN_KEY', 'lab-admin')
PUESTO = 'puesto-1'
results = []


class Reply:
    def __init__(self, status, text, ms):
        self.status_code = status
        self.text = text
        self.ms = ms

    def json(self):
        return json.loads(self.text)


def request(method, path, body=None, operator='ana', admin=False):
    headers = {'X-Operator': operator, 'Content-Type': 'application/json'}
    if admin:
        headers['X-Lab-Key'] = KEY
    data = None if body is None else json.dumps(body).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status, text = response.status, response.read().decode('utf-8')
    except urllib.error.HTTPError as error:       # 403, 409, 503...: son respuestas, no excepciones de la prueba
        status, text = error.code, error.read().decode('utf-8')
    return Reply(status, text, round((time.perf_counter() - started) * 1000))


def require(reply, status=200):
    if reply.status_code != status:
        raise RuntimeError(f'HTTP {reply.status_code} (se esperaba {status}): {reply.text[:200]}')
    return reply.json()


def record(name, ok, evidence):
    results.append({'test': name, 'passed': bool(ok), 'evidence': evidence})


def reset():
    require(request('POST', '/api/lab/reset', {}, admin=True))


def smoke():
    health = require(request('GET', '/api/health'))
    require(request('GET', '/access/health'))
    doc = require(request('GET', f'/api/puestos/{PUESTO}'))
    record('arranque', doc['version'] >= 1, {'decisions': health.get('decisions'), 'puesto': doc})


def conflict():
    """Dos operadores guardan sobre la MISMA version: debe pasar uno (200) y el otro recibir 409."""
    reset()
    before = require(request('GET', f'/api/puestos/{PUESTO}'))

    def edit(operator):
        return request('PUT', f'/api/puestos/{PUESTO}',
                       {'title': 'Cambio de ' + operator, 'expectedVersion': before['version']}, operator)

    with ThreadPoolExecutor(2) as pool:
        replies = list(pool.map(edit, ['ana', 'luis']))
    after = require(request('GET', f'/api/puestos/{PUESTO}'))
    statuses = sorted(r.status_code for r in replies)
    record('ediciones_concurrentes', statuses == [200, 409] and after['version'] == before['version'] + 1,
           {'statuses': statuses, 'before': before, 'after': after,
            'respuesta_409': next((r.json() for r in replies if r.status_code == 409), None)})


def revoke():
    """Luis ve su permiso en cache (true); se le revoca; una escritura NUEVA debe rechazarse con 403 aunque la cache diga true."""
    reset()
    prime = require(request('GET', f'/api/permissions/{PUESTO}', operator='luis'))
    require(request('PUT', f'/access/permissions/{PUESTO}/luis', {'canConfigure': False}, admin=True))
    try:
        stale = require(request('GET', f'/api/permissions/{PUESTO}', operator='luis'))
        write = request('PUT', f'/api/puestos/{PUESTO}', {'title': 'Cambio prohibido', 'expectedVersion': 1}, 'luis')
        final = require(request('GET', f'/api/puestos/{PUESTO}'))
        record('permiso_revocado',
               prime['canConfigure'] and write.status_code == 403 and final['version'] == 1,
               {'cache_antes': prime, 'pantalla_tras_revocar': stale, 'write_status': write.status_code, 'after': final})
    finally:
        require(request('PUT', f'/access/permissions/{PUESTO}/luis', {'canConfigure': True}, admin=True))


def expiry():
    """La presencia caduca a los 20 s sin renovacion."""
    reset()
    first = require(request('POST', f'/api/presence/{PUESTO}'))
    initial = require(request('GET', f'/api/presence/{PUESTO}'))
    time.sleep(22)
    later = require(request('GET', f'/api/presence/{PUESTO}'))
    record('presencia_expira',
           first['available'] and initial['available'] and 'ana' in initial['operators']
           and later['available'] and 'ana' not in later['operators'],
           {'registro': first, 'antes': initial, 'despues_de_22s': later})


def redis_down():
    """Redis debe estar detenido antes de invocar esta prueba (no hace reset). Se degrada la presencia; la edicion sigue."""
    doc = require(request('GET', f'/api/puestos/{PUESTO}'))
    edit = request('PUT', f'/api/puestos/{PUESTO}', {'title': 'Edicion sin Redis', 'expectedVersion': doc['version']})
    presence = request('POST', f'/api/presence/{PUESTO}')
    available = presence.json().get('available') if presence.status_code == 200 else None
    record('degradacion_redis', edit.status_code == 200 and presence.status_code == 200 and available is False,
           {'edit_status': edit.status_code, 'edit_ms': edit.ms, 'presence_status': presence.status_code,
            'presence_ms': presence.ms, 'presence': presence.text})


def redis_up():
    """Redis ya restaurado (dc start redis): la presencia debe volver a registrarse. Tras una falla el servicio degrada al instante
    durante una ventana corta (2 s) antes de volver a intentar, asi que se acepta hasta 8 s y se mide cuanto tarda."""
    started = time.perf_counter()
    attempts, presence, available = 0, None, None
    while attempts < 8:
        attempts += 1
        presence = request('POST', f'/api/presence/{PUESTO}')
        available = presence.json().get('available') if presence.status_code == 200 else None
        if available is True:
            break
        time.sleep(1)
    listed = request('GET', f'/api/presence/{PUESTO}')
    record('recuperacion_redis', presence.status_code == 200 and available is True,
           {'intentos': attempts, 'segundos_hasta_recuperar': round(time.perf_counter() - started, 1),
            'presence_status': presence.status_code, 'presence': presence.text, 'lista': listed.text})


def authority_down():
    """Gestion de acceso debe estar detenida y el permiso de Ana cacheado antes de invocar esta prueba."""
    doc = require(request('GET', f'/api/puestos/{PUESTO}'))
    edit = request('PUT', f'/api/puestos/{PUESTO}', {'title': 'Sin autoridad', 'expectedVersion': doc['version']})
    record('autoridad_no_disponible', edit.status_code == 503,
           {'edit_status': edit.status_code, 'edit_ms': edit.ms, 'body': edit.text})


parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['baseline', 'acceptance', 'smoke', 'reset', 'redis-down', 'redis-up', 'authority-down'])
args = parser.parse_args()
try:
    if args.mode in ('baseline', 'acceptance'):
        smoke(); conflict(); revoke(); expiry()
    elif args.mode == 'smoke':
        smoke()
    elif args.mode == 'reset':
        reset(); record('reset', True, {})
    elif args.mode == 'redis-down':
        redis_down()
    elif args.mode == 'redis-up':
        redis_up()
    else:
        authority_down()
except Exception as exc:                          # un error de arranque o de ejecucion NUNCA se esconde
    record('ejecucion', False, {'error': str(exc)})

report = {'mode': args.mode, 'utc': datetime.now(timezone.utc).isoformat(), 'results': results}
Path('results').mkdir(exist_ok=True)
name = f"{args.mode}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')}.json"
Path('results', name).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(report, indent=2, ensure_ascii=False))
print('Resultado guardado en results/' + name)
# baseline registra los defectos esperados (passed:false) y sale con 0; solo un error de arranque o de ejecucion da 1
failed = [x for x in results if not x['passed']]
if args.mode == 'baseline':
    failed = [x for x in failed if x['test'] in ('arranque', 'ejecucion')]
raise SystemExit(1 if failed else 0)
