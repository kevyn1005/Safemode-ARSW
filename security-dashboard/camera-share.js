/*
 * "Compartir ventana de la camara": el operador elige la ventana del software de camaras (como al compartir pantalla en una
 * videollamada) y este script manda su imagen al tracker por WebSocket (ws://<host>:8091, mensajes binarios JPEG).
 * La captura es de LA VENTANA, no de una zona de la pantalla: otras ventanas encima no la estorban.
 *
 * Modos (selector "Camaras"):
 *  - 1 camara: se envia la ventana completa, como siempre (sin zonas).
 *  - 2 camaras: la ventana es un mosaico; el operador dibuja con el mouse la zona de CAM 1 y de CAM 2 sobre una vista
 *    previa, se hace una prueba previa (tamano de la ventana y parecido de cada zona) y se envia cada recorte marcado con su
 *    camara (un byte con el numero de camara antes del JPEG). Las zonas quedan guardadas en este navegador, en fracciones
 *    de la ventana. Ver docs/adr/0003 y camera-zones.js (la logica pura, con pruebas en Node).
 *
 * Limites: la ventana de la camara NO puede estar minimizada, y la pestana de este panel debe seguir abierta.
 * Se pinta dentro de <div id="cameraShareSlot"></div>. Todo el texto se pone con textContent.
 */
