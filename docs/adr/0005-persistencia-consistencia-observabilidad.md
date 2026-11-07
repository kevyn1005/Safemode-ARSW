# ADR 0005: persistencia, consistencia y observabilidad (PostgreSQL, MongoDB, Redis, Prometheus, Grafana y k6)

- Equipo / integrantes: **ARSW-EQP-01** — Hever Barrera Batero, María Juliana Rodríguez Caicedo, Kevyn Daniel Forero Gonzalez
- Estado: **aceptado**. La verificación real (Docker Desktop 29.6.1 / Compose v5.2.0, 6 de noviembre de 2026) está en *Evidencia*.
- Commit final: el que se entrega (se obtiene con `git log -1 --format=%H`) · Rama: `heverpersistencia`
- Documentos hermanos: [matriz de decisiones](../persistencia/MATRIZ.md) · [guía y reproducción](../persistencia/README.md) ·
  [evidencias](../persistencia/EVIDENCIAS.md) · [dashboard exportado](../persistencia/dashboard.json)

## Contexto

Hasta ahora Safemode guardaba **todo en una sola base H2** ([ADR 0002](0002-docker-compose-gateway-y-base-de-datos.md)): los eventos de
presencia que escribe el tracker, las alertas que crea el motor y los nombres de las cámaras. No existían usuarios ni permisos (el
inicio de sesión es el [ADR 0004](0004-login-google-y-notificaciones.md), pendiente), las zonas de las cámaras vivían en el
`localStorage` de **cada navegador** (dos paneles no comparten lo que dibujó el administrador), no había datos temporales y no había
forma de comprobar el comportamiento bajo carga o con una dependencia caída.

Lo que hace el sistema y lo que nos importa de cada operación:

| Operación | Qué nos importa |
|---|---|
| Registrar un evento y crear su alerta | **No duplicar** (una alerta por evento, HU-14) y no perder ninguno |
| Leer el estado del panel | Que sea rápido; un dato un poco viejo se tolera |
| Configurar el puesto (zonas, avisos, título) | **No perder el cambio de otro administrador** y que quien guarda tenga permiso **en ese momento** |
| Mostrar qué puede hacer cada operador | Que sea rápido; puede estar un poco viejo |
| Presencia (quién tiene el panel abierto) | Que desaparezca sola a los 20 s |

Restricciones de la práctica: **una sola instancia de cada motor** (sin réplicas: no demuestra replicación, consenso ni CAP; detener
Redis es una falla de dependencia, no una partición), operadores ficticios (`ana`, `luis`, `marta`) simulados con la cabecera
`X-Operator` (**sin autenticación de producción**), y el servicio de permisos y el de configuración **no comparten una transacción**.
Restricción propia del proyecto: lo que ya funciona no debe dejar de funcionar.

Antes de corregir medimos el punto de partida (`evidencias/01-baseline.json`, `03` y `04`) y encontramos **cuatro** defectos:
(1) dos operadores con la misma versión guardan los dos (**200 y 200**) y se pierde un cambio; (2) un permiso en caché deja escribir a
quien ya fue revocado (**200**); (3) la presencia no expira (a los 22 s seguía `ana`); (4) con Redis detenido el heartbeat respondía
**503** y, además, en la primera medición la edición tardó **4021 ms** (un cuarto hallazgo, de DNS, explicado en *Consecuencias*;
`evidencias/previas/12b-…json`).

## Decisión

**Motores: cada uno por lo que garantiza** (la matriz completa está en [MATRIZ.md](../persistencia/MATRIZ.md)).

- **PostgreSQL** para lo que debe ser correcto siempre: operadores, puestos y membresías (claves primarias y foráneas, `CHECK` de rol,
  transacciones con auditoría) **y** los eventos y alertas que ya existían (la restricción `UNIQUE (event_id, event_occurred_at)` impone
  una alerta por evento). Lo comprobamos con `persistencia/db/evidence.sql` (`evidencias/02-sql-restricciones.txt`).
- **MongoDB** para la **configuración compartida del puesto**: un documento `puesto-1` con `title`, `cameras[]` (con su zona) y
  `preferences` **embebidas**, más la `version`. Se lee y se guarda completo por su identificador; una operación sobre un solo documento
  es atómica.
