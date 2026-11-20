// Editor de nombres de camaras (camera-names.js), ejecutado de verdad en jsdom con un gateway falso.
const test = require('node:test');
const assert = require('node:assert/strict');
const { cargar, respuesta } = require('./helpers/dom');

const HTML = '<!doctype html><html><body><div id="cameraNamesSlot" data-button-class="btn"></div></body></html>';
const CAMARAS = [{ id: 1, name: 'Entrada' }, { id: 2, name: 'CAM 2' }, { id: 3, name: 'CAM 3' }];

// jsdom no implementa <dialog>.showModal()/close(): se simulan con el atributo "open"
function conDialogo(w) {
  w.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
}

/** Gateway falso de /cameras: `lista` (null = 404) y lo que contesta cada POST a /cameras/<id> segun `guardar(id, nombre)`. */
function gateway({ lista = CAMARAS, guardar = () => ({ status: 200, cuerpo: {} }), falla = false } = {}) {
  const llamadas = [];
  const f = (url, opciones = {}) => {
    if (falla) return Promise.reject(new Error('sin red'));
    llamadas.push({ url, metodo: opciones.method || 'GET', cuerpo: opciones.body ? JSON.parse(opciones.body) : null });
    if (!opciones.method) return lista ? respuesta(200, lista) : respuesta(404, {});
    const r = guardar(Number(url.split('/').pop()), JSON.parse(opciones.body).name);
    return respuesta(r.status, r.cuerpo);
  };
  f.llamadas = llamadas;
  return f;
}

function pagina(opciones, html = HTML) {
  const fetch = gateway(opciones);
  const p = cargar('camera-names.js', { html, fetch, antes: conDialogo });
  p.fetch = fetch;
  p.slot = p.document.getElementById('cameraNamesSlot');
  p.boton = p.slot && p.slot.querySelector('button');
  return p;
}

async function abrirEditor(p) {
  await p.esperar();
  p.boton.click();
  await p.esperar();
  return p.document.querySelector('dialog');
}

test('sin el espacio del panel no hace nada ni falla', () => {
  const p = cargar('camera-names.js', { html: '<!doctype html><body></body>', fetch: () => respuesta(404, {}) });
  assert.equal(p.document.querySelector('button'), null);
  p.cerrar();
});

test('el boton queda oculto hasta que el gateway confirma que ofrece la ruta', async () => {
  const p = pagina({ lista: CAMARAS });
  assert.equal(p.slot.style.display, 'none', 'oculto al principio');
  await p.esperar();
  assert.equal(p.slot.style.display, '');
  assert.equal(p.boton.textContent, 'Nombres de cámaras');
  assert.equal(p.boton.className, 'btn');
  p.cerrar();
});

test('si el gateway no ofrece /cameras (404) el boton sigue oculto', async () => {
  const p = pagina({ lista: null });
  await p.esperar();
  assert.equal(p.slot.style.display, 'none');
  p.cerrar();
});

test('si el gateway no responde el boton sigue oculto y no se rompe', async () => {
  const p = pagina({ falla: true });
  await p.esperar();
  assert.equal(p.slot.style.display, 'none');
  p.cerrar();
});

test('el editor muestra solo las dos camaras que se vigilan, con su nombre y el limite de 40 letras', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  const entradas = dialogo.querySelectorAll('input');
  assert.equal(entradas.length, 2, 'la tercera camara no se ofrece');
  assert.equal(entradas[0].value, 'Entrada');
  assert.equal(entradas[1].value, 'CAM 2');
  assert.equal(entradas[0].maxLength, 40);
  assert.ok(dialogo.hasAttribute('open'));
  assert.equal(dialogo.querySelector('h2').textContent, 'Nombres de las cámaras');
  p.cerrar();
});

test('cancelar cierra el editor sin guardar nada', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  const cancelar = [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Cancelar');
  cancelar.click();
  assert.equal(p.document.querySelector('dialog'), null, 'el dialogo se retira de la pagina');
  assert.equal(p.fetch.llamadas.filter((c) => c.metodo === 'POST').length, 0);
  p.cerrar();
});

test('la tecla Escape (evento cancel) tambien lo cierra', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  const evento = new p.window.Event('cancel', { cancelable: true });
  dialogo.dispatchEvent(evento);
  assert.equal(evento.defaultPrevented, true);
  assert.equal(p.document.querySelector('dialog'), null);
  p.cerrar();
});

