// Pruebas de la logica pura de la configuracion compartida del puesto (sin navegador). Correr desde la raiz del repo:
//   node --test security-dashboard/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../puesto-sync.js');
const Z = require('../camera-zones.js');

const zone = (id, x, y, w, h) => ({ id, x, y, w, h });

test('zonesFromServer toma solo camaras con una zona valida, ordenadas por id', () => {
  const cameras = [
    { id: 2, zone: { x: 0.5, y: 0, w: 0.5, h: 1 } },
    { id: 1, zone: { x: 0, y: 0, w: 0.5, h: 1 } },
    { id: 3, zone: null },
    { id: 4 },
    { id: 5, zone: { x: 2, y: 0, w: 0.5, h: 1 } },        // fuera de 0..1
    { id: 6, zone: { x: 'a', y: 0, w: 0.5, h: 1 } },      // no es numero
    null,
    { id: 'x', zone: { x: 0, y: 0, w: 1, h: 1 } }         // id que no es entero
  ];
  assert.deepEqual(P.zonesFromServer(cameras), [zone(1, 0, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)]);
  assert.deepEqual(P.zonesFromServer(undefined), []);
  assert.deepEqual(P.zonesFromServer('no es una lista'), []);
});

test('camerasFromZones manda solo el rectangulo, dentro de 0..1 y con 4 decimales', () => {
  const sent = P.camerasFromZones([zone(1, 0.123456789, 0.2, 0.3, 0.4), zone(2, 0.9, 0.9, 0.5, 0.5)]);
  assert.deepEqual(sent[0], { id: 1, zone: { x: 0.1235, y: 0.2, w: 0.3, h: 0.4 } });
  // una zona que se pasa del borde (la tolerancia de camera-share.js llega a 1.0001) se recorta para que el servidor no la rechace
  assert.deepEqual(sent[1], { id: 2, zone: { x: 0.9, y: 0.9, w: 0.1, h: 0.1 } });
  sent.forEach((camera) => assert.deepEqual(Object.keys(camera).sort(), ['id', 'zone']));
  sent.forEach((camera) => assert.deepEqual(Object.keys(camera.zone).sort(), ['h', 'w', 'x', 'y']));
});

test('sameZones compara camaras y rectangulos con tolerancia', () => {
  const a = [zone(1, 0, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)];
  assert.equal(P.sameZones(a, [zone(2, 0.5, 0, 0.5, 1), zone(1, 0, 0, 0.5, 1)]), true, 'el orden no importa');
  assert.equal(P.sameZones(a, [zone(1, 0.0001, 0, 0.5, 1), zone(2, 0.5, 0, 0.5001, 1)]), true, 'diferencias de redondeo');
  assert.equal(P.sameZones(a, [zone(1, 0.01, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)]), false);
  assert.equal(P.sameZones(a, [zone(1, 0, 0, 0.5, 1)]), false, 'falta una camara');
  assert.equal(P.sameZones(a, [zone(1, 0, 0, 0.5, 1), zone(3, 0.5, 0, 0.5, 1)]), false, 'otra camara');
  assert.equal(P.sameZones([], []), true);
});

test('storedFromServer solo arma el modo de 2 camaras y exige confirmar con la ventana propia', () => {
  const stored = P.storedFromServer([zone(1, 0, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)]);
  assert.equal(stored.cameras, 2);
  assert.deepEqual(stored.reference, { w: 1, h: 1 }, 'tamano 1x1: la prueba previa pide confirmar con la ventana de este navegador');
  assert.ok(stored.zones.every((z) => z.fp === null), 'la huella es de cada navegador');
  assert.equal(P.storedFromServer([zone(1, 0, 0, 0.5, 1)]), null);
  assert.equal(P.storedFromServer([zone(1, 0, 0, 0.5, 1), zone(3, 0.5, 0, 0.5, 1)]), null);
  assert.equal(P.storedFromServer([]), null);
});

