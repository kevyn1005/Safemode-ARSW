# Safemode-ARSW — Contexto del proyecto

Este archivo resume el trabajo hecho hasta ahora (sobre todo en `object-presence-tracker` y
`vision-detection-service`) para que cualquier sesión de Claude Code que arranque en esta
carpeta tenga el contexto sin repetir explicaciones. El usuario (Hever / "Chimi", estudiante)
está aprendiendo mientras se construye esto — explica los conceptos nuevos paso a paso en
vez de solo aplicar cambios en silencio, y cuenta los resultados en lenguaje llano.

**Constraint explícito y permanente del usuario**: "cuidado todo el código está
funcionando, mira que no falle nada". Cualquier cambio debe mantener pasando los tests
existentes. Preferir extender / agregar parámetros opcionales (ej. permitir `null`)
antes que reescribir comportamiento que ya funciona.

Rama de trabajo actual: `heverpersistencia`.

## Cómo trabaja el usuario (preferencias observadas)

- **No commitear ni hacer push sin que lo pida.** El `git push` siempre lo hace el usuario
  desde su terminal. Cuando pide commits, prefiere varios commits separados por tema, con
  mensaje en español y el trailer `Co-Authored-By`. Antes de commitear se comprueba que el
  estado exacto de cada commit compile y pase los tests.
- **Prueba con cámara real y pega la salida de la consola** (a veces también pide mirar las
  fotos guardadas). Las fotos son PNG en `object-presence-tracker/data/frames/`: se pueden
  leer con la herramienta Read para verificar lo que el sistema decidió.
- Suele responder "sí, hazlo" a una recomendación concreta; le gusta que se le den las
  opciones con su costo y una recomendación, y decidir él. Le importa el gasto de tokens de
  APIs externas y la privacidad de las caras que salen a servicios de terceros.

## Qué hace el proyecto

Detecta objetos dejados sin vigilancia (mochilas, bolsos, maletas) frente a una cámara.
Registra en una base de datos cuándo un objeto "se asienta" (queda quieto), **quién es su
dueño probable** (con foto y descripción) y cuándo se retira, **quién lo retiró y si era el
dueño u otra persona** (base para el centro de alertas). Es un proyecto universitario de
arquitectura de software, con varios módulos Maven en un reactor:

- `camera-feed-service` — captura de frames.
- `vision-detection-service` — detección de objetos y personas (`ObjectDetector`, YOLOv8 en
  ONNX) y puntos del cuerpo (`PoseEstimator`, YOLOv8-pose en ONNX).
- `object-presence-tracker` — seguimiento, dueño, retiro y persistencia (foco de casi todo el
  trabajo reciente).
- `alert-engine` — motor de alertas sobre los retiros (ver roadmap, paso 3).
- `realtime-gateway` (WebSocket + servidor HTTP) y `security-dashboard` (`index.html`
  estático) — panel de alertas en vivo (ver roadmap, paso 4).

## Decisiones clave (y por qué, para no repetir la investigación)

1. **La "cámara" en realidad es captura de pantalla.** `FrameCapturer` usa
   `java.awt.Robot.createScreenCapture(Rectangle)` — captura una región del *monitor*,
   no lee un dispositivo de cámara. El flujo real del equipo es: abrir el visor de una
   cámara (la app de Windows "Cámara", o VLC apuntando a la IP de una cámara WiFi) en una
   ventana, y ajustar el `Rectangle` de `FrameCapturer` para que apunte a esa ventana.
   Se intentó 3 veces integrar lectura directa de webcam con la librería Sarxos
   (`webcam-capture`) y las 3 veces se revirtió — decisión explícita del usuario de
   mantener consistencia con el enfoque de pantalla del equipo en vez de introducir
   código específico de cámara. No reabrir ese camino salvo que el usuario lo pida.
