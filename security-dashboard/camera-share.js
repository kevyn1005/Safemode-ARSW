/*
 * "Compartir ventana de la camara": el operador elige la ventana de la camara (como al compartir pantalla en una
 * videollamada) y este script manda su imagen al tracker por WebSocket (ws://<host>:8091, mensajes binarios JPEG).
 * La captura es de LA VENTANA, no de una zona de la pantalla: otras ventanas encima no la estorban.
 * Limites: la ventana de la camara NO puede estar minimizada, y la pestana de este panel debe seguir abierta
 * (si se minimiza todo el navegador, Chrome la ralentiza y pueden llegar menos imagenes).
 * Se pinta dentro de <div id="cameraShareSlot"></div>. Todo el texto se pone con textContent.
 */
(function () {
  var slot = document.getElementById('cameraShareSlot');
  if (!slot) return;

  var FRAMES_PORT = 8091;
  var SEND_EVERY_MS = 500;      // 2 fotogramas por segundo, igual que la captura de pantalla
  var MAX_WIDTH = 1920;
  var JPEG_QUALITY = 0.85;
  var MAX_BUFFERED_BYTES = 2 * 1024 * 1024; // si el tracker no alcanza, se salta el envio en vez de acumular

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
  slot.appendChild(button);
  slot.appendChild(status);

  var stream = null, video = null, canvas = null, ticker = null, socket = null, reconnectTimer = null;

  function setIdle(message) {
    button.textContent = 'Compartir ventana de la cámara';
    status.textContent = message || '';
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
    status.textContent = connected
      ? 'Compartiendo «' + name + '». No minimices la cámara; sí puedes taparla con otras ventanas.'
      : 'Compartiendo «' + name + '», pero el tracker no responde (¿está corriendo con SAFEMODE_FRAME_SOURCE=browser?). Reintentando…';
  }

  function sendFrame() {
    if (!stream || !socket || socket.readyState !== WebSocket.OPEN) return;
    if (socket.bufferedAmount > MAX_BUFFERED_BYTES) return;
    var w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return;
    var scale = Math.min(1, MAX_WIDTH / w);
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(function (blob) {
      if (blob && socket && socket.readyState === WebSocket.OPEN) socket.send(blob);
    }, 'image/jpeg', JPEG_QUALITY);
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
    setIdle(message);
  }

  function startSharing() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
      setIdle('Este navegador no permite compartir pantalla (usa Chrome o Edge, en localhost).');
      return;
    }
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
      showSharing(false);
      startTicker();
      connectSocket();
    }).catch(function (err) {
      setIdle(err && err.name === 'NotAllowedError' ? 'No se eligió ninguna ventana.' : 'No se pudo compartir: ' + (err && err.message || err));
    });
  }

  button.addEventListener('click', function () {
    if (stream) stopSharing(''); else startSharing();
  });
  setIdle('');
})();
