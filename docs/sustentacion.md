# Sustentación — qué actualizar en la presentación

Guía para poner la presentación (`SafeMode_ARSW_Sustentacion_v2.pptx`, 9 diapositivas) al día con lo que ya está construido. **No
modifica el archivo**: cada cambio trae el texto listo para pegar. Se contrasta con los
lineamientos del curso (regla de oro: *Necesidad → Producto → Feature/HU → Componente → Release*) y con la rúbrica de 100 puntos. Los detalles viven en el [backlog](backlog.md), el
[Release Plan](release-plan.md) y los [ADR](adr/).

## 1. Qué quedó desfasado (lo más importante)

| # | Diapositiva | Lo que dice hoy | Lo que es verdad ahora |
|---|---|---|---|
| 1 | 9 · Riesgos | «la alerta se mantiene pero indica "puede ser el dueño que volvió"» | La alerta **baja a media** («Posible dueño que volvió») cuando quien retira se parece al dueño; no se silencia. Cambió la decisión del ADR 0003 |
| 2 | 9 · Riesgos | «Pendiente medir el rendimiento con 2 cámaras» | Ya se midió (702 ms por fotograma con `yolov8m` y 348 ms con `yolov8s` para 2 cámaras) |
| 3 | 9 · Riesgos | «acceso solo con inicio de sesión de Google» | El login es **pendiente**; hoy protege `localhost` más el control de `Origin` y `Host` |
| 4 | 8 · Gherkin | «en menos de 5 segundos» sin evidencia | Medido: media 0,5 s del evento al panel; peor caso extremo a extremo ≈ 3,5 s con 1 cámara |
| 5 | 8 · Release Plan | S8 como si todo estuviera hecho | S8 está **a la mitad**: navegador y Telegram hechos; correo y login pendientes |
| 6 | 7 · Componentes | «WebSocket / SSE» y avisos «por Telegram y correo… según los canales que elija cada usuario» | Solo se usa WebSocket; Telegram está hecho con una lista fija de chats; correo, login y canales por usuario son pendientes |
| 7 | 5 · Título | «Diagrama de infrastuctura» | Falta una «e»: *infraestructura* |
| 8 | 4, 5, 6, 7, 8, 9 · Pie | Los pies dicen «Página 2», «2», «2», «4», «5», «6» | Renumerar de 1 a 9 |

## 2. Texto propuesto por diapositiva

### Diapositiva 3 · Alcance inicial (MoSCoW)
Cambios menores para reflejar lo hecho:
- **Should:** *Alerta en el panel con fotos, fecha, hora y cámara (sin video), dueño probable, reporte en tabla y aviso de texto por
  navegador, Telegram o correo (correo pendiente).*
- **Could:** *Histórico de alertas por cancha; reidentificar al dueño por apariencia (hoy solo se compara el color de la ropa).*

Opcional, para cumplir con el BPMN que piden los lineamientos: convertir el «Proceso simplificado» en un BPMN con tres carriles.

| Carril | Elementos |
|---|---|
| **Jugador** | Inicio → *Deja la maleta en la banca* |
| **Sistema** | *Registra el objeto en reposo (3 s quieto)* → *Detecta que se retiró* → **compuerta exclusiva** «¿quién estaba junto al objeto?»: *solo el dueño* → fin sin alerta; *otra persona* → *Genera alerta alta*; *se parece al dueño* → *Genera alerta media* → *Avisa por panel, navegador y Telegram* |
| **Seguridad** | *Revisa el panel y las fotos* → **compuerta** «¿es sospechoso?»: sí → *Interviene*; no → *Marca como revisada*; en ambos casos *Busca el momento en la grabación (±30 s)* |

### Diapositivas 4, 5 y 6 · Imágenes de los diagramas
- Volver a exportar desde Archi las tres vistas del modelo `docs/diagramas/safemode-arquitectura.archimate`.
- **Diapositiva 4** (hoy «MVP actual»): título sugerido **«Vista As-Is: MVP actual (un solo computador)»** para que se distinga del To-Be.
- **Diapositiva 5:** corregir el título a **«Diagrama de infraestructura (To-Be)»**.
- **Diapositiva 6:** **«Vista de aplicación / componentes (To-Be)»**.
- Si se reordena, la secuencia argumental recomendada es: As-Is → To-Be de componentes → To-Be de infraestructura.

### Diapositiva 7 · Arquitectura To-Be: textos de los componentes
Reemplazar solo los que cambian:

| Componente | Texto propuesto |
|---|---|
| **Alert Engine** | Reglas de severidad: posible robo (alta); dueño que volvió, ambiguo y desaparecido (media); sin dueño (baja) |
| **Security Dashboard** | Alertas, reporte, zonas, captura de la ventana de cámaras y avisos del navegador |
| **Realtime Gateway** | WebSocket hacia el panel; entrega el panel y las fotos |

