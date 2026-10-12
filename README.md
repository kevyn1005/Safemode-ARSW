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

Sin Docker sigue funcionando como antes (no definir `SAFEMODE_DB_URL`).
Detalles y motivos en [ADR 0002](docs/adr/0002-docker-compose-gateway-y-base-de-datos.md).

### Compartir la ventana de la camara (en vez de capturar una zona de la pantalla)

Por defecto el tracker captura la mitad izquierda de la pantalla, asi que cualquier ventana encima de la camara arruina la
deteccion. Con esta opcion el operador comparte **la ventana de la camara** desde el panel, como en una videollamada:

1. Abre la camara (app Camara, VLC...) en su ventana.
2. Corre el tracker con la fuente "browser" (PowerShell):
   `$env:SAFEMODE_FRAME_SOURCE = "browser"` y luego el comando de siempre (`ObjectPresenceTrackerTest`).
3. Abre el panel (http://localhost:8080) en Chrome o Edge y pulsa **Compartir ventana de la camara**; elige la ventana.

Se puede tapar la ventana de la camara con otras y trabajar en el resto del computador. **No** se debe minimizar la
ventana de la camara (Windows deja de dibujarla) ni cerrar la pestana del panel; minimizar todo Chrome puede ralentizar
el envio. Los frames viajan solo por `localhost` (puerto 8091) y el tracker solo acepta paginas del panel (`Origin`).

### Dos camaras (con una sola camara fisica para probar)

Con el selector **Camaras = 2** del panel, la ventana compartida es un mosaico (como el de un software de camaras) y se dibuja
con el mouse la zona de **CAM 1** y la de **CAM 2**; cada zona se vigila por separado y las alertas dicen de que camara son. Los
nombres de camara se editan con **Nombres de camaras**. Para probar sin dos camaras: abre
`http://localhost:8080/mosaico-prueba.html` en otra ventana de Chrome (muestra tu camara en dos recuadros), pulsa
**Compartir ventana de la camara** en el panel y elige esa ventana. Detalles y limites en el
[ADR 0003](docs/adr/0003-dos-camaras-zonas-y-reporte.md).

### Reporte de detecciones (boton "Generar reporte")

El centro de alertas solo muestra los retiros sospechosos. El boton **Generar reporte** del panel abre una tabla con **todo lo que
registro el sistema**: cada objeto que quedo en reposo y cada retiro, con fecha, hora, camara, objeto, dueno probable, quien lo
retiro, fotos y si genero alerta (y por que no: por ejemplo, "Sin alerta: lo retiro su dueno"). Sirve para comprobar que el sistema
esta reconociendo aunque no haya saltado ninguna alerta. Para ver ademas lo que detecta fotograma a fotograma, revisa
`object-presence-tracker/data/tracker.log`: explica cada decision (si una persona es nueva o la misma, por que un objeto no tiene
dueno, de donde sale cada retiro). Al volver a iniciar la vigilancia desde el panel, el log anterior queda en `tracker.previous.log`.

### Avisos de alertas (navegador y Telegram)

Una alerta **nueva** de severidad **alta o media** puede avisarte aunque no estes mirando el panel. El aviso es solo texto
(`SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.`): sin fotos ni datos de personas, que se ven solo en el
panel. Las alertas de severidad baja no avisan.

- **Navegador**: pulsa **Avisos del navegador** en el panel y acepta el permiso. Funciona con la pestana del panel abierta, aunque
  este en segundo plano o en otra ventana.
- **Telegram** (gratis): 1) en Telegram habla con `@BotFather` y crea un bot con `/newbot`; 2) guarda la clave que te da **solo como
  variable de entorno**, sin pegarla en el chat ni en el repo (`setx SAFEMODE_TELEGRAM_BOT_TOKEN "..."` y abre una terminal nueva);
  3) abre tu bot en Telegram y escribe `/start`; 4) `mvn -pl realtime-gateway exec:java
  "-Dexec.mainClass=com.safemode.gateway.notify.TelegramChatIds"` muestra el numero de tu chat; 5) `setx SAFEMODE_TELEGRAM_CHAT_IDS
  "123456789"` (varios, separados por comas) y reinicia el gateway. Al arrancar dice `Avisos de alertas altas y medias por: telegram`.
  Para comprobarlo sin camara ni alertas: `mvn -pl realtime-gateway exec:java
  "-Dexec.mainClass=com.safemode.gateway.notify.TelegramTestMessage"` manda un mensaje de prueba a tu chat.
  Mientras no haya inicio de sesion, los chats son esa lista fija (ADR 0004).

### Boton "Iniciar vigilancia" (una sola terminal)

Con el gateway corriendo en el computador (no en Docker) el panel ofrece **Iniciar vigilancia** / **Detener vigilancia**: el
gateway lanza el tracker por ti (con la fuente "ventana compartida", sin limite de tiempo) y lo detiene de forma ordenada.

1. Una vez (y tras cambiar codigo): `mvn -pl realtime-gateway -am install -DskipTests`
2. Desde la raiz del repo: `mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"`
3. Abre http://localhost:8080, pulsa **Iniciar vigilancia** (pide un segundo clic: borra las fotos y eventos de la corrida
   anterior) y luego **Compartir ventana de la camara**.

La salida del tracker queda en `object-presence-tracker/data/tracker.log`. Seguridad: el comando esta fijo en el servidor,
los POST solo se aceptan desde el propio panel (`Origin` y `Host`), el boton no se ofrece si el gateway escucha fuera de
`localhost` (Docker), y si el gateway se cierra el tracker se detiene con el.

## Decisiones de arquitectura (ADR)

- [ADR 0001](docs/adr/0001-contenerizacion-servicios-captura-pantalla.md): contenerizacion de los servicios de captura.
- [ADR 0002](docs/adr/0002-docker-compose-gateway-y-base-de-datos.md): docker-compose para el gateway y la base de datos.
- [ADR 0003](docs/adr/0003-dos-camaras-zonas-y-reporte.md): dos camaras desde una ventana compartida, zonas dibujadas y
  reporte de alertas (propuesto, aun no implementado).
- [ADR 0004](docs/adr/0004-login-google-y-notificaciones.md): inicio de sesion con Google y notificaciones por navegador,
  Telegram y correo (propuesto, aun no implementado).

## Notas

- El modelo `vision-detection-service/src/main/resources/models/yolov8n.onnx`
  (~12.8 MB) esta commiteado directo en el repo. Si el equipo agrega mas
  modelos grandes, vale la pena evaluar Git LFS en conjunto (requiere que
  todos lo instalen).
- `FrameCapturerTest`, `ScreenCaptureTest` y `VisionDetectionTest` son
  scripts de verificacion manual (tienen `main`, no usan JUnit) — no
  corren con `mvn test`.
