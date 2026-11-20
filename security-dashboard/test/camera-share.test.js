// "Compartir ventana de la camara" (camera-share.js): captura de pantalla del navegador y envio de fotogramas al tracker. Se ejecuta el
// archivo REAL en jsdom con un navegador falso: getDisplayMedia, el video (con su tamano), el canvas, el Worker, el WebSocket y localStorage.
// Las zonas (modo de 2 camaras) usan camera-zones.js de verdad, cargado antes igual que en el panel.
const test = require('node:test');
const assert = require('node:assert/strict');
const { cargar, respuesta } = require('./helpers/dom');

const HTML = '<!doctype html><html><body><div id="cameraShareSlot" data-button-class="btn"></div></body></html>';
const BYTES = new Uint8Array([1, 2, 3]);

/**
 * Prepara el navegador falso. `e` es el estado que la prueba puede cambiar sobre la marcha (tamano del video, WebSocket lleno...).
 * Devuelve lo que la prueba necesita para mirar: pistas, sockets, workers, dibujos del canvas.
 */
function navegador(w, e) {
  const dibujos = { n: 0 };
  const ctx = {
    drawImage() { dibujos.n += 1; }, strokeRect() {}, fillRect() {}, fillText() {}, measureText: () => ({ width: 40 }),
    getImageData: (x, y, ancho, alto) => ({ data: new Uint8ClampedArray(ancho * alto * 4).fill(e.brillo ?? 128) }),
  };
  Object.defineProperty(w.HTMLVideoElement.prototype, 'videoWidth', { get: () => e.ancho, configurable: true });
  Object.defineProperty(w.HTMLVideoElement.prototype, 'videoHeight', { get: () => e.alto, configurable: true });
  w.HTMLVideoElement.prototype.play = () => (e.playFalla ? Promise.reject(new Error('autoplay')) : Promise.resolve());
  w.HTMLCanvasElement.prototype.getContext = () => ctx;
  w.HTMLCanvasElement.prototype.toBlob = function (cb) {
    cb(e.sinBlob ? null : { arrayBuffer: () => Promise.resolve(BYTES.buffer.slice(0)) });
  };
  w.HTMLCanvasElement.prototype.getBoundingClientRect = () => ({ left: 0, top: 0, width: 720, height: 405 });
  w.HTMLCanvasElement.prototype.setPointerCapture = () => {};
  w.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
  w.URL.createObjectURL = () => 'blob:prueba';
  w.URL.revokeObjectURL = () => {};

  const workers = [];
  w.Worker = function () {
    if (e.workerFalla) throw new Error('politica de la pagina');
    this.terminado = false;
    this.terminate = () => { this.terminado = true; };
    workers.push(this);
  };
  const sockets = [];
  w.WebSocket = function (url) {
    this.url = url;
    this.readyState = 0;
    this.bufferedAmount = e.buffer ?? 0;
    this.enviados = [];
    this.send = (b) => { this.enviados.push(b); };
    this.close = () => { this.readyState = 3; this.cerrado = true; if (this.onclose) this.onclose(); };
    sockets.push(this);
  };
  w.WebSocket.OPEN = 1;

  const pista = { label: 'Camara de la sala', detenida: false, onended: null, stop() { this.detenida = true; } };
  const flujo = { getVideoTracks: () => [pista], getTracks: () => [pista] };
  const pedidos = [];
  Object.defineProperty(w.navigator, 'mediaDevices', {
    configurable: true,
    value: e.sinCompartir ? undefined : {
      getDisplayMedia: (opciones) => { pedidos.push(opciones); return e.rechazo ? Promise.reject(e.rechazo) : Promise.resolve(flujo); },
    },
  });
  return { dibujos, workers, sockets, pista, flujo, pedidos, ctx };
}

