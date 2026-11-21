// Pruebas de rendimiento de Safemode con k6: carga, estres y pico (spike), contra el gateway DETRAS DEL BALANCEADOR.
// Informe y metodologia: docs/rendimiento/informe-k6.md. Ejecucion: performance/ejecutar.ps1
//
// FLUJO OBJETIVO (uso real del sistema): un puesto de vigilancia con N operadores que tienen el panel abierto.
//   - Cada operador mantiene UN WebSocket (alertas en tiempo real) y, mientras trabaja, consulta su sesion, las camaras y el reporte.
//   - El tracker registra retiros sospechosos; el motor crea la alerta y el gateway la empuja a TODOS los sockets (fan-out).
//     Los retiros los inserta un generador aparte en PostgreSQL (generar-alertas.sql), igual que el tracker real: k6 no escribe en bases.
//
// "SOLICITUD" = una peticion HTTP (auth/me, cameras, report, pagina).  "TRANSACCION DE NEGOCIO" = una alerta ENTREGADA a un operador:
// desde que se inserta el retiro en la base hasta que llega por su WebSocket (alerta_entrega_ms). El requisito del proyecto es < 5 s.
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const HTTP = __ENV.HTTP_URL || 'http://balanceador:8080';
const WS_URL = __ENV.WS_URL || 'ws://balanceador:8090';
const TIPO = __ENV.TIPO || 'carga';
const ETIQUETA = __ENV.ETIQUETA || 'prueba';
const ORIGIN = 'http://localhost:8080';   // el unico origen que el gateway acepta de un navegador
const THINK = Number(__ENV.THINK || 1);   // segundos entre iteraciones de un operador activo
const HOLD_WS = Number(__ENV.HOLD_WS || 30); // un socket se mantiene 30 s y se reabre (reconexiones normales del panel)
if (!__ENV.SESION) {
  throw new Error('Falta SESION: el valor de la cookie safemode_session de una sesion de prueba (lo crea performance/ejecutar.ps1)');
}
const COOKIE = `safemode_session=${__ENV.SESION}`;

// ---- Perfiles (usuarios virtuales = operadores) -------------------------------------------------------------------------
// carga:  la operacion esperada de un puesto (20 operadores activos y 20 sockets), 4 minutos.
// estres: escalones de 60 s (15 s de subida + 45 s de meseta) hasta degradar el servicio.
// pico:   base baja, salto brusco (10 s) a 10 veces la base, meseta corta y regreso; se mide la recuperacion.
const PERFILES = {
  carga: {
    http: [{ duration: '30s', target: 20 }, { duration: '3m', target: 20 }, { duration: '30s', target: 0 }],
    ws: [{ duration: '30s', target: 20 }, { duration: '3m', target: 20 }, { duration: '30s', target: 0 }],
  },
  estres: {
    http: [25, 50, 100, 200, 300, 400, 600].flatMap((t) => [{ duration: '15s', target: t }, { duration: '45s', target: t }]).concat([{ duration: '30s', target: 0 }]),
    ws: [25, 50, 100, 200, 300, 400, 600].flatMap((t) => [{ duration: '15s', target: t }, { duration: '45s', target: t }]).concat([{ duration: '30s', target: 0 }]),
  },
  pico: {
    http: [{ duration: '60s', target: 20 }, { duration: '10s', target: 300 }, { duration: '60s', target: 300 }, { duration: '10s', target: 20 }, { duration: '90s', target: 20 }],
    ws: [{ duration: '60s', target: 20 }, { duration: '10s', target: 300 }, { duration: '60s', target: 300 }, { duration: '10s', target: 20 }, { duration: '90s', target: 20 }],
  },
};
if (!PERFILES[TIPO]) {
  throw new Error('TIPO debe ser carga, estres o pico');
}