test('lo que arma storedFromServer lo acepta camera-zones.parseStored y mantiene las zonas', () => {
  const stored = P.storedFromServer([zone(1, 0, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)]);
  const parsed = Z.parseStored(JSON.stringify(stored));
  assert.ok(parsed, 'camera-share.js lo carga como unas zonas guardadas validas');
  assert.equal(parsed.zones.length, 2);
  // y la prueba previa BLOQUEA hasta confirmar con la ventana real (tamano distinto del 1x1 de referencia)
  const result = Z.preflight(parsed, 1280, 720, {});
  assert.equal(result.blocking.length, 1);
});

test('zonesFromStored lee solo los rectangulos de lo guardado en el navegador y descarta lo roto', () => {
  const text = JSON.stringify({ cameras: 2, reference: { w: 1280, h: 720 }, zones: [
    { id: 2, x: 0.5, y: 0, w: 0.5, h: 1, fp: [0, 1] }, { id: 1, x: 0, y: 0, w: 0.5, h: 1, fp: null }, { id: 3, x: 5, y: 0, w: 1, h: 1 }] });
  assert.deepEqual(P.zonesFromStored(text), [zone(1, 0, 0, 0.5, 1), zone(2, 0.5, 0, 0.5, 1)]);
  assert.deepEqual(P.zonesFromStored('no es json'), []);
  assert.deepEqual(P.zonesFromStored(null), []);
  assert.deepEqual(P.zonesFromStored('{"zones":"x"}'), []);
});

test('ida y vuelta: las zonas del panel viajan al servidor y vuelven iguales', () => {
  const local = [zone(1, 0.05, 0.1, 0.4, 0.8), zone(2, 0.55, 0.1, 0.4, 0.8)];
  const back = P.zonesFromServer(P.camerasFromZones(local));
  assert.equal(P.sameZones(local, back), true);
});

test('saveMessage distingue exito, conflicto, permiso, sesion, datos y servicio caido', () => {
  assert.match(P.saveMessage(200, 4), /versión 4/);
  assert.match(P.saveMessage(409), /Otro operador/);
  assert.match(P.saveMessage(403), /no configurarlo/);
  assert.match(P.saveMessage(401), /sesión/);
  assert.match(P.saveMessage(400), /rechazó/);
  assert.match(P.saveMessage(503), /no disponible/);
  assert.match(P.saveMessage(0), /no disponible/);
});

// ---------------------------------------------------------------------------------------------- flujo con un navegador simulado
function fakeBrowser(routes) {
  const listeners = {};
  const storage = {};
  const calls = [];
  const node = () => ({
    children: [], style: {}, attrs: {}, textContent: '',
    setAttribute(k, v) { this.attrs[k] = v; },
    appendChild(c) { this.children.push(c); },
    removeChild(c) { this.children.splice(this.children.indexOf(c), 1); },
    get firstChild() { return this.children[0] || null; }
  });
  const slot = node();
  const doc = { hidden: false, getElementById: (id) => (id === 'puestoSlot' ? slot : null), createElement: () => node() };
  const win = {
    localStorage: { getItem: (k) => (k in storage ? storage[k] : null), setItem: (k, v) => { storage[k] = v; } },
    fetch: (path, init) => {
      calls.push({ method: init.method, path, body: init.body ? JSON.parse(init.body) : undefined });
      const r = routes(init.method, path, init.body ? JSON.parse(init.body) : undefined) || { status: 404 };
      return Promise.resolve({ status: r.status, text: () => Promise.resolve(r.body === undefined ? '' : JSON.stringify(r.body)) });
    },
    addEventListener: (name, fn) => { (listeners[name] = listeners[name] || []).push(fn); },
    setInterval: () => 1,
    clearInterval: () => {}
  };
  const text = () => slot.children[0] ? slot.children[0].children.map((c) => c.textContent).join(' | ') : '';
  return { doc, win, slot, storage, calls, text, emit: (name, detail) => (listeners[name] || []).forEach((fn) => fn({ detail })) };
}

