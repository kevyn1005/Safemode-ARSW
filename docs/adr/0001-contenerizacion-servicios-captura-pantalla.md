# ADR 0001: Contenerizacion de camera-feed-service y vision-detection-service

## Estado
Aceptado y probado en Docker Desktop real (no solo revisado a ojo).

## Contexto
`camera-feed-service` usa `java.awt.Robot#createScreenCapture` para simular
un feed de camara capturando la pantalla. `vision-detection-service`
reutiliza esa misma clase (`FrameCapturer`) para alimentar el detector
YOLO. `Robot` depende de un entorno grafico real (`GraphicsEnvironment`);
dentro de un contenedor Docker headless, sin ajustes, la aplicacion lanza
`HeadlessException`/`UnsatisfiedLinkError` o falla al no encontrar un
`DISPLAY` de X11.

Ademas, ninguno de los dos modulos tenia `Dockerfile`, por lo que
`docker-compose.yml` apuntaba a builds inexistentes y fallaba de entrada.

## Decision
- Se agrego un `Dockerfile` a cada modulo, con contexto de build en la raiz
  del repo (`context: .` en `docker-compose.yml`), porque ambos son parte
  de un reactor Maven multi-modulo y necesitan el `pom.xml` padre y (en el
  caso de `vision-detection-service`) el modulo `camera-feed-service` del
  que dependen.
- Se instalan `xvfb`, `libxtst6` y `libxi6` (framebuffer X virtual +
  librerias que el AWT `Toolkit` de Java necesita para inicializar, aunque
  no haya pantalla real).
- `alert-engine`, `object-presence-tracker`, `realtime-gateway` y
  `security-dashboard` se comentaron en `docker-compose.yml`: todavia no
  tienen codigo real, asi que un contenedor para ellos arrancaria sin
  `Main-Class`/app real y fallaria igual. Se reactivan cuando el equipo
  implemente cada uno.

## Bugs encontrados probando en Docker Desktop real (con logs, no solo razonamiento)

1. **`xvfb-run -a mvn ... exec:java` se quedaba colgado indefinidamente.**
   Con `docker compose exec ... ps aux` se vio que `Xvfb` arrancaba y
   quedaba sano, pero el script `xvfb-run` (PID 1) nunca llegaba a lanzar
   `mvn` (no aparecia ningun proceso `mvn`/`java`).
2. **`mvn -pl <modulo> -am exec:java` no corre solo sobre `<modulo>`.**
   Con `-am`, Maven agrega el `pom.xml` padre al reactor, y una invocacion
   directa del goal `exec:java` se ejecuta sobre *todos* los proyectos del
   reactor. Como el padre no tiene `exec-maven-plugin` configurado, el
   build fallaba ahi con `mainClass ... missing or invalid`.
3. **`UnsatisfiedLinkError: libXtst.so.6`**: instalar solo `xvfb` no
   alcanza; el `Toolkit` de AWT necesita `libXtst`/`libXi` para inicializar
   aunque no se use entrada de teclado/mouse.
4. **Dependencia entre modulos del reactor no resoluble desde un proceso
   `mvn` nuevo**: instalar `camera-feed-service` con `mvn install` no
   basta si el `pom.xml` padre (`safemode-parent`) nunca se instalo al
   repositorio local; al releer el POM instalado de `camera-feed-service`
   como dependencia (no como parte de un reactor en disco), Maven necesita
   resolver su `<parent>` desde el repo, no del filesystem. Se agrego
   `mvn -N install` (instala solo el pom padre) antes de instalar
   `camera-feed-service`.

## Solucion aplicada
En vez de `xvfb-run` + `mvn exec:java` en tiempo de arranque:
- Se levanta `Xvfb :99` a mano en background dentro del `CMD`, con
  `sleep 1` de margen.
- Se arma el classpath en build-time (`mvn dependency:build-classpath`) y
  se corre la clase principal con `java -cp ...` directo, sin pasar por
  Maven en tiempo de ejecucion.

## Verificacion real (2026-09-18, Docker Desktop 29.6.1 / Compose v5.2.0)
`docker compose build` + `docker compose up camera-feed-service
vision-detection-service` corrieron de punta a punta sin errores:
`camera-feed-service` capturo 152 frames (1280x1024) de la pantalla
virtual de Xvfb y se detuvo solo a los 10s; `vision-detection-service`
capturo 20 frames (1195x660) y completo las 15 iteraciones de deteccion
("Sin detecciones" en todas, esperado porque la pantalla virtual esta
vacia). Ambos contenedores terminaron con `exited with code 0`.

## Consecuencias
- Dentro del contenedor, `Robot` captura la pantalla virtual de Xvfb (que
  esta vacia/negra), no la pantalla real del host. Sirve para validar que
  el pipeline arranca y corre sin errores, pero para ver detecciones
  reales sigue siendo necesario correr los servicios localmente (fuera de
  Docker), como se ha venido haciendo.
- El modelo `yolov8n.onnx` (~12.8 MB) sigue commiteado directo en git; no
  se toco su historial en este cambio porque migrarlo a Git LFS
  requeriria reescribir el historial y que todo el equipo instale
  git-lfs; queda como sugerencia para decidir en conjunto (ver README).
