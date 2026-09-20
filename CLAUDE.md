# Safemode-ARSW — Contexto del proyecto

Este archivo resume el trabajo hecho hasta ahora en el módulo `object-presence-tracker`
(y alrededores) para que cualquier sesión de Claude Code que arranque en esta carpeta
tenga el contexto sin tener que repetir explicaciones. El usuario (Hever / "Chimi",
estudiante) está aprendiendo mientras se construye esto — explica los conceptos nuevos
paso a paso en vez de solo aplicar cambios en silencio.

**Constraint explícito y permanente del usuario**: "cuidado todo el código está
funcionando, mira que no falle nada". Cualquier cambio debe mantener pasando los tests
existentes. Preferir extender / agregar parámetros opcionales (ej. permitir `null`)
antes que reescribir comportamiento que ya funciona.

Rama de trabajo actual: `heverpersistencia`.

## Qué hace el proyecto

Detecta objetos dejados sin vigilancia (mochilas, maletas, bolsos) frente a una cámara,
y registra en una base de datos cuándo un objeto "se asienta" (queda quieto) y cuándo se
retira, incluyendo si había una persona cerca en ese momento. Es un proyecto universitario
de arquitectura de software, con varios módulos Maven en un reactor:

- `camera-feed-service` — captura de frames.
- `vision-detection-service` — detección de objetos con YOLOv8 (ONNX).
- `object-presence-tracker` — lógica de seguimiento + persistencia (foco de casi todo el
  trabajo reciente).
- `alert-engine`, `realtime-gateway`, `security-dashboard` — carpetas ya existentes en el
  repo, todavía sin implementar (ver roadmap abajo).

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
   servidor de BD aparte, y se puede inspeccionar con SQL desde la consola de H2
   (`java -cp <ruta-al-jar-de-h2> org.h2.tools.Console`). Driver: `org.h2.Driver`,
   dependencia `com.h2database:h2:2.2.224`. Para pruebas se usa `jdbc:h2:mem:...` en
   memoria (`PresenceEventStore.inMemory(name)`).
3. **Las fotos de cada evento se guardan como archivo PNG en disco**
   (`object-presence-tracker/data/frames/`), y solo la *ruta* se guarda en la base de
   datos (columna `frame_path`, nullable) — no como BLOB. Es el patrón estándar para
   apps con contenido multimedia (las BDs no están pensadas para servir archivos
   binarios grandes).
4. **Maven reactor, ejecutar clases `main` de un solo módulo**: primero
   `mvn -pl object-presence-tracker -am install -DskipTests` (una vez, para poblar el
   `.m2` local con las dependencias de los otros módulos), y LUEGO
   `mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=..."` **sin** `-am`.
   Con `-am`, `exec:java` se ejecuta sobre TODO el reactor (incluyendo el POM padre) y
   falla con `ClassNotFoundException`. Para `test` (`mvn -pl X -am test`) sí se usa
   `-am` normalmente, ese comando no tiene este problema.
5. **PowerShell**: los argumentos `-D` deben ir entre comillas como un solo string,
   ej. `"-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest"`. Sin
   comillas, PowerShell lo trocea y Maven falla con "Unknown lifecycle phase".

## Estado actual de `object-presence-tracker`

- **`ObjectTracker`**: máquina de estados `NEW → AT_REST` (tras quedar quieto
  `REST_DURATION` = 3s) `→ REMOVED` (tras `MAX_FRAMES_UNSEEN` = 3 frames sin verlo, o un
  movimiento brusco estando en reposo). Matching de detecciones por IoU con fallback por
  distancia entre centros. `onFrame(BufferedImage frame, List<Detection> detections)`
  recibe también el frame completo; en los eventos `AT_REST`/`REMOVED` llama a
  `saveFrameSnapshot(...)`, que escribe un PNG (`ImageIO.write`) bajo
  `frameStorageDir` (por defecto `object-presence-tracker/data/frames/`, configurable
  vía constructor para pruebas con `@TempDir`) y devuelve la ruta, o `null` si `frame`
  es `null` (las pruebas de lógica pura no simulan cámara real y no rompen nada).
- **`PresenceEventStore`**: tabla `object_presence_event` con columnas `id`,
  `tracked_object_id`, `class_name`, `event_type`, `occurred_at`, `pos_x`, `pos_y`,
  `width`, `height`, `person_nearby`, `frame_path`. `recordRegistered(...)` y
  `recordRemoved(...)` reciben un `String framePath` adicional. Helpers de prueba:
  `countEvents(eventType)`, `lastPersonNearby(eventType)`, `lastFramePath(eventType)`.
- **`ObjectTrackerTest`**: 7 pruebas, todas verdes. 6 originales usan `onFrame(null, ...)`
  porque no simulan cámara; la nueva `guardaUnaImagenDelFrameCuandoRegistraElObjeto`
  usa `@TempDir` + una `BufferedImage` real y verifica que el PNG se escriba en disco.
- **`ObjectPresenceTrackerTest`** (`src/main/java`, no es JUnit): script de verificación
  manual con cámara/pantalla real. El `Rectangle region` está ajustado al monitor de
  quien prueba — **es un valor local, no portable, no se sube tal cual al repo
  compartido** (cada quien ajusta el suyo).
- Verificado end-to-end con webcam real (vía app de Windows Cámara + captura de
  pantalla): ciclo completo mochila → `REGISTERED_AT_REST` → `REMOVED`, confirmado con
  filas reales en H2 Console.

## Roadmap acordado con el usuario (en este orden)

1. ✅ **Guardar frame/imagen en cada evento** (lo de arriba, ya hecho y probado).
2. ⏭️ **Siguiente paso: motor de alertas** (`alert-engine`). Lógica que observa los
   eventos de `PresenceEventStore` (probablemente con foco en `REMOVED`, y
   especialmente `person_nearby = false` como caso más "sospechoso") y genera registros
   de alerta.
3. **Dashboard / centro de alertas**: tablero visual tipo "en la cámara 1 se retiró la
   maleta" (con la foto guardada como evidencia). Probablemente involucra
   `realtime-gateway` y/o `security-dashboard`, sin diseño detallado todavía.

## Notas operativas / gotchas

- Puede aparecer ruido de git por diferencias de fin de línea (CRLF en Windows vs LF)
  en archivos que nadie tocó a propósito (se ha visto en `ObjectDetector.java`,
  `VisionDetectionTest.java`). Antes de asumir que es un cambio real, comparar con
  `git diff --ignore-space-at-eol -w <archivo>` — si da 0 líneas, es solo ruido del
  checkout de Windows (`core.autocrlf`), no hay que arreglarlo ni commitearlo.
- `.gitignore` ya excluye `object-presence-tracker/data/` completo (cubre también la
  subcarpeta `frames/` sin necesidad de agregarla aparte).
- Modelos ONNX/PyTorch (`yolov8s.onnx`, `yolov8s.pt`) están comprometidos directamente
  en git por un compañero de equipo (son pesados, 44MB/22MB) — no es una decisión mía,
  simplemente así está el repo.
- El entorno donde se generó este contexto (Cowork) no tiene credenciales de GitHub: los
  `git push` siempre los hace el usuario desde su propia terminal.
