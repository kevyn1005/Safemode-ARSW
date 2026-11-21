// El reporte pintado en pantalla (report.js: mount), con un documento de jsdom y un gateway falso. El formato de cada fila ya lo prueba
// report.test.js; aqui se prueba el dialogo, la tabla, las fotos, los errores y el boton Actualizar.
const test = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const R = require('../report.js');

const REGISTRADO = {
  id: 1, occurredAt: '2026-10-11T17:48:50Z', cameraId: 1, cameraName: 'CAM 1', eventType: 'REGISTERED_AT_REST', className: 'backpack',
  trackedObjectId: 1, ownerPersonId: 1, ownerDescription: 'persona con camisa blanca', ownerAiDescription: null, removalKind: null,
  removerPersonId: null, removerDescription: null, removerAiDescription: null, framePhoto: '/photo/obj1_REGISTERED_AT_REST_1.png',
  ownerPhoto: '/photo/obj1_OWNER_person1_1.png', removerPhoto: null, alertType: null, severity: null,
};
const ROBO = Object.assign({}, REGISTRADO, {
  id: 2, eventType: 'REMOVED', cameraId: 2, cameraName: 'Cancha sur', removalKind: 'BY_OTHER', removerPersonId: 2,
  removerDescription: 'persona con camisa negra', removerAiDescription: 'chaqueta negra y jeans', removerPhoto: '/photo/obj1_REMOVER_person2_1.png',
  removerLooksLikeOwner: true, alertType: 'POSSIBLE_THEFT', severity: 'HIGH',
});
const SIN_NADIE = Object.assign({}, REGISTRADO, {
  id: 3, ownerPersonId: null, ownerDescription: null, ownerPhoto: null, framePhoto: null, eventType: 'REMOVED', removalKind: 'NO_ONE_NEAR',
});

function pagina(html = '<!doctype html><html><body><div id="reportSlot" data-button-class="btn"></div></body></html>') {
  const dom = new JSDOM(html, { pretendToBeVisual: true });
  const w = dom.window;
  w.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
  return { dom, document: w.document, window: w, esperar: () => new Promise((r) => setImmediate(() => setImmediate(r))) };
}

/** report.js llama a `fetch` global de Node: se reemplaza durante la prueba. */
function conFetch(fn, prueba) {
  const original = global.fetch;
  global.fetch = fn;
  return Promise.resolve(prueba()).finally(() => { global.fetch = original; });
}

const ok = (cuerpo) => () => Promise.resolve({ status: 200, ok: true, json: () => Promise.resolve(cuerpo) });
const sinInternet = () => Promise.reject(new Error('sin red'));

test('sin el espacio del reporte no hace nada', () => {
  const p = pagina('<!doctype html><body></body>');
  R.mount(p.document);
  assert.equal(p.document.querySelector('button'), null);
});

test('agrega el boton "Generar reporte" con la clase que pide el panel', () => {
  const p = pagina();
  R.mount(p.document);
  const boton = p.document.querySelector('#reportSlot button');
  assert.equal(boton.textContent, 'Generar reporte');
  assert.equal(boton.className, 'btn');
});

test('al pulsarlo abre el dialogo, pide /report y pinta una fila por evento con el resumen', async () => {
  const p = pagina();
  const pedidos = [];
  await conFetch((url, o) => { pedidos.push(url); return ok({ events: [ROBO, REGISTRADO] })(); }, async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    assert.equal(p.document.querySelector('dialog [role=status]').textContent, 'Cargando…');
    await p.esperar();
    await p.esperar();
  });
  assert.deepEqual(pedidos, ['/report']);
  const dialogo = p.document.querySelector('dialog');
  assert.ok(dialogo.hasAttribute('open'));
  assert.match(dialogo.querySelector('[role=status]').textContent, /2 evento\(s\) · 1 objeto\(s\) registrado\(s\) · 1 retiro\(s\) · 1 con alerta/);
  const filas = dialogo.querySelectorAll('tbody tr');
  assert.equal(filas.length, 2);
  assert.deepEqual([...dialogo.querySelectorAll('th')].map((t) => t.textContent),
    ['Fecha', 'Hora', 'Cámara', 'Qué pasó', 'Objeto', 'Dueño probable', 'Quién lo retiró', '¿Alerta?', 'Fotos']);
  assert.match(filas[0].textContent, /Cancha sur/);
  assert.match(filas[0].textContent, /Posible robo · Alta/);
  assert.match(filas[0].textContent, /chaqueta negra y jeans/, 'la descripcion de la IA va primero');
  assert.match(filas[0].textContent, /se viste parecido al dueño/);
  assert.match(filas[1].textContent, /color estimado por imagen/, 'el color del dueno es una estimacion');
});

