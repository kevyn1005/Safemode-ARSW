# Backlog de producto — SafeMode

Detección en tiempo real de hurto de pertenencias en canchas deportivas. Este documento baja el producto a trabajo verificable
siguiendo la jerarquía **Épica → Feature → HU** y la trazabilidad **Necesidad → Producto → Feature/HU → Componente → Release**
que piden los lineamientos ARSW 2026-2. Todo lo marcado *Hecho* tiene evidencia (pruebas automáticas, medición o demostración) que se
cita en cada HU; lo que falta está marcado *Pendiente* o *Parcial* con su motivo.

> Los tamaños son **estimaciones relativas propuestas** (serie 1, 2, 3, 5, 8) para confirmar con el equipo; no son tiempos medidos.
> Las pruebas automáticas al cierre de este documento: **301 de Java y 35 de Node, todas pasan**.

## 1. Necesidad, personas y alcance

| | |
|---|---|
| **Necesidad** | El jugador que deja su maleta en la banca mientras juega necesita cuidarla sin depender de que alguien se quede vigilando. |
| **Producto** | Aplicación que analiza en tiempo real la imagen de las cámaras ya instaladas (la ventana de su software de cámaras, compartida desde el navegador) y avisa en segundos cuando alguien se lleva un objeto dejado en reposo. |
| **Valor diferencial** | No reemplaza cámaras ni pide hardware nuevo; no guarda video (lo graba el software de cámaras); la alerta trae fotos, fecha, hora y cámara para encontrar ese momento. |
| **Fuera de alcance** | Reemplazar las cámaras, guardar video, reconocimiento facial o biométrico (el dueño es solo *probable*, no una identidad). |

**Personas / actores**

| Actor | Qué necesita |
|---|---|
| **Jugador** (dueño de la maleta) | Que su maleta no desaparezca sin que alguien se entere. No usa la aplicación directamente. |
| **Operador de seguridad** | Enterarse al instante, ver la evidencia y decidir si interviene. Usa el panel, Telegram y las notificaciones. |
| **Administrador del puesto** | Conectar la ventana de cámaras, definir las zonas, iniciar/detener la vigilancia y configurar los avisos. |
| **Sistema** | Los componentes que detectan, siguen, deciden y avisan (HU técnicas solo cuando son una capacidad del producto). |

## 2. Resumen del backlog (Épica → Feature → HU)

Prioridad **MoSCoW**. Estado: *Hecho*, *Parcial*, *Pendiente*. Release y sprint según el [Release Plan](release-plan.md).

