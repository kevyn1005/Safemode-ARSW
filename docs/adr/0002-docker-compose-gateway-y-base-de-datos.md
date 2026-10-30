# ADR 0002: docker-compose para el gateway en tiempo real y la base de datos compartida

## Estado
Aceptado. La verificacion real (build y arranque en Docker Desktop) esta al final.

## Contexto
El centro de alertas necesita tres procesos que se hablan: el tracker (detecta y guarda eventos), el motor de alertas y
el gateway (empuja las alertas al panel por WebSocket). Para que el profesor o un companero lo levante sin instalar Java
ni Maven, se quiere un `docker compose up`.

Dos restricciones impiden meterlo todo en contenedores:
1. **El tracker no puede ir en Docker.** Captura la pantalla real con `java.awt.Robot` (un contenedor no tiene pantalla,
   ver ADR 0001), usa modelos de ~200 MB que no estan en el repo (`data/models/`) y la clave de NVIDIA.
2. **El tracker y el gateway comparten la base de datos.** Antes lo hacian abriendo el mismo archivo H2 con
   `AUTO_SERVER`, que solo funciona entre procesos del mismo sistema de archivos. Entre un proceso en Windows y un
   contenedor Linux con el archivo montado es fragil (bloqueos de archivo sobre carpetas compartidas, y H2 anuncia en
   el archivo de bloqueo una direccion que el contenedor quiza no alcanza).

## Decision
- **H2 pasa a ser un servicio** (`h2-db`): el mismo H2 2.2.224 en modo servidor TCP, con su archivo en un volumen de
  Docker. El tracker (en el computador) y el gateway (en su contenedor) se conectan por red con
  `jdbc:h2:tcp://.../presence`. Es la forma normal de compartir una base entre procesos.
- **La conexion se configura con la variable de entorno `SAFEMODE_DB_URL`** (`PresenceEventStore.fromEnvironment`,
  `AlertStore.fromEnvironment`). Sin la variable todo funciona como antes (archivo local con `AUTO_SERVER`), asi que
  nada de lo que ya funcionaba cambia.
- **`realtime-gateway` es un solo contenedor** con el motor de alertas dentro (misma JVM, es una libreria) y el panel
  como archivo estatico que el gateway entrega. No hay contenedores separados para `alert-engine` ni
  `security-dashboard`: no tienen nada que servir por su cuenta.
- **Las fotos** las escribe el tracker en `object-presence-tracker/data/frames/` del computador y el contenedor las lee
  por un volumen montado en solo lectura. La base guarda rutas de Windows; el gateway toma solo el nombre del archivo
  (`AlertJson.photoUrl`, ahora acepta barras `/` y `\`).
- **Puertos solo en `127.0.0.1`** (`8080` pagina, `8090` WebSocket, `9093` base): la base y las fotos contienen datos de
  personas y no deben verse desde la red. Dentro del contenedor el gateway escucha en `0.0.0.0`
  (`SAFEMODE_BIND_HOST`), porque en `localhost` del contenedor el puerto publicado no llegaria.
- **La base se publica en el `9093`** del computador (por dentro sigue en 9092) porque la consola web de H2
  (`org.h2.tools.Console`) ya ocupa el 9092.
- El jar de H2 se descarga con checksum SHA-256 fijado en el `Dockerfile`.
- Se agrego `.dockerignore` en la raiz: el contexto de build no debe incluir `.git`, `target/` ni
  `object-presence-tracker/data/` (base, fotos de personas y modelos).

## Como se usa
```
docker compose up --build h2-db realtime-gateway        # y abrir http://localhost:8080
```
El tracker, en el computador y con la base del contenedor:
```
$env:SAFEMODE_DB_URL = "jdbc:h2:tcp://localhost:9093/presence"
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest"
```

## Consecuencias
- Con la base en el contenedor (`h2-db`), el archivo local `object-presence-tracker/data/presence` deja de usarse: son
  dos bases distintas. Si se corre el tracker **sin** `SAFEMODE_DB_URL` mientras el panel esta en Docker, las alertas no
  llegan (escribe en su archivo, no en la base del contenedor).
- El script del tracker vacia la tabla de eventos al empezar; la tabla `alert` no se vacia (quedan alertas viejas con
  fotos borradas hasta marcarlas como revisadas).
- La imagen del gateway usa `maven:3.9-eclipse-temurin-17` en una sola etapa, igual que las otras dos (coherencia con el
  ADR 0001, a costa de tamano).
- La base de datos no tiene contrasena: la unica proteccion es que el puerto se publica en `127.0.0.1`.

## Verificacion real (2026-09-21, Docker Desktop 29.6.1 / Compose v5.2.0)
`docker compose up -d --build h2-db realtime-gateway` construyo las dos imagenes y arranco los dos contenedores
(`h2-db` con healthcheck en verde, y el gateway despues de el). Probado de punta a punta con datos de prueba:
- la pagina responde en `http://localhost:8080` y una foto en `/photo/<archivo>` (200, `image/png`);
- desde el computador se escribio un retiro sospechoso en la base del contenedor por TCP
  (`jdbc:h2:tcp://localhost:9093/presence`): llego al cliente WebSocket como alerta `POSSIBLE_THEFT` y luego como
  `update` cuando se agrego la descripcion por IA; la foto se sirvio aunque la ruta guardada era de Windows;
- `docker compose down` detuvo el gateway y la base en ~1.5 s (el hook de cierre funciona con SIGTERM).

**No probado**: el tracker real con camara apuntando a la base del contenedor, ni la reconstruccion de las imagenes de
`camera-feed-service` y `vision-detection-service` con el `.dockerignore` nuevo (solo agrega exclusiones que esas imagenes
no copian).
