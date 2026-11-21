# ADR 0006: escalado horizontal del gateway detrás de un balanceador (sesiones compartidas, alertas a todas las réplicas)

- Equipo / integrantes: **ARSW-EQP-01** — Hever Barrera Batero, María Juliana Rodríguez Caicedo, Kevyn Daniel Forero Gonzalez
- Estado: **aceptado para el prototipo**; el despliegue físico distribuido está **diseñado, no desplegado** (ver *Límites*).
- Rama: `heverpersistencia` · Documento hermano: [arquitectura de disponibilidad](../disponibilidad/arquitectura-de-disponibilidad.md)

## Contexto

El Release 2 exige que el backend escale horizontalmente detrás de un balanceador de carga y que no todo corra en un solo servidor.
Hoy el despliegue público es **un solo servidor EC2** con Caddy, el gateway, PostgreSQL, Grafana y el tracker: si cae el servidor, cae
todo (99,5 % según el SLA de una instancia EC2, unas 3,6 h de caída al mes).

Antes de poner dos réplicas del gateway hubo que comprobar qué las impedía. Se encontraron **tres defectos reales**, cada uno
reproducido con una prueba que falló antes de arreglarlo:

| # | Defecto con 2 réplicas | Efecto | Prueba |
|---|---|---|---|
| 1 | Una réplica solo avisa a sus operadores de las alertas que **ella misma** inserta | Los operadores de la otra réplica no recibían la alerta en vivo (ni el sonido) hasta recargar | `AlertGatewayReplicasTest` (2 de 3 fallaban) |
| 2 | `insertIfAbsent` hace `SELECT` y luego `INSERT`: dos réplicas pueden pasar el `SELECT` a la vez | La que pierde la carrera lanzaba `No se pudo guardar la alerta` (3 de 3 corridas) | `TwoReplicasTest` |
| 3 | Las sesiones de los operadores viven en la memoria de cada proceso | La cookie emitida por una réplica no valía en la otra (401) | `SharedSessionsTest` |

## Decisión

1. **Réplicas sin estado.** Todo lo que importa vive en la base compartida; cualquier réplica atiende a cualquier operador.
2. **Alertas a todas las réplicas por sondeo de la base** (cada 0,5 s las últimas 20 alertas; las que la réplica no conoce las anuncia
   a sus operadores). La unicidad la sigue decidiendo la base (`UNIQUE (event_id, event_occurred_at)`); quien pierde la carrera
   (`SQLState 23505`) no falla. Las notificaciones externas (Telegram) las sigue emitiendo solo la réplica que insertó la alerta.
3. **Sesiones compartidas opcionales** (`SAFEMODE_SESSION_STORE=database`; sin la variable, en memoria como siempre). La tabla
   `operator_session` guarda la **huella SHA-256** del identificador, nunca la cookie; si la base no responde, "no hay sesión" (falla
   cerrado); un valor desconocido de la variable impide arrancar (no se cae en memoria en silencio).
4. **Balanceador con vigilancia activa y pasiva** (`/ready` del gateway, que comprueba la base; reintento en la otra réplica; sin afinidad
   de sesión). En el prototipo es Caddy; en AWS sería un Application Load Balancer.

### Alternativas descartadas

| Alternativa | Por qué no |
|---|---|
| Afinidad de sesión por IP en el balanceador, sesiones en memoria | El HTTP (8080) y el WebSocket (8090) son dos grupos de réplicas: cualquier diferencia de salud entre ellos manda al operador a réplicas distintas (401). Operadores tras la misma NAT quedan en una sola réplica y, si esta cae, todos pierden la sesión |
| Redis *pub/sub* para avisar a las demás réplicas | Añade una dependencia más en el camino crítico de la alerta. El sondeo de la base reutiliza lo que ya es obligatorio (la base) y su costo es una consulta de 20 filas cada 0,5 s |
| Sesiones firmadas sin estado (JWT propio) | No se podrían revocar al cerrar sesión; hoy el cierre de sesión debe valer al instante en todas las réplicas (ES-2) |
| Elegir un líder que sea el único que procesa eventos | Es más complejo (elección, caída del líder) y la restricción `UNIQUE` ya da la exclusión necesaria |

## Consecuencias

- **Buenas:** cualquier réplica puede morir sin que el operador vuelva a iniciar sesión; 0 peticiones HTTP fallidas y 0 alertas perdidas
  en la prueba (ver el documento de disponibilidad). Sin configurar nada el producto se comporta como antes (las 182 pruebas del módulo
  gateway pasan, entre ellas las 172 anteriores).
- **Costo de latencia:** la réplica que no insertó la alerta la anuncia hasta 0,5 s después (medido: 0,23 s a unos operadores y 0,42 s a
  otros en la evidencia; 0,21 s frente a 0,71 s en una corrida anterior).
- **Costo de carga:** cada réplica hace una consulta pequeña cada 0,5 s y, con sesiones en la base, una por petición protegida y una por
  socket cada 2 s.
- **Riesgos que quedan:** si una réplica muere justo después de insertar la alerta y antes de notificar a Telegram, ese aviso no sale
  (el panel sí la muestra); con la base caída los operadores quedan desconectados (la sesión falla cerrada); el arranque simultáneo de dos
  réplicas sobre una base vacía ejecuta `CREATE TABLE IF NOT EXISTS` a la vez (no falló en las pruebas; Docker reiniciaría a la que falle).
- **Seguridad:** la validación de ES-1 y ES-2 no cambia; el almacén en base solo guarda la huella del identificador.