2. **H2 embebido**, no Postgres/MySQL, para `PresenceEventStore`: corre embebido en un
   archivo local (`jdbc:h2:file:...;AUTO_SERVER=TRUE`), sin necesidad de levantar un
   servidor de BD aparte. Driver: `org.h2.Driver`, dependencia `com.h2database:h2:2.2.224`.
   Para pruebas se usa `jdbc:h2:mem:...` en memoria (`PresenceEventStore.inMemory(name)`).
   Las columnas nuevas se agregan con `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (una base
   creada antes de una columna no la recibe con `CREATE TABLE IF NOT EXISTS`).
3. **Las fotos se guardan como PNG en disco** (`object-presence-tracker/data/frames/`) y solo
   la *ruta* va en la base de datos — no como BLOB.
4. **Maven reactor, ejecutar clases `main` de un solo módulo**: primero
   `mvn -pl object-presence-tracker -am install -DskipTests` (una vez, y tras cambiar otro
   módulo, para poblar el `.m2` local), y LUEGO
   `mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=..."` **sin** `-am`.
   Con `-am`, `exec:java` corre sobre TODO el reactor y falla con `ClassNotFoundException`.
   Para `test` (`mvn -pl object-presence-tracker -am test`) sí se usa `-am`.
5. **PowerShell**: los argumentos `-D` van entre comillas como un solo string, ej.
   `"-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest"` y
   `"-Dexec.args=30"`. Sin comillas, PowerShell lo trocea.
6. **Los modelos grandes NO van al repo.** `yolov8m.onnx` (~99 MB) y `yolov8m-pose.onnx`
   (~101 MB) viven en `object-presence-tracker/data/models/` (carpeta ignorada por git;
   GitHub rechaza archivos de más de 100 MB). El repo solo tiene `yolov8s.onnx` (lo subió un
   compañero). Los scripts usan el modelo grande/pose si existe y, si no, funcionan con lo
   que haya. Cómo se generan: en un entorno virtual de Python **en una ruta corta**
   (`C:\Users\Home\AppData\Local\Temp\yolo-env`; con una ruta larga pip falla por el límite
   de 260 caracteres de Windows), `pip install torch torchvision --index-url
   https://download.pytorch.org/whl/cpu` y luego `pip install ultralytics onnx onnxslim
   onnxruntime`; dentro de `data/models/`: `yolo export model=yolov8m.pt format=onnx
   imgsz=640` (igual con `yolov8m-pose.pt`). El detector lee salida `[1][84][8400]`.
7. **Descripción del dueño con IA de NVIDIA: opcional, en segundo plano, con la clave por
   variable de entorno** `NVIDIA_API_KEY` (y `NVIDIA_VISION_MODEL` opcional). **Nunca poner la
   clave en código, logs, memoria ni en el repo.** El usuario la pegó varias veces en el chat:
   hay que recordarle regenerarla en `build.nvidia.com` y usar `setx NVIDIA_API_KEY "..."`.
   Solo `meta/llama-3.2-11b-vision-instruct` responde en su cuenta (el `90b` da timeout y
   `gemma-3`, `phi-3-vision`, `kosmos-2`, `neva`, `vila`, `cosmos` dan 404 aunque aparezcan
   en el catálogo). **Privacidad**: el recorte con la cara de la persona sale a un servicio
   externo; conviene que el equipo decida si queda activado por defecto.
8. **El clasificador de permisos de Claude Code bloquea subir fotos de personas a servicios
   externos desde la consola de Claude** (lo marca como fuga de datos). No reintentarlo por
   otro camino: el usuario corre él mismo `NvidiaCropCheck`/el script (con `!` la salida
   llega al chat) o agrega una regla de permiso estrecha con `/permissions`.

## Estado actual de `object-presence-tracker`

### Seguimiento de objetos (`ObjectTracker`, `TrackedObject`)

- Máquina de estados `NEW → AT_REST` (quieto `REST_DURATION` = 3 s) `→ REMOVED`. Solo se
  registra un objeto que lleva 3 s quieto: uno que se deja y se retira antes no genera evento.
- **Emparejamiento en dos pasos**: (1) cada detección con un objeto de su misma clase
  (IoU > 0.3, con respaldo por distancia entre centros < 350 px); (2) las que quedan sin
  objeto se emparejan por solape con un objeto sin emparejar de *otra* clase, porque una misma
  maleta sale a veces como `backpack`, `handbag` o `suitcase`. Se conserva la clase del primer
  frame. El log muestra `[el detector cambio de clase: a -> b]`.
