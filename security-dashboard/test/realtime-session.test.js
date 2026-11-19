// Pruebas de la conexion en tiempo real del panel (sin navegador). Correr desde la raiz del repo:
//   node --test security-dashboard/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const R = require('../realtime-session.js');

test('la URL del WebSocket sale del servidor que entrego la pagina, en el puerto del gateway', () => {
  assert.equal(R.socketUrl({ protocol: 'http:', hostname: 'localhost' }), 'ws://localhost:8090');
  assert.equal(R.socketUrl({ protocol: 'http:', hostname: '127.0.0.1' }), 'ws://127.0.0.1:8090');
  assert.equal(R.socketUrl({ protocol: 'https:', hostname: 'panel.example' }), 'wss://panel.example:8090');
});

test('un parametro ?ws= (o cualquier query o hash) NO cambia el destino del WebSocket', () => {
  const normal = R.socketUrl({ protocol: 'http:', hostname: 'localhost' });
  for (const search of ['?ws=wss://sitio-ajeno.example/', '?ws=ws://localhost:9999', '?demo&ws=ws://127.0.0.1:1']) {
    const loc = { protocol: 'http:', hostname: 'localhost', search, hash: '#ws=wss://otro.example', href: 'http://localhost:8080/' + search };
    assert.equal(R.socketUrl(loc), normal, search);
  }
});

test('sin nombre de servidor usa localhost', () => {
  assert.equal(R.socketUrl({ protocol: 'http:', hostname: '' }), 'ws://localhost:8090');
  assert.equal(R.socketUrl({}), 'ws://localhost:8090');
  assert.equal(R.socketUrl(null), 'ws://localhost:8090');
});

test('al cerrarse el socket, un 401 de /auth/me significa sesion vencida', async () => {
  const calls = [];
  const fetchFn = (url, options) => { calls.push([url, options]); return Promise.resolve({ status: 401 }); };
  assert.equal(await R.decideAfterClose(fetchFn), 'expired');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], '/auth/me');
  assert.equal(calls[0][1].credentials, 'same-origin');
});

test('con la sesion viva, un fallo del servidor o la red se reintenta como antes', async () => {
  assert.equal(await R.decideAfterClose(() => Promise.resolve({ status: 200 })), 'retry');
  assert.equal(await R.decideAfterClose(() => Promise.resolve({ status: 500 })), 'retry');
  assert.equal(await R.decideAfterClose(() => Promise.resolve({ status: 404 })), 'retry');
  assert.equal(await R.decideAfterClose(() => Promise.reject(new Error('sin red'))), 'retry');
  assert.equal(await R.decideAfterClose(() => { throw new Error('fetch no disponible'); }), 'retry');
  assert.equal(await R.decideAfterClose(() => Promise.resolve(undefined)), 'retry');
});