| ID | Épica / Feature | HU (resumen) | MoSCoW | Tamaño | Release · Sprint | Estado |
|---|---|---|---|---|---|---|
| | **E1 · Detección de objetos y personas** | | | | | |
| HU-01 | F1.1 Detección en el video | Detectar personas y maletas, mochilas y bolsos | Must | 3 | R1 · S1 | Hecho |
| HU-02 | F1.1 | Varias personas y objetos a la vez sin que se borren entre sí | Must | 3 | R1 · S1 | Hecho |
| HU-03 | F1.2 Objetos en reposo | Registrar un objeto cuando lleva 3 s quieto | Must | 3 | R1 · S2 | Hecho |
| HU-04 | F1.2 | Varios objetos a la vez, cada uno por separado | Must | 2 | R1 · S2 | Hecho |
| | **E2 · Análisis del retiro y del dueño** | | | | | |
| HU-05 | F2.1 Retiro del objeto | Detectar que un objeto en reposo fue retirado de la escena | Must | 5 | R2 · S3 | Hecho |
| HU-06 | F2.1 | No dar por retirado un objeto que solo está tapado | Should | 5 | R2 · S3 | Hecho |
| HU-07 | F2.2 Dueño probable | Registrar el dueño probable con foto y descripción | Should | 5 | R2 · S3 | Hecho |
| HU-08 | F2.2 | Describir al dueño y a quien retira con IA (opcional) | Could | 5 | R2 · S4 | Hecho |
| HU-09 | F2.3 Quién retira | Clasificar el retiro: dueño, otra persona, ambos, nadie o sin dueño | Must | 5 | R2 · S3 | Hecho |
| HU-10 | F2.3 | Mostrar la foto y la descripción de quien retiró | Should | 3 | R2 · S4 | Hecho |
| HU-11 | F2.3 | Mantener la identidad de cada persona sin heredar la de quien salió | Must | 5 | R2 · S4 | Hecho |
| HU-12 | F2.4 Dueño que vuelve | Reconocer al posible dueño que volvió por su objeto | Should | 5 | R3 · S7 | Hecho |
| | **E3 · Alertas en tiempo real** | | | | | |
| HU-13 | F3.1 Generación | Generar la alerta con el tipo y la severidad que corresponda | Must | 3 | R2 · S4 | Hecho |
| HU-14 | F3.1 | Una sola alerta por evento aunque haya varios paneles | Must | 2 | R2 · S4 | Hecho |
| HU-15 | F3.2 Centro de alertas | Ver las alertas nuevas en vivo, sin recargar | Must | 5 | R2 · S5 | Hecho |
| HU-16 | F3.2 | Marcar una alerta como revisada y que se vea en todos los paneles | Should | 3 | R2 · S5 | Hecho |
| HU-17 | F3.2 | Alarma sonora con cada alerta nueva | Should | 1 | R2 · S5 | Hecho |
| | **E4 · Evidencia y reportes** | | | | | |
| HU-18 | F4.1 Evidencia | Ver de qué cámara, a qué hora y con qué fotos salió una alerta | Must | 3 | R2 · S4 | Hecho |
| HU-19 | F4.2 Reporte | Generar un reporte en tabla de todo lo registrado | Should | 5 | R3 · S7 | Hecho |
| HU-20 | F4.3 Retención | Que las fotos viejas no llenen el disco | Could | 3 | Siguiente | Pendiente |
| | **E5 · Entrada de video y cámaras** | | | | | |
| HU-21 | F5.1 Ventana compartida | Compartir la ventana del software de cámaras desde el navegador | Must | 5 | R3 · S6 | Hecho |
| HU-22 | F5.1 | Iniciar y detener la vigilancia desde el panel con un botón seguro | Should | 5 | R3 · S6 | Hecho |
| HU-23 | F5.2 Dos cámaras | Elegir si se vigila 1 o 2 cámaras | Must | 3 | R3 · S6 | Hecho |
| HU-24 | F5.2 | Dibujar la zona de cada cámara y comprobar que sigue bien antes de iniciar | Must | 8 | R3 · S6 | Hecho |
| HU-25 | F5.2 | Analizar cada cámara por separado y saber de cuál es cada alerta | Must | 5 | R3 · S6 | Hecho |
| HU-26 | F5.2 | Ponerle nombre a cada cámara | Could | 2 | R3 · S6 | Hecho |
| HU-27 | F5.3 Pruebas | Probar las dos cámaras con una sola cámara física | Could | 2 | R3 · S6 | Hecho |
| HU-28 | F5.3 | Probar con dos cámaras reales | Must | 3 | Siguiente | Pendiente |
| | **E6 · Avisos fuera del panel** | | | | | |
| HU-29 | F6.1 Navegador | Recibir una notificación del sistema con cada alerta alta o media | Should | 3 | R3 · S8 | Hecho |
| HU-30 | F6.2 Telegram | Recibir un mensaje de Telegram con cada alerta alta o media | Should | 5 | R3 · S8 | Parcial |
| HU-31 | F6.3 Correo | Recibir un correo con cada alerta alta o media | Could | 3 | R3 · S8 | Pendiente |
| HU-32 | F6.4 Preferencias | Elegir por qué canales quiero que me avisen | Could | 3 | R3 · S8 | Pendiente |
| | **E7 · Acceso y seguridad** | | | | | |
| HU-33 | F7.1 Login | Entrar con Google solo si mi correo está autorizado | Must | 8 | R3 · S8 | Pendiente |
| HU-34 | F7.1 | Proteger el panel, el WebSocket y los botones con la sesión | Must | 5 | R3 · S8 | Pendiente |
| HU-35 | F7.2 Protección actual | Que solo el panel propio lea las alertas y accione el tracker | Must | 3 | R2 · S5 | Hecho |
| | **E8 · Operación y calidad** | | | | | |
| HU-36 | F8.1 Despliegue | Levantar el gateway y la base con Docker | Should | 3 | R2 · S5 | Hecho |
| HU-37 | F8.2 Medición | Conocer el rendimiento y la latencia reales | Should | 3 | R3 · S6 | Hecho |
| HU-38 | F8.3 Observabilidad | Entender por qué el sistema tomó una decisión | Should | 2 | R3 · S7 | Hecho |

**Cuenta:** 38 HU · 31 hechas · 1 parcial · 6 pendientes. Por prioridad: de las 19 *Must*, 16 están hechas y 3 pendientes (HU-28, HU-33 y
HU-34).

## 3. Definition of Ready y Definition of Done

**DoR — una HU entra a desarrollo cuando:**
- tiene el formato *Como [quién] quiero [qué] para [para qué]* (no es una tarea técnica);
- tiene al menos un criterio de aceptación en Gherkin con datos concretos;
- sus dependencias están identificadas (otra HU, una cuenta externa, un permiso);
- su tamaño es de 8 o menos (si no, se divide);
- si toca datos de personas, se revisó la privacidad (qué sale a terceros y qué no).

