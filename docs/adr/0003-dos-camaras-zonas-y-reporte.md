# ADR 0003: dos camaras desde una ventana compartida, zonas dibujadas y reporte de alertas

## Estado
Parcialmente implementado (oct-2026). Hecho y probado: `camera_id` en eventos, nombres de camara editables, un seguimiento
independiente por camara, protocolo de fotogramas por camara, modo de 1 o 2 camaras en el panel, zonas dibujadas con
prueba previa, modelo segun el modo y una pagina de prueba (`mosaico-prueba.html`). El boton "Generar reporte" (seccion 7).
Pendiente: la pista "puede ser el dueno que volvio" (seccion 8). Se pasara a "Aceptado" cuando se verifique con una
corrida real con camara (ver "Como probarlo").

## Contexto
Hasta ahora el sistema vigila **una sola camara**: el tracker recibe la imagen completa de la ventana compartida (ADR
posterior a 0002, "compartir ventana desde el navegador") o de una zona de la pantalla. Revisando la sustentacion contra
los lineamientos del curso aparecieron desajustes que este ADR resuelve:

- La sustentacion promete **concurrencia con varias camaras en paralelo**, pero solo hay una.
- El criterio de aceptacion promete un **clip de video** como evidencia, pero hoy la evidencia son fotos PNG.
- En la practica, los centros de vigilancia ya usan un software de camaras (VMS/NVR) que **muestra varias camaras en una
  sola ventana y graba el video por su cuenta**. Duplicar esa grabacion no aporta valor y agrega almacenamiento y
  privacidad que no hacen falta.
- Cada software de camaras, y cada operador, acomoda las camaras de forma distinta: no se puede suponer una disposicion
  fija (lado a lado, una encima de otra, cuadricula).

## Decision

### 1. Dos camaras como MVP, escalable
- El operador elige **1 o 2 camaras** en el panel.
  - **1 camara**: funciona exactamente como hoy (se analiza la ventana completa, sin zonas). Por dentro es una zona que
    ocupa todo el cuadro, asi que el codigo es el mismo y lo que ya funciona no cambia.
  - **2 camaras**: se activa el flujo de zonas descrito abajo (CAM 1 y CAM 2).
- Las camaras no se escriben a mano en el codigo: son una **lista de camaras**, cada una con su zona. Agregar una tercera
  mas adelante es permitir una entrada mas; el limite real pasa a ser la capacidad del computador.

### 2. Zonas dibujadas por el operador
- Al compartir la ventana, el panel muestra una **vista previa** y el operador **dibuja un rectangulo** sobre cada camara
  (CAM 1, CAM 2). Lo que queda fuera de las zonas (otras camaras del mosaico) se ignora.
- Cada zona se guarda **en porcentaje** del tamano de la ventana (no en pixeles), junto con el tamano de referencia y una
  miniatura. Asi el rectangulo sigue sobre la misma camara si cambia la escala.
- El recorte se hace **en el navegador**, que ya tiene la imagen: cada recorte viaja al tracker marcado con su camara. El
  tracker ve "CAM 1" y "CAM 2" como fuentes separadas.
- Dibujar las zonas solo indica **donde mirar**; la deteccion de personas y maletas la sigue haciendo YOLO.

### 3. Prueba previa a iniciar
Antes de arrancar con 2 camaras el panel muestra siempre una pantalla de verificacion (aunque tarde unos segundos mas):
1. **Vista previa en vivo con los rectangulos dibujados**: es la verificacion real, la hace el operador.
2. **Tamano de la ventana** igual al guardado. Si cambio, bloquea el inicio hasta redibujar o confirmar. Es fiable.
3. **Parecido de cada zona con su miniatura**. Es solo un aviso, no bloquea: una cancha cambia de aspecto (gente, luz) y
   no se quieren falsas alarmas. Se reutilizara la idea de `RegionFingerprint`.

Botones: **Iniciar** / **Volver a dibujar**. Mientras corre, si el tamano de la ventana compartida cambia, el panel pausa
la vigilancia y avisa.

Limite honesto: el sistema no puede saber con certeza que el operador reacomodo las camaras; por eso la vista previa es
siempre visible y los chequeos automaticos son una ayuda.

### 3b. Modelo de deteccion segun el modo (decidido)
- **1 camara: `yolov8m`**, como hoy (mejor calidad; ~0.37 s por fotograma cabe en el ritmo del tracker).
- **2 camaras: `yolov8s` con 8 hilos por detector** (los 8 nucleos fisicos de la CPU de desarrollo repartidos entre los dos
  detectores): cabe a ~2.6-2.9 fps por camara. Antes de adoptarlo hay que comprobar con una corrida real que no pierde
  objetos frente a `yolov8m`. El modelo y los hilos se eligen al arrancar segun el numero de camaras elegido en el panel
  (no se escribe a mano en el codigo). Se implementa junto con las dos camaras.

