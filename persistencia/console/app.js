// Consola del escenario: cada pestaña conserva la version que leyo (asi se reproduce la edicion perdida con dos pestanas).
const PUESTO = 'puesto-1';
let version = null;
const el = id => document.getElementById(id);

async function call(path, method = 'GET', body, admin = false) {
  const headers = {'Content-Type': 'application/json', 'X-Operator': el('operator').value};
  if (admin) headers['X-Lab-Key'] = el('key').value;
  const started = performance.now();
  try {
    const r = await fetch(path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
    const raw = await r.text();
    let data;
    try { data = JSON.parse(raw); } catch { data = {message: raw}; }
    const ms = Math.round(performance.now() - started);
    el('status').textContent = `HTTP ${r.status} · ${method} ${path} · ${ms} ms · ${new Date().toLocaleTimeString()}`;
    el('status').style.color = r.ok ? '#1b633a' : '#990000';
    el('response').textContent = JSON.stringify(data, null, 2);
    return r.ok ? data : null;
  } catch (error) {
    el('status').textContent = 'No se pudo conectar con el servidor';
    el('response').textContent = error.message;
    return null;
  }
}

function apply(data) {
  if (!data) return;
  version = data.version;
  el('title').value = data.title;
  if (data.preferences && data.preferences.minSeverity) el('minSeverity').value = data.preferences.minSeverity;
  el('version').textContent = `Versión ${version}`;
}

el('load').onclick = async () => apply(await call(`/api/puestos/${PUESTO}`));
el('save').onclick = async () => {
  if (version === null) { el('status').textContent = 'Primero cargue una versión.'; return; }
  apply(await call(`/api/puestos/${PUESTO}`, 'PUT',
    {expectedVersion: version, title: el('title').value, preferences: {minSeverity: el('minSeverity').value}}));
};
el('permission').onclick = () => call(`/api/permissions/${PUESTO}`);
el('touch').onclick = () => call(`/api/presence/${PUESTO}`, 'POST');
el('presence').onclick = () => call(`/api/presence/${PUESTO}`);
el('revoke').onclick = () => call(`/access/permissions/${PUESTO}/luis`, 'PUT', {canConfigure: false}, true);
el('grant').onclick = () => call(`/access/permissions/${PUESTO}/luis`, 'PUT', {canConfigure: true}, true);
el('reset').onclick = async () => {
  if (confirm('¿Restablecer título, versión, permisos y presencia de este laboratorio?')) {
    const r = await call('/api/lab/reset', 'POST', {}, true);
    if (r) apply(await call(`/api/puestos/${PUESTO}`));
  }
};