const settle = async () => { for (let i = 0; i < 12; i++) await new Promise((r) => setImmediate(r)); };
const camerasWithZones = [{ id: 1, zone: { x: 0, y: 0, w: 0.5, h: 1 } }, { id: 2, zone: { x: 0.5, y: 0, w: 0.5, h: 1 } }];
const puestoDoc = (version, cameras) => ({ id: 'puesto-1', title: 'Puesto principal', version, cameras, preferences: {} });
const localZones = [zone(1, 0.1, 0.1, 0.3, 0.8), zone(2, 0.6, 0.1, 0.3, 0.8)];
const userZonesDetail = { zones: localZones };

test('mount: sin la funcion en el gateway (404) no muestra nada y no toca lo guardado', async () => {
  const b = fakeBrowser(() => ({ status: 404 }));
  P.mount(b.doc, b.win);
  await settle();
  assert.equal(b.slot.style.display, 'none');
  assert.deepEqual(b.storage, {});
  assert.deepEqual(b.calls.map((c) => c.path), ['/puesto']);
});

test('mount: sin sesion (401) tampoco muestra nada, y al iniciar sesion se activa sin recargar', async () => {
  let logged = false;
  const b = fakeBrowser((method, path) => {
    if (!logged) return { status: 401 };
    if (path === '/puesto') return { status: 200, body: puestoDoc(1, camerasWithZones) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: false } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: ['ana'] } };
    return { status: 404 };
  });
  P.mount(b.doc, b.win);
  await settle();
  assert.equal(b.slot.style.display, 'none');
  logged = true;
  b.emit('safemode:login');
  await settle();
  assert.equal(b.slot.style.display, '');
  assert.match(b.text(), /Puesto compartido/);
  assert.match(b.text(), /solo lectura/);
  assert.match(b.text(), /En línea: ana/);
});

test('mount: las zonas del puesto reemplazan a las del navegador y piden confirmar con la ventana propia', async () => {
  const b = fakeBrowser((method, path) => {
    if (path === '/puesto') return { status: 200, body: puestoDoc(3, camerasWithZones) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: false } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: [] } };
    return { status: 404 };
  });
  b.storage[P.ZONES_KEY] = JSON.stringify({ cameras: 2, reference: { w: 1280, h: 720 }, zones: [
    { id: 1, x: 0.2, y: 0.2, w: 0.2, h: 0.2, fp: null }, { id: 2, x: 0.7, y: 0.2, w: 0.2, h: 0.2, fp: null }] });
  P.mount(b.doc, b.win);
  await settle();
  const stored = Z.parseStored(b.storage[P.ZONES_KEY]);
  assert.ok(stored);
  assert.equal(P.sameZones(P.zonesFromStored(b.storage[P.ZONES_KEY]), P.zonesFromServer(camerasWithZones)), true);
  assert.deepEqual(stored.reference, { w: 1, h: 1 }, 'se pide confirmar con la ventana propia');
  assert.equal(b.calls.some((c) => c.method === 'PUT'), false, 'quien solo puede ver nunca publica');
});

test('mount: un administrador publica sus zonas con la version que leyo, y el servidor las confirma', async () => {
  let version = 5;
  const b = fakeBrowser((method, path, body) => {
    if (path === '/puesto' && method === 'GET') return { status: 200, body: puestoDoc(version, camerasWithZones) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: true } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: ['ana', 'luis'] } };
    if (path === '/puesto' && method === 'PUT') { version += 1; return { status: 200, body: puestoDoc(version, body.cameras) }; }
    return { status: 404 };
  });
  P.mount(b.doc, b.win);
  await settle();
  b.emit('safemode:zones-saved', userZonesDetail);
  await settle();
  const put = b.calls.find((c) => c.method === 'PUT');
  assert.ok(put, 'se publico');
  assert.equal(put.body.expectedVersion, 5, 'con la version que se leyo');
  assert.deepEqual(Object.keys(put.body).sort(), ['cameras', 'expectedVersion'], 'solo lo que cambia: nada de huella ni tamano');
  assert.equal(put.body.cameras[0].zone.x, 0.1);
  assert.match(b.text(), /versión 6/);
  assert.match(b.text(), /Zonas compartidas con el puesto/);
  assert.match(b.text(), /puedes configurarlo/);
});