### 4. Estado independiente por camara (concurrencia)
- Un **seguimiento independiente por camara** (objetos, personas, ids y duenos no se mezclan).
- `camera_id` en los eventos y en las alertas; el id de objeto y de persona pasa a ser unico **por camara**. Las fotos
  incluyen la camara en el nombre. La columna se agrega con `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` y las filas
  viejas toman la camara 1 (compatibilidad).
- Cada alerta se sigue creando **una sola vez** (el indice unico de `alert` ya lo garantiza).
- Escenario de concurrencia que se defiende: dos camaras analizadas en paralelo, el ciclo de frames junto con el pool de
  IA en segundo plano, y varios paneles confirmando la misma alerta.

### 5. Evidencia: fotos mas fecha, hora y camara. Sin guardar video
- **No se guarda video**: lo graba el software de camaras. La alerta entrega fotos (objeto, dueno probable, quien lo
  retira) y la **fecha, hora y camara** para localizar el momento en la grabacion.
- Cambio de criterio de aceptacion (Gherkin): "genera una alerta en menos de 5 segundos con fotos, fecha, hora y camara,
  hacia el panel de seguridad" (antes decia "clip de video").
- La hora es la del computador donde corre el sistema, que puede diferir unos segundos de la del software de camaras. El
  reporte debe decir "buscar alrededor de esa hora (+/- 30 s)", no una hora exacta.

### 6. Nombres de camara editables
- El nombre (por defecto "CAM 1", "CAM 2") se guarda en una tabla de camaras y el usuario lo cambia cuando quiera desde
  el panel. Las alertas guardan solo el numero de camara y el nombre se resuelve al mostrarlas: si se renombra, las
  alertas viejas muestran el nombre nuevo.

### 7. Reporte
- El **centro de alertas se mantiene como esta**. Se agrega un boton **"Generar reporte"** que muestra una tabla
  ordenada con el detalle de la alerta: fecha, hora, camara, tipo, objeto, dueno probable, quien lo retiro y fotos, mas la
  pista para buscar la grabacion. Se arma con lo que ya esta en la base.

### 8. Dueno que sale de cuadro y vuelve (decidido: declarar el limite + avisar sin silenciar)
- **Problema**: el seguimiento olvida a una persona tras 5 frames sin verla. Si el dueno sale de cuadro y vuelve a llevarse
  su maleta recibe otro id y el retiro sale como `BY_OTHER` (posible robo), aunque era el.
- **Criterio rector**: en un sistema de seguridad es peor no avisar de un robo real que avisar de mas. Ninguna solucion
  puede **silenciar** una alerta por parecerse al dueno.
- **Decision (opciones A + D)**:
  - **A. Se declara el limite.** El criterio de aceptacion de la sustentacion pasa a: "el sistema alerta ante todo retiro
    por una persona distinta al dueno registrado y, cuando la persona se parece al dueno, lo indica con evidencia para que
    el operador decida".
  - **D. Solo avisar, nunca silenciar.** La alerta `BY_OTHER` se mantiene con su severidad. Si quien retira el objeto se
    parece al dueno registrado por color de ropa (la pista `remover_looks_like_owner` que estaba pendiente), la alerta y el
    reporte muestran **"puede ser el dueno que volvio"** con las fotos del dueno y de quien retira lado a lado.
- **Descartadas**:
  - Reasignar el id del dueno por color de ropa (B): un ladrono con ropa parecida quedaria como "el dueno" y no habria
    alerta; los margenes de color calibrados ya son cortos.
  - Reidentificacion por modelo de apariencia (C): queda como **mejora futura** en el Release Plan; es la que mas cierra el
    problema pero cuesta un modelo nuevo, mas CPU y calibracion, y habria que declararla como identidad por apariencia.
- **Refinamiento tras las pruebas con una camara (oct-2026, decidido por el equipo): D pasa a B.** En 4 de 5 ciclos el dueno salio
  de cuadro de 6 a 11 fotogramas (7 a 15 s), el seguimiento lo olvido (a los 6 fotogramas sin verlo) y al volver vestido igual su
  retiro salio como posible robo con severidad ALTA. Ahora, si TODAS las personas junto al objeto se visten parecido al dueno (color
  medio del torso a 60 o menos de distancia; mas estricto que el 80 de la identidad, porque de esto depende la severidad), la alerta
  es `POSSIBLE_OWNER_RETURN` ("Posible dueno que volvio", severidad MEDIA) con las fotos del dueno y de quien retiro lado a lado.
  **Sigue sin silenciarse**: queda visible y, al ser MEDIA, tambien notifica (ADR 0004). Si alguna persona se ve distinta, o falta la
  ropa del dueno (sin imagen), sigue siendo posible robo ALTA. Limite declarado: un ladron vestido de un color parecido al del dueno
  (p. ej. ropa oscura) saldra como MEDIA en vez de ALTA. Se guarda en la columna `remover_looks_like_owner`; el umbral de 60 se
  calibra con el log (`Retiro por otra persona, comparada con el dueno #N: persona #M color a X`): los retornos del mismo dueno
  midieron de 14 a 44 y las personas distintas de 93 a 180.