- **Retiro**: (a) 3 frames sin verlo (`MAX_FRAMES_UNSEEN`), o (b) el objeto en reposo aparece
  desplazado más de 25 px en **2 frames seguidos** (`REMOVAL_CONFIRM_FRAMES`). Un solo frame
  desplazado se ignora: una persona que pasa por delante tapa la maleta y la caja del
  detector salta (causó un retiro falso real con la maleta todavía en el sofá).
- **Verificación por imagen (opcional, `tracker.withRemovalVerification()`)**: antes de dar
  por retirado un objeto que el detector dejó de ver, se compara la zona donde estaba con
  cómo se veía la última vez (`RegionFingerprint`: cuadrícula de 24×24 puntos de brillo). Si
  cambió menos del 15 % (`STILL_THERE_MAX_CHANGE`), se espera (tope de 12 frames extra). **Los
  puntos que cubre la caja de una persona no se comparan** (un brazo por delante no es un cambio
  del objeto: un cinturón con un brazo encima daba 35 % sin excluir y 0 % excluyendo); si la
  persona tapa más del 65 % de la zona (`MIN_VISIBLE_FRACTION` 0.35) no se puede concluir y se
  decide como sin verificación (quien se lleva el objeto suele taparlo). Se
  calibró con fotos reales: retiro falso 0.04, mochila presente 0.05, retiro real (alguien la
  tapa y levanta) 0.28. Es opcional porque varios tests viejos usan el mismo frame de relleno
  al asentar y al retirar; `ObjectPresenceTrackerTest` la activa.
- `onFrame(BufferedImage frame, List<Detection> detections)` recibe el frame completo (puede
  ser `null` en pruebas de lógica pura). En cada evento guarda un PNG del frame.

### Personas y dueño

- **Seguimiento de personas** (`TrackedPerson`): id estable por sesión, emparejado por IoU y,
  si no, por distancia entre centros con límite `max(150 px, 60 % del lado mayor de la caja)`
  (una persona pegada a la cámara recorre cientos de px entre frames). Se olvida a una
  persona tras 5 frames sin verla: **si sale de cuadro y vuelve, recibe otro id**.
- **Dueño probable**: la persona más frecuente junto al objeto (distancia del centro del
  objeto a la caja de la persona ≤ `max(100 px, 40 % del lado mayor del objeto)`, ver
  `scaledProximity`) mientras el objeto está `NEW`. Se guarda id,
  descripción por color, y una foto que muestra a la persona **junto al objeto** (unión de
  ambas cajas). El color, la pose y la IA se leen solo del recorte de la persona.
- **`person_nearby`** (booleano histórico de `REMOVED`): hay alguien a ≤ `max(80 px, 40 %
  del lado mayor del objeto)` de la *caja* de la persona (no del centro) en el frame del retiro.
- **Radios que escalan con el objeto**: con la cámara pegada, una mochila mide 500 px y una
  persona sentada a su lado quedaba a 127 px, fuera de los 100 px fijos → sin dueño → todo
  retiro salía `OWNER_UNKNOWN` (alerta baja, no robo). Los 100/80 px son el mínimo; para
  objetos chicos no cambia nada.
- **`PersonDescriber`** (color, sin ML): color dominante del torso. Con puntos de pose usa el
  cuadrilátero hombros–caderas encogido al 60 %; si no, una franja fija (25–55 % de la
  altura). Ignora los píxeles del propio objeto y el tono de piel; el negro de una webcam con
  tinte (brillo < 0.35 y saturación < 0.55) sigue siendo negro. Falla con ropa clara o
  estampada (pijama rosa → "gris"/"blanca").
  Si hay pose confiable pero el objeto (su caja) tapa todo el torso, devuelve
  `CLOTHING_HIDDEN` ("ropa de color no determinado") en vez de caer en la franja fija: con
  la mochila sobre el pecho la franja leía el respaldo negro de la cama y una sudadera beige
  salía "camisa negra". Además `PIEL` (beige/rosado claro de una sudadera) se ignora a
  propósito para que brazos y cara no cuenten como ropa; por eso el color local es solo una
  estimación y **la descripción de la IA es la confiable**: el panel la muestra primero y
  marca el color como "estimado por imagen"; el titular de la alerta ya no repite colores.
