// Los avisos del navegador (browser-notifications.js: createNotifier y mount) con un navegador falso: Notification, localStorage y la
// pagina. El formato del texto ya lo prueba browser-notifications.test.js; aqui, el permiso, la preferencia, el boton y el envio.
const test = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');
const N = require('../browser-notifications.js');

/** Un navegador falso: Notification con el permiso pedido, localStorage en un mapa y foco registrado. */
function navegador({ permiso = 'default', respuestaPermiso = 'granted', conNotification = true, almacenamientoRoto = false, constructorFalla = false } = {}) {
  const enviadas = [];
  const datos = new Map();
  const win = {
    enfocado: 0,
    localStorage: almacenamientoRoto
      ? { getItem() { throw new Error('bloqueado'); }, setItem() { throw new Error('bloqueado'); } }
      : { getItem: (k) => (datos.has(k) ? datos.get(k) : null), setItem: (k, v) => datos.set(k, v) },
    focus() { this.enfocado += 1; },
  };
  if (conNotification) {
    win.Notification = function (titulo, opciones) {
      if (constructorFalla) throw new TypeError('Illegal constructor');
      this.titulo = titulo;
      this.opciones = opciones;
      this.cerrada = false;
      this.close = () => { this.cerrada = true; };
      enviadas.push(this);
    };
    win.Notification.permission = permiso;
    win.Notification.requestPermission = () => Promise.resolve(respuestaPermiso).then((r) => { win.Notification.permission = r; return r; });
  }
  win.enviadas = enviadas;
  win.datos = datos;
  return win;
}

const ALTA = { id: 7, severity: 'HIGH', type: 'POSSIBLE_THEFT', cameraName: 'Entrada', cameraId: 1, occurredAt: '2026-11-19T15:42:00', acknowledged: false };
const MEDIA = Object.assign({}, ALTA, { id: 8, severity: 'MEDIUM', type: 'OBJECT_VANISHED' });
const BAJA = Object.assign({}, ALTA, { id: 9, severity: 'LOW' });

test('sin Notification el navegador no esta soportado y no se avisa', () => {
  const n = N.createNotifier(navegador({ conNotification: false }));
  assert.equal(n.supported(), false);
  assert.equal(n.permission(), 'unsupported');
  assert.equal(n.isOn(), false);
  assert.equal(n.onNewAlert(ALTA), false);
});

test('turnOn en un navegador sin soporte responde "unsupported"', async () => {
  assert.equal(await N.createNotifier(navegador({ conNotification: false })).turnOn(), 'unsupported');
});

test('turnOn pide el permiso, enciende los avisos y muestra una notificacion de prueba', async () => {
  const win = navegador({ permiso: 'default', respuestaPermiso: 'granted' });
  const n = N.createNotifier(win);
  assert.equal(n.isOn(), false);
  assert.equal(await n.turnOn(), 'granted');
  assert.equal(n.isOn(), true);
  assert.equal(win.datos.get('safemode-notify'), '1');
  assert.equal(win.enviadas.length, 1);
  assert.equal(win.enviadas[0].titulo, 'SafeMode');
  assert.match(win.enviadas[0].opciones.body, /Avisos activados/);
});

test('si el usuario niega el permiso no se enciende nada', async () => {
  const win = navegador({ permiso: 'default', respuestaPermiso: 'denied' });
  const n = N.createNotifier(win);
  assert.equal(await n.turnOn(), 'denied');
  assert.equal(n.isOn(), false);
  assert.equal(win.enviadas.length, 0);
});

test('con el permiso ya concedido no vuelve a preguntar', async () => {
  const win = navegador({ permiso: 'granted' });
  let preguntas = 0;
  win.Notification.requestPermission = () => { preguntas += 1; return Promise.resolve('granted'); };
  assert.equal(await N.createNotifier(win).turnOn(), 'granted');
  assert.equal(preguntas, 0);
});

test('turnOff apaga los avisos aunque el permiso siga concedido', async () => {
  const win = navegador({ permiso: 'granted' });
  const n = N.createNotifier(win);
  await n.turnOn();
  n.turnOff();
  assert.equal(n.isOn(), false);
  assert.equal(win.datos.get('safemode-notify'), '0');
});