- **Redis** solo para datos temporales: presencia de operadores (**TTL 20 s**) y caché de permisos para pintar el panel (**TTL 60 s**).
- **Fotos de evidencia:** siguen en disco; la base guarda solo la ruta.

**Frontera de atomicidad: un documento de MongoDB.** Comparar la versión, cambiar el contenido y subir la versión se hacen **en una sola
operación**: `findOneAndUpdate({_id, version: esperada}, {$set…, $inc: {version: 1}})`. Si dos operadores tienen la misma versión,
solo uno coincide; el otro recibe `null` y el servicio responde **409**. Un `find` seguido de un `update` sin condición mantiene la
carrera (es el defecto de partida). El 409 **no es una caída**: es un rechazo correcto, y las métricas lo separan de los 5xx.

**Política de caché: la caché solo sirve para mostrar.** Para **guardar**, el servidor pregunta **siempre** a la autoridad (Gestión de
acceso → PostgreSQL). Ocultar un botón o acortar el TTL no basta: la decisión la toma el servidor.

**Garantía de escritura:**

| Situación | Respuesta | ¿Se guarda? |
|---|---|---|
| Revocación confirmada **antes** de empezar la escritura (aunque la caché diga que sí) | **403** | No |
| Versión vieja | **409** | No |
| No se puede validar el permiso (Gestión caída, tiempo agotado, 5xx) | **503** | No: *fail-closed*, no se convierte una falla en una concesión |
| MongoDB caído | **503** | No (el documento es el dato principal: no se degrada) |

**Degradación: Redis es opcional.** Si falla, el heartbeat responde **200 con `available:false`** y sube `safemode_degraded_total`; la
edición sigue funcionando mientras MongoDB y Gestión estén disponibles. La presencia se guarda con `SET … EX 20` en **una sola
escritura**, para que no pueda quedar una clave sin vencimiento si algo falla a la mitad. Solo se atrapa la falla del almacén (no los
errores de programación).

**Cómo encaja con lo que ya existía (sin romperlo):**

- **H2 sigue siendo el valor por defecto.** PostgreSQL se elige solo con `SAFEMODE_DB_URL=jdbc:postgresql://…`. `SqlDialect` reúne las
  tres instrucciones que difieren (clave autonumérica, tipo de la hora y «insertar o cambiar»); la ruta H2 no cambia ni una palabra.
- **La hora se guarda como `TIMESTAMP WITH TIME ZONE` en PostgreSQL**: el tracker corre en el computador (UTC-5) y el gateway en un
  contenedor (UTC); con `TIMESTAMP` sin zona el mismo instante se leería con 5 horas de diferencia. Comprobado en
  `evidencias/13-producto-sobre-postgres.txt` (10:00 -05 → `15:00:00Z` en el reporte del gateway).
- **El panel, el WebSocket y los otros Dockerfiles no se tocaron.** Los tres módulos nuevos entran al reactor de Maven con un perfil
  que se activa solo si existen sus carpetas (si el pom padre los exigiera siempre, las imágenes de `camera-feed-service`,
  `vision-detection-service` y `realtime-gateway` —que copian solo los cinco módulos de antes— dejarían de construirse; lo
  comprobamos al construir y por eso se corrigió).
- **Las decisiones están aisladas** en un solo paquete (`collaboration-service/…/decisions/`) con dos implementaciones: `SafeDecisions`
  (por defecto) y `BaselineDecisions` (los defectos del punto de partida, con `DECISIONS_MODE=base`) para **repetir la medición del
  «antes»**. El servicio anuncia el modo al arrancar.

**Observabilidad.** Prometheus recoge `/metrics` de cada servicio cada 5 s; los nombres llevan el prefijo `safemode_` y usan Micrometer
1.13.6, **la misma librería y versión que la rama de observabilidad del gateway**, para poder unirlas. La ruta de cada métrica es la
**plantilla** (`/api/puestos/{id}`), nunca el id real. Grafana provisiona dos dashboards (*Punto de partida*, 2 paneles, y
*ARSW-EQP-01 · Persistencia*, los 8 paneles) y deja lista la carpeta **SafeMode** para cargar, sin modificarlo, el tablero que Kevyn armó en su Grafana
(no está en el repositorio: ver `observability/grafana/dashboards/README.md`); k6 genera carga de lectura.