- **Pose** (`PoseEstimator`, en `vision-detection-service`): 17 puntos COCO sobre el recorte
  del dueño, una vez por evento (~300 ms). Opcional: `new ObjectTracker(store, poseEstimator)`.

### Descripción por IA (`NvidiaOwnerVisionDescriber`, `OwnerVisionDescriber`)

- Se activa con `tracker.withOwnerVisionDescriber(...)`; corre en un pool aparte de 2 hilos y
  **nunca frena el ciclo de frames**. Sin clave/red/respuesta, el dueño conserva la
  descripción por color. `awaitPendingDescriptions(...)` espera las pendientes antes de
  cerrar la base.
- El modelo llena campos JSON fijos (ropa superior/inferior, calzado, tatuajes, lentes,
  cabello) y **la frase la arma el código** ("ropa superior: ...; tatuajes: ..."): así no se
  cuelan estaturas inventadas. Los prompts muestran el formato con marcadores `<...>` y **sin
  barras `a | b`** (el modelo las copiaba y mezclaba tatuajes con "ninguno"; hay un test que
  lo impide). Se quitan "izquierdo/derecha" del texto (se equivocan de lado). Si la respuesta
  no viene en JSON se pide una vez más.
- **Tatuajes**: si la primera respuesta no los confirma (falta el dato o dice "ninguno"), se
  recortan los antebrazos con codos y muñecas de la pose (`ArmsCropper`, sin cara) y se hace
  una segunda pregunta solo por tatuajes. Se paga **una sola vez por persona y corrida**
  (`tattooCheckByPerson`, solo se recuerdan respuestas con dato).
- **Resiliencia del servicio**: timeout de 20 s por petición, 2 intentos, y **segunda
  petición idéntica a los 6 s** si no hay respuesta (gana la primera que responda). Antes
  se colgaba 20–60 s, sobre todo en la primera llamada con imagen de cada corrida. El
  calentamiento de solo texto al inicio (`warmUpAsync`) NO evita esos cuelgues.
- Resultado en `owner_ai_description` de las filas `REGISTERED_AT_REST` y `REMOVED` (se
  actualiza por id de fila, porque `tracked_object_id` se reinicia en cada ejecución).
- **Quien retira el objeto también se describe con IA, pero solo en retiros sospechosos**
  (`RemovalKind.worthDescribingRemover()`: `BY_OTHER`, `OWNER_AND_OTHER_NEAR`, `OWNER_UNKNOWN`,
  y solo si hay una persona a quien atribuirlo). Va a `remover_ai_description` de la fila
  `REMOVED`, en segundo plano, con el mismo flujo que el dueño (incluida la revisión de
  antebrazos, cacheada por persona). En `BY_OWNER` y `NO_ONE_NEAR` no se gasta ninguna
  llamada: bastan el color y la foto. El evento se guarda al instante y la IA lo completa
  ~3 s después (hasta ~26 s si el servicio se cuelga): un centro de alertas no debe esperarla.
- **Consumo de tokens**: cada respuesta trae `usage`; se imprime `[IA] Tokens de esta llamada`
  y `usageSummary()` da el total de la corrida (el script lo muestra al final; no cuenta la
  segunda petición descartada ni el calentamiento). Medido en una corrida real con
  `meta/llama-3.2-11b-vision-instruct`: la llamada de la persona entera (imagen a 1024 px)
  gasta **~6.700 tokens de entrada** y la de antebrazos (recorte de 512 px de alto) **~3.400**;
  la salida es de ~10 a ~70. Es decir, ~10.000 tokens por persona descrita: **la imagen
  domina el costo, no el texto**. El lado mayor de la imagen enviada se cambia con
  `NVIDIA_IMAGE_MAX_SIDE` (por defecto 1024; se subió a 1024 cuando el tatuaje se perdía a 640,
  pero desde que los tatuajes se revisan en el recorte de antebrazos quizá baste menos:
  pendiente de medir tokens vs. calidad con `NvidiaCropCheck`). Otros proveedores cuentan
  las imágenes distinto (p. ej. Claude usa aprox. ancho×alto/750 tokens por imagen), así que
  comparar costos exige comparar precio por token *y* tokens por imagen.

