// Utilidades para probar los scripts del panel que se pintan solos al cargarse (IIFE sobre `document`): se ejecuta el archivo REAL dentro
// de jsdom, con un fetch falso y temporizadores que la prueba dispara a mano. El script se corre con su ruta como nombre de archivo, para
// que c8 (cobertura) le atribuya las lineas ejecutadas; evaluado como texto suelto no contaria.
const { JSDOM } = require('jsdom');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

/** Respuesta de fetch con lo minimo que usan los scripts: status, ok y json(). */
function respuesta(status, cuerpo) {
  return Promise.resolve({
    status,
    ok: status >= 200 && status < 300,
    json: () => Promise.resolve(cuerpo),
    text: () => Promise.resolve(typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo)),
  });
}

/**
 * Carga `archivo` (relativo a security-dashboard/) en una pagina nueva.
 * @param {object} o  html: cuerpo de la pagina; fetch: (url, opciones) => Promise; antes: (window) => void para preparar el entorno;
 * previos: scripts a cargar antes; url: direccion de la pagina.
 * @returns {{dom, window, document, temporizadores, intervalos, esperar}}
 */
function cargar(archivo, o = {}) {
  const dom = new JSDOM(o.html || '<!doctype html><html><body></body></html>', {
    runScripts: 'outside-only',
    pretendToBeVisual: true,
    url: o.url || 'http://localhost:8080/',
  });
  const w = dom.window;
  const temporizadores = [];   // setTimeout pendientes: la prueba los dispara con disparar(n)
  const intervalos = [];       // setInterval registrados (no corren solos)
  w.setTimeout = (fn, ms) => { temporizadores.push({ fn, ms, activo: true }); return temporizadores.length; };
  w.clearTimeout = (id) => { if (temporizadores[id - 1]) temporizadores[id - 1].activo = false; };
  w.setInterval = (fn, ms) => { intervalos.push({ fn, ms, activo: true }); return intervalos.length; };
  w.clearInterval = (id) => { if (intervalos[id - 1]) intervalos[id - 1].activo = false; };
  w.fetch = o.fetch || (() => Promise.reject(new Error('fetch no preparado')));
  if (o.antes) o.antes(w);
  // `previos`: otros scripts que el panel carga antes (p. ej. camera-zones.js, que camera-share.js usa como window.CameraZones)
  [...(o.previos || []), archivo].forEach((nombre) => {
    const ruta = path.join(__dirname, '..', '..', nombre);
    new vm.Script(fs.readFileSync(ruta, 'utf8'), { filename: ruta }).runInContext(dom.getInternalVMContext());
  });
  return {
    dom,
    window: w,
    document: w.document,
    temporizadores,
    intervalos,
    /** Deja correr las promesas pendientes (los .then de un fetch falso). */
    esperar: () => new Promise((r) => setImmediate(() => setImmediate(r))),
    /** Dispara un setTimeout registrado (por defecto, el ultimo). */
    disparar: (n = temporizadores.length) => { const t = temporizadores[n - 1]; if (t && t.activo) { t.activo = false; t.fn(); } },
    cerrar: () => w.close(),
  };
}

module.exports = { cargar, respuesta };