**Una sola observabilidad (7 de noviembre).** Hasta ahora había dos Prometheus/Grafana que chocaban en los puertos 9090 y 3000: el de la
rama de Kevyn (gateway corriendo en el computador, `docker-compose.observability.yml`) y el de persistencia (access y collaboration). El
compose de persistencia ya tiene **uno solo** con cuatro objetivos: `access`, `collaboration`, `gateway` (`realtime-gateway:9464`) y la sonda
`ready-probe` (blackbox → `/ready` del gateway, que da 200 solo si el gateway puede consultar su base). El gateway publica además
`safemode_dependency_up{service="gateway",dependency="database"}` con el **mismo nombre** que los otros servicios, medida en un hilo aparte (un
scrape nunca espera a la base). Lo que distingue esta práctica se ve en una sola consulta: con PostgreSQL detenido, `up{job="gateway"}` sigue en 1
pero `dependency_up` y `probe_success` bajan a 0 (`evidencias/16-…`). **No se cambió nada de lo de Kevyn:** su compose
(`docker-compose.observability.yml`, que sigue valiendo para el gateway sin Docker y no se levanta junto con este), sus métricas
(`safemode_alerts_total`, `safemode_alert_latency_*`) y los nombres de sus jobs (`gateway`, `ready-probe`) quedan igual; solo se agregó la métrica
de la base.

**Integración con el panel del producto (7 de noviembre).** Hasta aquí los servicios de persistencia solo los usaba la consola del escenario. Ahora el
panel los usa a través del gateway, **apagado por defecto** (sin `SAFEMODE_COLLAB_URL`, `SAFEMODE_ACCESS_URL` y `SAFEMODE_ACCESS_KEY` el panel funciona
exactamente como antes):

- **El navegador no habla con `collaboration-service`:** pasa por el gateway (`GET/PUT /puesto`, `GET /puesto/permission`, `GET/POST /presence`),
  que exige la sesión de Google, pone la identidad del operador **desde la sesión (nunca desde lo que mande el navegador)** y reenvía la respuesta con su
  código: un 409 y un 403 llegan al panel como rechazos correctos, no como fallas. Si el servicio no responde, 503 (falla cerrado); un 401 del servicio
  se vuelve 502 para que el panel no lo confunda con «sesión vencida».
- **Identidad y permisos (decisión del equipo):** todos los correos de `SAFEMODE_ALLOWED_EMAILS` pueden **ver** el puesto; solo los de
  `SAFEMODE_ADMIN_EMAILS` pueden **configurarlo**, y solo **la primera vez**: `access-service` da de alta al operador (`POST /operators/provision`, una
  transacción, idempotente, el id sale del correo: `hever-barrera`, `hever-barrera-2` si choca) y **nunca cambia lo que ya existe**. Después manda
  PostgreSQL: revocar a alguien con `PUT /permissions` **no se deshace** cuando el gateway reinicia y vuelve a dar de alta ese correo
  (`evidencias/17-operadores-por-correo.txt`: `grant` y `revoke` en la auditoría; `canConfigure` sigue en `false`). La lista de administradores es el
  punto de partida, no la autoridad.
- **Qué se comparte de las zonas:** solo el rectángulo `{x, y, w, h}` de cada zona. La huella de brillo y el tamaño de la ventana son de cada navegador: las
  zonas que llegan del servidor se guardan con tamaño de referencia 1×1 y sin huella, así que la prueba previa de `camera-share.js` **bloquea hasta que
  el operador las confirma con su propia ventana**. Al confirmar, el panel avisa (`safemode:zones-saved`) y `puesto-sync.js` hace el `PUT` con la versión
  que leyó; un 409 recarga lo que guardó el otro y lo explica; un 403 o un 503 dejan las zonas solo en el navegador y dicen por qué. También muestra
  quién está en línea (latido cada 10 s; vence a los 20 s) y si ese operador puede configurar (decide el servidor; la pantalla solo pinta).