/** Una pagina con el panel listo. `o.estado` = estado inicial del navegador falso; `o.sinZonas` no carga camera-zones.js. */
function pagina(o = {}) {
  const e = Object.assign({ ancho: 1280, alto: 720 }, o.estado);
  let nav;
  const llamadas = [];
  const p = cargar('camera-share.js', {
    html: HTML,
    url: o.url,
    previos: o.sinZonas ? [] : ['camera-zones.js'],
    fetch: (url) => { llamadas.push(url); return o.camaras ? respuesta(200, o.camaras) : respuesta(404, {}); },
    antes: (w) => { nav = navegador(w, e); if (o.antes) o.antes(w); },
  });
  Object.assign(p, nav, { e, llamadas });
  p.boton = p.document.querySelector('#cameraShareSlot button');
  p.estado = p.document.querySelector('#cameraShareSlot [role=status]');
  p.modo = p.document.querySelector('#cameraShareSlot select');
  p.esperarMucho = async () => { for (let i = 0; i < 6; i += 1) await p.esperar(); };
  return p;
}

/** Empieza a compartir en modo de 1 camara hasta tener el socket abierto. */
async function compartirUnaCamara(p) {
  p.boton.click();
  await p.esperarMucho();
  p.sockets[0].readyState = 1;
  p.sockets[0].onopen();
}

// ------------------------------------------------------------------------------------------------------------------ la pagina
test('sin el espacio del panel no hace nada', () => {
  const p = cargar('camera-share.js', { html: '<!doctype html><body></body>' });
  assert.equal(p.document.querySelector('button'), null);
  p.cerrar();
});

test('pinta el selector de camaras, el boton y el estado', () => {
  const p = pagina();
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara');
  assert.equal(p.boton.className, 'btn');
  assert.deepEqual([...p.modo.options].map((o) => o.value), ['1', '2']);
  assert.equal(p.modo.value, '1');
  assert.equal(p.estado.textContent, '');
  p.cerrar();
});

test('el modo elegido se recuerda en este navegador y se recupera al volver', () => {
  const p = pagina();
  p.modo.value = '2';
  p.modo.dispatchEvent(new p.window.Event('change'));
  assert.equal(p.window.localStorage.getItem('safemode-cameras'), '2');
  const otra = pagina({ antes: (w) => w.localStorage.setItem('safemode-cameras', '2') });
  assert.equal(otra.modo.value, '2');
  p.cerrar();
  otra.cerrar();
});

test('sin camera-zones.js solo funciona el modo de 1 camara', () => {
  const p = pagina({ sinZonas: true, antes: (w) => w.localStorage.setItem('safemode-cameras', '2') });
  assert.equal(p.modo.options[1].disabled, true);
  assert.equal(p.modo.value, '1', 'aunque estuviera guardado el 2');
  p.cerrar();
});

test('con el almacenamiento bloqueado el panel sigue funcionando', () => {
  const p = pagina({
    antes: (w) => {
      Object.defineProperty(w, 'localStorage', { get() { throw new Error('bloqueado'); } });
    },
  });
  p.modo.value = '2';
  p.modo.dispatchEvent(new p.window.Event('change'));   // writeStorage no debe lanzar
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara');
  p.cerrar();
});

test('un navegador sin getDisplayMedia lo dice', () => {
  const p = pagina({ estado: { sinCompartir: true } });
  p.boton.click();
  assert.match(p.estado.textContent, /no permite compartir pantalla/);
  p.cerrar();
});

// ------------------------------------------------------------------------------------------------------------------ 1 camara
test('1 camara: pide la ventana, abre el WebSocket del tracker y dice que esta compartiendo', async () => {
  const p = pagina();
  p.boton.click();
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara', 'hasta que el navegador entrega la ventana no cambia');
  await p.esperarMucho();
  assert.equal(p.boton.textContent, 'Dejar de compartir');
  assert.equal(p.pedidos[0].audio, false);
  assert.equal(p.pedidos[0].selfBrowserSurface, 'exclude');
  assert.equal(p.sockets.length, 1);
  assert.equal(p.sockets[0].url, 'ws://localhost:8091');
  assert.equal(p.sockets[0].binaryType, 'arraybuffer');
  assert.equal(p.modo.disabled, true);
  assert.match(p.estado.textContent, /el tracker no responde/);
  p.sockets[0].readyState = 1;
  p.sockets[0].onopen();
  assert.match(p.estado.textContent, /Compartiendo «Camara de la sala» · 1 cámara\./);
  assert.equal(p.workers.length, 1, 'el reloj corre en un Worker');
  p.cerrar();
});