**DoD — una HU está terminada cuando:**
- el código está integrado y compila;
- sus pruebas automáticas pasan **y todas las anteriores siguen pasando** (regla del proyecto: lo que ya funciona no se rompe);
- si toca la interfaz, se verificó en un navegador real;
- las claves y contraseñas están solo en variables de entorno, nunca en el código, los registros ni el repositorio;
- hay evidencia (prueba, medición o demostración) y la documentación (ADR o README) está al día;
- se entrega en un commit por tema, probado en ese estado exacto.

## 4. Detalle de las HU y criterios de aceptación

Formato de cada una: **HU** · criterio en **Gherkin** · componente · evidencia.

### E1 · Detección de objetos y personas

#### F1.1 Detección en el video

**HU-01** · Como **sistema** quiero detectar personas y maletas, mochilas y bolsos en cada fotograma, para saber qué hay en la escena.
```gherkin
Given un fotograma con una persona y una mochila
When el Vision Detection Service lo analiza
Then devuelve una detección "person" y una "backpack", cada una con su caja y su confianza de 0.30 o más
```
Componente: Vision Detection Service (YOLOv8 en ONNX). Evidencia: `ObjectDetector`, `ObjectDetectorNmsTest`; primera detección real de maleta y persona el 2026-09-10.

**HU-02** · Como **sistema** quiero que varias personas y objetos del mismo fotograma se detecten a la vez, para no perder a nadie cuando hay mucha gente.
```gherkin
Given un fotograma con tres personas y dos mochilas
When se aplica la supresión de duplicados
Then se conservan las tres personas y las dos mochilas
 And una persona nunca borra a un bolso ni al revés
 And una misma maleta detectada como "backpack", "handbag" o "suitcase" cuenta como un solo objeto
```
Componente: Vision Detection Service. Evidencia: `ObjectDetectorNmsTest` (7 pruebas); commit "Detecta varias personas por frame" (2026-09-20).

#### F1.2 Objetos en reposo

**HU-03** · Como **operador de seguridad** quiero que el sistema registre un objeto solo cuando lleva tiempo quieto, para no recibir alertas por alguien que apenas pasa con su maleta.
```gherkin
Given una mochila nueva en la escena
When permanece en el mismo sitio (con una tolerancia de 25 px) durante 3 segundos
Then el Object Presence Tracker la registra "en reposo" con una foto del momento
 And si se la llevan antes de los 3 segundos no se genera ningún evento
```
Componente: Object Presence Tracker. Evidencia: `ObjectTrackerTest` (50 pruebas, con reloj manual).

**HU-04** · Como **operador** quiero que varios objetos a la vez se sigan por separado, para que cada alerta hable de su objeto.
```gherkin
Given dos mochilas registradas en reposo en la misma cámara
When se retira solo una
Then se genera el retiro de esa mochila y la otra sigue en reposo
```
Evidencia: prueba de varios objetos de la misma clase en `ObjectTrackerTest`; verificado con cámara real con dos maletas a la vez.

### E2 · Análisis del retiro y del dueño

#### F2.1 Retiro del objeto

**HU-05** · Como **sistema** quiero detectar cuándo una persona toma un objeto en reposo y lo retira de la escena, para generar una alerta de riesgo en tiempo real.
```gherkin
Given una maleta registrada en reposo en la banca
When deja de verse durante 3 fotogramas seguidos, o aparece desplazada más de 25 px en 2 fotogramas seguidos
Then el tracker registra el retiro con su hora y una foto del momento
 And un solo fotograma desplazado se ignora (una persona que pasa por delante tapa la caja)
```
Componente: Object Presence Tracker. Evidencia: `ObjectTrackerTest`; probado con cámara real (retiro por otra persona con la foto de quien se la llevó).

**HU-06** · Como **operador** quiero que un objeto que solo está tapado no se dé por retirado, para evitar falsas alarmas.
```gherkin
Given una mochila en reposo que el detector deja de ver
 And la zona donde estaba cambió menos del 15 % respecto a la última vez que se vio
When pasan los 3 fotogramas sin verla
Then se espera (hasta 12 fotogramas más) en vez de declararla retirada
 And los puntos que cubre una persona no se comparan
 And si una persona tapa más del 65 % de la zona se decide como sin verificación
```
Evidencia: `RemovalVerificationTest` (12 pruebas), calibrado con fotos reales (retiro falso 0.04, mochila presente 0.05, retiro real 0.28).