- **Costos y límites:** cada `PUT` sigue haciendo la llamada a Gestión de acceso (ver arriba). El gateway recuerda el **id** del operador (no cambia) pero
  nunca el permiso. Correr `tools reset` o `tools acceptance` (escenario del laboratorio) borra a los operadores reales: no usarlos en un despliegue.

## Alternativa considerada

**PostgreSQL con JSONB en lugar de MongoDB para el documento del puesto.** Es viable: `UPDATE puestos SET doc = …, version = version + 1
WHERE id = ? AND version = ?` también es atómico. Ventajas: un solo motor y la posibilidad de validar el permiso **y** guardar la
configuración **en la misma transacción**, lo que cerraría la ventana que explicamos abajo. La descartamos porque la práctica no pide
migrar de motor, el documento (cámaras con zona y preferencias anidadas) ya encaja bien en MongoDB y habría que migrar el acceso a datos
y volver a probar todo. Lo que perderíamos es poca flexibilidad del documento, que aquí casi no se usa.

**Quitar Redis** y preguntar siempre a PostgreSQL. Como las escrituras ya no usan la caché, solo se perdería velocidad al pintar el
panel y la presencia con vencimiento automático. Es la opción más simple si se quisiera reducir piezas.

**Seguir con todo en H2** (lo que había). H2 no necesita servidor, pero es una base embebida pensada para pruebas: no tiene usuarios ni
copias de seguridad, y el compose ya había necesitado un servidor TCP aparte (ADR 0002). No ofrece nada para permisos con claves
foráneas ni datos que expiran. Se mantiene como valor por defecto para no romper el arranque sin Docker.

**MongoDB para eventos y alertas.** Descartado: son filas de columnas fijas que se consultan por rango de `id` y orden, y la unicidad
de la alerta por evento la da PostgreSQL de forma declarativa.

## Consecuencias

**Fallas que toleramos (el sistema sigue):**

| Si falla… | Qué pasa | Prueba |
|---|---|---|
| Redis | Se pierde la presencia (`available:false`) y la caché; se sigue configurando el puesto. Al volver, el primer heartbeat ya funciona | `08-redis-down-solucion.json`, `08c-redis-up-solucion.json` |

**Fallas que bloquean a propósito:**

| Si falla… | Qué pasa | Prueba |
|---|---|---|
| Gestión de acceso / PostgreSQL | No se puede guardar (**503**) aunque la caché tenga el permiso: garantiza que nadie escriba con un permiso sin verificar. La pantalla sigue pintando desde la caché | `09-authority-down-solucion.json` |
| MongoDB | No se puede leer ni guardar la configuración (503). Las alertas **no dependen** de MongoDB: siguen | por diseño (no se probó) |

**Costos de nuestra decisión:**

- Cada `PUT` hace una **llamada extra a Gestión** (HTTP + una lectura por clave primaria). k6 solo mide lecturas, así que **no medimos** ese
  costo en escrituras.
- Un 409 obliga a quien edita a recargar y volver a intentar: es el precio de no perder cambios. Y, por embeber las cámaras y las
  preferencias en un documento, **dos administradores que editan cosas distintas (la zona de CAM 1 y el título) también chocan**.
- **La degradación rápida no es gratis: hallazgo de DNS.** Con un contenedor detenido, resolver su nombre tarda ≈ 4 s (el DNS de Docker
  lo reenvía al resolvedor externo) y ningún timeout de Redis o HTTP lo acota, porque ocurre antes de conectar. Es la causa del «≈ 4 s
  por petición» que el laboratorio anterior dejó sin diagnosticar (medimos 8008/3974/3962 ms, `evidencias/12-dns-contenedor-detenido.txt`).
  Se acotó con tres medidas: `dns_opt: [timeout:1, attempts:1]` (≈ 1 s por resolución), una **ventana de fallo rápido de 2 s** por dependencia
  (tras una falla, solo una petición por ventana paga la espera; las demás responden al instante) y una **caché negativa de DNS de 1 s** (con
  los 10 s por defecto de la JVM, el primer guardado tras restaurar Gestión daba 503 aunque Gestión ya respondía). Medido en la primera corrida:
  503 de autoridad caída en 2011 ms y 503 tras restaurar; en la corrida final: **503 en 11 ms** y el primer guardado tras restaurar da **200**.
  **Precio:** tras recuperarse una dependencia pueden pasar ≈ 2 s antes de volver a usarla (la prueba `redis-up` mide ese tiempo).
