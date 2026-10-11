/*
 * Editor de los nombres de las camaras ("CAM 1" y "CAM 2" por defecto, el usuario los cambia cuando quiera). Lee GET /cameras y
 * guarda con POST /cameras/<id> (JSON {"name": "..."}); el gateway avisa a todos los paneles y las alertas ya mostradas
 * toman el nombre nuevo. Si el gateway no ofrece la ruta (404) el boton se oculta.
 * Se pinta dentro de <div id="cameraNamesSlot"></div>. Todo el texto se pone con textContent.
 */
(function () {
  var slot = document.getElementById('cameraNamesSlot');
  if (!slot) return;

  var VISIBLE_CAMERAS = 2;   // el MVP vigila hasta 2 camaras
  var MAX_NAME = 40;         // igual que PresenceEventStore

  var button = document.createElement('button');
  button.type = 'button';
  button.className = slot.getAttribute('data-button-class') || '';
  button.textContent = 'Nombres de cámaras';
  slot.style.display = 'none'; // se muestra cuando el gateway confirma que ofrece la ruta
  slot.appendChild(button);

  function el(tag, text, css) {
    var node = document.createElement(tag);
    if (text) node.textContent = text;
    if (css) node.style.cssText = css;
    return node;
  }

  function fetchCameras() {
    return fetch('/cameras', { cache: 'no-store' }).then(function (r) {
      if (r.status === 404) return null;
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  fetchCameras().then(function (list) {
    if (list) slot.style.display = '';
  }).catch(function () { /* sin gateway: el boton sigue oculto */ });

  function save(id, name) {
    return fetch('/cameras/' + id, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: name })
    }).then(function (r) {
      if (r.ok) return null;
      return r.text().then(function (text) { return text || ('HTTP ' + r.status); });
    });
  }

  function openEditor(cameras) {
    var dialog = document.createElement('dialog');
    dialog.setAttribute('aria-labelledby', 'safemodeNamesTitle');
    dialog.style.cssText = 'background:#12161c;color:#e8ebf0;border:1px solid #2f3644;border-radius:10px;padding:16px;' +
      'width:calc(100% - 32px);max-width:420px;font:14px/1.45 system-ui,sans-serif';
    var title = el('h2', 'Nombres de las cámaras', 'margin:0 0 4px;font-size:18px');
    title.id = 'safemodeNamesTitle';
    dialog.appendChild(title);
    dialog.appendChild(el('p', 'Se muestran en las alertas y en los reportes. Déjalo vacío para volver a «CAM n».',
      'margin:0 0 12px;color:#9aa7b2;font-size:13px'));

    var inputs = [];
    cameras.filter(function (c) { return c.id <= VISIBLE_CAMERAS; }).forEach(function (camera) {
      var label = el('label', 'Cámara ' + camera.id, 'display:block;margin:0 0 10px;font-size:13px;color:#c3ccd3');
      var input = document.createElement('input');
      input.type = 'text';
      input.value = camera.name;
      input.maxLength = MAX_NAME;
      input.style.cssText = 'display:block;width:100%;margin-top:4px;padding:6px 8px;border-radius:6px;border:1px solid #2f3644;' +
        'background:#0b0f13;color:#e8ebf0;font:inherit;box-sizing:border-box';
      label.appendChild(input);
      dialog.appendChild(label);
      inputs.push({ id: camera.id, original: camera.name, input: input });
    });

    var message = el('p', '', 'min-height:1.4em;margin:0 0 8px;font-size:13px;color:#FF9A8B');
    message.setAttribute('role', 'alert');
    var actions = el('div', '', 'display:flex;gap:8px;justify-content:flex-end');
    var cancel = el('button', 'Cancelar');
    var saveButton = el('button', 'Guardar');
    [cancel, saveButton].forEach(function (b) {
      b.type = 'button';
      b.style.cssText = 'font:inherit;padding:6px 14px;border-radius:6px;border:1px solid #4FD1C5;background:transparent;color:#4FD1C5;cursor:pointer';
    });
    saveButton.style.background = '#4FD1C5'; saveButton.style.color = '#0b0f13'; saveButton.style.fontWeight = '700';
    cancel.style.borderColor = '#2f3644'; cancel.style.color = '#9aa7b2';
    actions.appendChild(cancel);
    actions.appendChild(saveButton);
    dialog.appendChild(message);
    dialog.appendChild(actions);

    function close() { dialog.close(); dialog.remove(); }
    cancel.addEventListener('click', close);
    dialog.addEventListener('cancel', function (event) { event.preventDefault(); close(); });
    saveButton.addEventListener('click', function () {
      var changed = inputs.filter(function (i) { return i.input.value.trim() !== i.original; });
      saveButton.disabled = true;
      message.textContent = '';
      changed.reduce(function (chain, item) {
        return chain.then(function (error) {
          return error ? error : save(item.id, item.input.value);
        });
      }, Promise.resolve(null)).then(function (error) {
        saveButton.disabled = false;
        if (error) { message.textContent = 'No se pudo guardar: ' + error; return; }
        close();
      }, function (err) {
        saveButton.disabled = false;
        message.textContent = 'No se pudo guardar: ' + (err && err.message || err);
      });
    });

    document.body.appendChild(dialog);
    dialog.showModal();
    if (inputs.length) inputs[0].input.focus();
  }

  button.addEventListener('click', function () {
    fetchCameras().then(function (list) { if (list) openEditor(list); });
  });
})();