// ---- Umbrales de aceptacion (explicitos; el informe registra los resultados REALES, no solo estas metas) ---------------------
const UMBRALES = {
  carga: {
    http_req_failed: ['rate<0.01'],
    // capacidad de procesamiento: 20 operadores generan ~58 req/s cuando el sistema responde rapido (3 peticiones + 1 s de pausa por vuelta); si el
    // promedio de la prueba (incluye las subidas y bajadas) cae de 40 req/s, el sistema esta frenando a sus usuarios. Se agrego DESPUES de la primera
    // corrida; el informe la evalua tambien sobre los resultados anteriores con el TPS de su resumen.
    http_reqs: ['rate>=40'],
    http_req_duration: ['p(95)<500', 'p(99)<1500'],
    'http_req_duration{name:auth_me}': ['p(95)<300'],
    'http_req_duration{name:report}': ['p(95)<800'],
    alerta_entrega_ms: ['p(95)<2000', 'p(99)<5000'],
    ws_errores: ['count==0'],
    checks: ['rate>0.99'],
  },
  estres: {   // aqui lo que importa es DONDE se rompen; estos umbrales marcan el limite "aceptable"
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
    alerta_entrega_ms: ['p(95)<5000'],
  },
  pico: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<1500'],
    alerta_entrega_ms: ['p(95)<5000'],
    ws_errores: ['count<20'],
  },
};

