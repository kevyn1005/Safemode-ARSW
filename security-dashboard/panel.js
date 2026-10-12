/*
 * Tablero de alertas de Safemode. Recibe las alertas del gateway por WebSocket (snapshot / alert / update) y las pinta como
 * filas de un tablero: la alerta nueva se enciende y se queda encendida hasta que un operador la marca como revisada.
 * Todo el texto que viene del servidor (incluida la descripcion de la IA) se pone con textContent, nunca como HTML.
 * Los botones de vigilancia, camaras, reporte y avisos los pintan sus propios scripts dentro de los "slots" de index.html.
 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Almacenamiento tolerante a fallos (puede estar bloqueado en ventanas privadas)
  var store = {
    get: function (area, key) { try { return window[area].getItem(key); } catch (e) { return null; } },
    set: function (area, key, value) { try { window[area].setItem(key, value); } catch (e) { /* no-op */ } },
    remove: function (area, key) { try { window[area].removeItem(key); } catch (e) { /* no-op */ } }
  };

  // Constructor de nodos: los textos entran siempre por textContent
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === 'text') node.textContent = attrs[k];
        else if (k === 'class') node.className = attrs[k];
        else if (k.slice(0, 2) === 'on') node.addEventListener(k.slice(2), attrs[k]);
        else node.setAttribute(k, attrs[k]);
      });
    }
    (children || []).forEach(function (c) {
      if (c) node.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return node;
  }

  // Iconos: referencian el juego dibujado en index.html (<symbol id="i-...">)
  var SVG_NS = 'http://www.w3.org/2000/svg';
  function icon(name, size) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'icon' + (size === 'sm' ? ' sm' : ''));
    svg.setAttribute('aria-hidden', 'true');
    var use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', '#i-' + name);
    svg.appendChild(use);
    return svg;
  }

  // ------------------------------------------------------------------ vocabulario
  var SEV = {
    HIGH: { label: 'Alta', cls: 'high', icon: 'high' },
    MEDIUM: { label: 'Media', cls: 'med', icon: 'med' },
    LOW: { label: 'Baja', cls: 'low', icon: 'low' }
  };
  var TYPES = {
    POSSIBLE_THEFT: 'Posible robo',
    POSSIBLE_OWNER_RETURN: 'Posible dueño que volvió',
    AMBIGUOUS_REMOVAL: 'Retiro ambiguo',
    OBJECT_VANISHED: 'Objeto desaparecido',
    UNKNOWN_OWNER_REMOVAL: 'Retiro sin dueño registrado'
  };
  var OBJECTS = { backpack: 'Mochila', handbag: 'Bolso', suitcase: 'Maleta' };
  var LOOKS_LIKE_OWNER = 'Quien se lo llevó se viste parecido al dueño: puede ser el dueño que volvió de salir de cuadro. ' +
    'Compara las fotos antes de descartarla.';

  // ------------------------------------------------------------------ datos de muestra (?demo): todos los estados
  function minutesAgo(m) { return new Date(Date.now() - m * 60000).toISOString(); }
  var SEED = [
    { id: 15, type: 'POSSIBLE_THEFT', severity: 'HIGH', className: 'backpack', cameraName: 'CAM 1', occurredAt: 3, acknowledged: false, isNew: true,
      message: 'Se retiró una mochila y la persona que la tomó no se parece al dueño registrado.',
      framePhoto: null, ownerPhoto: null, removerPhoto: null,
      ownerDescription: 'persona con camisa negra', removerDescription: 'persona con camisa blanca',
      ownerAiDescription: 'ropa superior: sudadera negra; ropa inferior: pantalón corto gris; tatuajes: ninguno',
      removerAiDescription: null, ownerAiPending: false, removerAiPending: true },
    { id: 14, type: 'POSSIBLE_OWNER_RETURN', severity: 'MEDIUM', className: 'backpack', cameraName: 'CAM 1', occurredAt: 14, acknowledged: false, isNew: false,
      message: 'Se retiró una mochila; quien la tomó se viste parecido al dueño.', removerLooksLikeOwner: true,
      framePhoto: null, ownerPhoto: null, removerPhoto: null,
      ownerDescription: 'persona con camisa negra', removerDescription: 'persona con camisa negra',
      ownerAiDescription: 'ropa superior: sudadera negra; ropa inferior: pantalón corto gris',
      removerAiDescription: 'ropa superior: sudadera negra; ropa inferior: pantalón corto gris', ownerAiPending: false, removerAiPending: false },
    { id: 13, type: 'OBJECT_VANISHED', severity: 'MEDIUM', className: 'suitcase', cameraName: 'CAM 2', occurredAt: 21, acknowledged: false, isNew: false,
      message: 'Una maleta dejó de verse y no había nadie cerca.',
      framePhoto: null, ownerPhoto: null, removerPhoto: null, ownerDescription: 'persona con camisa gris', removerDescription: null,
      ownerAiDescription: null, removerAiDescription: null, ownerAiPending: false, removerAiPending: false },
    { id: 12, type: 'UNKNOWN_OWNER_REMOVAL', severity: 'LOW', className: 'handbag', cameraName: 'CAM 2', occurredAt: 36, acknowledged: false, isNew: false,
      message: 'Se retiró un bolso que no tenía dueño registrado.',
      framePhoto: null, ownerPhoto: null, removerPhoto: null, ownerDescription: null, removerDescription: 'persona con camisa blanca',
      ownerAiDescription: null, removerAiDescription: null, ownerAiPending: false, removerAiPending: false },
    { id: 11, type: 'AMBIGUOUS_REMOVAL', severity: 'MEDIUM', className: 'backpack', cameraName: 'CAM 1', occurredAt: 48, acknowledged: true, isNew: false,
      message: 'Se retiró una mochila con varias personas cerca, entre ellas el dueño.',
      framePhoto: null, ownerPhoto: null, removerPhoto: null, ownerDescription: 'persona con camisa blanca', removerDescription: 'persona con camisa roja',
      ownerAiDescription: null, removerAiDescription: null, ownerAiPending: false, removerAiPending: false },
    { id: 10, type: 'POSSIBLE_THEFT', severity: 'HIGH', className: 'suitcase', cameraName: 'CAM 2', occurredAt: 70, acknowledged: true, isNew: false,
      message: 'Se retiró una maleta y la persona que la tomó no se parece al dueño registrado.',
      framePhoto: null, ownerPhoto: null, removerPhoto: null, ownerDescription: 'persona con chaqueta verde', removerDescription: 'persona con camisa azul',
      ownerAiDescription: null, removerAiDescription: null, ownerAiPending: false, removerAiPending: false }
  ];

  var DEMO = /[?&]demo\b/.test(location.search);
  var state = {
    alerts: [],
    cameras: [],
    openId: undefined,            // undefined = abrir la primera pendiente; null = el operador cerró todo
    showDone: true,
    soundOn: store.get('localStorage', 'safemode.sound') !== 'off',
    operator: store.get('sessionStorage', 'safemode.operator')
  };

  function prepareDemo() {
    return SEED.map(function (a) {
      var copy = Object.assign({}, a);
      copy.occurredAt = minutesAgo(a.occurredAt);
      copy.createdAt = copy.occurredAt;
      return copy;
    });
  }
  function resetAlerts() {
    state.alerts = DEMO ? prepareDemo().map(withDate) : [];
    state.openId = undefined;
  }
  function withDate(a) { a.when = new Date(a.occurredAt); return a; }

  // ------------------------------------------------------------------ textos
  function pad(n) { return String(n).padStart(2, '0'); }
  function clock(d) { return isNaN(d) ? '--:--:--' : pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }
  function fullTime(d) { return isNaN(d) ? '' : pad(d.getDate()) + '-' + pad(d.getMonth() + 1) + '-' + d.getFullYear() + ' ' + clock(d); }
  function relTime(d) {
    var s = Math.max(0, Math.round((Date.now() - d.getTime()) / 1000));
    if (isNaN(s)) return '';
    if (s < 60) return 'hace ' + s + ' s';
    if (s < 3600) return 'hace ' + Math.floor(s / 60) + ' min';
    if (s < 86400) return 'hace ' + Math.floor(s / 3600) + ' h';
    return pad(d.getDate()) + '-' + pad(d.getMonth() + 1);
  }
  function toFields(text) {
    if (!text) return [];
    return text.split('; ').map(function (part) {
      var i = part.indexOf(':');
      return i < 0 ? { k: '', v: part } : { k: part.slice(0, i + 1), v: part.slice(i + 1).trim() };
    });
  }
  // Frase corta para la fila: la prenda de arriba de la descripcion de la IA, o el color estimado
  function shortPerson(ai, desc) {
    var fields = toFields(ai);
    if (fields.length && fields[0].v) return fields[0].v;
    return desc || '';
  }
  function titleOf(a) { return TYPES[a.type] || a.type; }
  function objectOf(a) { return OBJECTS[a.className] || 'Objeto'; }

  // ------------------------------------------------------------------ piezas del tablero
  function emptyBox(text) {
    return el('div', { class: 'ph-empty' }, [icon('image'), el('span', { text: text })]);
  }

  function shot(a, tag, photo, emptyText, corners) {
    var cls = 'shot' + (corners ? ' corners' : '');
    if (!photo) return el('div', { class: cls }, [el('span', { class: 'tag', text: tag }), emptyBox(emptyText)]);
    var img = el('img', { src: photo, alt: tag.toLowerCase() + ', alerta ' + a.id, loading: 'lazy' });
    var box = el('button', { type: 'button', class: cls, 'aria-label': 'Ampliar foto: ' + tag.toLowerCase(),
      onclick: function () { openLightbox(tag + ' · alerta #' + a.id, fullTime(a.when), photo); } }, [img, el('span', { class: 'tag', text: tag })]);
    img.addEventListener('error', function () { img.remove(); box.appendChild(emptyBox('Foto no disponible')); });
    return box;
  }

  function personCard(title, ai, pending, desc, emptyText) {
    var card = el('div', { class: 'pcard' }, [el('h4', {}, [icon('user', 'sm'), title])]);
    if (ai) {
      var dl = el('dl');
      toFields(ai).forEach(function (f) {
        dl.appendChild(el('dt', { text: f.k }));
        dl.appendChild(el('dd', { text: f.v }));
      });
      card.appendChild(dl);
    } else if (pending) {
      card.setAttribute('aria-busy', 'true');
      card.appendChild(el('span', { class: 'sk', style: 'width: 90%' }));
      card.appendChild(el('span', { class: 'sk', style: 'width: 60%' }));
      card.appendChild(el('p', { class: 'est', text: 'La descripción de la IA llega en unos segundos.' }));
    } else if (!desc) {
      card.appendChild(el('p', { class: 'none', text: emptyText }));
    }
    if (desc) card.appendChild(el('p', { class: 'est' }, ['Color estimado por imagen: ', el('b', { text: desc })]));
    return card;
  }

  function buildDetail(a, isNewRow) {
    var txt = el('div', { class: 'txt' }, [el('p', { class: 'msg', text: a.message })]);
    if (a.removerLooksLikeOwner) txt.appendChild(el('p', { class: 'hint' }, [icon('user', 'sm'), LOOKS_LIKE_OWNER]));
    txt.appendChild(personCard('Dueño probable', a.ownerAiDescription, a.ownerAiPending, a.ownerDescription, 'Sin dueño registrado.'));
    txt.appendChild(personCard('Quien lo retiró', a.removerAiDescription, a.removerAiPending, a.removerDescription, 'Nadie a la vista.'));
    return el('div', { class: 'detail' + (isNewRow ? ' new' : ''), id: 'detail-' + a.id, role: 'region', 'aria-label': 'Detalle de la alerta ' + a.id }, [
      shot(a, 'DUEÑO', a.ownerPhoto, 'Sin dueño registrado'),
      shot(a, 'ESCENA DEL RETIRO', a.framePhoto, 'Sin foto de la escena', true),
      shot(a, 'RETIRÓ', a.removerPhoto, 'Nadie a la vista'),
      txt
    ]);
  }

  function thumb(a, tag, photo) {
    var img = el('img', { src: photo, alt: '', loading: 'lazy' });
    var b = el('button', { type: 'button', class: 'th', 'aria-label': 'Ampliar foto: ' + tag.toLowerCase() + ', alerta ' + a.id,
      onclick: function () { openLightbox(tag + ' · alerta #' + a.id, fullTime(a.when), photo); } }, [img]);
    img.addEventListener('error', function () { b.remove(); });
    return b;
  }

  function buildRow(a, isOpen) {
    var sev = SEV[a.severity] || SEV.LOW;
    var ack = !!a.acknowledged;
    var lit = !!a.isNew && !ack;
    var cls = 'row' + (ack ? ' done' : '') + (lit ? ' new' : '') + (a.fresh && lit && !reducedMotion ? ' fresh' : '') + (isOpen ? ' open' : '');

    var time = el('div', { class: 'c-time', title: fullTime(a.when) }, [
      el('span', { class: 't', text: clock(a.when) }),
      el('small', { 'data-ts': String(a.when.getTime()), text: relTime(a.when) }),
      lit ? el('span', { class: 'tagnew', text: 'NUEVA' }) : null
    ]);
    var badge = el('span', { class: 'badge ' + sev.cls }, [icon(sev.icon), sev.label]);

    var what = el('button', { type: 'button', class: 'what-btn', 'aria-expanded': isOpen ? 'true' : 'false', 'aria-controls': 'detail-' + a.id,
      onclick: function () { toggle(a.id); } }, [
      el('b', {}, [icon('right', 'sm'), titleOf(a)]),
      el('small', { text: objectOf(a) + (a.removerLooksLikeOwner ? ' · se parece al dueño' : '') + ' · #' + a.id }),
      a.cameraName ? el('small', { class: 'cam-inline', text: a.cameraName }) : null
    ]);

    var ownerLine = shortPerson(a.ownerAiDescription, a.ownerDescription) || 'sin registrar';
    var removerLine = shortPerson(a.removerAiDescription, a.removerDescription) ||
      (a.removerAiPending ? 'descripción en camino…' : 'nadie a la vista');
    var who = el('div', { class: 'c-who' }, [
      el('span', {}, [el('i', { text: 'DUEÑO' }), ownerLine]),
      el('span', {}, [el('i', { text: 'RETIRÓ' }), removerLine])
    ]);

    var photos = el('div', { class: 'c-ph' });
    if (a.ownerPhoto) photos.appendChild(thumb(a, 'DUEÑO', a.ownerPhoto));
    if (a.framePhoto) photos.appendChild(thumb(a, 'ESCENA DEL RETIRO', a.framePhoto));
    if (a.removerPhoto) photos.appendChild(thumb(a, 'RETIRÓ', a.removerPhoto));

    var go = el('div', { class: 'c-go' });
    if (ack) {
      go.appendChild(el('span', { class: 'chip', text: 'REVISADA' }));
    } else {
      go.appendChild(el('button', { type: 'button', class: 'btn' + (lit ? ' primary' : ''), 'aria-label': 'Marcar la alerta ' + a.id + ' como revisada',
        onclick: function () { acknowledge(a.id); } }, [icon('check', 'sm'), 'Revisada']));
    }

    var row = el('div', { class: cls, 'data-id': String(a.id),
      onclick: function (e) { if (!e.target.closest('button, a, input, select')) toggle(a.id); } },
      [time, el('div', { class: 'c-sev' }, [badge]), el('div', { class: 'c-what' }, [what]),
        el('div', { class: 'c-cam', text: a.cameraName || '' }), who, photos, go]);

    var wrap = el('article', { class: 'alert', 'aria-label': 'Alerta ' + a.id + ': ' + titleOf(a) }, [row]);
    if (isOpen) wrap.appendChild(buildDetail(a, lit));
    return wrap;
  }

  // ------------------------------------------------------------------ pintado
  function byNewest(x, y) { return y.id - x.id; }

  function render() {
    var loggedIn = !!state.operator;
    $('gate').hidden = loggedIn;
    $('appBody').hidden = !loggedIn;
    $('controls').hidden = !loggedIn;
    $('navOperator').hidden = !loggedIn;
    $('authBtn').hidden = !loggedIn;
    $('navOperatorName').textContent = state.operator || 'Operador';
    $('soundToggle').checked = state.soundOn;
    updateUnlockButton();
    if (!loggedIn) return;

    var pending = state.alerts.filter(function (a) { return !a.acknowledged; }).sort(byNewest);
    var done = state.alerts.filter(function (a) { return a.acknowledged; }).sort(byNewest);
    var count = function (sev) { return pending.filter(function (a) { return a.severity === sev; }).length; };
    [['countHigh', 'HIGH'], ['countMed', 'MEDIUM'], ['countLow', 'LOW']].forEach(function (c) {
      var n = count(c[1]);
      $(c[0]).textContent = String(n);
      $(c[0]).parentNode.classList.toggle('zero', n === 0);
    });
    $('countPending').textContent = String(pending.length);
    $('countDone').textContent = String(done.length);

    var openId = state.openId === undefined ? (pending[0] ? pending[0].id : null) : state.openId;
    var open = function (a) { return a.id === openId; };

    $('feed').replaceChildren.apply($('feed'), pending.map(function (a) { return buildRow(a, open(a)); }));
    $('emptyState').hidden = pending.length > 0;

    var shown = done.slice(0, 20);
    $('doneToggle').hidden = done.length === 0;
    $('doneToggle').setAttribute('aria-expanded', state.showDone ? 'true' : 'false');
    $('doneLabel').textContent = 'Revisadas · ' + done.length;
    $('doneNote').textContent = done.length > shown.length ? 'se muestran las ' + shown.length + ' más recientes' : '';
    $('doneFeed').replaceChildren.apply($('doneFeed'), state.showDone ? shown.map(function (a) { return buildRow(a, open(a)); }) : []);

    state.alerts.forEach(function (a) { a.fresh = false; }); // la barrida de la fila nueva suena una sola vez
  }

  function toggle(id) {
    var current = state.openId === undefined ? firstPendingId() : state.openId;
    state.openId = current === id ? null : id;
    render();
  }
  function firstPendingId() {
    var pending = state.alerts.filter(function (a) { return !a.acknowledged; }).sort(byNewest);
    return pending[0] ? pending[0].id : null;
  }

  function acknowledge(id) {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify({ action: 'ack', id: id }));
    state.alerts.forEach(function (a) { if (a.id === id) a.acknowledged = true; });
    if (state.openId === id) state.openId = undefined;
    render();
    var next = document.querySelector('#feed .c-go .btn');
    if (next) next.focus({ preventScroll: true });
  }

  $('doneToggle').addEventListener('click', function () { state.showDone = !state.showDone; render(); });
  $('resetDemo').addEventListener('click', function () { resetAlerts(); render(); });
  $('soundToggle').addEventListener('change', function (e) {
    state.soundOn = e.target.checked;
    store.set('localStorage', 'safemode.sound', state.soundOn ? 'on' : 'off');
    if (state.soundOn) ensureAudio();
    updateUnlockButton();
  });

  // Los "hace N min" se actualizan solos sin volver a pintar el tablero
  setInterval(function () {
    var nodes = document.querySelectorAll('small[data-ts]');
    for (var i = 0; i < nodes.length; i++) nodes[i].textContent = relTime(new Date(Number(nodes[i].getAttribute('data-ts'))));
  }, 15000);

  // ------------------------------------------------------------------ alarma sonora (Web Audio API, sin archivos)
  var audioCtx = null;
  function ensureAudio() {
    var Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return null;
    if (!audioCtx) { try { audioCtx = new Ctx(); } catch (e) { return null; } }
    if (audioCtx.state === 'suspended') audioCtx.resume().then(updateUnlockButton, function () {});
    return audioCtx;
  }
  function audioReady() { return !!audioCtx && audioCtx.state === 'running'; }
  function updateUnlockButton() { $('unlockBtn').hidden = !state.soundOn || audioReady(); }
  function playAlarm() {
    if (!state.soundOn) return;
    var ctx = ensureAudio();
    if (!ctx || ctx.state !== 'running') return;
    var now = ctx.currentTime;
    [0, 0.35].forEach(function (offset) {
      var osc = ctx.createOscillator();
      var gain = ctx.createGain();
      osc.type = 'square';
      osc.frequency.setValueAtTime(880, now + offset);
      osc.frequency.setValueAtTime(660, now + offset + 0.15);
      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.25, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.28);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + offset);
      osc.stop(now + offset + 0.3);
    });
  }
  $('unlockBtn').addEventListener('click', function () {
    var ctx = ensureAudio();
    if (!ctx) return;
    ctx.resume().then(function () { updateUnlockButton(); playAlarm(); }, function () {});
  });
  // El navegador bloquea el audio hasta el primer gesto del usuario
  ['click', 'keydown'].forEach(function (type) {
    document.addEventListener(type, function () { if (state.soundOn && state.operator) ensureAudio(); }, { once: true });
  });

  // ------------------------------------------------------------------ foto ampliada
  var lightbox = $('lightbox');
  var lastFocus = null;
  function openLightbox(title, time, photo) {
    lastFocus = document.activeElement;
    $('lbTitle').textContent = title;
    $('lbTime').textContent = time;
    $('lbStamp').textContent = time;
    var stage = $('lbEmpty').parentNode;
    var old = stage.querySelector('img');
    if (old) old.remove();
    $('lbEmpty').hidden = !!photo;
    if (photo) stage.insertBefore(el('img', { src: photo, alt: title }), stage.firstChild);
    lightbox.showModal();
  }
  $('lbClose').addEventListener('click', function () { lightbox.close(); });
  lightbox.addEventListener('click', function (e) { if (e.target === lightbox) lightbox.close(); });
  lightbox.addEventListener('close', function () { if (lastFocus) lastFocus.focus({ preventScroll: true }); });

  // ------------------------------------------------------------------ acceso del operador (maqueta: acepta cualquier usuario y clave)
  var loginForm = $('loginForm');
  loginForm.addEventListener('submit', function (e) {
    e.preventDefault();
    var user = $('usuario').value.trim();
    var pass = $('clave').value;
    if (!user || !pass) {
      $('loginError').hidden = false;
      (user ? $('clave') : $('usuario')).focus();
      return;
    }
    $('loginError').hidden = true;
    state.operator = user;
    store.set('sessionStorage', 'safemode.operator', user);
    loginForm.reset();
    render();
    if (state.soundOn) ensureAudio();
  });
  $('authBtn').addEventListener('click', function () {
    state.operator = null;
    store.remove('sessionStorage', 'safemode.operator');
    render();
    $('usuario').focus();
  });

  // ------------------------------------------------------------------ conexion en tiempo real (realtime-gateway)
  var ws = null;
  var MAX_CAMERAS = 2; // el MVP vigila hasta 2 camaras; la tabla guarda mas nombres de los que se usan
  var WS_URL = new URLSearchParams(location.search).get('ws') ||
    ((location.protocol === 'https:' ? 'wss://' : 'ws://') + (location.hostname || 'localhost') + ':8090');

  function aiPending(photo, desc, ai, createdAt) {
    return !ai && !!(photo || desc) && (Date.now() - new Date(createdAt).getTime()) < 90000;
  }
  function normalize(a) {
    withDate(a);
    a.removerAiPending = aiPending(a.removerPhoto, a.removerDescription, a.removerAiDescription, a.createdAt);
    a.ownerAiPending = aiPending(a.ownerPhoto, a.ownerDescription, a.ownerAiDescription, a.createdAt);
    return a;
  }
  function upsert(raw, isNew) {
    var a = normalize(raw);
    var i = state.alerts.findIndex(function (x) { return x.id === a.id; });
    a.isNew = i >= 0 ? (state.alerts[i].isNew && !a.acknowledged) : !!isNew;
    a.fresh = i < 0 && !!isNew;
    if (i >= 0) state.alerts[i] = a; else state.alerts.push(a);
    return a;
  }
  function setConn(ok) {
    $('connText').textContent = ok ? 'Conectado · en vivo' : 'Desconectado · reintentando…';
    $('connPill').className = 'pill ' + (ok ? 'live' : 'down');
  }
  function renderCameras(list) {
    state.cameras = (list || []).slice(0, MAX_CAMERAS);
    var box = $('camPills');
    box.replaceChildren.apply(box, state.cameras.map(function (c) {
      return el('span', { class: 'pill' }, [el('span', { class: 'dot' }), c.name || ('CAM ' + c.id)]);
    }));
  }
  function connect() {
    ws = new WebSocket(WS_URL);
    ws.onopen = function () { setConn(true); };
    ws.onmessage = function (e) {
      var msg;
      try { msg = JSON.parse(e.data); } catch (err) { return; }
      if (msg.type === 'snapshot') {
        state.alerts = [];
        msg.alerts.forEach(function (a) { upsert(a, false); });
        state.openId = undefined;
        if (msg.cameras) renderCameras(msg.cameras);
      } else if (msg.type === 'alert') {
        var created = upsert(msg.alert, true);
        state.openId = created.id; // la alerta nueva se abre sola para ver la evidencia
        playAlarm();
        if (window.SafemodeNotify) window.SafemodeNotify.onNewAlert(msg.alert);
      } else if (msg.type === 'update') {
        upsert(msg.alert, false);
      } else if (msg.type === 'cameras') {
        renderCameras(msg.cameras);
        // una camara renombrada cambia el nombre de las alertas ya mostradas
        render();
        return;
      } else { return; }
      render();
    };
    ws.onclose = function () { setConn(false); setTimeout(connect, 2000); };
    ws.onerror = function () { ws.close(); };
  }

  resetAlerts();
  if (DEMO) {
    $('demoTag').hidden = false;
    $('connText').textContent = 'Modo demo';
  } else {
    $('resetDemo').hidden = true;
    connect();
  }
  render();
})();