### 9. Dueno probable (alcance)
- El alcance de la sustentacion se corrige: el **dueno probable** (por cercania, con foto y descripcion) pasa de "Won't" a
  "Should". No es reconocimiento de identidad. La descripcion por IA externa (NVIDIA) es **opcional** y solo se activa si
  existe la clave; la privacidad de ese envio se declara en la sustentacion. No se guarda video.

## Consecuencias
- Cambios en: `PresenceEventStore` y `AlertStore` (camera_id, tabla de camaras), el tracker (una instancia por camara y
  fuente de frames con camara), `camera-share.js` (zonas, verificacion, recorte), el panel (camara en cada alerta, nombres
  y reporte).
- **Rendimiento medido** (computador de desarrollo, 16 nucleos, escena 1920x810, `DetectionBenchmark`):

  | Modelo | 1 camara | 2 camaras en fila (ciclo) | 2 camaras en paralelo (por fotograma) |
  |---|---|---|---|
  | `yolov8m` (el que se usa) | 371 ms | 702 ms | 638 ms |
  | `yolov8s` | 183 ms | 348 ms | 382 ms |

  Con `yolov8m` dos camaras **no alcanzan 2 fps** por camara (maximo ~1.6); con `yolov8s` si (~2.6-2.9). Ademas el bucle del
  tracker duerme 500 ms **despues** de detectar, asi que un fotograma real dura inferencia + 500 ms (~0.87 s con `yolov8m`
  y una camara, ~1.1 fps), no 500 ms. Ver las decisiones abiertas en "Pendientes relacionados".
- **Opcion (c), limitar los hilos de cada detector, probada** (`ObjectDetector(modelPath, intraOpThreads)`, constructor
  nuevo y opcional; el equipo tiene una CPU de 8 nucleos fisicos y 16 logicos). Con `yolov8m` y **8 hilos por detector**: 1
  camara 296-358 ms y 2 camaras en paralelo 588-794 ms por fotograma (las corridas varian por la carga del equipo). Con 4 o
  6 hilos no mejora y con 10 o 12 hilos por detector (mas que los nucleos fisicos entre los dos) empeora mucho (1.2 s).
  Conclusion: ayuda entre ~8 % y ~20 %, pero **no alcanza los 2 fps por camara con `yolov8m`** (maximo ~1.7).
- **Calidad de `yolov8s` frente a `yolov8m`**: en la unica escena de prueba con objetos ven lo mismo (persona 0.95; mochila
  0.86 con m y 0.81 con s). Es un solo caso: no demuestra que s no pierda objetos tapados o lejanos; hay que comprobarlo con
  una corrida real antes de cambiar de modelo.
- **Memoria nativa del detector (corregido)**: `ObjectDetector.detect` no cerraba los tensores ni el resultado de ONNX
  Runtime; en 600 fotogramas la memoria virtual del proceso subia de ~0.35 GB a ~2.4 GB hasta que corria el recolector de
  basura. Se cierran con `try-with-resources` y la memoria queda plana en ~440 MB con las mismas detecciones.
- **Latencia medida** (`AlertLatencyBenchmark`, componentes reales con base en memoria): del evento de retiro al panel por
  WebSocket, media ~0.5 s y p95 ~0.92 s (domina el intervalo de lectura del motor, 1 s). Sumando confirmar el retiro (3
  fotogramas), el peor caso estimado es ~3.5 s con 1 camara y `yolov8m`, ~4.3-4.5 s con 2 camaras y `yolov8m` (poco
  margen) y ~3.0 s con `yolov8s`; todos dentro de los 5 s, sin contar objetos tapados (verificacion por imagen).
- Las zonas pueden quedar mal puestas si el operador reacomoda el software; se mitiga con la prueba previa, pero no se
  elimina.
- Una ventana de camaras minimizada o con otro tamano deja de dar la imagen correcta (limite del sistema operativo).
- La pista "puede ser el dueno que volvio" compara color de ropa, que ya se sabe poco fiable con prendas claras o
  estampadas: es una ayuda para el operador, no una regla. Hay que escribir sus pruebas y mostrarla sin cambiar la
  severidad de la alerta.
- Las fotos siguen ocupando disco (~3 MB cada una): conviene una politica de retencion mas adelante.

