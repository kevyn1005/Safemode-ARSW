# Release Plan — SafeMode

Plan de releases de 8 sprints de una semana, con **el estado real de cada uno**, la evidencia que lo respalda y lo que falta. Cada
sprint se describe por la **capacidad de producto** que deja funcionando, no por tareas técnicas; las HU salen del
[backlog](backlog.md).

> Los sprints son lógicos (semana 1 a 8). Las fechas de la columna *Evidencia* son las de los commits que construyeron cada
> incremento: muestran **cuándo se hizo**, no semanas calendario exactas.

## Estado en una mirada

| | |
|---|---|
| **Sprints con incremento demostrable** | 7 de 8 (el S8 está a la mitad) |
| **HU** | 31 hechas · 1 parcial · 6 pendientes (de 38) |
| **Pruebas automáticas** | 301 de Java y 35 de Node, todas pasan |
| **Latencia medida** (del evento al panel) | media 0,5 s · p95 0,9 s; peor caso extremo a extremo ≈ 3,5 s con 1 cámara (objetivo: < 5 s) |
| **Pendiente** | correo, inicio de sesión con Google, preferencias por usuario, prueba con dos cámaras reales y retención de fotos |

## Releases y sprints

### R1 · «Ver»: el sistema ve la escena y recuerda lo que se queda quieto
*Resultado de producto:* demostrar sobre video real que el sistema distingue personas y maletas y sabe cuáles se dejaron en reposo.

| Sprint | Incremento demostrable | HU / Feature | Dependencias | Evidencia | Estado |
|---|---|---|---|---|---|
| **S1** | Detección de personas y maletas en el video, sin alertas todavía | HU-01, HU-02 · F1.1 | El modelo YOLOv8 en ONNX y una fuente de imagen | `ObjectDetectorNmsTest`; "Detección exitosa de maleta y persona" (2026-09-10) | Hecho |
| **S2** | Registro de objetos dejados en reposo (3 s quietos), con foto y sin alertas | HU-03, HU-04 · F1.2 | S1 | `ObjectTrackerTest` (50); "Implementa object-presence-tracker" (2026-09-19) | Hecho |

### R2 · «Alertar»: de un retiro a una alerta en vivo con evidencia
*Resultado de producto:* que el personal de seguridad se entere en menos de 5 s de que alguien se llevó un objeto, con la evidencia para actuar.

| Sprint | Incremento demostrable | HU / Feature | Dependencias | Evidencia | Estado |
|---|---|---|---|---|---|
| **S3** | Detección de que una persona toma el objeto y lo retira; dueño probable y tipo de retiro | HU-05, HU-06, HU-07, HU-09 · F2.1–F2.3 | S2 | `ObjectTrackerTest`, `RemovalVerificationTest` (12), `PersonDescriberTest` (17); 2026-09-20 y 2026-09-21 | Hecho |
| **S4** | Alerta con evidencia: fotos, fecha, hora y cámara; descripción por IA opcional; reglas de severidad | HU-08, HU-10, HU-11, HU-13, HU-14, HU-18 | S3 | `AlertRulesTest`, `AlertEngineTest`, `PersonIdentityTest`; fotos por evento (2026-09-20), motor de alertas (2026-09-21) | Hecho |
| **S5** | Panel de seguridad en tiempo real (WebSocket) con alarma sonora y despliegue con Docker | HU-15, HU-16, HU-17, HU-35, HU-36 · F3.2 | S4 | `AlertGatewayTest` (11); probado con cámara real y navegador (2026-09-25); Back y Front conectados con Docker (2026-10-01) | Hecho |

### R3 · «Operar y avisar»: más cámaras, más contexto y avisos fuera del panel
*Resultado de producto:* usar las cámaras que ya existen (hasta 2), reducir las falsas alarmas y avisar sin tener que mirar el panel.

| Sprint | Incremento demostrable | HU / Feature | Dependencias | Evidencia | Estado |
|---|---|---|---|---|---|
| **S6** | **Dos cámaras desde una ventana:** compartir la ventana, zonas dibujadas con prueba previa, un seguimiento por cámara, alertas con su cámara y nombres editables; mediciones de rendimiento | HU-21–HU-27, HU-37 · F5.1–F5.3, F8.2 | S5; el navegador comparte la ventana | `BrowserFrameSourceTest` (10), `MultiCameraTest` (10), 13 pruebas de Node; corrida de extremo a extremo con un mosaico real; mediciones del ADR 0003 (2026-10-10 y 2026-10-11) | Hecho (la prueba con 2 cámaras físicas está pendiente: HU-28) |
| **S7** | **Reporte en tabla** de todo lo registrado y **pista «posible dueño que volvió»** (baja a media, no silencia); registro de decisiones | HU-12, HU-19, HU-38 · F2.4, F4.2, F8.3 | S6 | `OwnerReturnTest` (9), `ReportJsonTest`, 9 pruebas de Node del reporte; verificado con las fotos reales de las pruebas (2026-10-11) | Hecho |
| **S8** | **Avisos de texto** por navegador, Telegram y correo, con **inicio de sesión con Google** | HU-29–HU-34 · F6.1–F6.4, F7.1 | S7; cuentas externas (bot de Telegram, proyecto de Google Cloud, contraseña de aplicación de correo) | 13 pruebas de Node del aviso del navegador; 31 pruebas del servicio de notificaciones; mensaje de prueba y alertas reales recibidos en Telegram (2026-10-11) | **Parcial:** navegador y Telegram hechos; correo, login y preferencias pendientes |

