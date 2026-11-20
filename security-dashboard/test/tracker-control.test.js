// Boton "Iniciar vigilancia" / "Detener vigilancia" (tracker-control.js), ejecutado de verdad en jsdom con un gateway falso.
const test = require('node:test');
const assert = require('node:assert/strict');
const { cargar, respuesta } = require('./helpers/dom');

const HTML = '<!doctype html><html><body><div id="trackerControlSlot" data-button-class="btn"></div></body></html>';

/** Un gateway falso: responde a /tracker/status con el estado actual y a /tracker/start|stop con lo que diga `acciones`. */
function gateway(estado, acciones = {}) {
  const llamadas = [];
  const f = (url, opciones = {}) => {
    llamadas.push({ url, metodo: opciones.method || 'GET' });
    if (url === '/tracker/status') return estado.status === 404 ? respuesta(404, {}) : respuesta(200, { state: estado.state, exitCode: estado.exitCode ?? -1 });
    const accion = url.split('?')[0].replace('/tracker/', '');
    const r = acciones[accion] || { status: 200, cuerpo: { state: 'RUNNING', exitCode: -1 } };
    if (r.status === 200 || r.status === 409) estado.state = r.cuerpo.state;   // el tracker cambia de verdad
    return respuesta(r.status, r.cuerpo);
  };
  f.llamadas = llamadas;
  return f;
}

function pagina(estado, acciones, antes) {
  const fetch = gateway(estado, acciones);
  const p = cargar('tracker-control.js', { html: HTML, fetch, antes });
  p.fetch = fetch;
  p.slot = p.document.getElementById('trackerControlSlot');
  p.boton = p.slot.querySelector('button');
  p.estado = p.slot.querySelector('[role=status]');
  return p;
}

test('sin el espacio del panel no hace nada ni falla', () => {
  const p = cargar('tracker-control.js', { html: '<!doctype html><body></body>', fetch: () => respuesta(404, {}) });
  assert.equal(p.intervalos.length, 0);
  p.cerrar();
});

test('si el gateway no ofrece el control (404) se oculta y se deja de preguntar', async () => {
  const p = pagina({ status: 404 });
  await p.esperar();
  assert.equal(p.slot.style.display, 'none');
  assert.equal(p.intervalos[0].activo, false, 'el intervalo de consulta se cancela');
  p.cerrar();
});

test('con el tracker detenido ofrece iniciar y lo dice', async () => {
  const p = pagina({ state: 'STOPPED' });
  await p.esperar();
  assert.equal(p.slot.style.display, 'inline-flex');
  assert.equal(p.boton.textContent, 'Iniciar vigilancia');
  assert.equal(p.estado.textContent, 'Vigilancia detenida.');
  assert.equal(p.boton.className, 'btn');
  p.cerrar();
});

test('iniciar pide un segundo clic porque borra fotos y eventos; el segundo lanza el tracker', async () => {
  const p = pagina({ state: 'STOPPED' });
  await p.esperar();
  p.boton.click();
  assert.match(p.boton.textContent, /Confirmar: borra fotos y eventos anteriores \(1 cámara\)/);
  assert.equal(p.fetch.llamadas.filter((c) => c.metodo === 'POST').length, 0, 'el primer clic no manda nada');
  p.boton.click();
  await p.esperar();
  const post = p.fetch.llamadas.find((c) => c.metodo === 'POST');
  assert.equal(post.url, '/tracker/start?cameras=1');
  assert.equal(p.boton.textContent, 'Detener vigilancia');
  assert.match(p.estado.textContent, /Vigilancia en marcha\. Comparte la ventana/);
  p.cerrar();
});

test('si no se confirma en 6 s la confirmacion se cancela', async () => {
  const p = pagina({ state: 'STOPPED' });
  await p.esperar();
  p.boton.click();
  assert.match(p.boton.textContent, /Confirmar/);
  assert.equal(p.temporizadores[0].ms, 6000);
  p.disparar();
  assert.equal(p.boton.textContent, 'Iniciar vigilancia');
  p.cerrar();
});