test('onNewAlert avisa de las altas (se quedan en pantalla) y las medias, y no de las bajas', async () => {
  const win = navegador({ permiso: 'granted' });
  const n = N.createNotifier(win);
  await n.turnOn();
  win.enviadas.length = 0;
  assert.equal(n.onNewAlert(ALTA), true);
  assert.equal(n.onNewAlert(MEDIA), true);
  assert.equal(n.onNewAlert(BAJA), false);
  assert.equal(n.onNewAlert(Object.assign({}, ALTA, { acknowledged: true })), false);
  assert.equal(win.enviadas.length, 2);
  assert.equal(win.enviadas[0].titulo, 'SafeMode · Alerta ALTA');
  assert.equal(win.enviadas[0].opciones.requireInteraction, true);
  assert.equal(win.enviadas[0].opciones.tag, 'safemode-alert-7');
  assert.match(win.enviadas[0].opciones.body, /posible robo en Entrada a las 15:42/);
  assert.equal(win.enviadas[1].titulo, 'SafeMode · Alerta media');
  assert.equal(win.enviadas[1].opciones.requireInteraction, false);
});

test('el aviso es solo texto: no lleva fotos ni descripciones de personas', async () => {
  const win = navegador({ permiso: 'granted' });
  const n = N.createNotifier(win);
  await n.turnOn();
  win.enviadas.length = 0;
  n.onNewAlert(Object.assign({}, ALTA, { ownerAiDescription: 'secreto', removerPhoto: '/photo/x.png' }));
  const opciones = win.enviadas[0].opciones;
  assert.deepEqual(Object.keys(opciones).sort(), ['body', 'requireInteraction', 'tag']);
  assert.doesNotMatch(opciones.body, /secreto|photo/);
});

test('al hacer clic en el aviso se enfoca la ventana y se cierra', async () => {
  const win = navegador({ permiso: 'granted' });
  const n = N.createNotifier(win);
  await n.turnOn();
  n.onNewAlert(ALTA);
  const aviso = win.enviadas[win.enviadas.length - 1];
  aviso.onclick();
  assert.equal(win.enfocado, 1);
  assert.equal(aviso.cerrada, true);
});

test('si el navegador no permite el constructor (Android) el aviso falla sin romper', async () => {
  const win = navegador({ permiso: 'granted', constructorFalla: true });
  win.datos.set('safemode-notify', '1');
  assert.equal(N.createNotifier(win).onNewAlert(ALTA), false);
});

test('sin almacenamiento la preferencia no se guarda pero nada se rompe', async () => {
  const win = navegador({ permiso: 'granted', almacenamientoRoto: true });
  const n = N.createNotifier(win);
  assert.equal(n.isOn(), false);
  assert.equal(await n.turnOn(), 'granted');
  assert.equal(n.isOn(), false, 'sin poder guardar la preferencia, no queda encendido');
  n.turnOff();
});

// ---- el boton (mount) ---------------------------------------------------------------------------------------------------------------
function pagina(win) {
  const dom = new JSDOM('<!doctype html><html><body><div id="notifySlot" data-button-class="btn"></div></body></html>');
  N.mount(dom.window.document, win);
  return { document: dom.window.document, boton: dom.window.document.querySelector('#notifySlot button'), esperar: () => new Promise((r) => setImmediate(() => setImmediate(r))) };
}

test('mount deja window.SafemodeNotify.onNewAlert aunque la pagina no tenga espacio para el boton', () => {
  const win = navegador({ permiso: 'granted' });
  const dom = new JSDOM('<!doctype html><body></body>');
  N.mount(dom.window.document, win);
  assert.equal(typeof win.SafemodeNotify.onNewAlert, 'function');
});

test('el boton dice "no" al principio y "sí" cuando se encienden los avisos', async () => {
  const win = navegador({ permiso: 'default', respuestaPermiso: 'granted' });
  const p = pagina(win);
  assert.equal(p.boton.textContent, 'Avisos del navegador: no');
  assert.equal(p.boton.className, 'btn');
  p.boton.click();
  await p.esperar();
  assert.equal(p.boton.textContent, 'Avisos del navegador: sí');
  p.boton.click();   // otro clic los apaga
  assert.equal(p.boton.textContent, 'Avisos del navegador: no');
});

test('si el permiso esta bloqueado el boton lo explica', () => {
  const p = pagina(navegador({ permiso: 'denied' }));
  assert.equal(p.boton.textContent, 'Avisos bloqueados');
  assert.match(p.boton.title, /candado/);
});

test('si el navegador no tiene notificaciones el boton se deshabilita', () => {
  const p = pagina(navegador({ conNotification: false }));
  assert.equal(p.boton.textContent, 'Avisos no disponibles');
  assert.equal(p.boton.disabled, true);
});