(function () {
  var slot = document.getElementById('cameraShareSlot');
  if (!slot) return;
  var Z = window.CameraZones; // camera-zones.js; si falta, solo funciona el modo de 1 camara

  var FRAMES_PORT = 8091;
  var SEND_EVERY_MS = 500;      // 2 fotogramas por segundo por camara, igual que la captura de pantalla
  var MAX_WIDTH = 1920;
  var JPEG_QUALITY = 0.85;
  var MAX_BUFFERED_BYTES = 2 * 1024 * 1024; // si el tracker no alcanza, se salta el envio en vez de acumular
  var MODE_KEY = 'safemode-cameras';
  var ZONES_KEY = 'safemode-zones';
  var ZONE_COLORS = { 1: '#4FD1C5', 2: '#F5C065' };

  // ------------------------------------------------------------------ almacenamiento (puede estar bloqueado)
  function readStorage(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function writeStorage(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* bloqueado: se ignora */ } }

  function savedMode() { return Z && readStorage(MODE_KEY) === '2' ? 2 : 1; }

  // ------------------------------------------------------------------ controles en la pagina
  var modeLabel = document.createElement('label');
  modeLabel.style.display = 'inline-flex';
  modeLabel.style.alignItems = 'center';
  modeLabel.style.gap = '6px';
  modeLabel.style.fontSize = '13px';
  modeLabel.appendChild(document.createTextNode('Cámaras'));
  var modeSelect = document.createElement('select');
  modeSelect.setAttribute('aria-label', 'Número de cámaras a vigilar');
  [1, 2].forEach(function (n) {
    var option = document.createElement('option');
    option.value = String(n);
    option.textContent = String(n);
    if (n === 2 && !Z) option.disabled = true;
    modeSelect.appendChild(option);
  });
  modeSelect.value = String(savedMode());
  modeLabel.appendChild(modeSelect);

  var button = document.createElement('button');
  button.type = 'button';
  button.className = slot.getAttribute('data-button-class') || '';
  var status = document.createElement('span');
  status.setAttribute('role', 'status');
  status.style.fontSize = '13px';
  slot.style.display = 'inline-flex';
  slot.style.flexWrap = 'wrap';
  slot.style.alignItems = 'center';
  slot.style.gap = '8px';
  slot.appendChild(modeLabel);
  slot.appendChild(button);
  slot.appendChild(status);

  modeSelect.addEventListener('change', function () {
    writeStorage(MODE_KEY, modeSelect.value);
  });

  // ------------------------------------------------------------------ estado
  var stream = null, video = null, canvas = null, ticker = null, socket = null, reconnectTimer = null;
  var sharingMode = 1;       // modo con que se empezo a compartir
  var config = null;         // 2 camaras: {cameras, reference:{w,h}, zones:[{id,x,y,w,h,fp}]}
  var names = {};            // id de camara -> nombre (de GET /cameras)
  var sending = false;       // hay un envio en curso: no se solapan ticks
  var paused = false;        // 2 camaras: la ventana cambio de tamano y se espera confirmacion

  function cameraName(id) { return names[id] || ('CAM ' + id); }

  function loadNames() {
    return fetch('/cameras', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) { list.forEach(function (c) { names[c.id] = c.name; }); })
      .catch(function () { /* sin nombres editados: se usan CAM n */ });
  }

  function setIdle(message) {
    button.textContent = 'Compartir ventana de la cámara';
    status.textContent = message || '';
    modeSelect.disabled = false;
  }

  function framesUrl() {
    var host = location.hostname || 'localhost';
    return (location.protocol === 'https:' ? 'wss://' : 'ws://') + host + ':' + FRAMES_PORT;
  }

  function connectSocket() {
    if (!stream) return;
    socket = new WebSocket(framesUrl());
    socket.binaryType = 'arraybuffer';
    socket.onopen = function () { showSharing(true); };
    socket.onclose = function () {
      socket = null;
      if (!stream) return;
      showSharing(false);
      reconnectTimer = setTimeout(connectSocket, 2000);
    };
    socket.onerror = function () { if (socket) socket.close(); };
  }

  function showSharing(connected) {
    var track = stream && stream.getVideoTracks()[0];
    var name = (track && track.label) || 'ventana';
    button.textContent = 'Dejar de compartir';
    modeSelect.disabled = true;
    var what = sharingMode === 2
      ? '2 cámaras (' + cameraName(1) + ', ' + cameraName(2) + ')'
      : '1 cámara';
    if (paused) {
      status.textContent = 'Pausado: la ventana «' + name + '» cambió de tamaño. Revisa las zonas para continuar.';
    } else if (connected) {
      status.textContent = 'Compartiendo «' + name + '» · ' + what + '. No minimices la cámara; sí puedes taparla con otras ventanas.';
    } else {
      status.textContent = 'Compartiendo «' + name + '», pero el tracker no responde (¿está corriendo? Pulsa «Iniciar vigilancia»). Reintentando…';
    }
  }

  // ------------------------------------------------------------------ envio de fotogramas
  function drawCrop(sx, sy, sw, sh) {
    var scale = Math.min(1, MAX_WIDTH / sw);
    canvas.width = Math.max(1, Math.round(sw * scale));
    canvas.height = Math.max(1, Math.round(sh * scale));
    canvas.getContext('2d').drawImage(video, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
  }

  function canvasToBytes() {
    return new Promise(function (resolve) {
      canvas.toBlob(function (blob) {
        if (!blob) { resolve(null); return; }
        blob.arrayBuffer().then(function (buffer) { resolve(new Uint8Array(buffer)); });
      }, 'image/jpeg', JPEG_QUALITY);
    });
  }

  function socketReady() {
    return socket && socket.readyState === WebSocket.OPEN && socket.bufferedAmount <= MAX_BUFFERED_BYTES;
  }

  function sendFrame() {
    if (!stream || !socketReady() || sending || paused) return;
    var w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return;

    if (sharingMode === 1) {
      // una camara: la ventana completa, sin byte de camara (el formato de siempre)
      drawCrop(0, 0, w, h);
      sending = true;
      canvasToBytes().then(function (bytes) {
        if (bytes && socketReady()) socket.send(bytes);
      }).then(function () { sending = false; }, function () { sending = false; });
      return;
    }

    if (Z.sizeChanged(config.reference, w, h)) {
      paused = true;
      showSharing(true);
      reviewZones(); // vuelve a abrir la verificacion
      return;
    }
    sending = true;
    config.zones.reduce(function (chain, zone) {
      return chain.then(function () {
        var px = Z.toPixels(zone, w, h);
        drawCrop(px.sx, px.sy, px.sw, px.sh);
        return canvasToBytes().then(function (bytes) {
          if (bytes && socketReady()) socket.send(Z.frameMessage(zone.id, bytes));
        });
      });
    }, Promise.resolve()).then(function () { sending = false; }, function () { sending = false; });
  }

  // El reloj corre en un Worker: los temporizadores de una pestana en segundo plano se ralentizan, los de un Worker menos.
  function startTicker() {
    var source = 'setInterval(function () { postMessage(0); },' + SEND_EVERY_MS + ');';
    var url = URL.createObjectURL(new Blob([source], { type: 'text/javascript' }));
    try {
      ticker = new Worker(url);
      ticker.onmessage = sendFrame;
    } catch (e) {
      ticker = setInterval(sendFrame, SEND_EVERY_MS); // sin Workers (politica de la pagina): temporizador normal
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  function stopSharing(message) {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (ticker) {
      if (typeof ticker.terminate === 'function') ticker.terminate(); else clearInterval(ticker);
      ticker = null;
    }
    var oldSocket = socket;
    socket = null;
    if (oldSocket) oldSocket.close();
    if (stream) stream.getTracks().forEach(function (t) { t.onended = null; t.stop(); });
    stream = null;
    if (video) { video.srcObject = null; video = null; }
    canvas = null;
    config = null;
    paused = false;
    sending = false;
    setIdle(message);
  }

  // ------------------------------------------------------------------ dialogo de zonas (2 camaras)
  var dialogOpen = false;

  /** Abre el dialogo y devuelve una promesa con la configuracion confirmada, o null si se cancelo. */
  function openZoneDialog(initial) {
    return new Promise(function (resolve) {
      dialogOpen = true;
      var dialog = document.createElement('dialog');
      dialog.setAttribute('aria-labelledby', 'safemodeZonesTitle');
      var style = dialog.style;
      style.background = '#12161c'; style.color = '#e8ebf0'; style.border = '1px solid #2f3644';
      style.borderRadius = '10px'; style.padding = '16px'; style.maxWidth = '780px'; style.width = 'calc(100% - 32px)';
      style.font = '14px/1.45 system-ui, sans-serif';

      function el(tag, text, css) {
        var node = document.createElement(tag);
        if (text) node.textContent = text;
        if (css) node.style.cssText = css;
        return node;
      }

      var title = el('h2', 'Configurar cámaras', 'margin:0 0 4px;font-size:18px');
      title.id = 'safemodeZonesTitle';
      var help = el('p', '', 'margin:0 0 10px;color:#9aa7b2;font-size:13px');
      var preview = document.createElement('canvas');
      preview.style.cssText = 'width:100%;display:block;background:#000;border-radius:6px;cursor:crosshair;touch-action:none';
      preview.setAttribute('aria-label', 'Vista previa de la ventana compartida: arrastra para marcar la zona de cada cámara');
      var picker = el('div', '', 'display:flex;flex-wrap:wrap;gap:8px;margin:10px 0');
      var checks = el('ul', '', 'margin:0 0 12px;padding-left:18px;font-size:13px');
      var actions = el('div', '', 'display:flex;flex-wrap:wrap;gap:8px;justify-content:flex-end');
      dialog.appendChild(title); dialog.appendChild(help); dialog.appendChild(preview);
      dialog.appendChild(picker); dialog.appendChild(checks); dialog.appendChild(actions);

      // borrador: las zonas guardadas (si las hay) y el tamano de referencia
      var draft = {
        reference: initial ? { w: initial.reference.w, h: initial.reference.h } : { w: video.videoWidth, h: video.videoHeight },
        zones: initial ? initial.zones.map(function (z) { return { id: z.id, x: z.x, y: z.y, w: z.w, h: z.h, fp: z.fp }; }) : []
      };
      var active = 1;
      var dragStart = null, dragNow = null;
      var vw = video.videoWidth, vh = video.videoHeight;
      preview.width = Math.min(720, vw);
      preview.height = Math.round(preview.width * vh / vw);
      var ctx = preview.getContext('2d');
      var probe = document.createElement('canvas'); // para sacar la huella de cada zona
      probe.width = 32; probe.height = 32;
      var probeCtx = probe.getContext('2d', { willReadFrequently: true });

      function zoneOf(id) { return draft.zones.filter(function (z) { return z.id === id; })[0]; }

      function currentFingerprints() {
        var result = {};
        draft.zones.forEach(function (zone) {
          var px = Z.toPixels(zone, video.videoWidth, video.videoHeight);
          probeCtx.drawImage(video, px.sx, px.sy, px.sw, px.sh, 0, 0, 32, 32);
          result[zone.id] = Z.fingerprint(probeCtx.getImageData(0, 0, 32, 32).data, 32, 32);
        });
        return result;
      }

      var confirmedSize = false; // el operador confirmo que las zonas siguen bien aunque el tamano cambio

      function evaluate() {
        var problems = Z.zoneProblems(draft.zones, 2);
        var pre = Z.preflight({ reference: draft.reference, zones: draft.zones }, video.videoWidth, video.videoHeight, currentFingerprints());
        var blocking = confirmedSize ? [] : pre.blocking;
        return { problems: problems, blocking: blocking, warnings: pre.warnings };
      }

      var startButton = el('button', 'Iniciar');
      var redrawButton = el('button', 'Volver a dibujar');
      var confirmButton = el('button', 'Las zonas siguen bien');
      var cancelButton = el('button', 'Cancelar');
      [startButton, redrawButton, confirmButton, cancelButton].forEach(function (b) {
        b.type = 'button';
        b.style.cssText = 'font:inherit;padding:6px 14px;border-radius:6px;border:1px solid #4FD1C5;background:transparent;color:#4FD1C5;cursor:pointer';
      });
      startButton.style.background = '#4FD1C5'; startButton.style.color = '#0b0f13'; startButton.style.fontWeight = '700';
      cancelButton.style.borderColor = '#2f3644'; cancelButton.style.color = '#9aa7b2';
      actions.appendChild(cancelButton); actions.appendChild(redrawButton); actions.appendChild(confirmButton); actions.appendChild(startButton);

      function renderPicker() {
        picker.textContent = '';
        [1, 2].forEach(function (id) {
          var b = el('button', cameraName(id) + (zoneOf(id) ? ' ✓' : ' (sin zona)'));
          b.type = 'button';
          var on = id === active;
          b.style.cssText = 'font:inherit;padding:4px 12px;border-radius:6px;cursor:pointer;border:2px solid ' + ZONE_COLORS[id] +
            ';background:' + (on ? ZONE_COLORS[id] : 'transparent') + ';color:' + (on ? '#0b0f13' : ZONE_COLORS[id]);
          b.setAttribute('aria-pressed', String(on));
          b.addEventListener('click', function () { active = id; refresh(); });
          picker.appendChild(b);
        });
        help.textContent = 'Arrastra sobre la vista previa para marcar la zona de ' + cameraName(active) +
          '. Cada zona debe cubrir una sola cámara del mosaico; lo que quede fuera se ignora.';
      }

      function renderChecks() {
        var r = evaluate();
        checks.textContent = '';
        function item(mark, color, text) {
          var li = document.createElement('li');
          li.style.color = color;
          li.textContent = mark + ' ' + text;
          checks.appendChild(li);
        }
        r.problems.forEach(function (p) { item('✗', '#FF9A8B', p); });
        r.blocking.forEach(function (p) { item('✗', '#FF9A8B', p); });
        r.warnings.forEach(function (p) { item('⚠', '#F5C065', p); });
        if (!r.problems.length && !r.blocking.length) {
          item('✓', '#4FD1C5', 'Tamaño de la ventana ' + video.videoWidth + '×' + video.videoHeight + ' y zonas listas.');
        }
        startButton.disabled = r.problems.length > 0 || r.blocking.length > 0;
        startButton.style.opacity = startButton.disabled ? '0.4' : '1';
        confirmButton.hidden = !(r.blocking.length > 0 && !r.problems.length);
        return r;
      }

      function drawPreview() {
        ctx.drawImage(video, 0, 0, preview.width, preview.height);
        draft.zones.forEach(function (z) { outline(z, ZONE_COLORS[z.id], cameraName(z.id)); });
        if (dragStart && dragNow) {
          var rect = Z.rectFromPoints(dragStart.x, dragStart.y, dragNow.x, dragNow.y);
          if (rect) outline(rect, ZONE_COLORS[active], cameraName(active));
        }
      }

      function outline(zone, color, label) {
        var px = Z.toPixels(zone, preview.width, preview.height);
        ctx.lineWidth = 3; ctx.strokeStyle = color; ctx.strokeRect(px.sx, px.sy, px.sw, px.sh);
        ctx.font = '600 14px system-ui, sans-serif';
        var width = ctx.measureText(label).width + 10;
        ctx.fillStyle = color; ctx.fillRect(px.sx, px.sy, width, 20);
        ctx.fillStyle = '#0b0f13'; ctx.fillText(label, px.sx + 5, px.sy + 15);
      }

      function pointOf(event) {
        var box = preview.getBoundingClientRect();
        return { x: (event.clientX - box.left) / box.width, y: (event.clientY - box.top) / box.height };
      }

      preview.addEventListener('pointerdown', function (event) {
        preview.setPointerCapture(event.pointerId);
        dragStart = dragNow = pointOf(event);
      });
      preview.addEventListener('pointermove', function (event) { if (dragStart) dragNow = pointOf(event); });
      preview.addEventListener('pointerup', function (event) {
        if (!dragStart) return;
        var rect = Z.rectFromPoints(dragStart.x, dragStart.y, pointOf(event).x, pointOf(event).y);
        dragStart = dragNow = null;
        if (!rect) return; // un clic suelto o una zona minuscula no cuenta
        draft.zones = draft.zones.filter(function (z) { return z.id !== active; });
        draft.zones.push({ id: active, x: rect.x, y: rect.y, w: rect.w, h: rect.h, fp: null });
        draft.zones.sort(function (a, b) { return a.id - b.id; });
        draft.reference = { w: video.videoWidth, h: video.videoHeight }; // desde ahora el tamano de referencia es este
        confirmedSize = false;
        active = active === 1 && !zoneOf(2) ? 2 : active; // despues de CAM 1 se pasa a CAM 2
        refresh();
      });

      function refresh() { renderPicker(); renderChecks(); }

      function close(result) {
        clearInterval(previewTimer);
        clearInterval(checksTimer);
        dialog.close();
        dialog.remove();
        dialogOpen = false;
        resolve(result);
      }

      startButton.addEventListener('click', function () {
        var r = evaluate();
        if (r.problems.length || r.blocking.length) return;
        var fingerprints = currentFingerprints(); // la huella de referencia es como se ve cada zona AHORA
        close({
          cameras: 2,
          reference: { w: video.videoWidth, h: video.videoHeight },
          zones: draft.zones.map(function (z) { return { id: z.id, x: z.x, y: z.y, w: z.w, h: z.h, fp: fingerprints[z.id] }; })
        });
      });
      redrawButton.addEventListener('click', function () {
        draft.zones = []; active = 1; confirmedSize = false;
        draft.reference = { w: video.videoWidth, h: video.videoHeight };
        refresh();
      });
      confirmButton.addEventListener('click', function () {
        confirmedSize = true;
        draft.reference = { w: video.videoWidth, h: video.videoHeight };
        draft.zones.forEach(function (z) { z.fp = null; }); // con otro tamano la huella vieja ya no sirve de referencia
        refresh();
      });
      cancelButton.addEventListener('click', function () { close(null); });
      dialog.addEventListener('cancel', function (event) { event.preventDefault(); close(null); }); // tecla Escape

      document.body.appendChild(dialog);
      dialog.showModal();
      var previewTimer = setInterval(drawPreview, 150);
      var checksTimer = setInterval(renderChecks, 1000);
      refresh();
      startButton.focus();
    });
  }

  /** Revisa las zonas (al empezar y cuando la ventana cambia de tamano). */
  function reviewZones() {
    if (dialogOpen) return;
    openZoneDialog(config).then(function (confirmed) {
      if (!stream) return; // se dejo de compartir mientras tanto
      if (!confirmed) { stopSharing('No se configuraron las zonas.'); return; }
      config = confirmed;
      writeStorage(ZONES_KEY, Z.serialize(config));
      paused = false;
      showSharing(!!(socket && socket.readyState === WebSocket.OPEN));
    });
  }

  // ------------------------------------------------------------------ compartir
  function whenVideoHasSize() {
    return new Promise(function (resolve, reject) {
      var tries = 0;
      (function check() {
        if (video && video.videoWidth > 0) { resolve(); return; }
        if (++tries > 50) { reject(new Error('la ventana no entrego imagen')); return; }
        setTimeout(check, 100);
      })();
    });
  }

  function startSharing() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      setIdle('Este navegador no permite compartir pantalla (usa Chrome o Edge, en localhost).');
      return;
    }
    sharingMode = Number(modeSelect.value) === 2 && Z ? 2 : 1;
    navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: 5, max: 10 } },
      audio: false,
      selfBrowserSurface: 'exclude'
    }).then(function (s) {
      stream = s;
      video = document.createElement('video');
      video.muted = true;
      video.playsInline = true;
      video.srcObject = s;
      video.play().catch(function () { /* se reintenta con los fotogramas siguientes */ });
      canvas = document.createElement('canvas');
      var track = s.getVideoTracks()[0];
      track.onended = function () { stopSharing('Se dejó de compartir la ventana de la cámara.'); };
      track.onmute = function () { status.textContent = 'La ventana no envía imagen (¿está minimizada?). Restáurala para seguir detectando.'; };
      track.onunmute = function () { showSharing(socket && socket.readyState === WebSocket.OPEN); };
      button.textContent = 'Dejar de compartir';
      modeSelect.disabled = true;
      status.textContent = 'Preparando la ventana…';

      return whenVideoHasSize().then(function () {
        if (sharingMode === 1) return null;
        return loadNames().then(function () {
          // zonas guardadas con el mismo numero de camaras: se muestran para la prueba previa
          var stored = Z.parseStored(readStorage(ZONES_KEY));
          var usable = stored && stored.cameras === 2 && stored.zones.length === 2 ? stored : null;
          return openZoneDialog(usable);
        });
      }).then(function (confirmed) {
        if (!stream) return; // se dejo de compartir mientras se configuraba
        if (sharingMode === 2) {
          if (!confirmed) { stopSharing('No se configuraron las zonas.'); return; }
          config = confirmed;
          writeStorage(ZONES_KEY, Z.serialize(config));
        }
        showSharing(false);
        startTicker();
        connectSocket();
      });
    }).catch(function (err) {
      if (stream) stopSharing('');
      setIdle(err && err.name === 'NotAllowedError' ? 'No se eligió ninguna ventana.' : 'No se pudo compartir: ' + (err && err.message || err));
    });
  }

  button.addEventListener('click', function () {
    if (stream) stopSharing(''); else startSharing();
  });
  setIdle('');
})();