### Análisis del retiro (`RemovalKind`)

Mientras el objeto está en reposo se anota en cada frame quién está a ≤ 100 px (o el radio
escalado del objeto, ver arriba). Al retirarse
cuentan esas personas, las que se acercaron al sitio **mientras desaparecía** (se borran si el
objeto vuelve a verse) y las que hay junto a él ahora. `removal_kind` en el evento `REMOVED`:

| Tipo | Significado | Para alertas |
|---|---|---|
| `BY_OWNER` | solo el dueño estaba cerca | normal |
| `BY_OTHER` | había otra persona y el dueño no | posible robo |
| `OWNER_AND_OTHER_NEAR` | el dueño y otra persona | ambiguo, revisar la foto |
| `NO_ONE_NEAR` | nadie cerca | desapareció solo / fuera de cámara |
| `OWNER_UNKNOWN` | sin dueño registrado y alguien cerca | revisar |

Además `remover_person_id`, `remover_description` (color, local) y `remover_crop_path` (foto de
quien se lo llevó junto al objeto), y `remover_ai_description` (IA, solo retiros sospechosos).
El log imprime `RETIRO BY_OTHER por persona #N (...)`. **El análisis del retiro (quién estaba
cerca y el tipo) es lógica local en Java con las cajas del detector YOLO: no usa NVIDIA.**
Solo la descripción textual opcional de quien retira la hace la IA externa.

### Base de datos (`PresenceEventStore`, tabla `object_presence_event`)

`id`, `tracked_object_id`, `class_name`, `event_type` (`REGISTERED_AT_REST` | `REMOVED`),
`occurred_at`, `pos_x`, `pos_y`, `width`, `height`, `person_nearby`, `frame_path`,
`owner_person_id`, `owner_description`, `owner_crop_path`, `owner_ai_description`,
`removal_kind`, `remover_person_id`, `remover_description`, `remover_crop_path`,
`remover_ai_description`.
`recordRegistered`/`recordRemoved` devuelven el id de la fila. Helpers de prueba:
`countEvents`, `lastPersonNearby`, `lastFramePath`, `lastOwner`, `lastOwnerAiDescription`,
`lastRemoval`, `lastRemoverAiDescription`, `lastTrackedObjectId`. `clearAllEvents()` es solo para el script manual.

### `vision-detection-service`

- `ObjectDetector`: umbral de confianza 0.3; NMS con IoU 0.5 (antes borraba todas las cajas de
  una clase salvo una, y solo se veía una persona por frame). El NMS también fusiona cajas
  de clases de bolso distintas (`backpack`/`handbag`/`suitcase`) que se solapan: una persona
  nunca borra a un bolso ni al revés. **`ObjectDetector.java` tiene fin de línea CRLF**: al
  editarlo por script hay que preservarlo (comprobar con `git diff --ignore-space-at-eol -w`).
- `PoseEstimator`: ver arriba. Tiene JUnit en modo `test` (se agregó para probar el NMS).

### Pruebas y scripts manuales

- Más de 100 pruebas JUnit, todas verdes: `mvn -pl object-presence-tracker -am test` (el
  módulo de visión entra por `-am`). El tracker se prueba con detecciones simuladas y un reloj
  manual (`MutableClock`); la IA se prueba contra un servidor HTTP falso local (sin red).
- **`ObjectPresenceTrackerTest`** (`src/main/java`, no es JUnit): prueba manual con cámara.
  Captura **60 s por defecto** (`"-Dexec.args=30"` para otro valor). **Al empezar borra los
  `.png` de `data/frames/` y vacía la tabla de eventos**; carga `yolov8m` y la pose si
  existen, y activa la IA si existe `NVIDIA_API_KEY`. Su `Rectangle region` es un valor local
  del monitor de quien prueba — **no es portable, no se sube tal cual al repo compartido**.
- **`NvidiaCropCheck`**: sin cámara, envía un recorte guardado a NVIDIA y muestra el texto
  crudo del modelo y la frase resultante (y prueba los antebrazos).