- **El pool de Redis debe validar la conexión.** Tras reiniciar Redis, las conexiones que el pool guardaba están muertas: la primera
  operación fallaba (cuenta como `error` en la métrica de caché y dejaba un heartbeat «degradado» sin motivo, y una caché sin llenar). Se
  corrigió con `testOnBorrow` y se comprueba con `tools redis-up`.
- **PostgreSQL sigue siendo un punto único de falla para el flujo del producto, pero ya no obliga a reiniciar nada** (igual que H2 lo era).
  *Antes (6-7 de noviembre):* `PresenceEventStore` y `AlertStore` guardaban **una sola conexión JDBC** sin reconexión: tras reiniciar PostgreSQL,
  Gestión de acceso (pool) se recuperaba sola pero el gateway fallaba hasta `docker compose restart realtime-gateway`
  (`evidencias/15-reinicio-postgres.txt`; la misma limitación existía con el servidor H2 del ADR 0002).
  *Ahora:* los dos almacenes piden la conexión a `ReconnectingConnection`, que la **valida antes de cada operación** (`Connection.isValid`) y,
  si murió, abre otra con la misma URL. Tres decisiones: (1) se valida antes de usar en vez de reintentar tras el error, para no ejecutar
  nada a medias sobre una conexión muerta; el costo es una ida y vuelta corta por operación; (2) tras un intento fallido se espera 1 s antes del
  siguiente (el motor consulta cada 250 ms y cada consulta pagaría el intento de conexión); (3) con PostgreSQL se limita la conexión a 3 s
  (`connectTimeout`), porque sin límite el ciclo de fotogramas del tracker podía colgarse. Se registra una línea al perder la base y otra al
  recuperarla. Medido (`evidencias/16-reinicio-postgres-con-reconexion.txt`): con PostgreSQL detenido, `dependency_up` y `probe_success`
  bajan a 0; al volver, el gateway **sin reiniciarse** vuelve a leer y crea la alerta del retiro nuevo. Se cubre con
  `ReconnectingConnectionTest` y `DatabaseRestartTest` (servidor H2 real que se apaga y se enciende) y con las pruebas contra PostgreSQL real.
  **Costo:** mientras la base está caída, cada operación del gateway falla igual (503/«no se pudo leer»); solo cambia que **se recupera sola**.
  **Efecto en el tracker (leído en el código, no probado con cámara):** el tracker solo toca la base al registrar un evento. Antes, la primera vez
  que lo hacía después de un reinicio de la base fallaba con la conexión muerta; ahora reconecta y sigue. **Límite que se mantiene:** si la
  base está caída *justo* cuando el tracker registra un evento, la excepción sale del bucle de fotogramas y **termina la corrida del tracker**
  (`runCamera` no la atrapa; es el comportamiento de antes y no se cambió para no tocar el seguimiento). Mejora natural: atrapar el fallo al
  guardar el evento y reintentarlo con el siguiente fotograma.

**La ventana entre validar el permiso y guardar:**
Gestión (PostgreSQL) y Colaboración (MongoDB) **no comparten una transacción**. El servidor primero pregunta el permiso y después guarda.
Si la revocación ocurre **justo entre esas dos cosas**, la escritura pasa aunque el permiso ya no exista. Nuestra garantía es solo para una
escritura que **empieza después de confirmada la revocación**; esa sí se rechaza (403).

*Alternativas de diseño para cerrar la ventana:* (1) poner permisos y documento en el **mismo motor** (PostgreSQL con JSONB) y validar y
guardar en una transacción; (2) que Gestión emita un **token de autorización de vida corta** que Colaboración verifique al guardar
(y que `findOneAndUpdate` incluya el número de «época» de permisos en el filtro); (3) un protocolo en dos pasos (*saga*) con
compensación. Ninguna se implementó ni se probó.

## Historial creciente

