/*
 * Avisos del navegador: cuando llega una alerta NUEVA de severidad alta o media, muestra una notificacion del sistema (como la de
 * WhatsApp Web) aunque la pestana del panel este en segundo plano. Hace falta que la pestana siga abierta; Web Push (con la pestana
 * cerrada) queda como mejora futura (ADR 0004). Es SOLO texto y manda a mirar el panel: sin fotos ni datos de personas.
 *
 * Se pinta dentro de <div id="notifySlot" data-button-class="btn"></div>. El permiso se pide al pulsar el boton (el navegador lo exige
 * con un clic) y la preferencia queda en localStorage. Los paneles llaman a window.SafemodeNotify.onNewAlert(alerta) con cada mensaje
 * 'alert' del WebSocket (no con 'snapshot' ni 'update'). La logica de formato es pura y se prueba con
 * `node --test "security-dashboard/test/*.test.js"`.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (typeof document !== 'undefined' && typeof window !== 'undefined') api.mount(document, window);
})(typeof self !== 'undefined' ? self : this, function () {
  var KEY = 'safemode-notify';
  var MAX_CAMERA_NAME = 40;
  var PHRASES = {
    POSSIBLE_THEFT: 'posible robo',
    AMBIGUOUS_REMOVAL: 'retiro ambiguo',
    OBJECT_VANISHED: 'objeto desaparecido',
    UNKNOWN_OWNER_REMOVAL: 'retiro sin dueño registrado',
    POSSIBLE_OWNER_RETURN: 'posible dueño que volvió'
  };
  var TITLES = { HIGH: 'SafeMode · Alerta ALTA', MEDIUM: 'SafeMode · Alerta media' };

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  /** Solo avisan las alertas nuevas de severidad alta o media: la baja queda en el panel para no saturar. */
  function shouldNotify(alert) {
    return !!alert && !alert.acknowledged && (alert.severity === 'HIGH' || alert.severity === 'MEDIUM');
  }

  /** El nombre de la camara lo escribe el usuario: sin caracteres de control y con un largo maximo. */
  function cleanCamera(name, cameraId) {
    var clean = typeof name === 'string' ? name.replace(/[\u0000-\u001f\u007f-\u009f]/g, ' ').trim() : '';
    if (!clean) return cameraId ? 'CAM ' + cameraId : 'la cámara';
    return clean.length > MAX_CAMERA_NAME ? clean.slice(0, MAX_CAMERA_NAME) : clean;
  }

  /** Ejemplo: "SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar." (hora de este computador). */
  function formatText(alert) {
    var when = new Date(alert.occurredAt);
    var time = isNaN(when.getTime()) ? '' : ' a las ' + pad(when.getHours()) + ':' + pad(when.getMinutes());
    return 'SafeMode: ' + (PHRASES[alert.type] || 'alerta') + ' en ' + cleanCamera(alert.cameraName, alert.cameraId) + time +
      '. Revisa el panel para verificar.';
  }

  /** La parte sin pantalla: preferencia, permiso y envio de la notificacion. Se prueba con un navegador falso. */
  function createNotifier(win) {
    function stored() { try { return win.localStorage.getItem(KEY) === '1'; } catch (e) { return false; } }
    function store(on) { try { win.localStorage.setItem(KEY, on ? '1' : '0'); } catch (e) { /* sin almacenamiento: vale para esta pagina */ } }
    function supported() { return typeof win.Notification !== 'undefined'; }
    function permission() { return supported() ? win.Notification.permission : 'unsupported'; }
    function isOn() { return permission() === 'granted' && stored(); }

    function show(title, body, tag, stay) {
      try {
        var notification = new win.Notification(title, { body: body, tag: tag, requireInteraction: !!stay });
        notification.onclick = function () {
          try { win.focus(); notification.close(); } catch (e) { /* ya cerrada */ }
        };
        return true;
      } catch (e) {
        return false;   // algunos navegadores (Android) no permiten el constructor
      }
    }

    return {
      supported: supported,
      permission: permission,
      isOn: isOn,
      /** Pide el permiso (solo desde un clic) y enciende los avisos; devuelve una promesa con el permiso final. */
      turnOn: function () {
        if (!supported()) return Promise.resolve('unsupported');
        var ask = permission() === 'granted' ? Promise.resolve('granted') : Promise.resolve(win.Notification.requestPermission());
        return ask.then(function (result) {
          if (result === 'granted') {
            store(true);
            show('SafeMode', 'Avisos activados: así se verá una alerta.', 'safemode-test', false);
          }
          return result;
        });
      },
      turnOff: function () { store(false); },
      onNewAlert: function (alert) {
        if (!isOn() || !shouldNotify(alert)) return false;
        return show(TITLES[alert.severity], formatText(alert), 'safemode-alert-' + alert.id, alert.severity === 'HIGH');
      }
    };
  }

  function mount(doc, win) {
    var notifier = createNotifier(win);
    win.SafemodeNotify = { onNewAlert: notifier.onNewAlert };

    var slot = doc.getElementById('notifySlot');
    if (!slot) return;
    var button = doc.createElement('button');
    button.type = 'button';
    button.className = slot.getAttribute('data-button-class') || '';
    slot.appendChild(button);

    function render() {
      button.disabled = false;
      button.title = 'Muestra una notificación del sistema cuando llega una alerta alta o media, aunque estés en otra pestaña o ventana ' +
        '(la pestaña del panel debe seguir abierta).';
      if (!notifier.supported()) {
        button.textContent = 'Avisos no disponibles';
        button.title = 'Este navegador no permite notificaciones.';
        button.disabled = true;
      } else if (notifier.permission() === 'denied') {
        button.textContent = 'Avisos bloqueados';
        button.title = 'El navegador los bloqueó para este sitio: actívalos en el candado de la barra de direcciones y recarga.';
      } else {
        button.textContent = 'Avisos del navegador: ' + (notifier.isOn() ? 'sí' : 'no');
      }
    }

    button.addEventListener('click', function () {
      if (notifier.isOn()) {
        notifier.turnOff();
        render();
      } else {
        notifier.turnOn().then(render, render);
      }
    });
    render();
  }

  return { shouldNotify: shouldNotify, cleanCamera: cleanCamera, formatText: formatText, createNotifier: createNotifier, mount: mount };
});
