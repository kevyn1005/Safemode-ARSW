// Pruebas de la logica de zonas (sin navegador). Correr desde la raiz del repo:  node --test security-dashboard/test/
const test = require('node:test');
const assert = require('node:assert/strict');
const Z = require('../camera-zones.js');

const GRID_CELLS = Z.GRID * Z.GRID;
const flat = (value) => new Array(GRID_CELLS).fill(value);
const solid = (width, height, [r, g, b]) => {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < width * height; i++) data.set([r, g, b, 255], i * 4);
  return data;
};

test('rectFromPoints ordena las esquinas, recorta a la ventana y descarta clics sueltos', () => {
  const rect = Z.rectFromPoints(0.5, 0.6, 0.1, 0.2);
  for (const [key, expected] of Object.entries({ x: 0.1, y: 0.2, w: 0.4, h: 0.4 })) {
    assert.ok(Math.abs(rect[key] - expected) < 1e-9, key + ' = ' + rect[key]);
  }
  assert.deepEqual(Z.rectFromPoints(-0.5, -0.5, 2, 2), { x: 0, y: 0, w: 1, h: 1 });
  assert.equal(Z.rectFromPoints(0.3, 0.3, 0.31, 0.9), null, 'muy angosta');
  assert.equal(Z.rectFromPoints(0.3, 0.3, 0.3, 0.3), null, 'un clic sin arrastrar');
});

test('toPixels da enteros dentro de la imagen y nunca una zona vacia', () => {
  assert.deepEqual(Z.toPixels({ x: 0.5, y: 0, w: 0.5, h: 1 }, 1920, 1080), { sx: 960, sy: 0, sw: 960, sh: 1080 });
  const edge = Z.toPixels({ x: 0.999, y: 0.999, w: 0.05, h: 0.05 }, 100, 100);
  assert.ok(edge.sx + edge.sw <= 100 && edge.sy + edge.sh <= 100 && edge.sw >= 1 && edge.sh >= 1);
});

test('la zona sigue sobre la misma camara si la ventana cambia de escala', () => {
  const zone = { x: 0.1, y: 0.2, w: 0.4, h: 0.5 };
  const a = Z.toPixels(zone, 1000, 500);
  const b = Z.toPixels(zone, 2000, 1000);
  assert.equal(b.sx, a.sx * 2);
  assert.equal(b.sw, a.sw * 2);
  assert.equal(b.sh, a.sh * 2);
});

test('overlapFraction mide contra la zona mas chica', () => {
  const a = { x: 0, y: 0, w: 0.5, h: 0.5 };
  assert.equal(Z.overlapFraction(a, { x: 0.5, y: 0, w: 0.5, h: 0.5 }), 0, 'solo se tocan en el borde');
  assert.ok(Math.abs(Z.overlapFraction(a, { x: 0.1, y: 0.1, w: 0.2, h: 0.2 }) - 1) < 1e-9, 'una dentro de la otra');
  assert.ok(Math.abs(Z.overlapFraction(a, { x: 0.25, y: 0, w: 0.5, h: 0.5 }) - 0.5) < 1e-9);
});

test('zoneProblems pide todas las zonas y no deja que se solapen', () => {
  const left = { id: 1, x: 0, y: 0, w: 0.5, h: 1 };
  const right = { id: 2, x: 0.5, y: 0, w: 0.5, h: 1 };
  assert.deepEqual(Z.zoneProblems([left, right], 2), []);
  assert.equal(Z.zoneProblems([left], 2).length, 1);
  const overlapping = { id: 2, x: 0.2, y: 0, w: 0.5, h: 1 };
  assert.match(Z.zoneProblems([left, overlapping], 2)[0], /se solapan/);
});

test('sizeChanged tolera un par de pixeles pero no un cambio real', () => {
  const ref = { w: 1280, h: 720 };
  assert.equal(Z.sizeChanged(ref, 1280, 720), false);
  assert.equal(Z.sizeChanged(ref, 1282, 719), false);
  assert.equal(Z.sizeChanged(ref, 1300, 720), true);
  assert.equal(Z.sizeChanged(ref, 1280, 600), true);
});

