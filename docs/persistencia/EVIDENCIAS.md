# Evidencias · ARSW-EQP-01 · Safemode

Integrantes: Hever Barrera Batero, María Juliana Rodríguez Caicedo, Kevyn Daniel Forero Gonzalez

> Procedimiento y comandos: [`README.md`](README.md). Matriz: [`MATRIZ.md`](MATRIZ.md). Decisión: [ADR 0005](../adr/0005-persistencia-consistencia-observabilidad.md).
> Los JSON, el texto de los experimentos y las capturas están en [`evidencias/`](evidencias/). El dashboard exportado está en [`dashboard.json`](dashboard.json).

## Entorno

Windows 11 Pro 10.0.26200 (Docker Desktop) · Docker 29.6.1 · Compose v5.2.0 · Java 26 local (compila a 17) y contenedores con Temurin 17.
Imágenes: PostgreSQL 16.6, MongoDB 7.0.16, Redis 7.4.2, Nginx 1.27.3, Prometheus 3.1.0, Grafana 11.5.1, k6 0.57.0, Python 3.12.
Todas las horas en **UTC-5 (Colombia)**, 6 de noviembre de 2026. «Base» = `DECISIONS_MODE=base` (los defectos del punto de partida); «solución» = por defecto.

## Registro

| Experimento | Inicio / fin (UTC-5) | Archivo / captura | Resultado observado | Interpretación |
|---|---|---|---|---|
| Restricciones SQL y rollback | 23:13 (corrida previa) | `02-sql-restricciones.txt` | `ROLLBACK`: el permiso de Luis pasa a `f` dentro de la transacción y vuelve a `t`; la auditoría queda en 0 filas. **Seis `ERROR` previstos:** clave primaria, dos claves foráneas, `CHECK` del rol, `UNIQUE` del correo y `UNIQUE` de la alerta por evento. 0 alertas de prueba quedan | Las garantías las da el motor: no hay membresías repetidas ni de operadores/puestos inexistentes, no hay roles inventados y **un evento genera una sola alerta** (HU-14) |
| **Baseline (ANTES)** | 23:51:12 – 23:51:44 | `01-baseline.json` | `ediciones_concurrentes` **[200, 200]** (versión 1 → 3); `permiso_revocado` **200** (la pantalla seguía en `true`); `presencia_expira`: a los 22 s sigue **['ana']** | Los tres defectos del punto de partida: se pierde un cambio, una caché vieja deja escribir a quien fue revocado y la presencia no expira |
| Dos operadores, misma versión (Exp. 1, DESPUÉS) | 23:55:24 | `07-experimento1-409.txt`; `capturas/consola-1…`, `consola-2…` | Ana **200** (versión 1 → 2); Luis **409** con `currentVersion: 2` | El 409 **no es una caída**: el servidor rechaza correctamente una edición sobre una versión vieja |
| **Acceptance (DESPUÉS)** | 23:54:53 – 23:55:24 | `06-acceptance-solucion.json` | **4/4 `passed:true`, código 0.** Conflicto **[200, 409]** (versión 1 → 2); revocado **403** con documento intacto (versión 1); presencia a los 22 s **[]** | Las tres decisiones cumplen los contratos. La pantalla sigue diciendo `true` desde la caché tras la revocación: la caché solo pinta, la escritura se decide en el servidor |
| Redis detenido y recuperación (ANTES) | 23:51:53 – 23:52:17 | `03-baseline-redis-down.json`, `03b-baseline-redis-up.json` | Presencia **503** (`Redis no disponible`); edición **200**. Redis restaurado: primera presencia `available:true` | Sin degradación, la caída de Redis rompe una función auxiliar |
| **Redis detenido y recuperación (DESPUÉS)** | 23:55:42 – 23:56:12 | `08-redis-down-solucion.json`, `08a/08b`, `08c-redis-up-solucion.json`; `capturas/grafana-5…` | Presencia **200 `available:false`** en 19 ms y edición **200** en 71 ms. Prometheus: `safemode_dependency_up{dependency="redis"}=0` mientras `up{job="collaboration"}=1`. Recuperación: `available:true` en **0,2 s** (1 intento) | Redis es opcional: se degrada la presencia, no la configuración. `up=1` no prueba que el servicio funcione: la dependencia lo dice |
| Autoridad detenida (ANTES) | 23:52:25 – 23:52:46 | `04-baseline-authority-down.json`, `04a-baseline-permiso-cacheado.json` | Con el permiso de Ana cacheado, la edición responde **200** en 26 ms: la caché oculta que no se pudo verificar nada | Una concesión vieja decide una escritura aunque la autoridad no responda |
| **Autoridad detenida (DESPUÉS)** | 23:56:22 – 23:56:45 | `09-authority-down-solucion.json`, `09a`, `09b`, `09c-guardar-tras-restaurar.txt` | Edición **503** en 11 ms **aunque la caché tenía el permiso**; la pantalla sigue pintando desde la caché (`source:cache`). Al restaurar Gestión: intento 1 -> HTTP 200 | Se prefiere rechazar a escribir con un permiso que no se pudo verificar |
| Producto sobre PostgreSQL | 23:23 (corrida previa) | `13-producto-sobre-postgres.txt` | Un retiro `BY_OTHER` insertado a las 10:00 (-05) → el motor del gateway (contenedor, UTC) crea la alerta `POSSIBLE_THEFT`/`HIGH`; `/report` la muestra a las `15:00:00Z`; revisar la alerta funciona | El flujo evento → alerta funciona sobre PostgreSQL y la hora es el mismo instante en el computador y en el contenedor |
| Pruebas Java contra PostgreSQL | 23:23 (corrida previa) | (salida de Maven) | `PostgresStoresIntegrationTest`: **3/3** desde el computador (hora de Colombia) contra el PostgreSQL del compose | Eventos, alertas (UNIQUE), cámaras (upsert) y la hora funcionan con el cliente en otra zona horaria |
| Reinicio de PostgreSQL (ANTES) | 00:01 – 00:02 (posterior) | `15-reinicio-postgres.txt` | Con PostgreSQL detenido, Gestión responde **503** y la configuración no guarda nada. Al restaurarlo, **Gestión se recupera sola** (HTTP 200 al primer intento). **El gateway no**: sigue sin leer los eventos hasta que se reinicia; tras reiniciarlo crea la alerta pendiente | Limitación de diseño previa (una sola conexión JDBC en los almacenes): queda documentada, no oculta |
| **Reinicio de PostgreSQL (DESPUÉS, con reconexión)** | 7-nov 08:55:42 – 08:56:20 (UTC-5) | `16-reinicio-postgres-con-reconexion.txt` | Con PostgreSQL detenido: `safemode_dependency_up{service="gateway"}` **0**, `probe_success` **0**, `up{job="gateway"}` sigue en **1**. Al volver (acepta conexiones en ~1 s): `dependency_up` regresa a 1 en 8 s (scrape de 5 s) y **un retiro `BY_OTHER` nuevo genera la alerta (id 7) sin reiniciar el gateway** (`StartedAt` idéntico antes y después). Logs: `[BD] Se perdio la conexion…` y `[BD] Reconectado…` (una vez por almacén) | `ReconnectingConnection` valida la conexión antes de cada operación y reabre la suya si murió: la limitación de la fila anterior queda resuelta. La sonda `/ready` y `dependency_up` lo muestran mientras dura la caída |
| Carga k6, base | 23:52:55 – 23:54:40 | `05-k6-carga-base.json`; `capturas/grafana-2…` | P95 del cliente **14,71 ms**, 5376 peticiones, 0 % de fallos. Backend (Prometheus, cubetas): ≈ 9,3 ms. Umbrales cumplidos | Referencia de la lectura |
| Carga k6, solución | 23:56:53 – 23:58:38 | `10-k6-carga-solucion.json`; `capturas/grafana-3…` | P95 del cliente **13,26 ms**, 5448 peticiones, 0 % de fallos. Backend ≈ 9,2 ms. Umbrales cumplidos | La diferencia con la base es **ruido**: k6 solo lee y la ruta de lectura no cambió |
| Acceptance final | 23:58:38 – 23:59:09 | `11-acceptance-final.json` | **4/4 `passed:true`, código 0** (tras haber detenido y restaurado Redis y Gestión) | El sistema vuelve a un estado correcto tras las fallas |