export const options = {
  scenarios: {
    operadores_http: { executor: 'ramping-vus', exec: 'operador', startVUs: 0, stages: PERFILES[TIPO].http, gracefulRampDown: '10s' },
    operadores_ws: { executor: 'ramping-vus', exec: 'socket', startVUs: 0, stages: PERFILES[TIPO].ws, gracefulRampDown: `${HOLD_WS + 5}s` },
  },
  thresholds: UMBRALES[TIPO],
  tags: { tipo: TIPO, etiqueta: ETIQUETA },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const alertasRecibidas = new Counter('alertas_recibidas');
const snapshots = new Counter('snapshots_recibidos');
const wsErrores = new Counter('ws_errores');
const entrega = new Trend('alerta_entrega_ms', true);

// /report (como las rutas que cambian estado) valida la cabecera Host contra el panel (anti DNS rebinding): llega con el nombre del panel, no con
// el del contenedor del balanceador. Es lo que manda un navegador real que abrio http://localhost:8080.
const headers = { headers: { Cookie: COOKIE, Host: 'localhost:8080' } };

// ---- Operador activo: consulta sesion, camaras y reporte (el panel lo hace al cargar y al generar el reporte) ----------------
export function operador() {
  const sesion = http.get(`${HTTP}/auth/me`, { headers: headers.headers, tags: { name: 'auth_me' } });
  check(sesion, { 'auth/me 200': (r) => r.status === 200 });

  const camaras = http.get(`${HTTP}/cameras`, { headers: headers.headers, tags: { name: 'cameras' } });
  check(camaras, { 'cameras 200': (r) => r.status === 200 });

  const reporte = http.get(`${HTTP}/report`, { headers: headers.headers, tags: { name: 'report' } });
  check(reporte, { 'report 200': (r) => r.status === 200, 'report es JSON': (r) => String(r.headers['Content-Type'] || '').includes('json') });

  if (Math.random() < 0.1) {   // de vez en cuando se recarga la pagina del panel (estatica, ~cientos de KB)
    const pagina = http.get(`${HTTP}/`, { tags: { name: 'pagina' } });
    check(pagina, { 'pagina 200': (r) => r.status === 200 });
  }
  sleep(THINK);
}

// ---- Socket de alertas: se conecta con la sesion, recibe el snapshot y cada alerta nueva ---------------------------------------
export function socket() {
  const respuesta = ws.connect(WS_URL, { headers: { Cookie: COOKIE, Origin: ORIGIN }, tags: { name: 'ws_alertas' } }, (s) => {
    s.on('message', (texto) => {
      let mensaje;
      try {
        mensaje = JSON.parse(texto);
      } catch (e) {
        wsErrores.add(1);
        return;
      }
      if (mensaje.type === 'snapshot') {
        snapshots.add(1);
      } else if (mensaje.type === 'alert') {
        alertasRecibidas.add(1);
        // occurredAt lo escribe PostgreSQL con now(); el reloj es el mismo (mismo kernel de Docker) para base, gateway y k6
        const ocurrio = Date.parse(String(mensaje.alert.occurredAt).replace(/(\.\d{3})\d*Z$/, '$1Z'));
        if (!Number.isNaN(ocurrio)) {
          entrega.add(Date.now() - ocurrio);
        }
      }
    });
    s.on('error', () => wsErrores.add(1));
    s.setTimeout(() => s.close(), HOLD_WS * 1000);
  });
  check(respuesta, { 'WebSocket abre (101)': (r) => r && r.status === 101 });
  if (!respuesta || respuesta.status !== 101) {
    wsErrores.add(1);
    sleep(1);
  }
}

// ---- Resumen: JSON exportable + TPS y TPM calculados con la definicion de arriba -------------------------------------------------
export function handleSummary(data) {
  const segundos = data.state.testRunDurationMs / 1000;
  const solicitudes = data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0;
  const alertas = data.metrics.alertas_recibidas ? data.metrics.alertas_recibidas.values.count : 0;
  const resumen = {
    tipo: TIPO,
    etiqueta: ETIQUETA,
    duracion_s: Number(segundos.toFixed(1)),
    solicitudes_http: solicitudes,
    TPS_solicitudes: Number((solicitudes / segundos).toFixed(2)),
    TPM_solicitudes: Number(((solicitudes / segundos) * 60).toFixed(1)),
    alertas_entregadas: alertas,
    TPS_alertas_entregadas: Number((alertas / segundos).toFixed(2)),
    TPM_alertas_entregadas: Number(((alertas / segundos) * 60).toFixed(1)),
    error_rate: data.metrics.http_req_failed ? Number(data.metrics.http_req_failed.values.rate.toFixed(5)) : null,
    http_p95_ms: data.metrics.http_req_duration ? Number(data.metrics.http_req_duration.values['p(95)'].toFixed(1)) : null,
    http_p99_ms: data.metrics.http_req_duration ? Number(data.metrics.http_req_duration.values['p(99)'].toFixed(1)) : null,
    entrega_p95_ms: data.metrics.alerta_entrega_ms ? Number(data.metrics.alerta_entrega_ms.values['p(95)'].toFixed(1)) : null,
    entrega_p99_ms: data.metrics.alerta_entrega_ms ? Number(data.metrics.alerta_entrega_ms.values['p(99)'].toFixed(1)) : null,
    vus_max: data.metrics.vus_max ? data.metrics.vus_max.values.max : null,
    ws_errores: data.metrics.ws_errores ? data.metrics.ws_errores.values.count : 0,
    umbrales: Object.fromEntries(Object.entries(data.metrics).filter(([, m]) => m.thresholds).map(([k, m]) => [k, Object.fromEntries(Object.entries(m.thresholds).map(([t, r]) => [t, r.ok]))])),
  };
  const texto = [
    `== ${TIPO} (${ETIQUETA}) ==`,
    `duracion ${resumen.duracion_s} s, VUs max ${resumen.vus_max}`,
    `solicitudes ${resumen.solicitudes_http}  TPS ${resumen.TPS_solicitudes}  TPM ${resumen.TPM_solicitudes}`,
    `alertas entregadas ${resumen.alertas_entregadas}  TPS ${resumen.TPS_alertas_entregadas}`,
    `error rate ${(resumen.error_rate * 100).toFixed(2)} %  P95 ${resumen.http_p95_ms} ms  P99 ${resumen.http_p99_ms} ms`,
    `entrega de alerta  P95 ${resumen.entrega_p95_ms} ms  P99 ${resumen.entrega_p99_ms} ms  errores WS ${resumen.ws_errores}`,
    '',
  ].join('\n');
  return {
    [`/results/k6-${TIPO}-${ETIQUETA}-resumen.json`]: JSON.stringify(resumen, null, 2),
    [`/results/k6-${TIPO}-${ETIQUETA}-completo.json`]: JSON.stringify(data, null, 2),
    stdout: texto,
  };
}