test('mount: un 409 carga lo que guardo el otro operador y lo avisa (no pisa su cambio)', async () => {
  const theirs = [{ id: 1, zone: { x: 0.3, y: 0.3, w: 0.2, h: 0.2 } }, { id: 2, zone: { x: 0.6, y: 0.3, w: 0.2, h: 0.2 } }];
  let served = camerasWithZones;
  const b = fakeBrowser((method, path) => {
    if (path === '/puesto' && method === 'GET') return { status: 200, body: puestoDoc(served === camerasWithZones ? 5 : 6, served) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: true } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: [] } };
    if (path === '/puesto' && method === 'PUT') { served = theirs; return { status: 409, body: { error: 'La version cambio', currentVersion: 6 } }; }
    return { status: 404 };
  });
  P.mount(b.doc, b.win);
  await settle();
  b.emit('safemode:zones-saved', userZonesDetail);
  await settle();
  assert.match(b.text(), /Otro operador guardó cambios antes que tú/);
  assert.equal(P.sameZones(P.zonesFromStored(b.storage[P.ZONES_KEY]), P.zonesFromServer(theirs)), true, 'quedaron las zonas del otro operador');
  assert.equal(b.calls.filter((c) => c.method === 'PUT').length, 1, 'no se reintenta a ciegas');
});

test('mount: sin permiso (403) o con el servicio caido (503) las zonas quedan en el navegador y se explica por que', async () => {
  for (const [status, expected] of [[403, /no configurarlo/], [503, /no disponible/]]) {
    const b = fakeBrowser((method, path) => {
      if (path === '/puesto' && method === 'GET') return { status: 200, body: puestoDoc(2, camerasWithZones) };
      if (path === '/puesto/permission') return { status: 200, body: { canConfigure: true } };   // la pantalla puede estar desactualizada
      if (path === '/presence') return { status: 200, body: { available: true, operators: [] } };
      if (path === '/puesto' && method === 'PUT') return { status, body: { error: 'x' } };       // pero el servidor decide al guardar
      return { status: 404 };
    });
    P.mount(b.doc, b.win);
    await settle();
    b.storage[P.ZONES_KEY] = JSON.stringify(P.storedFromServer(localZones));
    b.emit('safemode:zones-saved', userZonesDetail);
    await settle();
    assert.match(b.text(), expected, 'HTTP ' + status);
    assert.equal(P.sameZones(P.zonesFromStored(b.storage[P.ZONES_KEY]), localZones), true, 'lo del operador no se pierde (HTTP ' + status + ')');
  }
});

test('mount: si el puesto aun no tiene zonas y el operador puede configurar, publica las que ya tenia en el navegador', async () => {
  const b = fakeBrowser((method, path, body) => {
    if (path === '/puesto' && method === 'GET') return { status: 200, body: puestoDoc(1, [{ id: 1, zone: null }, { id: 2, zone: null }]) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: true } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: [] } };
    if (path === '/puesto' && method === 'PUT') return { status: 200, body: puestoDoc(2, body.cameras) };
    return { status: 404 };
  });
  b.storage[P.ZONES_KEY] = JSON.stringify(P.storedFromServer(localZones));
  P.mount(b.doc, b.win);
  await settle();
  const put = b.calls.find((c) => c.method === 'PUT');
  assert.ok(put);
  assert.equal(put.body.expectedVersion, 1);
});