test('por HTTPS el canal de frames usa wss y la ruta /frames del mismo dominio', async () => {
  const p = pagina({ url: 'https://safemode-arsw.duckdns.org/' });
  p.boton.click();
  await p.esperarMucho();
  assert.equal(p.sockets[0].url, 'wss://safemode-arsw.duckdns.org/frames');
  p.cerrar();
});

test('cada tick manda un fotograma JPEG de la ventana completa, sin byte de camara', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 1);
  assert.deepEqual([...p.sockets[0].enviados[0]], [1, 2, 3]);
  assert.ok(p.dibujos.n >= 1);
  p.cerrar();
});

test('no se acumulan fotogramas: se salta el envio si el tracker no alcanza, hay uno en curso o no hay imagen', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.sockets[0].bufferedAmount = 3 * 1024 * 1024;      // el tracker no alcanza
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 0);
  p.sockets[0].bufferedAmount = 0;
  p.e.ancho = 0;                                        // la ventana aun no entrega imagen
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 0);
  p.e.ancho = 1280;
  p.workers[0].onmessage();                             // dos ticks seguidos: el segundo se descarta
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 1);
  p.cerrar();
});

test('si el canvas no produce imagen no se manda nada y se puede volver a intentar', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.e.sinBlob = true;
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 0);
  p.e.sinBlob = false;
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 1);
  p.cerrar();
});

test('si el navegador no permite Workers el reloj es un temporizador normal y se limpia al parar', async () => {
  const p = pagina({ estado: { workerFalla: true } });
  await compartirUnaCamara(p);
  assert.equal(p.workers.length, 0);
  const intervalo = p.intervalos.find((i) => i.ms === 500);
  assert.ok(intervalo, 'setInterval cada 500 ms');
  intervalo.fn();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 1);
  p.boton.click();   // dejar de compartir
  assert.equal(intervalo.activo, false);
  p.cerrar();
});

test('dejar de compartir cierra todo: pistas, socket, reloj y estado', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  assert.equal(p.boton.textContent, 'Dejar de compartir');
  p.boton.click();
  assert.equal(p.pista.detenida, true);
  assert.equal(p.sockets[0].cerrado, true);
  assert.equal(p.workers[0].terminado, true);
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara');
  assert.equal(p.modo.disabled, false);
  assert.equal(p.estado.textContent, '');
  p.cerrar();
});

test('si el tracker se cae el panel lo dice y reintenta a los 2 s', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.sockets[0].readyState = 3;
  p.sockets[0].onclose();
  assert.match(p.estado.textContent, /el tracker no responde/);
  const espera = p.temporizadores.filter((t) => t.ms === 2000 && t.activo);
  assert.equal(espera.length, 1);
  espera[0].fn();
  assert.equal(p.sockets.length, 2, 'abre otro socket');
  p.cerrar();
});

test('un error del socket lo cierra para que se reconecte', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.sockets[0].onerror();
  assert.equal(p.sockets[0].cerrado, true);
  p.cerrar();
});

test('si el usuario deja de compartir la ventana desde el navegador, el panel lo refleja', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.pista.onended();
  assert.equal(p.estado.textContent, 'Se dejó de compartir la ventana de la cámara.');
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara');
  p.cerrar();
});

test('si la ventana deja de enviar imagen (minimizada) avisa, y al volver recupera el estado', async () => {
  const p = pagina();
  await compartirUnaCamara(p);
  p.pista.onmute();
  assert.match(p.estado.textContent, /¿está minimizada\?/);
  p.pista.onunmute();
  assert.match(p.estado.textContent, /Compartiendo «Camara de la sala»/);
  p.cerrar();
});