**Diseño:** una **colección separada** `puesto_history` en MongoDB, con **un documento por cada cambio** (no se implementó, como pedía
el laboratorio):

```json
{ "puesto_id": "puesto-1", "version": 7, "title": "…", "changed": ["cameras"], "by": "ana", "at": "2026-11-06T22:14:07Z" }
```

- **Índice:** `{ puesto_id: 1, version: -1 }`, único por puesto y versión.
- **Consulta:** los últimos cambios de un puesto: `find({puesto_id}).sort({version: -1}).limit(20)`.
- **Paginación por cursor** (no por `skip`): la primera página trae las 20 más recientes; la siguiente pide `version < última_vista`
  con el mismo `limit(20)`. Cada página tarda lo mismo aunque haya miles de versiones, y no se repiten ni se saltan filas si llegan
  cambios nuevos.
- **Retención:** política explícita (p. ej. 90 días o las últimas N versiones) con un índice TTL sobre `at`.
- **Escritura:** junto al cambio del puesto. Como dos colecciones no comparten transacción simple, la entrada del historial se escribiría
  **después** del cambio (si falla, se pierde una entrada de auditoría, no el cambio) o se usaría una transacción multidocumento de
  MongoDB, que exige un conjunto de réplicas (fuera del alcance: una sola instancia).

**Por qué embeberlo sin límite sería problemático:** (1) el documento crecería sin parar y MongoDB limita un documento a 16 MB; (2) cada
lectura del puesto traería todo el historial aunque solo se quiera el título; (3) cada edición reescribiría un documento cada vez más
grande; (4) las ediciones y las consultas de historial competirían por el mismo documento.

## Evidencia

Todo está en [`docs/persistencia/EVIDENCIAS.md`](../persistencia/EVIDENCIAS.md) (procedimiento, horas, capturas) y en
[`docs/persistencia/evidencias/`](../persistencia/evidencias/). Dashboard: [`dashboard.json`](../persistencia/dashboard.json).

| Qué se comprobó | Antes (`DECISIONS_MODE=base`) | Después | Archivo |
|---|---|---|---|
| Dos ediciones con la misma versión | **200 y 200** (versión 1 → 3) | **200 y 409** (versión 1 → 2) | `01-baseline.json` → `06-acceptance-solucion.json`; `capturas/consola-*` |
| Permiso revocado con caché | **200** | **403**, documento intacto | ídem |
| Presencia a los 22 s | sigue `["ana"]` | `[]` | ídem |
| Redis detenido | presencia **503** | presencia **200 `available:false`** y edición **200** | `03` → `08`, `capturas/grafana-4`, `grafana-5` |
| Gestión detenida | **200** (la caché oculta que no se pudo verificar nada) | **503** en 11 ms; al restaurar, el primer guardado da **200** | `04` → `09`, `09c` |
| Redis restaurado | — | presencia **`available:true`** en 0,2 s | `08c` |
| Restricciones de PostgreSQL | — | 6 errores previstos, 0 filas de prueba que queden | `02-sql-restricciones.txt` |
| El producto sobre PostgreSQL | — | evento → alerta (`POSSIBLE_THEFT`), hora correcta | `13-producto-sobre-postgres.txt` |
| Acceptance final | — | **4/4 `passed:true`, código 0** | `11-acceptance-final.json` |
| Gateway tras reiniciar PostgreSQL | Falla hasta reiniciar el gateway (0 filas; `docker compose restart`) | **Se recupera solo**: `dependency_up` 0 → 1, `probe_success` 0 → 1, crea la alerta nueva, `StartedAt` del gateway sin cambio | `15` → `16-reinicio-postgres-con-reconexion.txt` |
| Observabilidad unida | Dos Prometheus/Grafana en los mismos puertos | Un Prometheus con 4 objetivos `up`, 2 dashboards del laboratorio y la carpeta SafeMode lista para el de Kevyn, `gateway → database = 1` | `EVIDENCIAS.md`, sección *Observabilidad unida* |
| Carga k6 (P95 del cliente) | 14,71 ms | 13,26 ms | `05` y `10`; `capturas/grafana-2`, `grafana-3` |

**Límites que las pruebas NO demuestran:**