## Observabilidad

Dashboard **ARSW-EQP-01 · Persistencia (Safemode)**, en [`dashboard.json`](dashboard.json), con 8 paneles: los 2 iniciales y los 6 de
`OBSERVABILIDAD.md` del enunciado del laboratorio (P95, errores 5xx, dependencias, aciertos de caché, conflictos y presencia degradada). Capturas con la
ventana de tiempo visible en `evidencias/capturas/grafana-*.png`.

**1. ¿Por qué puede subir el contador de conflictos mientras baja la pérdida de cambios?**
Porque cada conflicto es un cambio que el sistema **rechazó** (409) en vez de pisar el de otro operador. Antes había 0 conflictos y se perdían
cambios sin avisar (200 y 200). Ahora hay conflictos y no se pierde nada: el conflicto indica que la protección funcionó.

**2. ¿Por qué el panel de caché no demuestra que una escritura fue autorizada?**
Porque solo cuenta las consultas de permiso de la pantalla, y un acierto puede ser un permiso viejo. Además las escrituras ya no usan la
caché: preguntan a la autoridad. Que la caché acierte mucho no dice si se guardó con permiso (en el experimento de autoridad detenida la caché
seguía acertando mientras las escrituras recibían 503).

**3. ¿Qué ve Grafana cuando Redis cae pero la API sigue respondiendo 200?**
`up` sigue en 1 (Prometheus aún lee las métricas), pero el panel *Dependencias* muestra `collaboration → redis` en 0 y *Presencia degradada* sube.
Por eso `up = 1` no significa que todo funcione. Los 5xx no suben: la degradación es un 200 distinto.