#### F2.2 Dueño probable

**HU-07** · Como **operador** quiero saber quién es el dueño probable del objeto, con su foto y una descripción, para reconocerlo en la grabación.
```gherkin
Given una persona que estuvo junto a la mochila mientras se asentaba
When la mochila se registra en reposo
Then queda guardado el dueño probable (la persona que más fotogramas estuvo cerca)
 And con una foto de la persona junto al objeto y una descripción por color de ropa
 And si nadie estuvo cerca el objeto queda sin dueño
```
Componente: Object Presence Tracker. Evidencia: `ObjectTrackerTest`, `PersonDescriberTest` (17 pruebas). El dueño es *probable*: no es una identidad.

**HU-08** · Como **operador** quiero una descripción más precisa del dueño y de quien retira (ropa, calzado, tatuajes), sin que el sistema se frene, para identificarlos mejor.
```gherkin
Given que existe la clave del servicio de visión de NVIDIA en una variable de entorno
When se registra un dueño o un retiro sospechoso
Then la descripción se pide en segundo plano y se agrega al evento unos segundos después
 And si no hay clave, red o respuesta, queda la descripción por color y nada se frena
 And los tatuajes se revisan en un recorte de los antebrazos solo si la primera respuesta no los confirma
```
Evidencia: `NvidiaOwnerVisionDescriberTest` (36 pruebas, contra un servidor falso); consumo medido ≈ 10 000 tokens por persona. Privacidad: el recorte de la persona sale a un servicio externo y por eso es **opcional**.

#### F2.3 Quién retira

**HU-09** · Como **operador** quiero que el sistema distinga si se llevó el objeto su dueño u otra persona, para no alertar cuando el dueño recoge lo suyo.
```gherkin
Given una maleta con dueño registrado
When se retira
Then el retiro se clasifica así:
  | quién estaba junto al objeto            | tipo                   |
  | solo el dueño                           | BY_OWNER               |
  | alguien distinto del dueño              | BY_OTHER               |
  | el dueño y otra persona                 | OWNER_AND_OTHER_NEAR   |
  | nadie                                   | NO_ONE_NEAR            |
  | (maleta sin dueño) alguien              | OWNER_UNKNOWN          |
```
Evidencia: `ObjectTrackerTest` (`classifyRemoval`), verificado con cámara real (BY_OTHER y BY_OWNER).

**HU-10** · Como **operador** quiero ver la foto y la descripción de quien se llevó el objeto, para buscarlo.
```gherkin
Given un retiro por otra persona
When se guarda el evento
Then incluye la foto de esa persona junto al objeto y su descripción por color
 And la descripción por IA se agrega después, solo en los retiros sospechosos (BY_OTHER, OWNER_AND_OTHER_NEAR, OWNER_UNKNOWN)
```

**HU-11** · Como **sistema** quiero mantener la identidad de cada persona entre fotogramas sin que una persona nueva herede la de quien acaba de salir, para que un robo no se lea como retiro del dueño.
```gherkin
Given el dueño (camisa beige) salió de cuadro
When entra otra persona (camisa blanca) en el mismo sitio y se lleva la mochila
Then recibe un id nuevo y el retiro es BY_OTHER
 And quien vuelve tras un solo fotograma con la misma ropa conserva su id
 And una persona se olvida tras 6 fotogramas sin verla
```
Evidencia: `PersonIdentityTest` (11 pruebas); la ropa se compara por el color medio del torso (distancia ≤ 80).

#### F2.4 Dueño que vuelve

**HU-12** · Como **operador** quiero que la alerta me diga cuando quien retiró el objeto se parece al dueño que había salido de cuadro, para no tratar como robo grave a un dueño que vuelve por lo suyo.
```gherkin
Given una maleta con dueño registrado cuyo dueño salió de cuadro 6 o más fotogramas (≈ 7 s) y el seguimiento ya lo olvidó
When regresa alguien con ropa parecida (color a 60 o menos de distancia) y se la lleva
Then la alerta es "Posible dueño que volvió", de severidad media, con las fotos del dueño y de quien retiró lado a lado
 And no se silencia: queda visible en el centro de alertas y notifica
 And si junto al objeto hay alguien con ropa distinta, o no hay imagen para comparar, sigue siendo "Posible robo" de severidad alta
```
Evidencia: `OwnerReturnTest` (9 pruebas), `AlertRulesTest`; decisión en el ADR 0003, sección 8. Límite declarado: un ladrón vestido de un color parecido saldría en media.

### E3 · Alertas en tiempo real

#### F3.1 Generación de alertas