## Qué falta y en qué orden

| Orden | Incremento propuesto | HU | Depende de | Por qué en este orden |
|---|---|---|---|---|
| 1 | **Acceso con Google** y protección de toda la superficie (panel, WebSocket, reporte y botones del tracker) | HU-33, HU-34 | El proyecto de Google Cloud del equipo | Cierra la brecha de seguridad actual (hoy solo protege `localhost` más el control de `Origin` y `Host`) y desbloquea lo demás |
| 2 | **Avisos por usuario:** correo y elegir canales; vincular el Telegram de cada usuario | HU-31, HU-32 | HU-33; una contraseña de aplicación de correo | Hoy los chats de Telegram son una lista fija |
| 3 | **Validación con dos cámaras reales** y comparación de `yolov8s` frente a `yolov8m` | HU-28 | Una segunda cámara y el software que las muestre juntas | Es la prueba pendiente del S6; confirma el rendimiento y la calidad |
| 4 | **Retención de fotos** | HU-20 | — | Cada foto pesa ≈ 3 MB |
| 5 | **Pulido con datos reales:** calibrar los umbrales de ropa (60 y 80), ids duplicados al entrar, pasar los umbrales de fotogramas a segundos | — | Más corridas | Mejora la precisión sin cambiar el producto |

**Mejoras futuras (fuera de este plan):** reidentificar al dueño por apariencia (hoy solo se compara el color de la ropa), Web Push
(avisos con la pestaña cerrada), filtro de avisos por cámara y por severidad por usuario.

## Riesgos y mitigaciones

| Riesgo | Efecto | Mitigación | Estado |
|---|---|---|---|
| Falsa alarma cuando el dueño sale de cuadro y vuelve por su objeto | Alertas altas por el propio dueño | Si quien retira se viste parecido al dueño, la alerta baja a «Posible dueño que volvió» (media), sin silenciarla y con las fotos de ambos | Mitigado; **riesgo residual declarado:** un ladrón vestido de un color parecido saldría en media |
| Rendimiento con 2 cámaras | `yolov8m` no llega a 2 fps por cámara (702 ms por fotograma con 2) | 1 cámara usa `yolov8m`, 2 cámaras usan `yolov8s` con los hilos repartidos (348 ms); peor caso ≈ 3,0 s | Mitigado; falta comprobar la calidad de `yolov8s` con cámaras reales |
| Zonas mal puestas o ventana de cámaras minimizada o con otro tamaño | El sistema ve otra cosa | Prueba previa que bloquea si el tamaño cambió; límite documentado de que la ventana no puede minimizarse | Mitigado parcialmente (límite del sistema operativo) |
| Privacidad | Datos de personas a terceros | No se guarda video; los avisos externos son solo texto; la descripción por IA externa es opcional y declarada | Controlado |
| Dependencia de servicios externos (Telegram, NVIDIA, Google) | Un canal caído | Todo en segundo plano con reintentos limitados; nada retrasa la alerta; si falta la clave el canal se desactiva | Controlado |
| Seguridad hasta que exista el login | Un tercero en la misma red | El gateway escucha solo en `localhost` y exige `Origin` y `Host`; el botón del tracker ejecuta un comando fijo | **Abierto** hasta HU-33 y HU-34 |
| Calibración con pocas pruebas | Umbrales de color poco robustos | Registro de decisiones con las distancias medidas; más corridas | Abierto |
| Disco | Las fotos llenan el disco | Retención de fotos (HU-20) | Abierto |

## Evidencia general

- **Pruebas:** 301 de Java (visión 9, cámara 10, tracker 166, motor de alertas 19, gateway 97) y 35 de Node (zonas, reporte, avisos del
  navegador). Cada commit se probó en su estado exacto.
- **Mediciones:** `DetectionBenchmark` y `AlertLatencyBenchmark`; resultados en el [ADR 0003](adr/0003-dos-camaras-zonas-y-reporte.md).
- **Decisiones de arquitectura:** [ADR 0001](adr/0001-contenerizacion-servicios-captura-pantalla.md),
  [0002](adr/0002-docker-compose-gateway-y-base-de-datos.md), [0003](adr/0003-dos-camaras-zonas-y-reporte.md) y
  [0004](adr/0004-login-google-y-notificaciones.md).
- **Verificación con cámara real:** una cámara en las pruebas de retiro por el dueño y por otra persona; dos cámaras con un mosaico y
  un tracker real.
