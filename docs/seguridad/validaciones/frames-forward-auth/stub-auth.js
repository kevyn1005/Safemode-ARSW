// Imita el contrato de GET /auth/me del gateway: 200 con una cookie de sesion valida, 401 sin ella. Registra lo que Caddy le manda.
const http = require('http');
const fs = require('fs');
const log = process.argv[2];
http.createServer((req, res) => {
  const cookie = req.headers['cookie'] || '';
  const ok = cookie.includes('safemode_session=valida');
  fs.appendFileSync(log, JSON.stringify({ metodo: req.method, url: req.url, upgrade: req.headers['upgrade'] || null, connection: req.headers['connection'] || null,
    tieneCookie: !!cookie, forwardedUri: req.headers['x-forwarded-uri'] || null, forwardedMethod: req.headers['x-forwarded-method'] || null, respondo: ok ? 200 : 401 }) + '\n');
  res.writeHead(ok ? 200 : 401, { 'Content-Type': 'application/json' });
  res.end(ok ? '{"email":"operador@example.com"}' : '{"error":"sesion requerida"}');
}).listen(18081, '127.0.0.1');