**Párrafo de responsabilidades (reemplaza al actual):**

> El Object Presence Tracker soporta la detección de que alguien se llevó el objeto (un seguimiento independiente por cámara), y el
> Alert Engine junto al Realtime Gateway soportan la generación y entrega de la alerta. Si el Object Presence Tracker falla, no se
> generan alertas nuevas pero el video se sigue grabando en el software de cámaras; si el Realtime Gateway falla, las alertas quedan en
> Alert DB y se muestran al reconectar. En el MVP todo corre en un solo computador, sin nube: el Event Broker se reemplaza por lectura
> periódica de la base (H2) cada segundo. El Notification Service avisa de las alertas altas y medias solo con texto: **por Telegram ya
> está construido** (con una lista fija de chats) y el aviso del navegador lo muestra el propio panel; **el correo, el inicio de sesión
> con Google y los canales por usuario están pendientes**.

**Recuadro «Nube — canal en vivo»:** cambiar «por WebSocket/SSE» por «por WebSocket».

### Diapositiva 8 · Backlog trazable y Release Plan

**Criterio de aceptación (Gherkin), nuevo «Then»:**

> Given que una maleta fue marcada como en reposo en la banca
> When una persona la toma y la retira de esa zona
> Then el panel de seguridad recibe una alerta con fotos, fecha, hora y cámara en menos de 5 segundos
> *(medido: media de 0,5 s desde el evento y ≈ 3,5 s en el peor caso extremo a extremo con 1 cámara)*

**HU adicional sugerida** (cabe como «HU 3» si hay espacio):
*Como operador de seguridad quiero que la alerta me diga cuando quien retiró el objeto se parece al dueño, para no tratar como robo grave
a un dueño que vuelve por lo suyo.*

**Release Plan — filas que cambian** (y una marca de estado en cada sprint: ✔ hecho · ½ parcial):

| R | Sprint | Incremento funcional | Estado |
|---|---|---|---|
| R1 | S1 | Detección de personas y objetos en el video (sin alertas aún) | ✔ |
| R1 | S2 | Registro de objetos dejados en reposo (zona), sin alertas todavía | ✔ |
| R2 | S3 | Detección de que una persona toma un objeto y lo retira de la escena | ✔ |
| R2 | S4 | Generación de alerta en tiempo real con evidencia fotográfica (fotos, fecha, hora y cámara) | ✔ |
| R2 | S5 | Dashboard en tiempo real para seguridad + notificación (alarma sonora) | ✔ |
| R3 | S6 | Dos cámaras desde una ventana: zonas dibujadas, prueba previa y alertas por cámara | ✔ (falta probar con 2 cámaras físicas) |
| R3 | S7 | Reporte en tabla (fecha, hora, cámara, fotos) y alerta «posible dueño que volvió» | ✔ |
| R3 | S8 | Avisos de texto por navegador y Telegram (hechos); correo e inicio de sesión con Google (pendientes) | ½ |

Línea al pie: **«7 de 8 sprints con incremento demostrable · 31 de 38 HU hechas»**.

### Diapositiva 9 · Concurrencia, tiempo real y riesgos

**Concurrencia** *(reemplaza)*:
> Dos cámaras se analizan en paralelo, cada una con su propio detector y seguimiento: los objetos y personas de una cámara no se
> mezclan con los de la otra. Al mismo tiempo corren la descripción por IA y los avisos externos en hilos aparte, para que nada de eso
> retrase la alerta. Varios paneles pueden revisar la misma alerta: se crea una sola vez (índice único por evento) y marcarla como
> revisada se refleja en todos los paneles.

**Tiempo real** *(reemplaza)*:
> El retiro se confirma en ≈ 2,6 s (3 fotogramas) y la alerta llega al panel por WebSocket en 0,5 s de media (p95 0,9 s; medido). Peor
> caso extremo a extremo estimado: ≈ 3,5 s con 1 cámara y ≈ 3,0 s con 2 cámaras. Debe llegar mientras el sospechoso sigue en la zona:
> un aviso diferido no sirve porque el objeto ya se fue. Cada alerta se genera una sola vez, sin perderse ni duplicarse.

**Calidad + riesgos** *(reemplaza)*:
> Atributos prioritarios: baja latencia, precisión, privacidad y disponibilidad. **Riesgos y mitigación:** (1) falsa alarma si el dueño
> sale de cuadro y vuelve: si quien retira se viste parecido al dueño, la alerta baja de «posible robo» (alta) a «posible dueño que
> volvió» (media), sin silenciarla y con las fotos de ambos; un ladrón vestido igual saldría en media (riesgo declarado). (2) Rendimiento
> con 2 cámaras: `yolov8m` no llega a 2 fps por cámara, así que con 2 cámaras se usa `yolov8s` (348 ms frente a 702 ms). (3) Privacidad:
> no se guarda video, los avisos externos son solo texto y la descripción por IA externa es opcional. (4) Seguridad: hoy el panel
> responde solo en `localhost` con control de `Origin` y `Host`; el inicio de sesión con Google está pendiente.

