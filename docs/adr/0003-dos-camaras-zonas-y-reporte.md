# ADR 0003: dos camaras desde una ventana compartida, zonas dibujadas y reporte de alertas

## Estado
Propuesto. Ninguna parte esta implementada todavia; este documento fija el diseno acordado por el equipo antes de
construirlo. Se actualizara a "Aceptado" cuando se verifique con camaras reales.

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

### 9. Dueno probable (alcance)
- El alcance de la sustentacion se corrige: el **dueno probable** (por cercania, con foto y descripcion) pasa de "Won't" a
  "Should". No es reconocimiento de identidad. La descripcion por IA externa (NVIDIA) es **opcional** y solo se activa si
  existe la clave; la privacidad de ese envio se declara en la sustentacion. No se guarda video.

## Consecuencias
- Cambios en: `PresenceEventStore` y `AlertStore` (camera_id, tabla de camaras), el tracker (una instancia por camara y
  fuente de frames con camara), `camera-share.js` (zonas, verificacion, recorte), el panel (camara en cada alerta, nombres
  y reporte).
- **Rendimiento sin medir**: analizar dos camaras duplica el trabajo del modelo. Hay que medir los fps que aguanta el
  computador antes de prometer cifras; si no alcanza, se baja la frecuencia por camara.
- Las zonas pueden quedar mal puestas si el operador reacomoda el software; se mitiga con la prueba previa, pero no se
  elimina.
- Una ventana de camaras minimizada o con otro tamano deja de dar la imagen correcta (limite del sistema operativo).
- La pista "puede ser el dueno que volvio" compara color de ropa, que ya se sabe poco fiable con prendas claras o
  estampadas: es una ayuda para el operador, no una regla. Hay que escribir sus pruebas y mostrarla sin cambiar la
  severidad de la alerta.
- Las fotos siguen ocupando disco (~3 MB cada una): conviene una politica de retencion mas adelante.

## Pendientes relacionados
- Corregir las diapositivas (alcance, arquitectura As-Is vs To-Be, concurrencia, evidencia, declaracion de uso de IA que
  incluya el modelo de vision de NVIDIA y el asistente de desarrollo) y escribir el backlog con criterios Gherkin para
  lo construido (pose, descripcion por IA, clasificacion del retiro, compartir ventana, boton de vigilancia, camaras,
  pista de dueno que vuelve). La reidentificacion por apariencia (opcion C) entra al Release Plan como mejora futura.