**HU-13** · Como **operador** quiero que cada retiro sospechoso genere una alerta con su severidad, para atender primero lo más grave.
```gherkin
Given un retiro registrado
When el Alert Engine lo evalúa
Then genera:
  | tipo de retiro            | alerta                         | severidad |
  | BY_OTHER                  | Posible robo                   | Alta      |
  | BY_OTHER (parece dueño)   | Posible dueño que volvió       | Media     |
  | OWNER_AND_OTHER_NEAR      | Retiro ambiguo                 | Media     |
  | NO_ONE_NEAR               | Objeto desaparecido            | Media     |
  | OWNER_UNKNOWN             | Retiro sin dueño registrado    | Baja      |
  | BY_OWNER                  | (no genera alerta)             | —         |
```
Componente: Alert Engine. Evidencia: `AlertRulesTest`, `AlertEngineTest` (19 pruebas entre los dos).

**HU-14** · Como **operador** quiero que cada evento genere una sola alerta aunque haya varios paneles abiertos o el motor reprocese los eventos, para no ver duplicados.
```gherkin
Given un retiro que ya generó su alerta
When el motor vuelve a leer ese evento (por un reinicio o porque se vació la tabla)
Then no crea una segunda alerta (índice único por evento y hora)
```
Concurrencia: `processNewEvents` es sincronizado y la tabla `alert` tiene un índice único. Evidencia: `AlertEngineTest` (`noRepiteAlertasAlProcesarDosVeces`, `unMotorNuevoNoDuplicaLasAlertasYaGuardadas`).

#### F3.2 Centro de alertas

**HU-15** · Como **operador** quiero ver las alertas nuevas en vivo sin recargar la página, para reaccionar mientras el sospechoso sigue en la zona.
```gherkin
Given el panel abierto y conectado
When se genera una alerta alta
Then aparece en el panel en menos de 5 segundos desde que se confirma el retiro
 And al conectarse el panel recibe primero las alertas existentes (snapshot)
 And cuando llega la descripción por IA la tarjeta se actualiza sin sonar otra vez
```
Componente: Realtime Gateway (WebSocket) y Security Dashboard. Evidencia: `AlertGatewayTest` (11 pruebas) y medición: del evento al panel, **media 0,5 s y p95 0,9 s** (`AlertLatencyBenchmark`); peor caso extremo a extremo estimado ≈ 3,5 s con 1 cámara.

**HU-16** · Como **operador** quiero marcar una alerta como revisada y que se vea en todos los paneles, para no atenderla dos veces.
```gherkin
Given dos paneles abiertos con la misma alerta pendiente
When uno la marca como revisada
Then el otro la ve revisada sin recargar
```
Evidencia: `AlertGatewayTest` (ack y difusión).

**HU-17** · Como **operador** quiero una alarma sonora con cada alerta nueva, para enterarme aunque no esté mirando la pantalla.
```gherkin
Given el panel con el sonido activado
When llega una alerta nueva
Then suena un aviso de dos tonos
 And no suena al conectar (snapshot) ni cuando una alerta solo se actualiza
```
Evidencia: probado con cámara real (sonó en un retiro BY_OTHER y no en uno BY_OWNER).

### E4 · Evidencia y reportes

**HU-18** · Como **operador de seguridad** quiero ver de qué cámara, a qué hora y con qué fotos salió una alerta, para encontrar ese momento en la grabación.
```gherkin
Given una alerta generada por un retiro en la CAM 2
When la abro en el panel
Then veo el nombre de la cámara, la fecha, la hora, la foto del momento, la del dueño y la de quien retiró
 And no hay video: la grabación se consulta en el software de cámaras (buscar ±30 s alrededor de esa hora)
```
Componentes: Object Presence Tracker (fotos y eventos), Alert DB, Security Dashboard. Evidencia: `AlertJsonTest`; probado en el panel real.

**HU-19** · Como **operador** quiero generar un reporte en tabla de todo lo que registró el sistema, también lo que no generó alerta, para comprobar que reconoce los objetos aunque no haya saltado ninguna alerta.
```gherkin
Given que el sistema registró una mochila en reposo y su dueño la recogió
When pulso "Generar reporte"
Then veo una fila "Registrado en reposo" y otra "Retirado", con fecha, hora, cámara, objeto, dueño, quien retiró y fotos
 And la fila del retiro dice "Sin alerta: lo retiró su dueño"
```
Evidencia: `ReportJsonTest`, `DashboardHttpServerTest` y 9 pruebas de Node (`report.test.js`); verificado en el panel real con tu base.

**HU-20** · Como **administrador** quiero que las fotos viejas se borren o se limiten, para que el disco no se llene (cada foto pesa ≈ 3 MB). *Pendiente: política de retención.*
```gherkin
Given fotos de eventos más viejas que el periodo de retención
When el sistema arranca o pasa el periodo
Then se eliminan esas fotos y sus rutas dejan de mostrarse
```

