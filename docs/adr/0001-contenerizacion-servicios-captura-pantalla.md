# ADR 0001: Contenerizacion de camera-feed-service y vision-detection-service con Xvfb

## Estado
Aceptado

## Contexto
`camera-feed-service` usa `java.awt.Robot#createScreenCapture` para simular
un feed de camara capturando la pantalla. `vision-detection-service`
reutiliza esa misma clase (`FrameCapturer`) para alimentar el detector
YOLO. `Robot` depende de un entorno grafico real (`GraphicsEnvironment`);
dentro de un contenedor Docker headless, sin este ajuste, la aplicacion
lanza `HeadlessException` o falla al no encontrar un `DISPLAY` de X11.

Ademas, ninguno de los dos modulos tenia `Dockerfile`, por lo que
`docker-compose.yml` apuntaba a builds inexistentes y fallaba de entrada.

## Decision
- Se agrego un `Dockerfile` a cada modulo, con contexto de build en la raiz
  del repo (`context: .` en `docker-compose.yml`), porque ambos son parte
  de un reactor Maven multi-modulo y necesitan el `pom.xml` padre y (en el
  caso de `vision-detection-service`) el modulo `camera-feed-service` del
  que dependen.
- Dentro de la imagen se instala `xvfb` y se ejecuta la aplicacion con
  `xvfb-run`, que levanta un framebuffer X virtual antes de arrancar la
  JVM. Esto evita tocar el codigo de `FrameCapturer`/`ObjectDetector`, que
  ya funciona corriendolo localmente.
- Se sigue usando `exec-maven-plugin` (el mismo mecanismo que ya usa el
  equipo para correr los demos localmente) en vez de generar un fat-jar,
  para no cambiar la forma en que el proyecto resuelve dependencias.
- `alert-engine`, `object-presence-tracker`, `realtime-gateway` y
  `security-dashboard` se comentaron en `docker-compose.yml`: todavia no
  tienen codigo real, asi que un contenedor para ellos arrancaria sin
  `Main-Class`/app real y fallaria igual. Se reactivan cuando el equipo
  implemente cada uno.

## Consecuencias
- `docker-compose build` ya no falla por Dockerfiles inexistentes.
- Dentro del contenedor, `Robot` captura la pantalla virtual de Xvfb (que
  esta vacia/negra), no la pantalla real del host. Sirve para validar que
  el pipeline arranca sin errores, pero para ver detecciones reales sigue
  siendo necesario correr los servicios localmente (fuera de Docker), como
  se ha venido haciendo.
- No se pudo ejecutar `docker build`/`docker-compose up` para verificar
  esto de punta a punta porque el entorno usado para este cambio no tiene
  Docker instalado; son Dockerfiles razonados pero no probados en runtime.
  No deberian afectar en nada la forma en que el equipo corre el proyecto
  localmente (via `mvn exec:java` / el IDE), que sigue igual.
- El modelo `yolov8n.onnx` (~12.8 MB) sigue commiteado directo en git; no
  se toco su historial en este cambio porque migrarlo a Git LFS
  requeriria reescribir el historial y que todo el equipo instale
  git-lfs; queda como sugerencia para decidir en conjunto (ver README).
