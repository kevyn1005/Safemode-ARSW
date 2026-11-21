// Hace de "Grafana": un servidor SIN autenticacion que contesta 200 a todo y registra cada peticion que le llega. Si Caddy bloquea
// de verdad, una peticion sin sesion NO debe aparecer en este registro.
const http = require('http');
const fs = require('fs');
const log = process.argv[2];
http.createServer((req, res) => {
  fs.appendFileSync(log, JSON.stringify({ metodo: req.method, url: req.url, tieneCookie: !!req.headers['cookie'] }) + '\n');
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('grafana-upstream');
}).listen(18082, '127.0.0.1');