### E5 · Entrada de video y cámaras

#### F5.1 Ventana compartida

**HU-21** · Como **administrador del puesto** quiero compartir la ventana del software de cámaras desde el navegador, para que el sistema vea las cámaras sin integrarse con cada marca y aunque la ventana esté tapada por otras.
```gherkin
Given la ventana del software de cámaras abierta
When en el panel pulso "Compartir ventana de la cámara" y la elijo
Then el navegador envía fotogramas (≈ 2 por segundo) por WebSocket al tracker
 And si el último fotograma tiene más de 3 segundos el tracker lo ignora
 And solo se aceptan conexiones del origen del panel
```
Componentes: Security Dashboard (captura), Camera Feed Service (`BrowserFrameSource`). Evidencia: `BrowserFrameSourceTest` (10 pruebas); probado con cámara real. Límite: la ventana no puede minimizarse.

**HU-22** · Como **administrador** quiero iniciar y detener la vigilancia desde el panel con un botón, para no abrir otra terminal.
```gherkin
Given el gateway corriendo en esta máquina
When pulso "Iniciar vigilancia" (y confirmo con un segundo clic, porque borra las fotos y los eventos anteriores)
Then el gateway lanza el tracker con un comando fijo (nada del navegador entra al comando)
 And "Detener vigilancia" lo cierra de forma ordenada y, si no responde en 90 s, lo mata
 And las rutas solo aceptan POST con el Origin y el Host del panel, y no existen si el gateway no escucha solo en localhost
```
Evidencia: `TrackerProcessTest` (13 pruebas, con un proceso hijo real) y `DashboardHttpServerTest`.

#### F5.2 Dos cámaras

**HU-23** · Como **administrador** quiero elegir si vigilo 1 o 2 cámaras, para usar el sistema como me convenga.
```gherkin
Given el selector "Cámaras" del panel
When elijo 1
Then el sistema funciona como siempre, con la ventana completa
When elijo 2
Then la ventana compartida es un mosaico y se pide la zona de cada cámara
```

**HU-24** · Como **administrador** quiero dibujar con el mouse la zona de cada cámara y que el sistema compruebe antes de iniciar que siguen bien puestas, para que un cambio de acomodo no dañe la vigilancia sin que me entere.
```gherkin
Given las zonas de CAM 1 y CAM 2 guardadas (en fracciones de la ventana)
When pulso "Iniciar" en el diálogo
Then la prueba previa compara el tamaño de la ventana y el parecido de cada zona con cuando se dibujó
 And si el tamaño cambió, bloquea el inicio y pide volver a dibujar
 And si una zona ya no se parece, solo avisa
```
Evidencia: 13 pruebas de Node (`camera-zones.test.js`) y verificación en el panel real con un mosaico (zonas, cambio de tamaño, inicio bloqueado).

**HU-25** · Como **operador** quiero que cada cámara se analice por separado y que la alerta diga de cuál es, para saber dónde intervenir.
```gherkin
Given dos cámaras vigiladas a la vez
When se retira una mochila solo de la CAM 2
Then solo la CAM 2 genera el retiro, con camera_id 2 y fotos con el prefijo cam2_
 And los objetos y personas de una cámara no se mezclan con los de la otra
```
Concurrencia: un detector y un seguimiento por cámara, cada uno en su hilo. Evidencia: `MultiCameraTest` (10 pruebas) y corrida de extremo a extremo con tracker real y detección real. Falta la prueba con dos cámaras físicas (HU-28).

**HU-26** · Como **administrador** quiero ponerle nombre a cada cámara cuando quiera, para reconocerla en las alertas y reportes.
```gherkin
Given las cámaras "CAM 1" y "CAM 2"
When cambio el nombre de la CAM 2 a "Cancha sur"
Then todas las alertas, también las anteriores, y el reporte muestran "Cancha sur"
 And un nombre vacío vuelve al nombre por defecto
```
Evidencia: `PresenceEventStoreQueryTest`, `DashboardHttpServerTest`.

**HU-27** · Como **desarrollador** quiero probar el modo de dos cámaras con una sola cámara física, para validar sin hardware adicional.
```gherkin
Given una sola cámara física
When abro la página de mosaico de prueba
Then muestra la cámara en dos recuadros y puedo compartirla como si fueran dos cámaras
```

