/*
 * Boton "Iniciar vigilancia": le pide al gateway (POST /tracker/start) que lance el tracker, y "Detener vigilancia"
 * (POST /tracker/stop). No manda ningun dato al servidor: el comando que se ejecuta esta fijo en el gateway.
 * Si el gateway no ofrece el control (corre en Docker, o abierto a la red) /tracker/status da 404 y el control se oculta.
 * Se pinta dentro de <div id="trackerControlSlot"></div>. Todo el texto se pone con textContent.
 */
(function () {
  var slot = document.getElementById('trackerControlSlot');
  if (!slot) return;

  var POLL_MS = 2000;
  var CONFIRM_MS = 6000;

  var button = document.createElement('button');
  button.type = 'button';
  button.className = slot.getAttribute('data-button-class') || '';
  var status = document.createElement('span');
  status.setAttribute('role', 'status');
  status.style.fontSize = '13px';
  slot.style.display = 'none';       // se muestra cuando el gateway confirma que ofrece el control
  slot.style.flexWrap = 'wrap';
  slot.style.alignItems = 'center';
  slot.style.gap = '8px';
  slot.appendChild(button);
  slot.appendChild(status);

  var MODE_KEY = 'safemode-cameras'; // lo escribe camera-share.js (selector "Camaras")
  var startedCameras = null;         // con cuantas camaras se inicio desde ESTE panel (null = no se sabe)

  function chosenCameras() {
    try { return localStorage.getItem(MODE_KEY) === '2' ? 2 : 1; } catch (e) { return 1; }
  }

  var state = 'STOPPED';
  var exitCode = -1;
  var confirming = false;
  var confirmTimer = null;
  var busy = false;

  function render() {
    var running = state === 'RUNNING';
    var stopping = state === 'STOPPING';
    if (confirming) {
      button.textContent = 'Confirmar: borra fotos y eventos anteriores (' + chosenCameras() + ' cámara' + (chosenCameras() === 2 ? 's' : '') + ')';
      status.textContent = '';
    } else if (running) {
      button.textContent = 'Detener vigilancia';
      status.textContent = startedCameras && startedCameras !== chosenCameras()
        ? 'Vigilancia en marcha con ' + startedCameras + ' cámara(s), pero elegiste ' + chosenCameras() +
          '. Detén e inicia de nuevo la vigilancia para que el cambio surta efecto.'
        : 'Vigilancia en marcha. Comparte la ventana de la cámara cuando el tracker termine de cargar.';
    } else if (stopping) {
      button.textContent = 'Deteniendo…';
      status.textContent = 'Cerrando el tracker (espera las descripciones de IA pendientes, puede tardar hasta un minuto).';
    } else {
      button.textContent = 'Iniciar vigilancia';
      status.textContent = state === 'CRASHED'
        ? 'El tracker se cerró con error (código ' + exitCode + '). Revisa object-presence-tracker/data/tracker.log'
        : 'Vigilancia detenida.';
    }
    button.disabled = busy || stopping;
  }

  function apply(json) {
    state = json.state;
    exitCode = json.exitCode;
    slot.style.display = 'inline-flex';
    render();
  }

  function refresh() {
    fetch('/tracker/status', { cache: 'no-store' }).then(function (response) {
      if (response.status === 404) {   // el gateway no ofrece el control: se oculta y se deja de preguntar
        slot.style.display = 'none';
        clearInterval(poller);
        return null;
      }
      return response.ok ? response.json() : null;
    }).then(function (json) {
      if (json) apply(json);
    }).catch(function () { /* el gateway no responde: se reintenta en el siguiente ciclo */ });
  }

  function post(action) {
    busy = true;
    render();
    var cameras = action === 'start' ? chosenCameras() : 0;
    var query = action === 'start' ? '?cameras=' + cameras : '';
    var launched = false; // 200 = lo lanzo este clic; 409 = ya estaba corriendo (no se sabe con cuantas camaras)
    fetch('/tracker/' + action + query, { method: 'POST' }).then(function (response) {
      launched = response.status === 200;
      if (response.status === 200 || response.status === 409) return response.json();
      throw new Error('HTTP ' + response.status);
    }).then(function (json) {
      if (action === 'start' && launched) startedCameras = cameras;
      if (action === 'stop') startedCameras = null;
      apply(json);
    }).catch(function (err) {
      status.textContent = 'No se pudo completar la acción: ' + (err && err.message || err);
    }).then(function () {
      busy = false;
      render();
    });
  }

  function cancelConfirm() {
    confirming = false;
    if (confirmTimer) { clearTimeout(confirmTimer); confirmTimer = null; }
  }

  button.addEventListener('click', function () {
    if (state === 'RUNNING') {
      post('stop');
    } else if (confirming) {
      cancelConfirm();
      post('start');
    } else {
      // iniciar borra las fotos y los eventos de la corrida anterior: se pide un segundo clic
      confirming = true;
      confirmTimer = setTimeout(function () { cancelConfirm(); render(); }, CONFIRM_MS);
      render();
    }
  });

  var poller = setInterval(refresh, POLL_MS);
  refresh();
})();
