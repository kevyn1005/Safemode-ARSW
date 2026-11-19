/*
 * Logica pura (sin DOM) de la conexion en tiempo real del panel: a que servidor se conecta el WebSocket de alertas y que
 * hacer cuando se cierra. Se separa de index.html para poder probarla con Node sin navegador (ver
 * security-dashboard/test/realtime-session.test.js).
 *
 * Auditoria de seguridad (pista 4): antes, la URL del WebSocket salia del parametro ?ws= de la pagina, asi que un enlace como
 * http://localhost:8080/?ws=wss://sitio-ajeno/ hacia que el panel REAL (con el operador ya dentro) mostrara las alertas que
 * le mandara ese servidor ajeno, con el rotulo "Conectado · en vivo". Ahora la URL sale SOLO de donde esta la pagina.
 *
 * Sesion vencida: el gateway cierra el WebSocket (codigo 1008) cuando la sesion que lo abrio termina (cerro sesion, vencio a
 * las 12 h o se reinicio el gateway) y rechaza los intentos nuevos. Antes el panel reintentaba cada 2 s para siempre sin decir
 * nada; ahora, al cerrarse, pregunta a /auth/me si la sesion sigue viva y, si no, pide iniciar sesion de nuevo.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.RealtimeSession = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  var WS_PORT = 8090;       // el mismo que fija GatewayRunner (WS_PORT)
  var RETRY_MS = 2000;      // espera antes de volver a conectar cuando la sesion sigue viva

  /**
   * URL del WebSocket de alertas: el mismo servidor que entrego la pagina, en el puerto del gateway. Solo mira el protocolo y
   * el nombre del servidor; NO lee la query ni el hash, asi que nada que venga en un enlace puede cambiar el destino.
   */
  function socketUrl(loc) {
    var scheme = loc && loc.protocol === 'https:' ? 'wss://' : 'ws://';
    var host = (loc && loc.hostname) || 'localhost';
    return scheme + host + ':' + WS_PORT;
  }

  /**
   * Que hacer cuando el socket se cierra con el operador todavia "dentro" de la pagina. fetchFn es fetch (se recibe como
   * parametro para probarlo). Devuelve una promesa de 'expired' (el servidor dice que ya no hay sesion: HTTP 401) o 'retry'
   * (cualquier otra cosa, incluido servidor caido o sin red: se reintenta como antes).
   */
  function decideAfterClose(fetchFn) {
    return Promise.resolve()
      .then(function () { return fetchFn('/auth/me', { credentials: 'same-origin' }); })
      .then(function (response) { return response && response.status === 401 ? 'expired' : 'retry'; })
      .catch(function () { return 'retry'; });
  }

  return { WS_PORT: WS_PORT, RETRY_MS: RETRY_MS, socketUrl: socketUrl, decideAfterClose: decideAfterClose };
});