test('fingerprint: una imagen igual da distancia 0 y una muy distinta, mucha', () => {
  const dark = Z.fingerprint(solid(64, 64, [10, 10, 10]), 64, 64);
  const dark2 = Z.fingerprint(solid(128, 32, [10, 10, 10]), 128, 32);
  const bright = Z.fingerprint(solid(64, 64, [240, 240, 240]), 64, 64);
  assert.equal(dark.length, GRID_CELLS);
  assert.ok(Z.fingerprintDistance(dark, dark2) < 1e-9, 'el tamano de la imagen no importa');
  assert.ok(Z.fingerprintDistance(dark, bright) > 0.8);
  assert.equal(Z.looksDifferent(dark, dark2), false);
  assert.equal(Z.looksDifferent(dark, bright), true);
});

test('fingerprintDistance no compara huellas incompatibles', () => {
  assert.equal(Z.fingerprintDistance(null, flat(0.5)), Infinity);
  assert.equal(Z.fingerprintDistance([0.1], flat(0.5)), Infinity);
  assert.equal(Z.fingerprintDistance([], []), Infinity);
});

test('frameMessage antepone el byte de la camara y respeta el rango', () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0x01, 0x02]);
  const message = Z.frameMessage(2, jpeg);
  assert.deepEqual(Array.from(message), [2, 0xff, 0xd8, 0x01, 0x02]);
  assert.throws(() => Z.frameMessage(0, jpeg), RangeError);
  assert.throws(() => Z.frameMessage(9, jpeg), RangeError);
});

const goodStored = () => ({
  cameras: 2,
  reference: { w: 1280, h: 720 },
  zones: [
    { id: 1, x: 0, y: 0, w: 0.5, h: 1, fp: flat(0.4) },
    { id: 2, x: 0.5, y: 0, w: 0.5, h: 1, fp: null }
  ]
});

test('parseStored acepta lo que serialize guardo', () => {
  const parsed = Z.parseStored(Z.serialize(goodStored()));
  assert.equal(parsed.cameras, 2);
  assert.equal(parsed.zones.length, 2);
  assert.equal(parsed.zones[1].fp, null);
  assert.deepEqual(parsed.reference, { w: 1280, h: 720 });
});

test('parseStored rechaza todo lo que no tenga la forma esperada', () => {
  const bad = (mutate) => { const s = goodStored(); mutate(s); return Z.parseStored(JSON.stringify(s)); };
  assert.equal(Z.parseStored(null), null);
  assert.equal(Z.parseStored('no es json'), null);
  assert.equal(Z.parseStored('[]'), null);
  assert.equal(bad((s) => { s.reference.w = 0; }), null);
  assert.equal(bad((s) => { s.reference.w = 12.5; }), null);
  assert.equal(bad((s) => { s.cameras = 99; }), null);
  assert.equal(bad((s) => { s.zones[0].id = 9; }), null);
  assert.equal(bad((s) => { s.zones[1].id = 1; }), null, 'ids repetidos');
  assert.equal(bad((s) => { s.zones[0].w = 1.5; }), null);
  assert.equal(bad((s) => { s.zones[0].x = 0.8; s.zones[0].w = 0.5; }), null, 'se sale de la ventana');
  assert.equal(bad((s) => { s.zones[0].w = 0.01; }), null, 'demasiado chica');
  assert.equal(bad((s) => { s.zones[0].fp = [0.1, 0.2]; }), null, 'huella de otro tamano');
  assert.equal(bad((s) => { s.zones[0].fp = flat(5); }), null, 'huella fuera de rango');
  assert.equal(bad((s) => { s.zones.push(...new Array(9).fill({ id: 3, x: 0, y: 0, w: 0.1, h: 0.1 })); }), null);
});

test('preflight: el tamano distinto bloquea y el parecido solo avisa', () => {
  const config = Z.parseStored(Z.serialize(goodStored()));
  const same = { 1: flat(0.4), 2: flat(0.9) };

  assert.deepEqual(Z.preflight(config, 1280, 720, same), { blocking: [], warnings: [] }, 'la zona 2 no tiene huella: no se compara');

  const resized = Z.preflight(config, 1920, 1080, same);
  assert.equal(resized.blocking.length, 1);
  assert.match(resized.blocking[0], /cambió de tamaño/);

  const other = Z.preflight(config, 1280, 720, { 1: flat(0.95) });
  assert.deepEqual(other.blocking, []);
  assert.equal(other.warnings.length, 1);
  assert.match(other.warnings[0], /CAM 1/);
});

test('preflight tolera que aun no haya huellas actuales', () => {
  const config = Z.parseStored(Z.serialize(goodStored()));
  assert.deepEqual(Z.preflight(config, 1280, 720, null), { blocking: [], warnings: [] });
});