test('con dos camaras elegidas lo dice y las manda', async () => {
  const p = pagina({ state: 'STOPPED' }, {}, (w) => w.localStorage.setItem('safemode-cameras', '2'));
  await p.esperar();
  p.boton.click();
  assert.match(p.boton.textContent, /\(2 cámaras\)/);
  p.boton.click();
  await p.esperar();
  assert.equal(p.fetch.llamadas.find((c) => c.metodo === 'POST').url, '/tracker/start?cameras=2');
  p.cerrar();
});

test('avisa si la vigilancia corre con otro numero de camaras que el elegido', async () => {
  const p = pagina({ state: 'STOPPED' });
  await p.esperar();
  p.boton.click();
  p.boton.click();      // inicia con 1 camara
  await p.esperar();
  p.window.localStorage.setItem('safemode-cameras', '2');   // ahora el usuario elige 2
  p.intervalos[0].fn();   // la siguiente consulta repinta
  await p.esperar();
  assert.match(p.estado.textContent, /en marcha con 1 cámara\(s\), pero elegiste 2/);
  p.cerrar();
});

test('con el tracker en marcha el boton lo detiene', async () => {
  const p = pagina({ state: 'RUNNING' }, { stop: { status: 200, cuerpo: { state: 'STOPPED', exitCode: 0 } } });
  await p.esperar();
  assert.equal(p.boton.textContent, 'Detener vigilancia');
  p.boton.click();
  await p.esperar();
  assert.equal(p.fetch.llamadas.find((c) => c.metodo === 'POST').url, '/tracker/stop');
  assert.equal(p.boton.textContent, 'Iniciar vigilancia');
  p.cerrar();
});

test('mientras se detiene deshabilita el boton y explica la espera', async () => {
  const p = pagina({ state: 'STOPPING' });
  await p.esperar();
  assert.equal(p.boton.textContent, 'Deteniendo…');
  assert.equal(p.boton.disabled, true);
  assert.match(p.estado.textContent, /descripciones de IA pendientes/);
  p.cerrar();
});

test('si el tracker se cerro con error muestra el codigo y donde mirar', async () => {
  const p = pagina({ state: 'CRASHED', exitCode: 3 });
  await p.esperar();
  assert.match(p.estado.textContent, /código 3/);
  assert.match(p.estado.textContent, /tracker\.log/);
  p.cerrar();
});

test('un error del servidor al iniciar se muestra y el boton vuelve a estar disponible', async () => {
  const p = pagina({ state: 'STOPPED' }, { start: { status: 500, cuerpo: {} } });
  await p.esperar();
  p.boton.click();
  p.boton.click();
  await p.esperar();
  assert.match(p.estado.textContent, /No se pudo completar la acción: HTTP 500/);
  assert.equal(p.boton.disabled, false);
  p.cerrar();
});

test('409 (ya estaba corriendo) se acepta sin suponer con cuantas camaras', async () => {
  const p = pagina({ state: 'STOPPED' }, { start: { status: 409, cuerpo: { state: 'RUNNING', exitCode: -1 } } });
  await p.esperar();
  p.boton.click();
  p.boton.click();
  await p.esperar();
  assert.equal(p.boton.textContent, 'Detener vigilancia');
  assert.doesNotMatch(p.estado.textContent, /pero elegiste/, 'no se sabe con cuantas camaras se inicio, no se avisa');
  p.cerrar();
});

test('si el gateway no responde a la consulta no se rompe y se reintenta en el siguiente ciclo', async () => {
  let fallar = true;
  const fetch = (url) => (fallar ? Promise.reject(new Error('sin red')) : respuesta(200, { state: 'STOPPED', exitCode: -1 }));
  const p = cargar('tracker-control.js', { html: HTML, fetch });
  await p.esperar();
  assert.equal(p.document.getElementById('trackerControlSlot').style.display, 'none');
  fallar = false;
  p.intervalos[0].fn();
  await p.esperar();
  assert.equal(p.document.getElementById('trackerControlSlot').style.display, 'inline-flex');
  p.cerrar();
});