test('si el usuario cancela el selector de ventana no se queda a medias', async () => {
  const rechazo = Object.assign(new Error('Permission denied'), { name: 'NotAllowedError' });
  const p = pagina({ estado: { rechazo } });
  p.boton.click();
  await p.esperarMucho();
  assert.equal(p.estado.textContent, 'No se eligió ninguna ventana.');
  assert.equal(p.boton.textContent, 'Compartir ventana de la cámara');
  assert.equal(p.modo.disabled, false);
  p.cerrar();
});

test('cualquier otro error al compartir se muestra', async () => {
  const p = pagina({ estado: { rechazo: new Error('boom') } });
  p.boton.click();
  await p.esperarMucho();
  assert.equal(p.estado.textContent, 'No se pudo compartir: boom');
  p.cerrar();
});

test('si la ventana nunca entrega imagen se rinde tras 50 intentos', async () => {
  const p = pagina({ estado: { ancho: 0, alto: 0 } });
  p.boton.click();
  await p.esperarMucho();
  for (let i = 0; i < 52; i += 1) {
    p.disparar();   // cada reintento de whenVideoHasSize
    await p.esperar();
  }
  await p.esperarMucho();
  assert.match(p.estado.textContent, /No se pudo compartir: la ventana no entrego imagen/);
  assert.equal(p.pista.detenida, true);
  p.cerrar();
});

test('si el video no arranca solo (autoplay) se sigue intentando con los fotogramas siguientes', async () => {
  const p = pagina({ estado: { playFalla: true } });
  await compartirUnaCamara(p);
  assert.match(p.estado.textContent, /Compartiendo/);
  p.cerrar();
});

// ------------------------------------------------------------------------------------------------------------------ 2 camaras
const CAMARAS = [{ id: 1, name: 'Entrada' }, { id: 2, name: 'Pasillo' }];

/** Dibuja con el raton una zona sobre la vista previa del dialogo (la vista previa mide 720x405 en la pantalla falsa). */
function dibujar(p, x0, y0, x1, y1) {
  const vista = p.document.querySelector('dialog canvas');
  const ev = (tipo, x, y) => vista.dispatchEvent(new p.window.MouseEvent(tipo, { clientX: x * 720, clientY: y * 405 }));
  ev('pointerdown', x0, y0);
  ev('pointermove', (x0 + x1) / 2, (y0 + y1) / 2);
  ev('pointerup', x1, y1);
}

function boton(p, texto) {
  return [...p.document.querySelectorAll('dialog button')].find((b) => b.textContent.startsWith(texto));
}

async function abrirDialogoDeZonas(p) {
  p.modo.value = '2';
  p.boton.click();
  await p.esperarMucho();
  return p.document.querySelector('dialog');
}

test('2 camaras: abre el dialogo con los nombres de las camaras y la prueba previa', async () => {
  const p = pagina({ camaras: CAMARAS });
  const dialogo = await abrirDialogoDeZonas(p);
  assert.ok(dialogo, 'el dialogo de zonas se abre');
  assert.ok(p.llamadas.includes('/cameras'), 'pide los nombres editados');
  assert.match(dialogo.textContent, /Configurar cámaras/);
  assert.match(dialogo.textContent, /Entrada \(sin zona\)/);
  assert.match(dialogo.textContent, /Pasillo \(sin zona\)/);
  assert.equal(boton(p, 'Iniciar').disabled, true, 'sin zonas no se puede iniciar');
  p.cerrar();
});

