| Escalón (VUs) | req/s k6 | req/s servidor | Latencia media k6 (ms) | Media servidor (ms) | P95 servidor (ms)* | Errores HTTP | Errores WS (/s) | CPU máx. (núcleos) | Alertas entregadas (/s) | Entrega media k6 (ms) | Entrega P95 servidor (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 25 | 73.3 | 73.0 | 21 | 2 | 48 | 0.00 % | 0.0 | 0.2 | 50.4 | 278 | 853 |
| 50 | 143.5 | 143.3 | 26 | 2 | 48 | 0.00 % | 0.0 | 0.2 | 100.6 | 252 | 833 |
| 100 | 210.9 | 211.5 | 148 | 125 | 826 | 0.00 % | 0.0 | 2.0 | 198.8 | 2379 | 8977 |
| 200 | 467.2 | 483.5 | 105 | 77 | 469 | 0.00 % | 0.0 | 2.0 | 317.4 | 1601 | 9433 |
| 300 | 633.3 | 656.0 | 147 | 50 | 234 | 0.00 % | 0.0 | 1.6 | 688.2 | 4093 | 9313 |
| 400 | 677.5 | 679.3 | 256 | 44 | 236 | 0.00 % | 2.5 | 2.1 | 902.1 | 6742 | 10000 |
| 600 | 569.9 | 545.5 | 785 | 97 | 447 | 0.00 % | 9.2 | 1.8 | 1231.4 | 9920 | 10000 |

\* El histograma del servidor termina en 5 s: un valor de 5000 significa «5 s o más» (la media a su lado muestra lo que el tope esconde). La latencia media de k6 es la del cliente (incluye la espera antes de que el servidor atienda). Cada escalón mide solo su meseta (se descartan los primeros 5 s tras la subida).