**HU-28** · Como **administrador** quiero probar el sistema con dos cámaras reales, para confirmar el rendimiento y la calidad de detección con `yolov8s`. *Pendiente: requiere una segunda cámara y el software que las muestre juntas.*
```gherkin
Given dos cámaras reales mostradas juntas en una ventana
When se deja un objeto en cada una y se retira de una
Then solo esa cámara genera la alerta y la latencia se mantiene dentro de los 5 segundos
```

### E6 · Avisos fuera del panel

**HU-29** · Como **operador** quiero recibir una notificación del sistema con cada alerta alta o media aunque tenga otra pestaña o ventana, para enterarme sin estar mirando el panel.
```gherkin
Given que activé "Avisos del navegador" y acepté el permiso
When llega una alerta nueva de severidad alta o media
Then aparece una notificación con el texto "SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar."
 And no lleva fotos ni datos de personas
 And las alertas bajas y las revisadas no avisan
```
Evidencia: 13 pruebas de Node con un navegador falso y verificación en el panel real. Límite: la pestaña del panel debe seguir abierta (Web Push queda para después).

**HU-30** · Como **operador** quiero recibir un mensaje de Telegram con cada alerta alta o media, para enterarme aunque no esté frente al computador. *Parcial: los chats son una lista fija hasta que exista el login.*
```gherkin
Given un bot de Telegram y mi chat configurados solo por variables de entorno
When llega una alerta nueva de severidad alta o media
Then recibo un mensaje de texto, una sola vez por alerta, sin fotos ni datos de personas
 And si Telegram falla o tarda, la alerta y los otros canales no se retrasan y se reintenta hasta 3 veces
 And la clave del bot no aparece en ningún registro ni mensaje de error
```
Componente: Notification Service (`NotificationService`, `TelegramChannel`). Evidencia: 31 pruebas (servidor falso local, canal falso, comprobación de que los registros no filtran la clave ni los chats) y prueba real con el bot del equipo.

**HU-31** · Como **operador** quiero recibir un correo con cada alerta alta o media, para tener otro canal de respaldo. *Pendiente: requiere una contraseña de aplicación de correo.* Mismo criterio que HU-30 con SMTP.

**HU-32** · Como **operador** quiero elegir por qué canales me avisan, para no recibir lo que no uso. *Pendiente: depende del login (HU-33).*
```gherkin
Given un usuario con sesión que activó el canal Telegram y desactivó el correo
When se genera una alerta de severidad alta
Then recibe el mensaje de Telegram y no recibe el correo
 And no recibe un segundo mensaje por la misma alerta
```

### E7 · Acceso y seguridad

**HU-33** · Como **administrador** quiero que solo entren al panel los correos autorizados con su cuenta de Google, para que cualquier persona con una cuenta no pueda ver las alertas. *Pendiente: requiere el proyecto de Google Cloud del equipo.*
```gherkin
Given un correo que no está en la lista autorizada
When intenta iniciar sesión con Google
Then el gateway verifica el token en el servidor (firma, audiencia, vencimiento y correo verificado) y rechaza el acceso
 And con un correo autorizado crea una sesión corta (cookie HttpOnly)
```

**HU-34** · Como **administrador** quiero que la sesión proteja también el WebSocket, marcar alertas, el reporte y los botones del tracker, para cerrar la brecha de seguridad actual. *Pendiente (depende de HU-33).*

**HU-35** · Como **administrador** quiero que solo el panel propio pueda leer las alertas y accionar el tracker, mientras no exista el login, para que una página ajena no pueda usarlos.
```gherkin
Given el gateway escuchando solo en localhost
When una página de otro origen intenta abrir el WebSocket o hacer un POST a /tracker/*
Then se rechaza por el Origin; y un Host raro también (protección contra DNS rebinding)
```
Evidencia: `AlertGatewayTest`, `DashboardHttpServerTest` (rutas `/tracker`, `/cameras`, `/report`).

### E8 · Operación y calidad

**HU-36** · Como **administrador** quiero levantar el gateway y la base con un solo comando de Docker, para desplegar sin instalar todo a mano.
```gherkin
Given Docker instalado
When ejecuto "docker compose up --build h2-db realtime-gateway"
Then el panel responde en http://localhost:8080 y el tracker del computador escribe en la misma base por TCP
```
Evidencia: ADR 0002; el tracker no va en contenedor porque necesita la pantalla, los modelos y la clave.

