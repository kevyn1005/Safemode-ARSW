| Escalón (VUs) | req/s k6 | req/s servidor | Latencia media k6 (ms) | Media servidor (ms) | P95 servidor (ms)* | Errores HTTP | Errores WS (/s) | CPU máx. (núcleos) | Alertas entregadas (/s) | Entrega media k6 (ms) | Entrega P95 servidor (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 38.1 | 35.2 | 316 | 302 | 2034 | 0.00 % | 0.0 | 0.7 | 49.6 | 428 | 1507 |
| 50 | 33.8 | 34.2 | 1123 | 1146 | 4920 | 0.00 % | 0.0 | 0.7 | 27.0 | 4048 | 3810 |
| 100 | 47.5 | 47.7 | 1783 | 1269 | 4647 | 0.00 % | 0.3 | 0.7 | 27.8 | 5896 | 3250 |
| 200 | 40.0 | 39.1 | 4690 | 1630 | 5000 | 0.00 % | 18.7 | 0.7 | 0.0 | sin entregas | 8028 |
| 300 | 36.1 | 42.2 | 7045 | 1624 | 5000 | 0.00 % | 33.8 | 0.7 | 0.0 | sin entregas | 7897 |
| 400 | 48.1 | 49.5 | 7919 | 1347 | 4700 | 0.00 % | 48.8 | 0.6 | 0.0 | sin entregas | 4136 |
| 600 | 35.8 | 35.2 | 16617 | 1855 | 5000 | 0.00 % | 79.8 | 0.7 | 0.0 | sin entregas | 7781 |

\* El histograma del servidor termina en 5 s: un valor de 5000 significa «5 s o más» (la media a su lado muestra lo que el tope esconde). La latencia media de k6 es la del cliente (incluye la espera antes de que el servidor atienda). Cada escalón mide solo su meseta (se descartan los primeros 5 s tras la subida).