- Verificado end-to-end con cámara real: dos maletas a la vez, cada una con su dueño, IA de
  NVIDIA, y retiro por otra persona (`BY_OTHER`) con la foto de quien se la llevó.

## Límites conocidos (no reabrir la investigación, ya se midieron)

- **El id de persona no sobrevive a salir de cuadro** (> 5 frames). Si el dueño se va y vuelve
  a llevarse su propia maleta, saldrá `BY_OTHER` (falsa alarma). Falta reidentificar por
  apariencia; hoy solo hay color de ropa.
- **Retiros falsos por detector**: 3 frames sin verlo se consideran retiro aunque la maleta siga
  ahí (tapada, baja confianza). Mitigado con `withRemovalVerification()` (ver arriba), pero
  falla si quien la tapa lleva ropa de brillo parecido al objeto (solo retrasa el retiro
  hasta el tope de 12 frames) o si cambia la luz de toda la zona.
- **Persona nueva con el id de otra** (mitigado con `PersonAppearance`): quien entraba justo
  cuando otra persona salía heredaba su id (el respaldo por distancia llega a 60 % del tamaño
  de la caja, cientos de px con cámara pegada), y un robo salía `BY_OWNER` sin alerta. Ahora
  se compara el color medio del torso: una persona que no se veía en el frame anterior solo
  conserva su id si la ropa se parece (distancia RGB ≤ 80), y un emparejamiento solo por
  cercanía la exige siempre. Calibrado con pocas fotos reales (misma persona 29–68, personas
  distintas 84–144), así que el margen es corto. Falla si las dos personas visten parecido; y
  si la misma persona cambia mucho de color (luz, movimiento) puede recibir un id nuevo y su
  propio retiro saldría `BY_OTHER`. Sin imagen (tests) el comportamiento es el de antes.
  No se usan UUID: el problema era la lógica de emparejamiento, no el formato del id.
- **La IA se equivoca en detalles pequeños**: dijo `lentes: ninguno` para alguien con lentes y
  "cabello largo" para alguien de pelo corto; con la persona entera el tatuaje casi no se ve
  (por eso el recorte de antebrazos). Los dibujos concretos de un tatuaje son adivinanzas.
- El color por pose dice "negra" para un azul marino oscuro (ambiguo en webcam).
- Solo se vigilan mochila, bolso y maleta; el objeto se registra tras 3 s quieto.

## Roadmap acordado con el usuario (en este orden)

1. ✅ Guardar frame/imagen en cada evento.
2. ✅ Dueño probable con foto, color, pose e IA opcional; retiro con quién lo retiró.
3. ✅ **Motor de alertas** (`alert-engine`, paquete `com.safemode.alerts`). `AlertEngine`
   lee los `REMOVED` de `PresenceEventStore` (`findRemovedAfter`, `findById`, `maxEventId`),
   `AlertRules` decide según `removal_kind` (`BY_OTHER`→`POSSIBLE_THEFT` HIGH,
   `OWNER_AND_OTHER_NEAR`→`AMBIGUOUS_REMOVAL` MEDIUM, `NO_ONE_NEAR`→`OBJECT_VANISHED` MEDIUM,
   `OWNER_UNKNOWN`→`UNKNOWN_OWNER_REMOVAL` LOW, `BY_OWNER` sin alerta) y `AlertStore` guarda
   la tabla `alert` en la misma base H2 (única por `event_id` + hora, porque los ids se
   reinician al vaciar). `addListener(...)` avisa de cada alerta nueva; `evidenceOf(alert)`
   trae el evento con fotos y descripciones (la de IA llega unos segundos después). Para
   correrlo junto al tracker, en otra terminal: `mvn -pl alert-engine exec:java
   "-Dexec.mainClass=com.safemode.alerts.AlertEngineRunner" "-Dexec.args=120"`.
