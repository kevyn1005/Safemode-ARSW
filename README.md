# Safemode-ARSW

Sistema de vigilancia inteligente (proyecto de la asignatura ARSW) que
detecta personas y objetos (mochilas, bolsos, maletas) a partir de un feed
de video, organizado como un conjunto de microservicios.

## Modulos

| Modulo | Estado | Descripcion |
|---|---|---|
| `camera-feed-service` | Implementado | Captura de pantalla (simula camara) con `java.awt.Robot`, expone el ultimo frame en memoria. |
| `vision-detection-service` | Implementado | Deteccion de objetos con YOLOv8 (ONNX Runtime) sobre los frames de `camera-feed-service`. |
| `object-presence-tracker` | Pendiente | Solo esqueleto Maven, sin codigo todavia. |
| `alert-engine` | Pendiente | Solo esqueleto Maven, sin codigo todavia. |
| `realtime-gateway` | Pendiente | Solo esqueleto Maven, sin codigo todavia. |
| `security-dashboard` | Pendiente | Solo `package.json` placeholder, sin codigo todavia. |

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

## Notas

- El modelo `vision-detection-service/src/main/resources/models/yolov8n.onnx`
  (~12.8 MB) esta commiteado directo en el repo. Si el equipo agrega mas
  modelos grandes, vale la pena evaluar Git LFS en conjunto (requiere que
  todos lo instalen).
- `FrameCapturerTest`, `ScreenCaptureTest` y `VisionDetectionTest` son
  scripts de verificacion manual (tienen `main`, no usan JUnit) — no
  corren con `mvn test`.