**HU-37** · Como **equipo** quiero conocer el rendimiento y la latencia reales, para decidir con datos (modelo, hilos, cumplimiento de los 5 s).
```gherkin
Given el benchmark de detección y el de latencia
When se ejecutan en el computador de desarrollo
Then quedan documentados los resultados:
  | medición                          | resultado                                          |
  | yolov8m, 1 cámara                 | 371 ms por fotograma                               |
  | yolov8m, 2 cámaras                | 702 ms (en fila) · 638 ms (en paralelo)            |
  | yolov8s, 1 / 2 cámaras            | 183 ms · 348 ms (en fila) · 382 ms (en paralelo)   |
  | del evento al panel (WebSocket)   | media 0,5 s · p95 0,9 s                            |
```
Evidencia: `DetectionBenchmark`, `AlertLatencyBenchmark` y el ADR 0003. Efecto: 1 cámara usa `yolov8m` y 2 cámaras usan `yolov8s`.

**HU-38** · Como **desarrollador** quiero que el sistema explique en el registro por qué decidió algo, para corregir comportamientos con datos y no a ciegas.
```gherkin
Given una corrida del tracker
When una persona recibe un id nuevo, un objeto queda sin dueño o se registra un retiro
Then el registro dice por qué (distancia de color, persona más cercana, quién estaba junto al objeto)
 And al iniciar otra corrida, el registro anterior queda en tracker.previous.log
```
Evidencia: `PersonIdentityTest`, `OwnerReturnTest`, `TrackerProcessTest`.

## 5. Story map (flujo de valor por release)

| Paso del flujo | **R1** · Ver | **R2** · Alertar | **R3** · Operar y avisar | **Siguiente** |
|---|---|---|---|---|
| Ver la escena | HU-01, HU-02 | | HU-21 ventana compartida, HU-23/24/25/26 dos cámaras | HU-28 cámaras reales |
| Registrar el objeto | HU-03, HU-04 | | | |
| Detectar el retiro | | HU-05, HU-06 | HU-12 dueño que vuelve | |
| Saber quién | | HU-07, HU-09, HU-10, HU-11, HU-08 | | |
| Alertar | | HU-13, HU-14, HU-15, HU-16, HU-17 | | |
| Dar evidencia | | HU-18 | HU-19 reporte | HU-20 retención |
| Avisar fuera del panel | | | HU-29, HU-30 | HU-31 correo, HU-32 canales |
| Acceso y seguridad | | HU-35 | HU-33, HU-34 (pendientes) | |
| Operar y medir | | HU-36 | HU-22 botón, HU-37, HU-38 | |

El **MVP** es el recorrido R1 + R2 completo: ver → registrar → detectar el retiro → alertar al panel con evidencia en menos de 5 s.

## 6. Trazabilidad: necesidad → componente → release

| Necesidad | Feature | HU principales | Componente que la soporta | Release |
|---|---|---|---|---|
| Enterarme si se llevan mi maleta | F1.2, F2.1, F3.1 | HU-03, HU-05, HU-13 | Object Presence Tracker → Alert Engine | R1–R2 |
| Enterarme *a tiempo* | F3.2 | HU-15, HU-37 | Realtime Gateway (WebSocket) · Event Broker (lectura periódica) | R2 |
| Saber quién y cuándo, sin guardar video | F2.2, F2.3, F4.1 | HU-07, HU-09, HU-10, HU-18 | Object Presence Tracker · Alert DB | R2 |
| No recibir falsas alarmas | F2.1, F2.4 | HU-06, HU-11, HU-12 | Object Presence Tracker · Alert Engine | R2–R3 |
| Usar las cámaras que ya existen | F5.1, F5.2 | HU-21, HU-24, HU-25 | Security Dashboard · Camera Feed Service | R3 |
| Enterarme sin mirar el panel | F6.1, F6.2 | HU-29, HU-30 | Notification Service · Security Dashboard | R3 |
| Que solo personal autorizado vea las alertas | F7.1, F7.2 | HU-33, HU-34, HU-35 | Realtime Gateway · Google (externo) | R2 / R3 |

## 7. Concurrencia y tiempo real

| Escenario | Qué ocurre al mismo tiempo | Qué se protege |
|---|---|---|
| **Dos cámaras en paralelo** | Un detector y un seguimiento por cámara, cada uno en su hilo | Los objetos y personas de una cámara no se mezclan con los de la otra (estado independiente por cámara) |
| **Análisis de video + IA + avisos** | El ciclo de fotogramas corre junto a la descripción por IA (pool de 2 hilos) y a los avisos externos (pool propio) | Ni la IA ni un canal lento retrasan el ciclo de fotogramas ni la alerta |
| **Varios paneles** | Varios operadores ven y revisan la misma alerta | La alerta se crea una sola vez (índice único); marcarla como revisada se difunde a todos |
| **Tiempo real** | El retiro se confirma en ≈ 2,6 s (3 fotogramas) y llega al panel por WebSocket | Un aviso diferido no sirve: el objeto ya se fue. Medido: media 0,5 s, p95 0,9 s desde el evento |