**4. ¿Por qué el P95 de k6 y el del backend no tienen el mismo valor?**
k6 mide desde el cliente (incluye Nginx, la red y el tiempo de espera de cada solicitud); el backend mide solo su procesamiento y lo estima con las
cubetas del histograma, así que es aproximado (el resultado cae en el límite superior de una cubeta). Aquí: k6 ≈ 14,7 / 13,3 ms
y backend ≈ 9,3 / 9,2 ms.

## Observabilidad unida (7 de noviembre)

Dos Prometheus/Grafana (el de la rama de Kevyn y el de persistencia) publicaban los mismos puertos. `docker-compose.persistencia.yml` ya tiene **uno**.
Comprobado levantando el stack completo (`up -d --build --wait`, 11 contenedores sanos) y consultando la API de Prometheus y la de Grafana:

| Comprobación | Resultado |
|---|---|
| Objetivos de Prometheus | `access`, `collaboration`, `gateway` y `ready-probe`: los 4 en **`up`**, sin errores |
| `safemode_dependency_up` | `gateway → database` **1**, `access → postgres` 1, `collaboration → mongodb / redis / access` 1 (todas con el mismo nombre) |
| `probe_success{job="ready-probe"}` | **1** (el gateway puede consultar su base) |
| Dashboards en Grafana (carpeta ARSW) | *Punto de partida* (2 paneles) y *ARSW-EQP-01 · Persistencia* (8). La carpeta **SafeMode** queda lista para el tablero de Kevyn (no está en el repo) |
| Métricas del gateway consultadas en Prometheus (las que usan los paneles de Kevyn) | Todas responden `success`. Con 2 retiros sospechosos: `safemode_alerts_total` `HIGH` = 2 en 5 min, P95 de `safemode_alert_latency_seconds` ≈ **0,48 s**, heap ≈ 24 MB, CPU del proceso ≈ 0,06 % |
| Pruebas contra PostgreSQL real (`SAFEMODE_TEST_PG_URL`) | `PostgresStoresIntegrationTest` **3/3** con los almacenes ya reconectables |

