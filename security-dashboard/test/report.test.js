// Pruebas del formato del reporte (sin navegador). Correr desde la raiz del repo:  node --test "security-dashboard/test/*.test.js"
const test = require('node:test');
const assert = require('node:assert/strict');
const R = require('../report.js');

const registered = {
  id: 1, occurredAt: '2026-10-11T17:48:50Z', cameraId: 1, cameraName: 'CAM 1', eventType: 'REGISTERED_AT_REST',
  className: 'backpack', trackedObjectId: 1, ownerPersonId: 1, ownerDescription: 'persona con camisa blanca',
  ownerAiDescription: null, removalKind: null, removerPersonId: null, removerDescription: null, removerAiDescription: null,
  framePhoto: '/photo/obj1_REGISTERED_AT_REST_1.png', ownerPhoto: '/photo/obj1_OWNER_person1_1.png', removerPhoto: null,
  alertType: null, severity: null
};

const removedByOther = Object.assign({}, registered, {
  id: 2, eventType: 'REMOVED', cameraId: 2, cameraName: 'Cancha sur', removalKind: 'BY_OTHER', removerPersonId: 2,
  removerDescription: 'persona con camisa negra', removerAiDescription: 'chaqueta negra y jeans',
  removerPhoto: '/photo/obj1_REMOVER_person2_1.png', alertType: 'POSSIBLE_THEFT', severity: 'HIGH'
});

test('un registro en reposo se describe como "solo se registra", con dueno y fotos', () => {
  const info = R.describeRow(registered);
  assert.equal(info.what, 'Registrado en reposo');
  assert.equal(info.object, 'Mochila #1');
  assert.equal(info.camera, 'CAM 1');
  assert.deepEqual(info.verdict, { text: 'Solo se registra (no es alerta)', level: 'info' });
  assert.equal(info.owner.label, 'Persona #1');
  assert.equal(info.owner.text, 'persona con camisa blanca');
  assert.equal(info.owner.estimated, true, 'sin descripcion de la IA el color es solo una estimacion');
  assert.deepEqual(info.remover, { label: '', text: '', estimated: false, looksLikeOwner: false });
  assert.deepEqual(info.photos.map((p) => p.label), ['Objeto', 'Dueño']);
});

test('un retiro por otra persona dice que alerta genera y muestra la descripcion de la IA primero', () => {
  const info = R.describeRow(removedByOther);
  assert.equal(info.what, 'Retirado');
  assert.equal(info.detail, 'Lo retiró otra persona');
  assert.equal(info.camera, 'Cancha sur');
  assert.deepEqual(info.verdict, { text: 'Posible robo · Alta', level: 'high' });
  assert.equal(info.remover.text, 'chaqueta negra y jeans');
  assert.equal(info.remover.estimated, false);
  assert.equal(info.photos.length, 3);
});

test('si quien retira se viste parecido al dueno el reporte lo dice y la alerta es de "dueno que volvio"', () => {
  const info = R.describeRow(Object.assign({}, removedByOther, { removerLooksLikeOwner: true, alertType: 'POSSIBLE_OWNER_RETURN', severity: 'MEDIUM' }));
  assert.equal(info.remover.looksLikeOwner, true);
  assert.deepEqual(info.verdict, { text: 'Posible dueño que volvió · Media', level: 'medium' });
  assert.equal(R.describeRow(removedByOther).remover.looksLikeOwner, false);
});

test('el retiro por el dueno aparece en el reporte pero explica por que no hay alerta', () => {
  const info = R.describeRow(Object.assign({}, removedByOther, { removalKind: 'BY_OWNER', alertType: null, severity: null }));
  assert.deepEqual(info.verdict, { text: 'Sin alerta: lo retiró su dueño', level: 'none' });
  assert.equal(info.detail, 'Lo retiró su dueño');
});

test('un tipo de retiro o de alerta desconocido no rompe el reporte', () => {
  const info = R.describeRow(Object.assign({}, removedByOther, { removalKind: 'TIPO_NUEVO', alertType: 'ALERTA_NUEVA', severity: 'EXTREME', className: 'laptop' }));
  assert.equal(info.detail, 'TIPO_NUEVO');
  assert.equal(info.verdict.text, 'ALERTA_NUEVA · EXTREME');
  assert.equal(info.object, 'laptop #1');
});

test('sin nombre de camara se usa "CAM n"', () => {
  assert.equal(R.describeRow(Object.assign({}, registered, { cameraName: null, cameraId: 2 })).camera, 'CAM 2');
});

test('solo se aceptan fotos que sirve el propio gateway', () => {
  assert.equal(R.safePhoto('/photo/obj1.png'), '/photo/obj1.png');
  for (const bad of ['http://evil.example/x.png', '//evil.example/x.png', '/photo/../secreto.txt', '/photo/a/b.png', '/photo/', '/photo/a.png?x=1',
    'javascript:alert(1)', '/otra/a.png', '/photo/a\\b.png', null, undefined, 42]) {
    assert.equal(R.safePhoto(bad), null, String(bad));
  }
  const info = R.describeRow(Object.assign({}, registered, { framePhoto: 'http://evil.example/x.png', ownerPhoto: '/photo/ok.png' }));
  assert.deepEqual(info.photos, [{ label: 'Dueño', url: '/photo/ok.png' }]);
});

test('el resumen cuenta registros, retiros y alertas', () => {
  const byOwner = Object.assign({}, removedByOther, { removalKind: 'BY_OWNER', alertType: null });
  assert.deepEqual(R.summarize([registered, removedByOther, byOwner]), { total: 3, registered: 1, removed: 2, alerts: 1 });
  assert.deepEqual(R.summarize([]), { total: 0, registered: 0, removed: 0, alerts: 0 });
});

test('la fecha y la hora salen en la hora del computador y una fecha rota queda vacia', () => {
  const when = R.formatWhen('2026-10-11T17:48:50Z');
  const local = new Date('2026-10-11T17:48:50Z');
  const two = (n) => String(n).padStart(2, '0');
  assert.equal(when.date, two(local.getDate()) + '/' + two(local.getMonth() + 1) + '/' + local.getFullYear());
  assert.equal(when.time, two(local.getHours()) + ':' + two(local.getMinutes()) + ':' + two(local.getSeconds()));
  assert.deepEqual(R.formatWhen('no es una fecha'), { date: '', time: '' });
  assert.deepEqual(R.formatWhen(undefined), { date: '', time: '' });
});
