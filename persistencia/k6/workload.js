// Carga de LECTURA sobre los servicios de persistencia. Solo lee, a proposito: no mezcla la carga con las pruebas de
// concurrencia (esas son check.py acceptance). Por eso k6 NO demuestra consistencia: comprueba estructura y version valida.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://console';
const type = __ENV.TYPE || 'carga';
const stages = {
  carga: [{duration: '20s', target: 5}, {duration: '60s', target: 5}, {duration: '20s', target: 0}],
  estres: [{duration: '20s', target: 10}, {duration: '30s', target: 30}, {duration: '30s', target: 60}, {duration: '20s', target: 0}],
  pico: [{duration: '20s', target: 2}, {duration: '5s', target: 40}, {duration: '20s', target: 40}, {duration: '5s', target: 2}, {duration: '20s', target: 2}],
  resistencia: [{duration: '20s', target: 5}, {duration: '5m', target: 5}, {duration: '20s', target: 0}]
};
if (!stages[type]) throw new Error('TYPE debe ser carga, estres, pico o resistencia');

const semanticErrors = new Counter('semantic_errors');
export const options = {
  stages: stages[type],
  // umbrales didacticos (hipotesis de clase), no resultados garantizados
  thresholds: {http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<500'], checks: ['rate>0.99'], semantic_errors: ['count==0']}
};

export default function () {
  // 1) la configuracion del puesto (MongoDB): estructura y version validas
  const r = http.get(`${BASE}/api/puestos/puesto-1`, {tags: {name: 'leer_puesto'}});
  let data; try { data = r.json(); } catch { data = null; }
  const valid = !!data && data.id === 'puesto-1' && Number.isInteger(data.version) && data.version >= 1
    && Array.isArray(data.cameras) && !!data.preferences;
  check(r, {'puesto HTTP 200': x => x.status === 200, 'puesto: estructura y version validas': () => valid});
  semanticErrors.add(valid ? 0 : 1);

  // 2) el permiso para pintar la pantalla (cache de Redis, con la autoridad detras en el primer fallo)
  const p = http.get(`${BASE}/api/permissions/puesto-1`, {headers: {'X-Operator': 'luis'}, tags: {name: 'consultar_permiso'}});
  check(p, {'permiso HTTP 200': x => x.status === 200});

  // 3) quien esta en linea (Redis)
  const o = http.get(`${BASE}/api/presence/puesto-1`, {tags: {name: 'consultar_presencia'}});
  check(o, {'presencia HTTP 200': x => x.status === 200});
  sleep(0.2);
}

export function handleSummary(data) {
  return {
    [`/results/k6-${type}-summary.json`]: JSON.stringify(data, null, 2),
    stdout: JSON.stringify({
      prueba: type,
      P95_ms: data.metrics.http_req_duration.values['p(95)'],
      fallos: data.metrics.http_req_failed.values.rate,
      nota: 'Umbrales didacticos; justificar resultados segun recursos.'
    }, null, 2) + '\n'
  };
}