test('mount: al cerrar sesion se oculta y se olvida todo', async () => {
  const b = fakeBrowser((method, path) => {
    if (path === '/puesto') return { status: 200, body: puestoDoc(1, camerasWithZones) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: false } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: ['ana'] } };
    return { status: 404 };
  });
  P.mount(b.doc, b.win);
  await settle();
  assert.equal(b.slot.style.display, '');
  b.emit('safemode:logout');
  await settle();
  assert.equal(b.slot.style.display, 'none');
  assert.equal(b.text(), '');
});

// ---------------------------------------------------------------------------------------------- historial de cambios
test('historyLine resume quien, que y cuando, con nombres en espanol para los campos', () => {
  assert.match(P.historyLine({ version: 6, by: 'ana', changed: ['cameras'], at: '2026-11-07T14:42:51.719Z' }), /^v6 · ana · zonas · \d{2}-\d{2} \d{2}:\d{2}$/);
  assert.match(P.historyLine({ version: 7, by: 'luis', changed: ['title', 'preferences', 'cameras'], at: '2026-11-07T14:42:51Z' }),
    /^v7 · luis · título, preferencias, zonas · /);
  assert.equal(P.historyLine({ version: 8, by: 'ana', changed: [], at: 'no es una fecha' }), 'v8 · ana · sin detalle');
  assert.equal(P.historyLine({ version: 9, changed: ['otra'] }), 'v9 · ? · otra');
  assert.equal(P.historyLine({ version: 10, by: 'ana', changed: 'no es lista', at: null }), 'v10 · ana · sin detalle');
});

test('mount: muestra los ultimos cambios del puesto y los actualiza despues de guardar', async () => {
  let items = [{ version: 4, by: 'luis', changed: ['title'], at: '2026-11-07T14:00:00Z' }, { version: 3, by: 'ana', changed: ['cameras'], at: '2026-11-07T13:00:00Z' }];
  let version = 4;
  const b = fakeBrowser((method, path, body) => {
    if (path === '/puesto' && method === 'GET') return { status: 200, body: puestoDoc(version, camerasWithZones) };
    if (path === '/puesto/permission') return { status: 200, body: { canConfigure: true } };
    if (path === '/presence') return { status: 200, body: { available: true, operators: [] } };
    if (path === '/puesto/history?limit=3') return { status: 200, body: { items, nextBefore: null } };
    if (path === '/puesto' && method === 'PUT') {
      version += 1;
      items = [{ version, by: 'ana', changed: ['cameras'], at: '2026-11-07T15:00:00Z' }].concat(items);
      return { status: 200, body: puestoDoc(version, body.cameras) };
    }
    return { status: 404 };
  });
  P.mount(b.doc, b.win);
  await settle();
  assert.match(b.text(), /Últimos cambios:/);
  assert.match(b.text(), /v4 · luis · título/);
  assert.match(b.text(), /v3 · ana · zonas/);

  b.emit('safemode:zones-saved', userZonesDetail);
  await settle();
  assert.match(b.text(), /v5 · ana · zonas/, 'el cambio recien guardado aparece sin esperar al siguiente refresco');
  assert.equal(b.calls.filter((c) => c.path === '/puesto/history?limit=3').length >= 2, true);
});

test('mount: si el historial no esta disponible (404 o servicio caido) el resto del panel sigue igual', async () => {
  for (const status of [404, 503]) {
    const b = fakeBrowser((method, path) => {
      if (path === '/puesto') return { status: 200, body: puestoDoc(2, camerasWithZones) };
      if (path === '/puesto/permission') return { status: 200, body: { canConfigure: false } };
      if (path === '/presence') return { status: 200, body: { available: true, operators: ['ana'] } };
      if (path.startsWith('/puesto/history')) return { status, body: { error: 'x' } };
      return { status: 404 };
    });
    P.mount(b.doc, b.win);
    await settle();
    assert.match(b.text(), /Puesto compartido/, 'HTTP ' + status);
    assert.match(b.text(), /En línea: ana/, 'HTTP ' + status);
    assert.doesNotMatch(b.text(), /Últimos cambios/, 'HTTP ' + status);
  }
});
