/*
 * Logica pura (sin DOM) de las zonas de camara: dibujar, guardar, comprobar y recortar. Se separa de camera-share.js para
 * poder probarla con Node sin navegador ni camara (ver security-dashboard/test/camera-zones.test.js).
 *
 * Una zona es un rectangulo EN FRACCIONES (0 a 1) del ancho y el alto de la ventana compartida: asi sigue sobre la misma
 * camara si la ventana cambia de escala. Se guarda junto con el tamano de referencia de la ventana y una huella (cuadricula
 * de brillo) de lo que habia en la zona, para la prueba previa a iniciar (ADR 0003).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.CameraZones = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  var MAX_CAMERAS = 8;
  var MIN_FRACTION = 0.05;       // una zona mas chica que el 5 % del ancho o del alto no se acepta (fue un clic suelto)
  var SIZE_TOLERANCE_PX = 2;     // la ventana "cambio de tamano" si ancho o alto difieren en mas de esto
  var LOOKS_DIFFERENT = 0.30;    // distancia de huella (0 a 1) a partir de la cual la zona "ya no se parece"
  var MAX_OVERLAP = 0.30;        // dos zonas no pueden compartir mas de esta fraccion de la mas chica
  var GRID = 16;                 // la huella es una cuadricula de GRID x GRID celdas

  function clamp01(v) { return Math.min(1, Math.max(0, v)); }

  /** Rectangulo entre dos puntos (en fracciones, en cualquier orden), o null si queda mas chico que el minimo. */
  function rectFromPoints(x0, y0, x1, y1) {
    var left = clamp01(Math.min(x0, x1)), right = clamp01(Math.max(x0, x1));
    var top = clamp01(Math.min(y0, y1)), bottom = clamp01(Math.max(y0, y1));
    var w = right - left, h = bottom - top;
    if (w < MIN_FRACTION || h < MIN_FRACTION) return null;
    return { x: left, y: top, w: w, h: h };
  }

  /** La zona en pixeles enteros dentro de una imagen de width x height (para recortar con drawImage). */
  function toPixels(zone, width, height) {
    var sx = Math.min(width - 1, Math.max(0, Math.round(zone.x * width)));
    var sy = Math.min(height - 1, Math.max(0, Math.round(zone.y * height)));
    var sw = Math.max(1, Math.min(width - sx, Math.round(zone.w * width)));
    var sh = Math.max(1, Math.min(height - sy, Math.round(zone.h * height)));
    return { sx: sx, sy: sy, sw: sw, sh: sh };
  }

  /** Cuanto de la zona mas chica cubre la interseccion de las dos (0 a 1). */
  function overlapFraction(a, b) {
    var ix = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
    var iy = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
    var smaller = Math.min(a.w * a.h, b.w * b.h);
    return smaller <= 0 ? 0 : (ix * iy) / smaller;
  }

  /** Problemas de un conjunto de zonas (arreglo de textos; vacio = todo bien). */
  function zoneProblems(zones, expectedCount) {
    var problems = [];
    if (zones.length !== expectedCount) {
      problems.push('Faltan zonas: hay ' + zones.length + ' y se necesitan ' + expectedCount + '.');
    }
    for (var i = 0; i < zones.length; i++) {
      for (var j = i + 1; j < zones.length; j++) {
        if (overlapFraction(zones[i], zones[j]) > MAX_OVERLAP) {
          problems.push('Las zonas de CAM ' + zones[i].id + ' y CAM ' + zones[j].id + ' se solapan demasiado.');
        }
      }
    }
    return problems;
  }

  /** True si la ventana ya no tiene el tamano con que se dibujaron las zonas. */
  function sizeChanged(reference, width, height) {
    return Math.abs(reference.w - width) > SIZE_TOLERANCE_PX || Math.abs(reference.h - height) > SIZE_TOLERANCE_PX;
  }

  /** Huella de una imagen RGBA: brillo medio (0 a 1) de cada celda de una cuadricula GRID x GRID. */
  function fingerprint(rgba, width, height) {
    var sums = new Array(GRID * GRID).fill(0);
    var counts = new Array(GRID * GRID).fill(0);
    for (var y = 0; y < height; y++) {
      var cy = Math.min(GRID - 1, Math.floor(y * GRID / height));
      for (var x = 0; x < width; x++) {
        var cx = Math.min(GRID - 1, Math.floor(x * GRID / width));
        var i = (y * width + x) * 4;
        var cell = cy * GRID + cx;
        sums[cell] += (0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2]) / 255;
        counts[cell]++;
      }
    }
    return sums.map(function (sum, cell) { return counts[cell] ? sum / counts[cell] : 0; });
  }

  /** Diferencia media de brillo entre dos huellas (0 = iguales, 1 = opuestas); Infinity si no son comparables. */
  function fingerprintDistance(a, b) {
    if (!a || !b || a.length !== b.length || a.length === 0) return Infinity;
    var total = 0;
    for (var i = 0; i < a.length; i++) total += Math.abs(a[i] - b[i]);
    return total / a.length;
  }

  function looksDifferent(a, b) { return fingerprintDistance(a, b) > LOOKS_DIFFERENT; }

  /** Mensaje binario para el tracker: un byte con la camara (1 a 8) y despues la imagen JPEG. */
  function frameMessage(cameraId, jpegBytes) {
    if (!(cameraId >= 1 && cameraId <= MAX_CAMERAS)) throw new RangeError('camara fuera de rango: ' + cameraId);
    var message = new Uint8Array(jpegBytes.length + 1);
    message[0] = cameraId;
    message.set(jpegBytes, 1);
    return message;
  }

  function isFraction(v) { return typeof v === 'number' && isFinite(v) && v >= 0 && v <= 1; }

  /**
   * Lee lo guardado en el navegador (texto JSON) y lo valida; devuelve null si no hay nada o algo esta mal. Lo guardado puede
   * venir roto o manipulado, asi que no se confia en su forma: {cameras, reference: {w, h}, zones: [{id, x, y, w, h, fp}]}.
   */
  function parseStored(text) {
    var data;
    try { data = JSON.parse(text); } catch (e) { return null; }
    if (!data || typeof data !== 'object' || !Array.isArray(data.zones) || !data.reference) return null;
    var ref = data.reference;
    if (!(Number.isInteger(ref.w) && Number.isInteger(ref.h) && ref.w > 0 && ref.h > 0)) return null;
    if (!Number.isInteger(data.cameras) || data.cameras < 1 || data.cameras > MAX_CAMERAS) return null;
    if (data.zones.length > MAX_CAMERAS) return null;
    var seen = {};
    var zones = [];
    for (var i = 0; i < data.zones.length; i++) {
      var z = data.zones[i];
      if (!z || !Number.isInteger(z.id) || z.id < 1 || z.id > MAX_CAMERAS || seen[z.id]) return null;
      if (!(isFraction(z.x) && isFraction(z.y) && isFraction(z.w) && isFraction(z.h))) return null;
      if (z.w < MIN_FRACTION || z.h < MIN_FRACTION || z.x + z.w > 1.0001 || z.y + z.h > 1.0001) return null;
      var fp = null;
      if (z.fp != null) {
        if (!Array.isArray(z.fp) || z.fp.length !== GRID * GRID || !z.fp.every(isFraction)) return null;
        fp = z.fp;
      }
      seen[z.id] = true;
      zones.push({ id: z.id, x: z.x, y: z.y, w: z.w, h: z.h, fp: fp });
    }
    return { cameras: data.cameras, reference: { w: ref.w, h: ref.h }, zones: zones };
  }

  function serialize(config) { return JSON.stringify(config); }

  /**
   * Resultado de la prueba previa para unas zonas guardadas y la ventana actual: tamano igual o no, y por zona si se parece a
   * la huella guardada. {blocking: [...textos], warnings: [...textos]}; el tamano distinto BLOQUEA y el parecido solo avisa.
   */
  function preflight(config, width, height, currentFingerprints) {
    var blocking = [], warnings = [];
    if (sizeChanged(config.reference, width, height)) {
      blocking.push('La ventana cambió de tamaño (era ' + config.reference.w + '×' + config.reference.h + ' y ahora es ' + width + '×' + height +
        '). Vuelve a dibujar las zonas o confirma que siguen bien puestas.');
    }
    config.zones.forEach(function (zone) {
      var now = currentFingerprints && currentFingerprints[zone.id];
      if (zone.fp && now && looksDifferent(zone.fp, now)) {
        warnings.push('La zona de CAM ' + zone.id + ' ya no se parece a como la dejaste: ¿sigue sobre la misma cámara?');
      }
    });
    return { blocking: blocking, warnings: warnings };
  }

  return {
    MAX_CAMERAS: MAX_CAMERAS, MIN_FRACTION: MIN_FRACTION, LOOKS_DIFFERENT: LOOKS_DIFFERENT, GRID: GRID,
    rectFromPoints: rectFromPoints, toPixels: toPixels, overlapFraction: overlapFraction, zoneProblems: zoneProblems,
    sizeChanged: sizeChanged, fingerprint: fingerprint, fingerprintDistance: fingerprintDistance,
    looksDifferent: looksDifferent, frameMessage: frameMessage, parseStored: parseStored, serialize: serialize,
    preflight: preflight
  };
});