- **La carrera de revocación** (revocar justo después de validar y antes de guardar) **no está probada ni resuelta**; solo la documentamos.
- **No hay replicación, consenso ni CAP.** Es una instancia de cada motor; apagar Redis es una falla de dependencia, no una partición.
- **k6 solo lee**, así que no prueba consistencia (eso lo hacen los experimentos de aceptación) y no mide el costo de la llamada extra a
  Gestión en las escrituras. La diferencia de P95 entre las dos cargas es ruido de medición: la ruta de lectura no cambió.
- **La consola del escenario no tiene autenticación real:** su identidad es una cabecera (`X-Operator`). El panel del producto sí usa la sesión de Google
  (ver *Integración con el panel*), pero **esa integración no se probó con una cuenta de Google real ni con una cámara real**: se probó con pruebas
  automáticas (gateway con servicios falsos y el módulo del panel con un navegador simulado) y, por partes, contra PostgreSQL real (`17-…`).
- La **recuperación del tracker** tras un reinicio de la base no se probó con cámara real (usa el mismo almacén que el gateway, que sí se probó). Si
  la base está caída justo cuando registra un evento, la corrida del tracker termina (ver *Consecuencias*). La reconexión solo se probó con H2
  (pruebas automáticas) y PostgreSQL (Docker).

## Participación y herramientas

**Integrantes:** el trabajo de esta rama (`heverpersistencia`) lo realizó **Hever Barrera Batero** (persistencia). Se integra con la
observabilidad del gateway (rama `kevynobservabilidad`) y el inicio de sesión (rama `Juli/unión`) **sin duplicar** ninguna de las dos
(ver *Cómo encaja…*). *[El equipo debe confirmar o ajustar aquí el reparto de la defensa: cada integrante explica una decisión y una
evidencia, como pide el laboratorio.]*

| Integrante | Área que puede explicar | Decisión que puede explicar |
|---|---|---|
| Hever Barrera Batero | Datos y versiones: la matriz, PostgreSQL con sus restricciones, MongoDB y el control optimista de versión (`savePuesto`) | Por qué cada dato va en su motor y por qué comparar y subir la versión en una sola operación evita perder ediciones |
| María Juliana Rodríguez Caicedo | Permisos y autoridad (`permissionForWrite`), la ventana entre validar y guardar | Por qué la caché sirve para mostrar y no para autorizar, y por qué se rechaza (503) si no se puede validar |
| Kevyn Daniel Forero Gonzalez | Degradación y observabilidad (`touchPresence`, Prometheus, Grafana, k6) | Por qué el TTL va en la misma escritura, qué significa degradar sin detener el flujo, y por qué `up = 1` no prueba que todo funcione |

**Documentación consultada:** README y documentos del laboratorio (`docs/CONTRATOS.md`, `docs/OBSERVABILIDAD.md`) y las referencias que
trae (Compose, MongoDB, Redis, PostgreSQL, Prometheus, Grafana, k6), más la documentación de Micrometer y de los drivers de Jedis,
MongoDB y PostgreSQL.

**Apoyo de asistentes (declarado):** se usó **Claude Code** (asistente de IA de Anthropic) para: analizar el proyecto y el enunciado,
escribir los módulos nuevos y el soporte de PostgreSQL, las pruebas, el compose, el dashboard y los scripts de prueba, ejecutar y medir
los experimentos, y redactar la matriz, este ADR y las evidencias. Los hallazgos de DNS y del pool de Redis salieron de **medir**, no de
suponer. La ampliación del 7 de noviembre (reconexión de los almacenes y observabilidad unida) también se hizo con Claude Code, y se
verificó con pruebas y con el reinicio real de PostgreSQL. Todos los integrantes deben poder explicar el código entregado y las decisiones; las explicaciones de la matriz y de este ADR son
las que deben defender.

## Cómo reproducir

Ver [`docs/persistencia/README.md`](../persistencia/README.md): `docker compose -f docker-compose.yml -f docker-compose.persistencia.yml up -d --build --wait …`,
`tools smoke|baseline|acceptance|redis-down|redis-up|authority-down` y `k6`. El «antes» se reproduce con `DECISIONS_MODE=base`.
