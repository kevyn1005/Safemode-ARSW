| Escalón (VUs) | req/s k6 | req/s servidor | Latencia media k6 (ms) | Media servidor (ms) | P95 servidor (ms)* | Errores HTTP | Errores WS (/s) | CPU máx. (núcleos) | Alertas entregadas (/s) | Entrega media k6 (ms) | Entrega P95 servidor (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 24.4 | 24.4 | 705 | 684 | 2328 | 0.00 % | 0.0 | 0.7 | 11.8 | 838 | 475 |
| 50 | 31.4 | 31.7 | 1253 | 997 | 4608 | 0.00 % | 0.6 | 0.8 | 6.3 | 11197 | 3250 |
| 100 | 34.0 | 32.1 | 2710 | 1059 | 4641 | 0.00 % | 10.5 | 0.7 | 0.0 | sin entregas | 5250 |
| 200 | 30.1 | 32.9 | 6137 | 1037 | 4632 | 0.00 % | 27.5 | 0.7 | 0.0 | sin entregas | 495 |
| 300 | 27.6 | 29.4 | 10467 | 1124 | 4638 | 0.00 % | 40.7 | 0.6 | 0.0 | sin entregas | 3300 |
| 400 | 29.5 | 31.6 | 12561 | 1092 | 4645 | 0.00 % | 56.3 | 0.7 | 0.0 | sin entregas | 475 |
| 600 | 36.7 | 37.5 | 14524 | 811 | 4514 | 0.00 % | 101.0 | 0.6 | 0.0 | sin entregas | 495 |

\* El histograma del servidor termina en 5 s: un valor de 5000 significa «5 s o más» (la media a su lado muestra lo que el tope esconde). La latencia media de k6 es la del cliente (incluye la espera antes de que el servidor atienda). Cada escalón mide solo su meseta (se descartan los primeros 5 s tras la subida).
