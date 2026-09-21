# Safemode-ARSW

Sistema de vigilancia inteligente (proyecto de la asignatura ARSW) que
detecta personas y objetos (mochilas, bolsos, maletas) a partir de un feed
de video, organizado como un conjunto de microservicios.

## Modulos

| Modulo | Estado | Descripcion |
|---|---|---|
| `camera-feed-service` | Implementado | Captura de pantalla (simula camara) con `java.awt.Robot`, expone el ultimo frame en memoria. |
| `vision-detection-service` | Implementado | Deteccion de objetos con YOLOv8 (ONNX Runtime) sobre los frames de `camera-feed-service`. |
| `object-presence-tracker` | Implementado | Seguimiento de mochilas, bolsos y maletas: dueno, retiro (por el dueno o por otra persona) y registro en H2 con fotos. |
| `alert-engine` | Implementado | Convierte los retiros sospechosos en alertas guardadas en H2 y avisa a quien se suscriba. |
| `realtime-gateway` | Implementado | Servidor WebSocket que empuja cada alerta al panel en cuanto se crea, y servidor HTTP para la pagina y las fotos. |
| `security-dashboard` | Implementado | Pagina `index.html` (sin build) con el centro de alertas en vivo: severidad, fotos de evidencia y boton de revisada. |

Ver el diagrama en [docs/diagramas/arquitectura-general.md](docs/diagramas/arquitectura-general.md).

## Como correr lo que ya existe

Localmente (como se ha venido probando):

```
mvn -pl camera-feed-service -am exec:java
mvn -pl vision-detection-service -am org.codehaus.mojo:exec-maven-plugin:3.2.0:java -Dexec.mainClass=com.safemode.vision.VisionDetectionTest -Dexec.classpathScope=compile
```

Con Docker (solo build/arranque; dentro del contenedor no hay pantalla
real que capturar, ver [ADR 0001](docs/adr/0001-contenerizacion-servicios-captura-pantalla.md)):

```
docker-compose up --build camera-feed-service vision-detection-service
```

### Centro de alertas en vivo (Docker)

La base de datos y el gateway con el panel corren en contenedores; el tracker corre en el computador (necesita la
pantalla real, los modelos y la clave de NVIDIA) y se conecta a la base del contenedor:

```
docker compose up --build h2-db realtime-gateway        # y abrir http://localhost:8080
```

En otra terminal (PowerShell), el tracker apuntando a esa base (puerto `9093`):

```
$env:SAFEMODE_DB_URL = "jdbc:h2:tcp://localhost:9093/presence"
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest"
```

Sin Docker sigue funcionando como antes (no definir `SAFEMODE_DB_URL`; ver la lista de pasos en `CLAUDE.md`).
Detalles y motivos en [ADR 0002](docs/adr/0002-docker-compose-gateway-y-base-de-datos.md).

## Notas

- El modelo `vision-detection-service/src/main/resources/models/yolov8n.onnx`
  (~12.8 MB) esta commiteado directo en el repo. Si el equipo agrega mas
  modelos grandes, vale la pena evaluar Git LFS en conjunto (requiere que
  todos lo instalen).
- `FrameCapturerTest`, `ScreenCaptureTest` y `VisionDetectionTest` son
  scripts de verificacion manual (tienen `main`, no usan JUnit) — no
  corren con `mvn test`.