test('guardar manda solo lo que cambio, con el nombre nuevo, y cierra al terminar', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  dialogo.querySelectorAll('input')[1].value = 'Pasillo';
  [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar').click();
  await p.esperar();
  await p.esperar();
  const posts = p.fetch.llamadas.filter((c) => c.metodo === 'POST');
  assert.deepEqual(posts.map((c) => [c.url, c.cuerpo]), [['/cameras/2', { name: 'Pasillo' }]]);
  assert.equal(p.document.querySelector('dialog'), null);
  p.cerrar();
});

test('guardar sin cambios no manda nada y cierra', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar').click();
  await p.esperar();
  await p.esperar();
  assert.equal(p.fetch.llamadas.filter((c) => c.metodo === 'POST').length, 0);
  assert.equal(p.document.querySelector('dialog'), null);
  p.cerrar();
});

test('dejar el nombre vacio manda vacio (el gateway vuelve a «CAM n»)', async () => {
  const p = pagina({});
  const dialogo = await abrirEditor(p);
  dialogo.querySelectorAll('input')[0].value = '   ';
  [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar').click();
  await p.esperar();
  await p.esperar();
  assert.equal(p.fetch.llamadas.find((c) => c.metodo === 'POST').cuerpo.name, '   ');
  p.cerrar();
});

test('si el servidor rechaza un nombre lo muestra, no sigue con los demas y deja volver a intentar', async () => {
  const p = pagina({ guardar: (id) => (id === 1 ? { status: 400, cuerpo: 'nombre no permitido' } : { status: 200, cuerpo: {} }) });
  const dialogo = await abrirEditor(p);
  const entradas = dialogo.querySelectorAll('input');
  entradas[0].value = 'Mal';
  entradas[1].value = 'Bien';
  const guardar = [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar');
  guardar.click();
  assert.equal(guardar.disabled, true, 'mientras guarda no se puede pulsar otra vez');
  await p.esperar();
  await p.esperar();
  assert.match(dialogo.querySelector('[role=alert]').textContent, /No se pudo guardar: nombre no permitido/);
  assert.equal(guardar.disabled, false);
  assert.equal(p.fetch.llamadas.filter((c) => c.metodo === 'POST').length, 1, 'tras el primer error no se manda el segundo');
  assert.ok(p.document.querySelector('dialog'), 'el editor sigue abierto');
  p.cerrar();
});

test('si el servidor falla sin texto usa el codigo HTTP', async () => {
  const p = pagina({ guardar: () => ({ status: 500, cuerpo: '' }) });
  const dialogo = await abrirEditor(p);
  dialogo.querySelectorAll('input')[0].value = 'X';
  [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar').click();
  await p.esperar();
  await p.esperar();
  assert.match(dialogo.querySelector('[role=alert]').textContent, /No se pudo guardar: HTTP 500/);
  p.cerrar();
});

test('si se cae la red al guardar lo dice', async () => {
  const fetch = gateway({});
  let fallar = false;
  const p = cargar('camera-names.js', {
    html: HTML,
    antes: conDialogo,
    fetch: (url, o) => (fallar && o && o.method ? Promise.reject(new Error('sin red')) : fetch(url, o)),
  });
  const boton = p.document.querySelector('button');
  await p.esperar();
  boton.click();
  await p.esperar();
  const dialogo = p.document.querySelector('dialog');
  dialogo.querySelectorAll('input')[0].value = 'Z';
  fallar = true;
  [...dialogo.querySelectorAll('button')].find((b) => b.textContent === 'Guardar').click();
  await p.esperar();
  await p.esperar();
  assert.match(dialogo.querySelector('[role=alert]').textContent, /No se pudo guardar: sin red/);
  p.cerrar();
});

test('si al pulsar el boton el gateway ya no ofrece la ruta no abre nada', async () => {
  const p = pagina({ lista: CAMARAS });
  await p.esperar();
  p.fetch.llamadas.length = 0;
  const fetch404 = () => respuesta(404, {});
  p.window.fetch = fetch404;
  p.boton.click();
  await p.esperar();
  assert.equal(p.document.querySelector('dialog'), null);
  p.cerrar();
});