**Uso de IA** *(sin cambios de fondo; añadir una línea de validación)*:
> YOLOv8 (visión por computador) corre en el propio computador para detectar personas y objetos. Un modelo de visión de NVIDIA, opcional,
> describe al dueño y a quien retira: el recorte de la persona sale a un servicio externo. Claude Code se usó como asistente de
> desarrollo y documentación. **Validación del equipo:** todo se revisó y se comprobó con pruebas automáticas (301 de Java y 35 de Node),
> mediciones y pruebas con cámara real; la responsabilidad del resultado es del equipo.

**Gate de aceptación** *(reemplaza)*:
> SafeMode está listo cuando demuestra el flujo de extremo a extremo con 1 y 2 cámaras: objeto en reposo → alguien lo retira → alerta con
> fotos, fecha, hora y cámara en el Security Dashboard en menos de 5 segundos (medido ≈ 3,5 s en el peor caso). Si quien retira se
> parece al dueño, la alerta lo indica como «posible dueño que volvió».

## 3. Chequeo contra la rúbrica (100 puntos)

| Criterio | Peso | Evidencia que tienen hoy | Qué reforzar |
|---|---|---|---|
| Problema, persona/necesidad y propósito | 15 % | Diapositivas 1 y 2: jugador, maleta en la banca, objetos de valor | Una cifra o un caso real ayuda; evitar que suene genérico |
| Producto, propuesta de valor y proceso/BPM | 15 % | Diapositivas 2 y 3 con MoSCoW | El proceso hoy son flechas: pasarlo a **BPMN con carriles** (ver arriba) |
| Arquitectura To-Be: componentes e infraestructura | 25 % | Modelo ArchiMate con 3 vistas y las diapositivas 4 a 7 | Re-exportar las imágenes; que cada componente se pueda ligar a una HU (hay una tabla en el [backlog](backlog.md#6-trazabilidad-necesidad--componente--release)) |
| Backlog: Épica → Feature → HU + criterios | 15 % | **38 HU con Gherkin**, DoR, DoD, MoSCoW y story map en `docs/backlog.md` | En la diapositiva 8 mostrar la jerarquía y un ejemplo; el detalle va en la wiki |
| Release Plan / planificación incremental | 10 % | 8 sprints con incremento de valor y evidencia | Mostrar el estado real (7 de 8) y qué sigue |
| Concurrencia y escenario en tiempo real | 10 % | Diapositiva 9 + la tabla de concurrencia del backlog | Nombrar los mecanismos (estado por cámara, índice único, hilos aparte) |
| Claridad, argumentación y respuestas | 10 % | Las respuestas de abajo | Practicar el flujo necesidad → componente → release sin leer |

## 4. Preguntas de defensa y respuestas

**¿Qué necesidad concreta justifica el producto?**
Quien juega en una cancha deja su maleta en la banca, con llaves del carro, billetera, documentos y a veces el computador, y no puede
vigilarla. Las cámaras graban, pero nadie las mira en tiempo real: el robo se descubre cuando ya no se puede hacer nada. SafeMode
convierte esas mismas cámaras en un vigilante.

**¿Qué cambia en el proceso cuando exista la solución?**
Antes se descubría al terminar el partido y había que revisar horas de grabación. Ahora seguridad recibe una alerta en segundos con la
cámara, la hora y las fotos del dueño y de quien se la llevó; puede intervenir mientras el sospechoso sigue en la zona y, si no alcanza,
sabe el minuto exacto (±30 s) para buscar en la grabación.

**¿Qué componente soporta una Feature/HU específica y por qué?**
- *Detectar el retiro (HU-05):* el **Object Presence Tracker**, porque es el único que guarda el estado de cada objeto (nuevo → en reposo →
  retirado) y de cada persona, por cámara.
- *Decidir la severidad (HU-13):* el **Alert Engine**, separado del seguimiento para poder cambiar las reglas sin tocar la visión.
- *Mostrarla en vivo (HU-15):* el **Realtime Gateway**, que la empuja por WebSocket sin que el panel pregunte.
- *Avisar fuera del panel (HU-29, HU-30):* el **Notification Service** y el propio panel, en hilos aparte para no frenar la alerta.

**¿Dónde existe realmente concurrencia y qué debe coordinarse o protegerse?**
En tres sitios: (1) **dos cámaras en paralelo**, cada una con su detector y su seguimiento, para que sus objetos y personas no se
mezclen; (2) **el análisis de video junto a la IA y los avisos**, en hilos aparte para que un servicio externo lento no retrase la
alerta; (3) **varios paneles sobre la misma alerta**: se crea una sola vez (índice único) y «revisada» se difunde a todos.

**¿Qué requiere tiempo real y qué ocurriría si la respuesta fuera diferida?**
La alerta del retiro: debe llegar mientras el sospechoso sigue en la zona. Medimos 0,5 s de media del evento al panel (p95 0,9 s) y
≈ 3,5 s en el peor caso extremo a extremo. Si fuera diferida (un reporte al final del día) se descubriría el robo cuando ya no se puede
intervenir; por eso el reporte existe solo como complemento.

**¿Qué quedó funcionando al final del primer sprint/release?**
R1 (S1 y S2): detectar personas y maletas y registrar los objetos dejados en reposo. R2 (S3 a S5): la alerta al panel con evidencia.
Hoy están hechos R1, R2 y casi todo R3; falta correo, login y la prueba con dos cámaras físicas.

**¿Cuál es hoy el mayor riesgo técnico o arquitectónico?**
Dos: (1) la **identidad de las personas** se compara por el color de la ropa, así que un ladrón vestido como el dueño se vería parecido
(mitigado: baja a media, no se silencia, y el operador compara las fotos); (2) el **rendimiento con dos cámaras reales**, que se resolvió
con `yolov8s` pero falta comprobar su calidad en vivo. Además, la **seguridad** depende de `localhost` hasta que exista el login.

## 5. Cierre del feedback (observaciones de la revisión interna)

> Son las observaciones que salieron al contrastar la presentación con los lineamientos; **no son comentarios del docente**. Si hubo
> comentarios en la sustentación, se agregan como filas nuevas con el mismo formato.

| Observación | Decisión / acción | Evidencia | Estado |
|---|---|---|---|
| El alcance no aclaraba qué es el «dueño» | Es un dueño **probable** por cercanía, no una identidad; la IA externa es opcional y su privacidad está declarada | ADR 0003 §9; diapositivas 2 y 3 | Cerrado |
| Se mezclaban la arquitectura actual y la futura | Tres vistas separadas en el modelo ArchiMate: As-Is, To-Be de componentes y To-Be de infraestructura | `docs/diagramas/safemode-arquitectura.archimate` | Cerrado (re-exportar las imágenes) |
| La concurrencia no tenía escenarios concretos | Dos cámaras, IA y avisos en hilos aparte, varios paneles; con sus mecanismos | Backlog §7; diapositiva 9 | Cerrado |
| La evidencia prometía un «clip de video» | Alerta con fotos, fecha, hora y cámara; no se guarda video | ADR 0003 §5 | Cerrado |
| El uso de IA no declaraba el servicio externo ni el asistente de desarrollo | YOLOv8, NVIDIA (opcional) y Claude Code declarados, con validación del equipo | Diapositiva 9 | Cerrado |
| «Menos de 5 segundos» nunca se había medido | `AlertLatencyBenchmark` y `DetectionBenchmark` | ADR 0003, «Consecuencias» | Cerrado |
| Falsa alarma si el dueño sale de cuadro y vuelve | Opción B: baja a «posible dueño que volvió» (media) sin silenciar | ADR 0003 §8; `OwnerReturnTest` | Cerrado con riesgo residual declarado |
| El backlog solo tenía 2 HU | 38 HU con Gherkin, DoR, DoD, MoSCoW y story map | `docs/backlog.md` | Cerrado |
| El Release Plan no mostraba el estado | Estado y evidencia por sprint, y qué falta | `docs/release-plan.md` | Cerrado (S8 parcial) |
| Correo, inicio de sesión con Google y avisos por usuario | Pendientes de las cuentas externas del equipo | Backlog HU-31 a HU-34 | **Abierto** |
| Probar con dos cámaras reales | Pendiente de la segunda cámara | Backlog HU-28 | **Abierto** |

## 6. Declaración de uso de IA (texto sugerido)

| Herramienta | Para qué se usó | Cómo se validó |
|---|---|---|
| **YOLOv8** (visión por computador, local) | Detectar personas y objetos y estimar la pose del dueño | Pruebas automáticas, calibración con fotos reales y pruebas con cámara |
| **Modelo de visión de NVIDIA** (servicio externo, opcional) | Describir al dueño y a quien retira el objeto | Se prueba contra un servidor falso; el recorte de la persona sale a un tercero, por eso es opcional y se declara |
| **Claude Code** (asistente de desarrollo) | Apoyo en análisis, código, pruebas y documentación (ADR, backlog) | El equipo revisó el código, ejecutó las pruebas, probó con cámara real y decidió; la responsabilidad del resultado es del equipo |
