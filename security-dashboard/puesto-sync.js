/*
 * Configuracion compartida del puesto (ADR 0005): las zonas de las camaras dejan de vivir solo en el navegador de cada operador
 * y se guardan en MongoDB a traves del gateway (GET/PUT /puesto), con control de version: si dos operadores editan sobre la misma
 * version, uno guarda (200) y el otro recibe 409 y ve lo que guardo el primero. Tambien muestra quien esta en linea (presencia
 * en Redis, latido cada 10 s; vence a los 20 s) y si ESTE operador puede configurar (decide el servidor; aqui solo se pinta).
 *
 * Que se comparte: solo el rectangulo de cada zona {x, y, w, h} (fracciones 0 a 1). La huella de brillo y el tamano de la ventana
 * son de cada navegador y se recalculan al confirmar las zonas, asi que unas zonas que llegan del servidor SIEMPRE se confirman
 * con la ventana propia antes de vigilar (la prueba previa de camera-share.js las bloquea hasta entonces).
 *
 * Si el gateway no ofrece la ruta (404, no esta configurada) o no hay sesion (401), no se muestra nada y el panel funciona
 * exactamente como antes (zonas solo en este navegador). Todo el texto del servidor se pinta con textContent.
 * Tambien lista los ultimos cambios del puesto (quien, que y cuando: GET /puesto/history, coleccion aparte en MongoDB).
 * Se pinta dentro de <div id="puestoSlot"></div>. Las funciones puras se exportan para probarlas con Node
 * (security-dashboard/test/puesto-sync.test.js).
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else {
    root.PuestoSync = api;
    if (typeof document !== 'undefined') api.mount(document, root);
  }
})(typeof self !== 'undefined' ? self : this, function () {
  var ZONES_KEY = 'safemode-zones';       // el mismo que usa camera-share.js
  var EPSILON = 0.0005;                    // dos zonas que difieren menos que esto son la misma
  var PERIOD_MS = 10000;                   // latido de presencia y revision del puesto (la presencia vence a los 20 s)
  var HISTORY_SHOWN = 3;                   // cuantos cambios recientes se muestran en el panel (el servicio pagina hasta 50)
  var CHANGED_LABELS = { cameras: 'zonas', title: 'título', preferences: 'preferencias' };

  function isFraction(v) { return typeof v === 'number' && isFinite(v) && v >= 0 && v <= 1; }
  function round4(v) { return Math.round(v * 10000) / 10000; }
  function clamp01(v) { return Math.min(1, Math.max(0, v)); }

  /** Zonas validas del documento del puesto: [{id, x, y, w, h}] ordenadas por id (las camaras sin zona o con una zona rota se ignoran). */
  function zonesFromServer(cameras) {
    var zones = [];
    if (!Array.isArray(cameras)) return zones;
    cameras.forEach(function (camera) {
      var z = camera && camera.zone;
      if (!camera || !Number.isInteger(camera.id) || !z) return;
      if (!(isFraction(z.x) && isFraction(z.y) && isFraction(z.w) && isFraction(z.h))) return;
      zones.push({ id: camera.id, x: z.x, y: z.y, w: z.w, h: z.h });
    });
    return zones.sort(function (a, b) { return a.id - b.id; });
  }

  /** El cuerpo de "cameras" para PUT /puesto a partir de las zonas del panel: solo el rectangulo, dentro de 0..1 y con 4 decimales. */
  function camerasFromZones(zones) {
    return zones.map(function (zone) {
      var x = clamp01(zone.x), y = clamp01(zone.y);
      return { id: zone.id, zone: { x: round4(x), y: round4(y), w: round4(Math.min(zone.w, 1 - x)), h: round4(Math.min(zone.h, 1 - y)) } };
    });
  }

  /** Mismas camaras y mismos rectangulos (con tolerancia). */
  function sameZones(a, b) {
    if (a.length !== b.length) return false;
    var byId = {};
    b.forEach(function (z) { byId[z.id] = z; });
    return a.every(function (z) {
      var o = byId[z.id];
      return !!o && Math.abs(z.x - o.x) < EPSILON && Math.abs(z.y - o.y) < EPSILON &&
        Math.abs(z.w - o.w) < EPSILON && Math.abs(z.h - o.h) < EPSILON;
    });
  }

  /**
   * Lo que se guarda en el navegador cuando llegan zonas del servidor, en el formato de camera-share.js. Solo para el modo de 2
   * camaras con las zonas 1 y 2 (si no, null). El tamano de referencia 1x1 y la huella vacia hacen que la prueba previa pida
   * confirmar las zonas con la ventana de este navegador antes de vigilar.
   */
  function storedFromServer(zones) {
    if (zones.length !== 2 || zones[0].id !== 1 || zones[1].id !== 2) return null;
    return {
      cameras: 2,
      reference: { w: 1, h: 1 },
      zones: zones.map(function (z) { return { id: z.id, x: z.x, y: z.y, w: z.w, h: z.h, fp: null }; })
    };
  }

  /** Las zonas (solo rectangulos) de lo guardado en este navegador, o [] si no hay nada valido. */
  function zonesFromStored(text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return []; }
    if (!data || !Array.isArray(data.zones)) return [];
    return data.zones.filter(function (z) {
      return z && Number.isInteger(z.id) && isFraction(z.x) && isFraction(z.y) && isFraction(z.w) && isFraction(z.h);
    }).map(function (z) { return { id: z.id, x: z.x, y: z.y, w: z.w, h: z.h }; }).sort(function (a, b) { return a.id - b.id; });
  }

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  /** Una entrada del historial como una linea: "v6 · ana · zonas, título · 07-11 09:42" (la hora en la zona del navegador). */
  function historyLine(entry) {
    var parts = ['v' + entry.version, String(entry.by == null ? '?' : entry.by)];
    var changed = Array.isArray(entry.changed) ? entry.changed.map(function (field) { return CHANGED_LABELS[field] || String(field); }) : [];
    parts.push(changed.length ? changed.join(', ') : 'sin detalle');
    var when = typeof entry.at === 'string' && entry.at ? new Date(entry.at) : null; // new Date(null) seria 1970
    if (when && !isNaN(when.getTime())) {
      parts.push(pad2(when.getDate()) + '-' + pad2(when.getMonth() + 1) + ' ' + pad2(when.getHours()) + ':' + pad2(when.getMinutes()));
    }
    return parts.join(' · ');
  }

  /** Mensaje para el operador segun lo que respondio el servidor al guardar. */
  function saveMessage(status, version) {
    if (status === 200) return 'Zonas compartidas con el puesto (versión ' + version + ').';
    if (status === 409) return 'Otro operador guardó cambios antes que tú: se cargaron sus zonas. Confírmalas con tu ventana y, si quieres cambiarlas, vuelve a guardar.';
    if (status === 403) return 'Tu cuenta puede ver el puesto pero no configurarlo: las zonas quedan solo en este navegador.';
    if (status === 401) return 'La sesión venció: inicia sesión de nuevo para compartir las zonas.';
    if (status === 400) return 'El servidor rechazó las zonas (datos no válidos): quedan solo en este navegador.';
    return 'No se pudieron guardar las zonas en el servidor (servicio no disponible): quedan solo en este navegador.';
  }

  // ---------------------------------------------------------------------------------------------- parte del navegador
  function mount(doc, win) {
    var slot = doc.getElementById('puestoSlot');
    if (!slot) return;

    var state = { active: false, version: null, zones: [], canConfigure: false, online: [], title: '', message: '', history: [] };
    var timer = null;
    var saving = false;

    var box = doc.createElement('div');
    box.setAttribute('role', 'status');
    box.setAttribute('aria-live', 'polite');
    box.style.cssText = 'font-size:.85rem;line-height:1.4;max-width:34rem;opacity:.9';
    slot.style.display = 'none'; // se muestra cuando el gateway confirma la ruta y hay sesion
    slot.appendChild(box);

    function line(text) {
      var p = doc.createElement('div');
      p.textContent = text;
      return p;
    }

    function render() {
      slot.style.display = state.active ? '' : 'none';
      while (box.firstChild) box.removeChild(box.firstChild);
      if (!state.active) return;
      var head = 'Puesto compartido' + (state.title ? ' · ' + state.title : '') + ' · versión ' + state.version + ' · ' +
        (state.canConfigure ? 'puedes configurarlo' : 'solo lectura');
      box.appendChild(line(head));
      if (state.online.length) box.appendChild(line('En línea: ' + state.online.join(', ')));
      if (state.history.length) {
        box.appendChild(line('Últimos cambios:'));
        state.history.forEach(function (entry) {
          var row = line(historyLine(entry));
          row.style.cssText = 'padding-left:.75rem;opacity:.85';
          box.appendChild(row);
        });
      }
      if (state.message) box.appendChild(line(state.message));
    }

    function call(method, path, body) {
      var init = { method: method, credentials: 'same-origin', cache: 'no-store', headers: {} };
      if (body !== undefined) { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
      return win.fetch(path, init).then(function (r) {
        return r.text().then(function (text) {
          var json = null;
          try { json = text ? JSON.parse(text) : null; } catch (e) { /* respuesta que no es JSON */ }
          return { status: r.status, json: json };
        });
      });
    }

    function readLocal() { try { return win.localStorage.getItem(ZONES_KEY); } catch (e) { return null; } }
    function writeLocal(text) { try { win.localStorage.setItem(ZONES_KEY, text); } catch (e) { /* bloqueado: vale para esta pagina */ } }

    /** El servidor manda: si sus zonas difieren de las de este navegador, se reemplazan (hay que confirmarlas con la ventana propia). */
    function applyServerZones(announce) {
      var stored = storedFromServer(state.zones);
      if (!stored) return;
      if (sameZones(state.zones, zonesFromStored(readLocal()))) return;
      writeLocal(JSON.stringify(stored));
      if (announce) state.message = 'Las zonas se actualizaron desde el puesto compartido (versión ' + state.version + '). Confírmalas con tu ventana antes de vigilar.';
    }

    /** Lee el puesto y el permiso. Devuelve false si la funcion no esta disponible o no hay sesion. */
    function load(announce) {
      return call('GET', '/puesto').then(function (res) {
        if (res.status !== 200 || !res.json) { state.active = false; return false; }
        var changed = state.version !== null && res.json.version !== state.version;
        state.version = res.json.version;
        state.title = typeof res.json.title === 'string' ? res.json.title : '';
        state.zones = zonesFromServer(res.json.cameras);
        state.active = true;
        if (state.zones.length) applyServerZones(announce && changed);
        return call('GET', '/puesto/permission').then(function (perm) {
          state.canConfigure = perm.status === 200 && !!(perm.json && perm.json.canConfigure === true);
          // el puesto aun no tiene zonas y este operador puede configurarlo: publica las que ya tiene en este navegador
          if (!state.zones.length && state.canConfigure) publish(zonesFromStored(readLocal()));
          return loadHistory().then(function () { return true; });
        });
      }).catch(function () { state.active = false; return false; });
    }

    /** Los ultimos cambios del puesto; si el servicio no los da (404, caido), simplemente no se muestran. */
    function loadHistory() {
      return call('GET', '/puesto/history?limit=' + HISTORY_SHOWN).then(function (res) {
        state.history = res.status === 200 && res.json && Array.isArray(res.json.items) ? res.json.items.slice(0, HISTORY_SHOWN) : [];
      }).catch(function () { state.history = []; });
    }

    function presence() {
      return call('POST', '/presence').then(function () { return call('GET', '/presence'); }).then(function (res) {
        state.online = res.status === 200 && res.json && res.json.available && Array.isArray(res.json.operators) ? res.json.operators : [];
      }).catch(function () { state.online = []; });
    }

    /** Guarda las zonas en el puesto con la version que se leyo (el servidor decide el permiso y los conflictos). */
    function publish(zones) {
      if (saving || !state.active || !zones.length || sameZones(zones, state.zones)) return Promise.resolve();
      if (!state.canConfigure) { state.message = saveMessage(403); render(); return Promise.resolve(); }
      saving = true;
      return call('PUT', '/puesto', { expectedVersion: state.version, cameras: camerasFromZones(zones) }).then(function (res) {
        if (res.status === 200 && res.json) {
          state.version = res.json.version;
          state.zones = zonesFromServer(res.json.cameras);
          state.message = saveMessage(200, state.version);
        } else if (res.status === 409) {
          state.message = saveMessage(409);
          return load(false).then(function () { applyServerZones(false); });
        } else {
          state.message = saveMessage(res.status);
        }
      }).catch(function () { state.message = saveMessage(0); })
        .then(function () { return state.active ? loadHistory() : null; })
        .then(function () { saving = false; render(); });
    }

    function tick() {
      if (doc.hidden || !state.active) return;
      load(true).then(function (ok) { return ok ? presence() : null; }).then(render);
    }

    function start() {
      if (timer) return;
      load(false).then(function (ok) {
        if (!ok) { render(); return; }
        return presence().then(function () {
          render();
          timer = win.setInterval(tick, PERIOD_MS);
        });
      });
    }

    function stop() {
      if (timer) { win.clearInterval(timer); timer = null; }
      state = { active: false, version: null, zones: [], canConfigure: false, online: [], title: '', message: '', history: [] };
      render();
    }

    // camera-share.js avisa cada vez que el operador confirma sus zonas
    win.addEventListener('safemode:zones-saved', function (event) {
      var zones = event && event.detail && Array.isArray(event.detail.zones) ? event.detail.zones : [];
      publish(zones);
    });
    win.addEventListener('safemode:login', start);   // el panel lo dispara despues de iniciar sesion sin recargar la pagina
    win.addEventListener('safemode:logout', stop);
    start();                                          // sesion ya abierta al cargar la pagina (si no, 401 y queda oculto)
  }

  return {
    ZONES_KEY: ZONES_KEY, zonesFromServer: zonesFromServer, camerasFromZones: camerasFromZones, sameZones: sameZones,
    storedFromServer: storedFromServer, zonesFromStored: zonesFromStored, saveMessage: saveMessage, historyLine: historyLine, mount: mount
  };
});