test('2 camaras: se dibujan las dos zonas, se inicia y cada tick manda un recorte marcado con su camara', async () => {
  const p = pagina({ camaras: CAMARAS });
  let zonasGuardadas = null;
  p.window.addEventListener('safemode:zones-saved', (ev) => { zonasGuardadas = ev.detail.zones; });
  const dialogo = await abrirDialogoDeZonas(p);
  dibujar(p, 0.05, 0.05, 0.45, 0.95);
  assert.match(dialogo.textContent, /Entrada ✓/);
  dibujar(p, 0.55, 0.05, 0.95, 0.95);
  assert.match(dialogo.textContent, /Pasillo ✓/);
  assert.match(dialogo.textContent, /Tamaño de la ventana 1280×720 y zonas listas/);
  p.intervalos.forEach((i) => { if (i.activo) i.fn(); });   // el repintado de la vista previa y de las comprobaciones
  const iniciar = boton(p, 'Iniciar');
  assert.equal(iniciar.disabled, false);
  iniciar.click();
  await p.esperarMucho();
  assert.equal(p.document.querySelector('dialog'), null, 'el dialogo se cierra');
  const guardado = JSON.parse(p.window.localStorage.getItem('safemode-zones'));
  assert.equal(guardado.cameras, 2);
  assert.deepEqual(guardado.zones.map((z) => z.id), [1, 2]);
  assert.deepEqual(Array.from(zonasGuardadas.map((z) => z.id)), [1, 2]);
  assert.ok(!('fp' in zonasGuardadas[0]), 'se comparten solo los rectangulos, sin la huella');
  p.sockets[0].readyState = 1;
  p.sockets[0].onopen();
  assert.match(p.estado.textContent, /2 cámaras \(Entrada, Pasillo\)/);
  p.workers[0].onmessage();
  await p.esperarMucho();
  const enviados = p.sockets[0].enviados;
  assert.equal(enviados.length, 2, 'un recorte por camara');
  assert.equal(enviados[0][0], 1, 'el primer byte es la camara');
  assert.equal(enviados[1][0], 2);
  assert.deepEqual([...enviados[0].slice(1)], [1, 2, 3]);
  p.cerrar();
});

test('2 camaras: dibujar de nuevo una zona la reemplaza y "Volver a dibujar" las borra', async () => {
  const p = pagina({ camaras: CAMARAS });
  const dialogo = await abrirDialogoDeZonas(p);
  dibujar(p, 0.05, 0.05, 0.45, 0.95);
  dibujar(p, 0.55, 0.05, 0.95, 0.95);
  boton(p, 'Entrada').click();                  // se elige CAM 1 para redibujarla
  dibujar(p, 0.10, 0.10, 0.40, 0.90);
  assert.match(dialogo.textContent, /Entrada ✓/);
  boton(p, 'Volver a dibujar').click();
  assert.match(dialogo.textContent, /Entrada \(sin zona\)/);
  assert.match(dialogo.textContent, /Pasillo \(sin zona\)/);
  p.cerrar();
});

test('2 camaras: un clic suelto o una zona minuscula no cuenta, y dos zonas encimadas no dejan iniciar', async () => {
  const p = pagina({ camaras: CAMARAS });
  const dialogo = await abrirDialogoDeZonas(p);
  dibujar(p, 0.5, 0.5, 0.51, 0.51);             // un clic suelto
  assert.match(dialogo.textContent, /Entrada \(sin zona\)/);
  dibujar(p, 0.05, 0.05, 0.70, 0.95);
  dibujar(p, 0.30, 0.05, 0.95, 0.95);           // se encima mucho con la primera
  assert.equal(boton(p, 'Iniciar').disabled, true);
  assert.match(dialogo.querySelector('ul').textContent, /✗/);
  p.cerrar();
});

test('2 camaras: cancelar el dialogo (boton o Escape) detiene la captura', async () => {
  const p = pagina({ camaras: CAMARAS });
  await abrirDialogoDeZonas(p);
  boton(p, 'Cancelar').click();
  await p.esperarMucho();
  assert.equal(p.estado.textContent, 'No se configuraron las zonas.');
  assert.equal(p.pista.detenida, true);

  const q = pagina({ camaras: CAMARAS });
  const dialogo = await abrirDialogoDeZonas(q);
  const evento = new q.window.Event('cancel', { cancelable: true });
  dialogo.dispatchEvent(evento);
  assert.equal(evento.defaultPrevented, true);
  await q.esperarMucho();
  assert.equal(q.estado.textContent, 'No se configuraron las zonas.');
  p.cerrar();
  q.cerrar();
});

