# Validación del escalado horizontal del gateway

Prueba ejecutable de lo que afirma [`arquitectura-de-disponibilidad.md`](../../arquitectura-de-disponibilidad.md): dos réplicas del gateway
detrás de un balanceador Caddy, con PostgreSQL real, y una réplica que cae mientras hay operadores conectados.

```powershell
powershell -ExecutionPolicy Bypass -File docs\disponibilidad\validaciones\escalado\ejecutar.ps1 -Salida docs\disponibilidad\evidencias\escalado-gateway.txt
```

Requisitos: Docker en marcha, Java 17+ y `gson` en `~/.m2` (lo trae el gateway), puertos `18080` y `18090` libres. La primera vez construye la
imagen del gateway (varios minutos); después tarda unos 2 minutos. Sin `-Mantener` baja el prototipo y borra su volumen al terminar.

**No toca tu stack normal:** usa el proyecto Compose `safemode-escalado`, su propia base y puertos distintos. La «sesión» es una fila de prueba
que el script inserta en la base del prototipo (con la huella SHA-256 del identificador, como el producto); no hay inicio de sesión de Google.

## Qué comprueba (17 comprobaciones)

| Bloque | Comprobación |
|---|---|
| 1 | La cookie creada una sola vez vale en las dos réplicas (30/30 con 200); el balanceador reparte; sin cookie o con una inventada, 401 |
| 2 | Seis operadores por WebSocket por el balanceador; quedan repartidos entre las dos réplicas |
| 3 | Una alerta nueva llega a los seis, una sola vez cada uno, en menos de 5 s |
| 4 | `docker kill` de una réplica: los operadores se reconectan solos; una alerta creada **durante** la caída no se pierde ni se duplica; la réplica vuelve (arrancada a mano: Docker trata `kill` como parada manual) |
| 5 | Una réplica termina sola (`SIGTERM` interno): Docker la reinicia y el balanceador la reincorpora sin intervención |
| 6 | Durante los dos fallos, 4 usuarios hacen una petición cada 50 ms: ninguna falla y la más lenta queda bajo el límite del cliente |

## Límites

Un solo equipo (las réplicas comparten CPU, red y disco), fallos inyectados por proceso, base y balanceador únicos, carga baja y sin Google, HTTPS ni
ALB reales. Detalle en la sección 6 del documento de arquitectura.

## Archivos

`ejecutar.ps1` (orquesta) · `ScaleProbe.java` (las comprobaciones; imita al panel: espera de 2 s, `/auth/me`, reconexión) ·
[`docker-compose.escalado.yml`](../../../../docker-compose.escalado.yml) · [`Caddyfile.escalado`](../../../../Caddyfile.escalado)