El P95 sale `NaN` mientras no haya alertas (no hay observaciones del histograma). Se verificó por la API de Prometheus, no con capturas. **Los paneles de
Kevyn no se tocaron:** su tablero vive en su Grafana y no está en el repositorio, así que no se pudo ni se quiso reemplazar (ver
`observability/grafana/dashboards/README.md`).

## Hallazgos que salieron de medir (no de suponer)

1. **La «degradación lenta» es DNS.** Con un contenedor detenido, resolver su nombre tarda ≈ 4 s (`12-dns-contenedor-detenido.txt`) y ningún
   timeout de Redis o HTTP lo acota. En la primera corrida, sin acotar el DNS, la edición con Redis detenido tardó **4021 ms**
   (`previas/12b-baseline-redis-down-sin-dns_opt.json`). Se acotó con `dns_opt` y con una **ventana de fallo rápido** de 2 s por dependencia.
2. **Conexiones muertas del pool de Redis.** Tras reiniciar Redis, la primera operación fallaba (el contador `safemode_cache_total{result="error"}`
   subió de más). Se corrigió validando la conexión al tomarla del pool y se comprueba con `tools redis-up`.
3. **La caché negativa de DNS de la JVM (10 s) retrasaba la recuperación**: el primer guardado tras restaurar Gestión daba 503. Se acotó a 1 s.
   El precio de las dos protecciones es que, tras recuperarse una dependencia, pueden pasar hasta ≈ 2–3 s antes de volver a usarla.
4. **Un cambio nuestro rompía los Dockerfiles existentes** (el pom padre exigía los módulos nuevos y tres imágenes copian solo los cinco de
   antes). Se detectó al construir y se corrigió con un perfil de Maven que se activa solo si las carpetas existen.

## Problemas pendientes

No hay pruebas de aceptación fallidas. Lo que **no está resuelto**, con su evidencia:

1. **Carrera de revocación (límite de diseño).** Si se revoca un permiso justo entre la validación y el guardado, la escritura puede pasar.
   Gestión y Colaboración no comparten transacción. Está documentado en el ADR con alternativas; no se implementó ni se probó.
2. **No medimos el costo de la llamada extra a Gestión en las escrituras bajo carga**: k6 solo hace lecturas.
3. **El panel del producto no usa todavía estos servicios** (se integra después del inicio de sesión, ADR 0004): la consola del escenario es la
   que los ejercita.
4. **PostgreSQL como punto único de falla del producto:** el gateway ya **se recupera solo** de un reinicio (`16-…`); sigue sin haber réplica. Si la base
   está caída justo cuando el tracker registra un evento, la corrida del tracker termina (ver el ADR, *Consecuencias*).

## Hechos del proceso

- Se hicieron **tres pasadas completas** del procedimiento (antes → después → k6 → aceptación final). La primera encontró el hallazgo 1, la segunda
  los hallazgos 2 y 3 y la tercera, con el código final, es la que documenta este archivo. Solo se conserva de las anteriores lo que aporta
  (`previas/`: la edición de 4021 ms) y las evidencias hechas una sola vez: SQL (`02`) y el producto sobre PostgreSQL (`13`).
- Las capturas de Grafana y de la consola las tomó un script con Chrome en modo sin cabeza; las horas se leen en la barra de tiempo de Grafana.
- El «antes» se obtuvo con el mismo código y `DECISIONS_MODE=base`, no con una versión distinta del repositorio.