4. ✅ **Tiempo real + panel** (lo que pidió el profesor: ver las alertas en vivo desde un
   panel distinto). `realtime-gateway` (paquete `com.safemode.gateway`, librería
   `Java-WebSocket` 1.5.6 + `slf4j-nop`): `AlertGateway` es el servidor WebSocket (puerto
   8090, solo `localhost`); se engancha con `AlertEngine.addListener` y manda `snapshot` al
   conectar, `alert` por cada alerta nueva y `update` cuando cambia una (llega la IA, o se
   revisa); el cliente manda `{"action":"ack","id":N}`. Revisa el `Origin` del navegador
   (solo `http://localhost:8080`) para que una web ajena no pueda leer las alertas.
   `DashboardHttpServer` (puerto 8080) entrega `security-dashboard/index.html` y las fotos
   (`/photo/<archivo>`, solo por nombre dentro de `data/frames/`). `security-dashboard/index.html`
   es una página estática sin build (todo el texto del servidor, incluida la IA, se pinta con
   `textContent`). Para verlo: terminal 1 el tracker; terminal 2 (raíz del repo, tras
   `mvn -pl realtime-gateway -am install -DskipTests`)
   `mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"`
   (ya incluye el motor de alertas, no hace falta `AlertEngineRunner`) y abrir
   `http://localhost:8080`. **Con Docker** (ver `docs/adr/0002-...md`): `docker compose up --build h2-db
   realtime-gateway` (base H2 en modo servidor + gateway) y el tracker en el computador con
   `$env:SAFEMODE_DB_URL = "jdbc:h2:tcp://localhost:9093/presence"` (`PresenceEventStore/AlertStore.fromEnvironment`;
   sin la variable usan el archivo local como siempre). El tracker no va en Docker (necesita `Robot`, modelos y la
   clave). Puertos solo en `127.0.0.1`; el gateway escucha en `0.0.0.0` dentro del contenedor (`SAFEMODE_BIND_HOST`).
   Se publica la base en 9093 porque la consola web de H2 ya usa el 9092. Si se corre el tracker SIN `SAFEMODE_DB_URL`
   con el panel en Docker, las alertas no llegan (son dos bases distintas). **Pendiente**: probar con cámara real y un navegador (los tests
   cubren el WebSocket con un cliente Java, no la página); docker-compose sigue comentado.

Pendientes menores que el usuario conoce: revisar lentes con un recorte de la cara (necesita
decisión de privacidad), pista `remover_looks_like_owner` por color de ropa, y regenerar la
clave de NVIDIA. La tabla `alert` no se vacía cuando el script del tracker vacía los eventos:
quedan alertas viejas (con fotos borradas) hasta marcarlas como revisadas.

## Notas operativas / gotchas

- Puede aparecer ruido de git por fin de línea (CRLF en Windows vs LF, `core.autocrlf=true`)
  en archivos que nadie tocó a propósito. Antes de asumir que es un cambio real, comparar con
  `git diff --ignore-space-at-eol -w <archivo>` — si da 0 líneas, es solo ruido.
- `.gitignore` excluye `object-presence-tracker/data/` completo (frames, modelos, base H2).
- `yolov8s.onnx` / `yolov8s.pt` están comprometidos por un compañero (44 MB / 22 MB); así está
  el repo.
- **Consola web de H2** (para ver la tabla): `java -cp <ruta-al-h2-2.2.224.jar>
  org.h2.tools.Console` abre `http://localhost:8082`; URL JDBC con ruta **absoluta** y barras
  normales (`jdbc:h2:file:C:/Users/Home/Desktop/Safemode-ARSW/object-presence-tracker/data/presence;AUTO_SERVER=TRUE`),
  usuario y contraseña vacíos. Desde **PowerShell 5.1** no sirve `org.h2.tools.Shell -user ""`
  (descarta los argumentos vacíos y da "Wrong user name or password"): usar la consola web o
  Git Bash. Consulta útil: `SELECT id, tracked_object_id, event_type, owner_person_id,
  removal_kind, remover_person_id, remover_description FROM object_presence_event ORDER BY id`.
- Para commitear archivos con cambios de dos temas sin herramientas interactivas: armar la
  versión intermedia de cada archivo, meterla al índice con
  `git hash-object -w --path=<ruta> <archivo>` + `git update-index --cacheinfo 100644,<sha>,<ruta>`,
  y probar ese estado exacto con `git checkout-index -a --prefix=<carpeta-corta>/` antes de
  commitear.
- El entorno donde se generó el contexto original (Cowork) no tiene credenciales de GitHub.