## Implementacion (que quedo hecho)
- **Protocolo de fotogramas**: cada mensaje binario del navegador empieza con UN byte con la camara (1 a 8) y despues el
  JPEG; un mensaje que empieza directo por la imagen (JPEG `0xFF`, PNG `0x89`) es de la camara 1 (formato anterior).
  `BrowserFrameSource.forCamera(n)` da la vista de cada camara; cada una caduca por separado a los 3 s.
- **Tracker**: `SAFEMODE_CAMERAS` (1 a 8, por defecto 1) crea un detector y un `ObjectTracker.withCameraId(n)` por camara,
  cada uno en su hilo; el modelo y los hilos salen de `SAFEMODE_MODEL` / `SAFEMODE_DETECTOR_THREADS` o de la regla 3b. Una
  camara que falla detiene todo de forma ordenada. El boton del panel lo lanza con `POST /tracker/start?cameras=N` (solo
  digitos de 1 a 8).
- **Datos**: columna `camera_id` y tabla `camera (id, name)`; `GET /cameras` y `POST /cameras/<id>` (JSON `{"name": ...}`,
  con `Origin`, `Host` y `Content-Type` validados); el WebSocket envia `cameras` al conectar y al renombrar, y cada alerta
  lleva `cameraId` y `cameraName`.
- **Panel**: `camera-zones.js` (logica pura, 13 pruebas en Node), `camera-share.js` (selector, dialogo de zonas, prueba previa y
  envio), `camera-names.js` (editor de nombres). Las zonas se guardan en el `localStorage` del navegador (por operador y
  equipo, en fracciones de la ventana). `mosaico-prueba.html` simula un mosaico con tu camara.
- **Reporte** (seccion 7): `GET /report` (solo lectura, sin parametros, con `Host` validado) devuelve los ultimos 200 eventos; el
  boton "Generar reporte" (`security-dashboard/report.js`) los muestra en una tabla con fecha, hora, camara, que paso, objeto,
  dueno probable, quien lo retiro, si genero alerta y fotos. A diferencia del centro de alertas, lista TAMBIEN lo que no alerta
  (cada objeto registrado en reposo y los retiros del propio dueno) y dice por que: "Sin alerta: lo retiro su dueno". Asi, si no
  salta ninguna alerta, el reporte permite ver si el sistema reconocio el objeto. Las fotos solo se aceptan si su URL es
  `/photo/<archivo>` y todo el texto se pinta con `textContent`.
- **Verificado de extremo a extremo** (tracker real con 2 camaras, deteccion real, protocolo real): una maleta en CAM 1 y CAM 2,
  retirada solo de la CAM 2; en la base quedaron `camera_id` 1 y 2 con el objeto #1 en cada una, el retiro solo de la CAM 2 y
  fotos `cam2_...` solo para la CAM 2.

## Como probarlo con una sola camara fisica
1. Gateway: `mvn -pl realtime-gateway exec:java "-Dexec.mainClass=com.safemode.gateway.GatewayRunner"`; abrir
   `http://localhost:8080`, en el selector **Camaras** poner **2** y pulsar **Iniciar vigilancia**.
2. En **otra ventana** de Chrome abrir `http://localhost:8080/mosaico-prueba.html`, pulsar **Activar camara** (el recuadro B
   repite tu camara espejada, o un video).
3. En el panel pulsar **Compartir ventana de la camara**, elegir la ventana del mosaico, dibujar la zona de CAM 1 y de CAM 2
   y pulsar **Iniciar**.
4. Dejar un objeto en un recuadro, esperar 3 s y retirarlo; la alerta debe decir de que camara es.

## Pendientes relacionados
- **Decidir como cumplir el ritmo con 2 camaras** (resultados en "Consecuencias"): (a) pasar a `yolov8s`, que cabe holgado
  pero detecta peor; (b) mantener `yolov8m` y procesar a ~1.2 fps por camara, con poco margen para los 5 s; (c) limitar los
  hilos de cada detector (ONNX Runtime usa todos los nucleos y dos detectores se estorban) y medir de nuevo; (d) pacing a
  periodo fijo en el bucle del tracker (dormir solo lo que falte hasta 500 ms), que da 2 fps con `yolov8m` y 1 camara pero
  cambia el ritmo con que se calibraron los umbrales en fotogramas (retiro falso por una persona que pasa delante).
- Corregir las diapositivas (alcance, arquitectura As-Is vs To-Be, concurrencia, evidencia, declaracion de uso de IA que
  incluya el modelo de vision de NVIDIA y el asistente de desarrollo) y escribir el backlog con criterios Gherkin para
  lo construido (pose, descripcion por IA, clasificacion del retiro, compartir ventana, boton de vigilancia, camaras,
  pista de dueno que vuelve). La reidentificacion por apariencia (opcion C) entra al Release Plan como mejora futura.
