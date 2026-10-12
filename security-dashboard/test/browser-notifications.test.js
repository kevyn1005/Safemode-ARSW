// Pruebas de los avisos del navegador (sin navegador: se usa uno falso). Correr desde la raiz del repo:  node --test "security-dashboard/test/*.test.js"
const test = require('node:test');
const assert = require('node:assert/strict');
const N = require('../browser-notifications.js');

const alert = (extra) => Object.assign({
  id: 7, type: 'POSSIBLE_THEFT', severity: 'HIGH', acknowledged: false, cameraId: 1, cameraName: 'CAM 1',
  occurredAt: new Date(2026, 9, 11, 15, 42, 10).toISOString()
}, extra);

/** Un navegador falso: guarda las notificaciones que se piden y lo que hay en localStorage. */
function fakeBrowser({ permission = 'granted', stored = '1', request = permission, constructorThrows = false, withNotification = true } = {}) {
  const shown = [];
  const store = stored === null ? {} : { 'safemode-notify': stored };
  const win = {
    localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = v; } },
    focus() {}
  };
  if (withNotification) {
    class FakeNotification {
      constructor(title, options) {
        if (constructorThrows) throw new Error('constructor no permitido');
        shown.push({ title, options });
        this.onclick = null;
      }
      close() {}
    }
    FakeNotification.permission = permission;
    FakeNotification.requestPermission = () => Promise.resolve(request);
    win.Notification = FakeNotification;
  }
  return { win, shown, store };
}

test('solo avisan las alertas nuevas altas y medias', () => {
  assert.equal(N.shouldNotify(alert()), true);
  assert.equal(N.shouldNotify(alert({ severity: 'MEDIUM' })), true);
  assert.equal(N.shouldNotify(alert({ severity: 'LOW' })), false);
  assert.equal(N.shouldNotify(alert({ acknowledged: true })), false);
  assert.equal(N.shouldNotify(null), false);
  assert.equal(N.shouldNotify(undefined), false);
});

test('el texto dice que paso, en que camara y a que hora, y manda al panel', () => {
  assert.equal(N.formatText(alert()), 'SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.');
  assert.equal(N.formatText(alert({ type: 'POSSIBLE_OWNER_RETURN', cameraName: 'Cancha sur' })),
    'SafeMode: posible dueño que volvió en Cancha sur a las 15:42. Revisa el panel para verificar.');
});

test('el texto no lleva datos de personas y tolera datos que falten o sean raros', () => {
  const text = N.formatText(alert({ ownerDescription: 'camisa negra', removerAiDescription: 'tatuaje en el brazo', message: 'Posible robo: la mochila' }));
  assert.ok(!/camisa|tatuaje|mochila/.test(text), text);
  assert.equal(N.formatText(alert({ cameraName: null, cameraId: 2 })), 'SafeMode: posible robo en CAM 2 a las 15:42. Revisa el panel para verificar.');
  assert.equal(N.formatText(alert({ occurredAt: 'no es una fecha' })), 'SafeMode: posible robo en CAM 1. Revisa el panel para verificar.');
  assert.equal(N.formatText(alert({ type: 'TIPO_NUEVO' })).startsWith('SafeMode: alerta en CAM 1'), true);
});

test('el nombre de la camara se limpia', () => {
  assert.equal(N.cleanCamera('  CAM 1 \n'), 'CAM 1');
  assert.equal(N.cleanCamera('a\u0000b'), 'a b');
  assert.equal(N.cleanCamera('', 3), 'CAM 3');
  assert.equal(N.cleanCamera(undefined), 'la cámara');
  assert.equal(N.cleanCamera('x'.repeat(100)).length, 40);
});

test('con permiso y avisos encendidos, una alerta alta muestra la notificacion y se queda hasta que se vea', () => {
  const { win, shown } = fakeBrowser();
  const notifier = N.createNotifier(win);

  assert.equal(notifier.onNewAlert(alert()), true);
  assert.deepEqual(shown, [{
    title: 'SafeMode · Alerta ALTA',
    options: { body: 'SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.', tag: 'safemode-alert-7', requireInteraction: true }
  }]);
});

test('una alerta media avisa pero no se queda fija', () => {
  const { win, shown } = fakeBrowser();

  assert.equal(N.createNotifier(win).onNewAlert(alert({ id: 8, severity: 'MEDIUM' })), true);
  assert.equal(shown[0].title, 'SafeMode · Alerta media');
  assert.equal(shown[0].options.requireInteraction, false);
  assert.equal(shown[0].options.tag, 'safemode-alert-8');
});

test('no avisa una alerta baja, una revisada, ni sin permiso o con los avisos apagados', () => {
  for (const [name, env, a] of [
    ['baja', {}, alert({ severity: 'LOW' })],
    ['revisada', {}, alert({ acknowledged: true })],
    ['sin permiso', { permission: 'default' }, alert()],
    ['bloqueado', { permission: 'denied' }, alert()],
    ['avisos apagados', { stored: '0' }, alert()],
    ['sin preferencia guardada', { stored: null }, alert()],
    ['sin Notification', { withNotification: false }, alert()]
  ]) {
    const { win, shown } = fakeBrowser(env);
    assert.equal(N.createNotifier(win).onNewAlert(a), false, name);
    assert.equal(shown.length, 0, name);
  }
});

test('un navegador que no deja crear notificaciones no rompe el panel', () => {
  const { win } = fakeBrowser({ constructorThrows: true });

  assert.equal(N.createNotifier(win).onNewAlert(alert()), false);
});

test('encender pide el permiso, guarda la preferencia y muestra una notificacion de prueba', async () => {
  const { win, shown, store } = fakeBrowser({ permission: 'default', stored: null, request: 'granted' });
  const notifier = N.createNotifier(win);
  assert.equal(notifier.isOn(), false);

  assert.equal(await notifier.turnOn(), 'granted');
  win.Notification.permission = 'granted';   // el navegador actualiza el permiso al concederlo

  assert.equal(store['safemode-notify'], '1');
  assert.equal(notifier.isOn(), true);
  assert.equal(shown.length, 1);
  assert.equal(shown[0].options.tag, 'safemode-test');
});

test('si el usuario rechaza el permiso no se enciende nada', async () => {
  const { win, shown, store } = fakeBrowser({ permission: 'default', stored: null, request: 'denied' });
  const notifier = N.createNotifier(win);

  assert.equal(await notifier.turnOn(), 'denied');

  assert.equal('safemode-notify' in store, false);
  assert.equal(notifier.isOn(), false);
  assert.equal(shown.length, 0);
});

test('apagar guarda la preferencia y deja de avisar', () => {
  const { win, shown, store } = fakeBrowser();
  const notifier = N.createNotifier(win);

  notifier.turnOff();

  assert.equal(store['safemode-notify'], '0');
  assert.equal(notifier.onNewAlert(alert()), false);
  assert.equal(shown.length, 0);
});

test('sin soporte de notificaciones se dice y no se rompe', async () => {
  const { win } = fakeBrowser({ withNotification: false });
  const notifier = N.createNotifier(win);

  assert.equal(notifier.supported(), false);
  assert.equal(notifier.permission(), 'unsupported');
  assert.equal(await notifier.turnOn(), 'unsupported');
});

test('si localStorage falla el navegador sigue funcionando para esta pagina', () => {
  const { win } = fakeBrowser();
  win.localStorage = { getItem() { throw new Error('bloqueado'); }, setItem() { throw new Error('bloqueado'); } };

  const notifier = N.createNotifier(win);

  assert.equal(notifier.isOn(), false);
  assert.doesNotThrow(() => notifier.turnOff());
});