test('cada foto es un enlace que abre en pestana nueva y, si ya no esta en el disco, se reemplaza por "no disponible"', async () => {
  const p = pagina();
  await conFetch(ok({ events: [ROBO] }), async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    await p.esperar();
  });
  const enlaces = p.document.querySelectorAll('dialog tbody a');
  assert.equal(enlaces.length, 3, 'objeto, dueño y quien retira');
  assert.equal(enlaces[0].target, '_blank');
  assert.equal(enlaces[0].rel, 'noopener');
  assert.match(enlaces[0].title, /se abre en una pestaña nueva/);
  enlaces[0].querySelector('img').dispatchEvent(new p.window.Event('error'));
  assert.equal(p.document.querySelectorAll('dialog tbody a').length, 2, 'la foto rota se retira');
  assert.match(p.document.querySelector('dialog tbody').textContent, /no disponible/);
});

test('un evento sin dueno ni fotos muestra guiones, no celdas vacias', async () => {
  const p = pagina();
  await conFetch(ok({ events: [SIN_NADIE] }), async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    await p.esperar();
  });
  const celdas = [...p.document.querySelectorAll('dialog tbody td')].map((c) => c.textContent);
  assert.ok(celdas.filter((c) => c === '—').length >= 3, 'dueño, quien retira y fotos');
});

test('sin eventos explica que todavia no hay y donde mirar', async () => {
  const p = pagina();
  await conFetch(ok({ events: [] }), async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    await p.esperar();
  });
  assert.match(p.document.querySelector('dialog').textContent, /Todavía no hay eventos/);
  assert.match(p.document.querySelector('dialog').textContent, /tracker\.log/);
  assert.equal(p.document.querySelector('dialog table'), null);
});

test('si la respuesta no trae una lista de eventos se trata como vacia', async () => {
  const p = pagina();
  await conFetch(ok({ events: 'no es una lista' }), async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    await p.esperar();
  });
  assert.match(p.document.querySelector('dialog').textContent, /Todavía no hay eventos/);
});

for (const [nombre, respuesta, esperado] of [
  ['404: este panel no ofrece el reporte', () => Promise.resolve({ status: 404, ok: false }), /este panel no ofrece el reporte/],
  ['500: dice el codigo', () => Promise.resolve({ status: 500, ok: false }), /HTTP 500/],
  ['sin red: dice por que', sinInternet, /sin red/],
]) {
  test(`error al cargar - ${nombre}`, async () => {
    const p = pagina();
    await conFetch(respuesta, async () => {
      R.mount(p.document);
      p.document.querySelector('#reportSlot button').click();
      await p.esperar();
      await p.esperar();
    });
    const alerta = p.document.querySelector('dialog [role=alert]');
    assert.match(alerta.textContent, /No se pudo cargar el reporte: /);
    assert.match(alerta.textContent, esperado);
    assert.equal(p.document.querySelector('dialog [role=status]').textContent, '');
    assert.equal([...p.document.querySelectorAll('dialog button')].find((b) => b.textContent === 'Actualizar').disabled, false);
  });
}

test('Actualizar vuelve a pedir el reporte y no se puede pulsar mientras carga', async () => {
  const p = pagina();
  let pedidos = 0;
  await conFetch(() => { pedidos += 1; return ok({ events: [REGISTRADO] })(); }, async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    await p.esperar();
    const actualizar = [...p.document.querySelectorAll('dialog button')].find((b) => b.textContent === 'Actualizar');
    actualizar.click();
    assert.equal(actualizar.disabled, true);
    await p.esperar();
    await p.esperar();
    assert.equal(actualizar.disabled, false);
  });
  assert.equal(pedidos, 2);
});

test('Cerrar y la tecla Escape retiran el dialogo', async () => {
  const p = pagina();
  await conFetch(ok({ events: [] }), async () => {
    R.mount(p.document);
    p.document.querySelector('#reportSlot button').click();
    await p.esperar();
    [...p.document.querySelectorAll('dialog button')].find((b) => b.textContent === 'Cerrar').click();
    assert.equal(p.document.querySelector('dialog'), null);
    p.document.querySelector('#reportSlot button').click();
    const evento = new p.window.Event('cancel', { cancelable: true });
    p.document.querySelector('dialog').dispatchEvent(evento);
    assert.equal(evento.defaultPrevented, true);
    assert.equal(p.document.querySelector('dialog'), null);
    await p.esperar();
  });
});