test('2 camaras: las zonas guardadas se muestran para la prueba previa y se pueden confirmar de una vez', async () => {
  const guardadas = JSON.stringify({
    cameras: 2, reference: { w: 1280, h: 720 },
    zones: [{ id: 1, x: 0.05, y: 0.05, w: 0.4, h: 0.9, fp: null }, { id: 2, x: 0.55, y: 0.05, w: 0.4, h: 0.9, fp: null }],
  });
  const p = pagina({ camaras: CAMARAS, antes: (w) => w.localStorage.setItem('safemode-zones', guardadas) });
  const dialogo = await abrirDialogoDeZonas(p);
  assert.match(dialogo.textContent, /Entrada ✓/);
  assert.match(dialogo.textContent, /Pasillo ✓/);
  boton(p, 'Iniciar').click();
  await p.esperarMucho();
  assert.equal(p.document.querySelector('dialog'), null);
  p.cerrar();
});

test('2 camaras: si la ventana cambia de tamano se pausa y se vuelve a pedir confirmar las zonas', async () => {
  const p = pagina({ camaras: CAMARAS });
  await abrirDialogoDeZonas(p);
  dibujar(p, 0.05, 0.05, 0.45, 0.95);
  dibujar(p, 0.55, 0.05, 0.95, 0.95);
  boton(p, 'Iniciar').click();
  await p.esperarMucho();
  p.sockets[0].readyState = 1;
  p.sockets[0].onopen();
  p.e.ancho = 1920;                              // el operador cambio el tamano de la ventana compartida
  p.e.alto = 1080;
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.match(p.estado.textContent, /Pausado: la ventana «Camara de la sala» cambió de tamaño/);
  assert.equal(p.sockets[0].enviados.length, 0, 'mientras esta pausado no se manda nada');
  const dialogo = p.document.querySelector('dialog');
  assert.ok(dialogo, 'se abre la verificacion otra vez');
  assert.ok(boton(p, 'Las zonas siguen bien'), 'se puede confirmar que las zonas siguen bien');
  boton(p, 'Las zonas siguen bien').click();
  boton(p, 'Iniciar').click();
  await p.esperarMucho();
  assert.equal(p.document.querySelector('dialog'), null);
  assert.doesNotMatch(p.estado.textContent, /Pausado/);
  p.workers[0].onmessage();
  await p.esperarMucho();
  assert.equal(p.sockets[0].enviados.length, 2, 'vuelve a mandar tras confirmar');
  p.cerrar();
});

test('2 camaras: si se cancela la verificacion tras cambiar el tamano, se detiene la captura', async () => {
  const p = pagina({ camaras: CAMARAS });
  await abrirDialogoDeZonas(p);
  dibujar(p, 0.05, 0.05, 0.45, 0.95);
  dibujar(p, 0.55, 0.05, 0.95, 0.95);
  boton(p, 'Iniciar').click();
  await p.esperarMucho();
  p.sockets[0].readyState = 1;
  p.sockets[0].onopen();
  p.e.ancho = 1000;
  p.workers[0].onmessage();
  await p.esperarMucho();
  boton(p, 'Cancelar').click();
  await p.esperarMucho();
  assert.equal(p.estado.textContent, 'No se configuraron las zonas.');
  p.cerrar();
});

test('si se deja de compartir mientras se configuran las zonas no se arranca nada', async () => {
  const p = pagina({ camaras: CAMARAS });
  await abrirDialogoDeZonas(p);
  dibujar(p, 0.05, 0.05, 0.45, 0.95);
  dibujar(p, 0.55, 0.05, 0.95, 0.95);
  p.pista.onended();                             // el navegador corta la captura con el dialogo abierto
  assert.equal(p.document.querySelector('dialog'), null, 'el dialogo se cierra solo: ya no hay video que configurar');
  await p.esperarMucho();
  assert.equal(p.sockets.length, 0);
  assert.equal(p.workers.length, 0);
  assert.equal(p.estado.textContent, 'Se dejó de compartir la ventana de la cámara.');
  p.cerrar();
});
