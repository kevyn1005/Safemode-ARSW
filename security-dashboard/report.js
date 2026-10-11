/*
 * Boton "Generar reporte": pide GET /report al gateway y muestra en una tabla TODO lo que registro el sistema, tambien lo que no
 * genero alerta (por ejemplo, el dueno que recoge su mochila). Cada fila trae fecha, hora, camara, que paso, objeto, dueno probable,
 * quien lo retiro, si genero alerta y las fotos. El centro de alertas no cambia.
 * Se pinta dentro de <div id="reportSlot"></div>. Todo el texto del servidor (descripciones de la IA incluidas) se pone con
 * textContent, y una foto solo se acepta si su URL es /photo/<archivo>. La logica de formato es pura y se prueba con
 * `node --test "security-dashboard/test/*.test.js"` (no necesita navegador).
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (typeof document !== 'undefined') api.mount(document);
})(typeof self !== 'undefined' ? self : this, function () {
  var EVENTS = { REGISTERED_AT_REST: 'Registrado en reposo', REMOVED: 'Retirado' };
  var OBJECTS = { backpack: 'Mochila', handbag: 'Bolso', suitcase: 'Maleta' };
  var REMOVALS = {
    BY_OWNER: 'Lo retiró su dueño',
    BY_OTHER: 'Lo retiró otra persona',
    OWNER_AND_OTHER_NEAR: 'Estaban cerca el dueño y otra persona',
    NO_ONE_NEAR: 'Desapareció sin nadie cerca',
    OWNER_UNKNOWN: 'Lo retiraron y no tenía dueño registrado'
  };
  var ALERTS = {
    POSSIBLE_THEFT: 'Posible robo', AMBIGUOUS_REMOVAL: 'Retiro ambiguo',
    OBJECT_VANISHED: 'Objeto desaparecido', UNKNOWN_OWNER_REMOVAL: 'Retiro sin dueño registrado'
  };
  var SEVERITIES = { HIGH: 'Alta', MEDIUM: 'Media', LOW: 'Baja' };
  var PHOTO_URL = /^\/photo\/[^\/?#\\]+$/;

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  /** Fecha (dd/mm/aaaa) y hora (hh:mm:ss, 24 h) en la hora de este computador; vacias si el texto no es una fecha. */
  function formatWhen(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return { date: '', time: '' };
    return {
      date: pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear(),
      time: pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())
    };
  }

  /** La URL de la foto si es de las que sirve el gateway (/photo/<archivo>); si no, null: nunca se usa una URL ajena. */
  function safePhoto(url) {
    return typeof url === 'string' && PHOTO_URL.test(url) ? url : null;
  }

  function person(id, aiText, localText) {
    var label = id === null || id === undefined ? '' : 'Persona #' + id;
    var text = aiText || localText || '';
    return { label: label, text: text, estimated: !aiText && !!localText };
  }

  /** Lo que dice el reporte sobre si ese evento genero alerta (y por que no, cuando no la genera). */
  function verdict(row) {
    if (row.eventType !== 'REMOVED') return { text: 'Solo se registra (no es alerta)', level: 'info' };
    if (row.alertType) {
      var severity = SEVERITIES[row.severity] || row.severity || '';
      return { text: (ALERTS[row.alertType] || row.alertType) + (severity ? ' · ' + severity : ''), level: String(row.severity || 'low').toLowerCase() };
    }
    return { text: row.removalKind === 'BY_OWNER' ? 'Sin alerta: lo retiró su dueño' : 'Sin alerta', level: 'none' };
  }

  /** Una fila del servidor convertida en los textos que se muestran (sin HTML). */
  function describeRow(row) {
    var when = formatWhen(row.occurredAt);
    var objectName = OBJECTS[row.className] || row.className || 'Objeto';
    var photos = [];
    [['Objeto', row.framePhoto], ['Dueño', row.ownerPhoto], ['Quien lo retiró', row.removerPhoto]].forEach(function (p) {
      var url = safePhoto(p[1]);
      if (url) photos.push({ label: p[0], url: url });
    });
    return {
      date: when.date,
      time: when.time,
      camera: row.cameraName || ('CAM ' + (row.cameraId || 1)),
      what: EVENTS[row.eventType] || row.eventType || '',
      detail: row.eventType === 'REMOVED' ? (REMOVALS[row.removalKind] || row.removalKind || '') : '',
      object: objectName + (row.trackedObjectId ? ' #' + row.trackedObjectId : ''),
      owner: person(row.ownerPersonId, row.ownerAiDescription, row.ownerDescription),
      remover: row.eventType === 'REMOVED' ? person(row.removerPersonId, row.removerAiDescription, row.removerDescription) : person(null, '', ''),
      verdict: verdict(row),
      photos: photos
    };
  }

  /** Resumen de la lista: cuantos objetos se registraron, cuantos retiros y cuantos generaron alerta. */
  function summarize(rows) {
    var summary = { total: rows.length, registered: 0, removed: 0, alerts: 0 };
    rows.forEach(function (row) {
      if (row.eventType === 'REGISTERED_AT_REST') summary.registered++;
      if (row.eventType === 'REMOVED') summary.removed++;
      if (row.alertType) summary.alerts++;
    });
    return summary;
  }

  var COLORS = { high: '#FF6B5E', medium: '#F5C065', low: '#7DB7FF', info: '#4FD1C5', none: '#9aa7b2' };

  function mount(doc) {
    var slot = doc.getElementById('reportSlot');
    if (!slot) return;

    var button = doc.createElement('button');
    button.type = 'button';
    button.className = slot.getAttribute('data-button-class') || '';
    button.textContent = 'Generar reporte';
    slot.appendChild(button);

    function el(tag, text, css) {
      var node = doc.createElement(tag);
      if (text) node.textContent = text;
      if (css) node.style.cssText = css;
      return node;
    }

    function cell(content, css) {
      var td = el('td', typeof content === 'string' ? content : '', 'padding:8px 10px;border-top:1px solid #2f3644;vertical-align:top;' + (css || ''));
      if (content && typeof content !== 'string') td.appendChild(content);
      return td;
    }

    function personCell(info) {
      var box = doc.createElement('div');
      if (!info.label && !info.text) { box.textContent = '—'; box.style.color = '#9aa7b2'; return box; }
      if (info.label) box.appendChild(el('div', info.label, 'font-weight:600'));
      if (info.text) box.appendChild(el('div', info.text, 'color:#c3ccd3'));
      if (info.estimated) box.appendChild(el('div', 'color estimado por imagen', 'color:#9aa7b2;font-size:11px'));
      return box;
    }

    function photosCell(photos) {
      var box = el('div', '', 'display:flex;gap:6px;flex-wrap:wrap');
      if (!photos.length) { box.textContent = '—'; box.style.color = '#9aa7b2'; return box; }
      photos.forEach(function (photo) {
        var link = doc.createElement('a');
        link.href = photo.url;
        link.target = '_blank';
        link.rel = 'noopener';
        link.title = photo.label + ' (se abre en una pestaña nueva)';
        var img = doc.createElement('img');
        img.src = photo.url;
        img.alt = photo.label;
        img.loading = 'lazy';
        img.style.cssText = 'width:72px;height:54px;object-fit:cover;border-radius:4px;border:1px solid #2f3644;display:block';
        link.appendChild(img);
        img.addEventListener('error', function () { // la foto ya no esta en el disco
          if (link.parentNode) link.parentNode.replaceChild(el('span', 'no disponible', 'display:block;width:72px;font-size:11px;color:#9aa7b2'), link);
        });
        var figure = el('div', '', 'text-align:center');
        figure.appendChild(link);
        figure.appendChild(el('div', photo.label, 'font-size:10px;color:#9aa7b2;margin-top:2px'));
        box.appendChild(figure);
      });
      return box;
    }

    function renderTable(rows) {
      var table = el('table', '', 'width:100%;border-collapse:collapse;font-size:13px;min-width:860px');
      var head = doc.createElement('tr');
      ['Fecha', 'Hora', 'Cámara', 'Qué pasó', 'Objeto', 'Dueño probable', 'Quién lo retiró', '¿Alerta?', 'Fotos'].forEach(function (name) {
        head.appendChild(el('th', name, 'text-align:left;padding:8px 10px;position:sticky;top:0;background:#1a212a;color:#9aa7b2;font-weight:600;white-space:nowrap'));
      });
      var thead = doc.createElement('thead');
      thead.appendChild(head);
      table.appendChild(thead);
      var body = doc.createElement('tbody');
      rows.forEach(function (row) {
        var info = describeRow(row);
        var tr = doc.createElement('tr');
        tr.appendChild(cell(info.date, 'white-space:nowrap'));
        tr.appendChild(cell(info.time, 'white-space:nowrap;font-variant-numeric:tabular-nums'));
        tr.appendChild(cell(info.camera, 'white-space:nowrap;font-weight:600;color:#4FD1C5'));
        var what = el('div');
        what.appendChild(el('div', info.what, 'font-weight:600'));
        if (info.detail) what.appendChild(el('div', info.detail, 'color:#9aa7b2;font-size:12px'));
        tr.appendChild(cell(what));
        tr.appendChild(cell(info.object, 'white-space:nowrap'));
        tr.appendChild(cell(personCell(info.owner), 'max-width:220px'));
        tr.appendChild(cell(personCell(info.remover), 'max-width:220px'));
        tr.appendChild(cell(el('span', info.verdict.text, 'color:' + (COLORS[info.verdict.level] || COLORS.none) + ';font-weight:600')));
        tr.appendChild(cell(photosCell(info.photos)));
        body.appendChild(tr);
      });
      table.appendChild(body);
      return table;
    }

    function openReport() {
      var dialog = doc.createElement('dialog');
      dialog.setAttribute('aria-labelledby', 'safemodeReportTitle');
      dialog.style.cssText = 'background:#12161c;color:#e8ebf0;border:1px solid #2f3644;border-radius:10px;padding:16px;' +
        'width:calc(100% - 32px);max-width:1180px;font:14px/1.45 system-ui,sans-serif';
      var title = el('h2', 'Reporte de detecciones', 'margin:0 0 4px;font-size:18px');
      title.id = 'safemodeReportTitle';
      dialog.appendChild(title);
      dialog.appendChild(el('p', 'Todo lo que registró el sistema, también lo que no generó alerta. La hora es la del computador donde corre ' +
        'el sistema: busca en la grabación de la cámara unos 30 segundos antes y después.', 'margin:0 0 10px;color:#9aa7b2;font-size:13px'));

      var bar = el('div', '', 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:0 0 10px');
      var summary = el('span', 'Cargando…', 'flex:1 1 220px;font-size:13px;color:#c3ccd3');
      summary.setAttribute('role', 'status');
      var refresh = el('button', 'Actualizar');
      var close = el('button', 'Cerrar');
      [refresh, close].forEach(function (b) {
        b.type = 'button';
        b.style.cssText = 'font:inherit;padding:6px 14px;border-radius:6px;border:1px solid #4FD1C5;background:transparent;color:#4FD1C5;cursor:pointer';
      });
      close.style.borderColor = '#2f3644'; close.style.color = '#9aa7b2';
      bar.appendChild(summary);
      bar.appendChild(refresh);
      bar.appendChild(close);
      dialog.appendChild(bar);

      var content = el('div', '', 'max-height:60vh;overflow:auto;border:1px solid #2f3644;border-radius:6px');
      dialog.appendChild(content);

      function show(text, isError) {
        content.textContent = '';
        content.appendChild(el('p', text, 'margin:0;padding:16px;color:' + (isError ? '#FF9A8B' : '#c3ccd3')));
        if (isError) content.firstChild.setAttribute('role', 'alert');
      }

      function load() {
        refresh.disabled = true;
        summary.textContent = 'Cargando…';
        fetch('/report', { cache: 'no-store' }).then(function (response) {
          if (response.status === 404) throw new Error('este panel no ofrece el reporte (abre el panel que sirve el gateway)');
          if (!response.ok) throw new Error('HTTP ' + response.status);
          return response.json();
        }).then(function (report) {
          var rows = Array.isArray(report.events) ? report.events : [];
          var sums = summarize(rows);
          summary.textContent = sums.total + ' evento(s) · ' + sums.registered + ' objeto(s) registrado(s) · ' + sums.removed +
            ' retiro(s) · ' + sums.alerts + ' con alerta · lo más reciente primero';
          if (!rows.length) {
            show('Todavía no hay eventos. El sistema registra un objeto cuando lo ve quieto durante 3 segundos y registra un retiro cuando ' +
              'deja de verlo. Si no aparece nada, revisa object-presence-tracker/data/tracker.log para ver qué está detectando.', false);
            return;
          }
          content.textContent = '';
          content.appendChild(renderTable(rows));
        }).catch(function (err) {
          summary.textContent = '';
          show('No se pudo cargar el reporte: ' + (err && err.message || err), true);
        }).then(function () { refresh.disabled = false; });
      }

      function closeDialog() { dialog.close(); dialog.remove(); }
      close.addEventListener('click', closeDialog);
      refresh.addEventListener('click', load);
      dialog.addEventListener('cancel', function (event) { event.preventDefault(); closeDialog(); });

      doc.body.appendChild(dialog);
      dialog.showModal();
      close.focus();
      load();
    }

    button.addEventListener('click', openReport);
  }

  return { describeRow: describeRow, summarize: summarize, formatWhen: formatWhen, safePhoto: safePhoto, mount: mount };
});
