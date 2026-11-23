# Sustentación del Release 2 — guion, demo, cifras y respuestas

Guía para la sustentación del **sábado 21 de noviembre de 2026**. El requerimiento pide **mostrar en ejecución** la promesa de valor, el tiempo real, la concurrencia, los atributos
de calidad implementados y el tablero de observabilidad; **explicar los resultados de k6 y su correlación con Prometheus/Grafana**; y tener a mano repositorio, pipelines, diagramas
y evidencias. La demostración y los documentos deben reflejar **la misma versión**.

Esta guía **no modifica la presentación** (`SafeMode_ARSW_Sustentacion_v2.pptx`): trae el texto listo para pegar. Los detalles viven en el [backlog](backlog.md), el
[Release Plan](release-plan.md), el [documento de arquitectura](arquitectura/safemode-arquitectura.pdf) y el [artículo](articulo/articulo.pdf).

> **Nombres.** «R1–R3» son los releases de *producto* del [Release Plan](release-plan.md); «Release 2» es la entrega final del curso (observabilidad, persistencia, despliegue
> en AWS, disponibilidad, rendimiento, seguridad y mantenibilidad: los sprints 9 y 10 del artículo).

## 0. Antes de entrar (lista de verificación)

| # | Qué | Cómo se comprueba | Responsable |
|---|---|---|---|
| 1 | **Una sola versión.** Los PDF citan el commit `8fe3fdd`; lo que se muestra debe ser esa versión o una posterior sin cambios de código | `git log -1 --format=%h -- . ':(exclude)docs'` en la rama que se demuestre; si se despliega otra, volver a compilar los PDF (`docs\arquitectura\compilar.ps1`) | Hever |
| 2 | **Los 33 commits de `heverpersistencia` en `develop` y desplegados** (o decidir demostrar en local) | `git fetch origin`; PR `heverpersistencia → develop`; el pipeline despliega por SSH **y necesita la EC2 encendida** | Hever y Juli |
| 3 | **EC2 encendida y dominio apuntando a ella** (el 20-nov el sitio no respondía) | [`apagar-y-encender-la-ec2.md`](despliegue/apagar-y-encender-la-ec2.md): *Start Lab*, iniciar, actualizar DuckDNS; encender 2-3 h antes | Juli |
| 4 | **`verificar-despliegue.ps1` todo en `OK`** | `powershell -ExecutionPolicy Bypass -File docs\despliegue\verificar-despliegue.ps1` | Hever |
| 5 | **Entrar con Google** con un correo de la lista blanca, ya iniciado en el navegador de la demo | Abrir el sitio; si pide credenciales, entrar antes | Todos |
| 6 | **Cámara y escena**: ventana de la cámara abierta (no minimizada), una maleta, dos personas | Ensayo completo (sección 1.1) | Hever |
| 7 | **Grafana con datos de k6** abierto en otra pestaña, con el período de la prueba | Ver sección 1.3 | Hever |
| 8 | **Telegram**: ningún `docker-compose` pasa hoy las variables del bot; solo funciona con `GatewayRunner` en local y las variables `SAFEMODE_TELEGRAM_*`. Mostrarlo solo si se ensayó | Decidir: o se muestra desde un gateway local, o se cuenta como «hecho, no desplegado» | Equipo |
| 9 | **Plan B local** (si la EC2 falla): el stack en el computador de Hever lleva horas arriba, **pero su `.env` tiene `SAFEMODE_GOOGLE_CLIENT_ID` en `PENDIENTE`**: no hay login real. Para usarlo hace falta el client id real (lo tiene Juli) y que el proyecto de Google autorice `http://localhost:8080` como origen | Probarlo hoy, no mañana | Hever y Juli |
| 10 | **Respaldo grabado**: un video corto de la demo completa (alerta incluida), por si falla la cámara o la red | Grabar un ensayo exitoso | Hever |
| 11 | **Pestañas preparadas**: panel · Grafana · Actions del repositorio · SonarQube (si se enciende: `docker compose -p safemode-sonar -f docker-compose.sonar.yml start`, ~2,5 GB) · los dos PDF | Abrirlas en orden antes de entrar | Todos |
| 12 | **Azure DevOps: agregar al docente** (Organization settings → Users → *Add users*, con su correo institucional; hoy solo están los tres integrantes) y dejar abierto, con la cuenta del equipo, Boards → Sprints → **Sprint 10** y **Sprint 2** (pestaña *Backlog*) y Work items (jerarquía épica → feature → historia). Al 20-nov: 103 elementos, 10 sprints, 85 cerrados | [`azure-devops/README.md`](azure-devops/README.md) | Hever |

### 0.1 Reparto sugerido para terminar hoy (para que el equipo se reparta sin preguntar)

| Quién | Hoy | Quién presenta mañana |
|---|---|---|
| **Hever** | **Hecho el 20-nov:** `SafeMode_ARSW_Sustentacion_v4.pptx` (ignorado por git) con las diapositivas **3, 8 y 9** al día y la nueva **10** «Lo que falta» (todo lo demás sigue como en v3; faltan la 7, las imágenes de 4 a 6 y las nuevas A a D de Juli y Kevyn: pueden copiar sus diapositivas a esta v4 con *Reutilizar diapositivas*). **Pendiente:** `git fetch` y PR `heverpersistencia → develop`; ensayo con cámara real y grabar el video de respaldo; diapositivas **3, 8 y 9** y la nueva **E** (lo que falta); revisar los PDF con ojos humanos | §1.1 promesa de valor y §1.2 concurrencia (la demo en vivo) |
| **Juli** | Encender la EC2 y actualizar DuckDNS (2-3 h antes); fusionar el PR y confirmar que el pipeline despliega; correr `verificar-despliegue.ps1`; pasarle a Hever el client id real de Google (y autorizar `http://localhost:8080`) para el plan B; diapositivas **7** y las nuevas **C** (seguridad) y **D** (mantenibilidad, portabilidad y CI/CD) | §1.5 seguridad, §1.6 mantenibilidad/portabilidad/CI y §1.7 Azure DevOps |
| **Kevyn** | Dejar Grafana listo con el período del estrés de k6 (y la clave de Grafana a mano); diapositivas **4, 5 y 6** (insertar las imágenes ya exportadas en `docs/arquitectura/figuras/`: vistas 04 a 17; corregir «infrastuctura» y renumerar los pies) y las nuevas **A** (k6) y **B** (disponibilidad) | §1.3 observabilidad y k6, §1.4 disponibilidad y el cierre |
| **Todos** | Ensayo completo y cronometrado esta noche, con el guion de la sección 1; cada uno repasa las preguntas de defensa de su bloque (sección 7) | |

## 1. Guion (≈ 13 minutos; si hay 10, reducir 4 y 7 a una frase)

Cada bloque dice **qué se muestra**, **qué se dice** y **qué cifra se nombra** (todas salen de una medición versionada: ver sección 2).

### 1.1 Promesa de valor y tiempo real (3 min) — *en vivo*
1. **Entrar** con Google al sitio. Decir: «solo entran correos autorizados; sin sesión toda ruta responde 401».
2. **Compartir la ventana** de la cámara desde el panel y esperar el fotograma. Una persona deja la maleta; esperar **3 s quieta** (el panel registra el objeto, con foto del dueño probable).
3. **Otra persona se la lleva.** En menos de 5 s aparece la **alerta alta («Posible robo»)** con fecha, hora, cámara y tres fotos; suena la alarma. Cifra: **media 0,5 s del evento al panel (p95 0,9 s)**; confirmar el retiro tarda ≈ 2,6 s (3 fotogramas).
4. **Variante que sorprende** (si da el tiempo): que el dueño recoja su propia maleta → sin alerta; o que la recoja alguien vestido parecido → **«Posible dueño que volvió» (media)**, no se silencia.
5. **Marcar revisada** y abrir el **Reporte** (lo que alertó y lo que no).

### 1.2 Concurrencia (2 min) — *en vivo*
- Abrir el panel **en dos navegadores** (dos sesiones). Una alerta nueva aparece en ambos; «revisada» en uno se refleja en el otro. Decir: se crea **una sola vez** (índice único por evento y hora).
- Configuración compartida del puesto (zonas): dos ediciones con la misma versión → una gana (200) y la otra recibe **409**; sin permiso verificable, **503** (falla cerrada).
  Evidencia: el laboratorio mostraba `[200, 200]` (una escritura perdida) y ahora `[200, 409]`.

### 1.3 Observabilidad y k6 (3 min) — *Grafana*
- Mostrar el **tablero SafeMode** (Grafana 13.3.1; 3 tableros, 24 paneles): estado de cada dependencia, solicitudes y latencia por ruta, entrega de alertas, sockets, k6.
- **Explicar k6 con la correlación**: «k6 mide lo que ve el cliente; Prometheus lo que ve el servidor; coinciden». Mostrar el período de la prueba de estrés con ambas series.
- Contar la historia en cuatro frases: (1) con 20 operadores el sistema inicial incumplía 3 de 9 umbrales y **la CPU estaba en 0,7 núcleos de 16**; (2) la causa era un servidor de 32 hilos con **una sola conexión JDBC** y una consulta por fila (N+1); la ley de Little lo reproduce (32 hilos / ≈ 1 s ≈ 32 req/s por réplica); (3) tres cambios (consultas por lotes, nombres de cámara una vez, barrido en una consulta); (4) se repitió **la misma prueba**: la meseta pasó de 34–48 a **570–678 req/s**.
- Nombrar las **8 reglas de alerta** y que en las pruebas se dispararon las de **latencia y entrega, no la de CPU**: por eso se vigila la experiencia y no solo el recurso.

### 1.4 Disponibilidad y escalabilidad (2 min) — *evidencia guardada; en vivo solo si se ensayó*
- Mostrar el diagrama (vista de despliegue del modelo ArchiMate) y [`disponibilidad/evidencias/escalado-gateway.txt`](disponibilidad/evidencias/escalado-gateway.txt).
- Cifras: **0 de 555 peticiones fallidas** al matar una réplica (SIGKILL), operadores reconectados en **≤ 2,1 s**, ninguna alerta perdida ni duplicada, réplica de vuelta en ≈ 5 s. Cálculo: **99,5 % → 99,9375 %** (3,6 h → 27 min de caída al mes).
- **Límite, dicho por nosotros:** el servidor público es **uno solo**; el diseño distribuido está calculado y validado como prototipo, no desplegado.

### 1.5 Seguridad (1,5 min)
- Correr `verificar-despliegue.ps1` (solo lecturas públicas): rutas protegidas → 401 sin sesión, `/grafana/` → 401, `/frames` sin sesión → rechazado.
- Nombrar los tres escenarios aprobados: **ES-1** (autenticación y autorización: 35 pruebas), **ES-2** (ataques web: `Origin`/`Host`, límites de hilos, cierre del WebSocket al terminar la sesión), **ES-3** (integridad de `/frames`: 12 de 12).
- **Brechas declaradas:** cookie sin `Secure` y sin registro de auditoría.

### 1.6 Mantenibilidad, portabilidad y CI/CD (1,5 min)
- **Actions**: ejecución en verde con los *reusable workflows* (compilar, probar, cobertura, calidad, despliegue que **depende** del control de calidad).
- **SonarQube** (o la captura de `docs/mantenibilidad/`): control de calidad en verde; fiabilidad, seguridad y mantenibilidad **A**; 0 errores, 0 vulnerabilidades; cobertura **81,8 % backend / 99,3 % panel**, código nuevo 98,5 %; **479 pruebas de Java y 144 de Node**.
- **Portabilidad**: el mismo conjunto de archivos Compose corre en el computador, en el prototipo de escalado y en AWS; sin servicios propios de un proveedor. **Límite:** la migración a otro servidor no se ejecutó como prueba.

### 1.7 Gestión del sprint: Azure DevOps (1 min)
- Abrir **Boards → Sprints → Sprint 10** (15 historias, todas cerradas) y mostrar que cada historia trae sus puntos, el criterio de aceptación y, en la descripción, **la fecha real de su evidencia**. Luego **Work items**: épica → feature → historia (103 elementos; 10 sprints semanales del 7 de septiembre al 20 de noviembre).
- Decir con franqueza: «Los sprints 1 a 5 y las historias HU-01 a HU-05 existían desde el inicio. El seguimiento diario se llevó en el repositorio (backlog, plan de releases y 175 commits). El 20 de noviembre completamos Azure DevOps con lo ya hecho y por eso los elementos llevan la etiqueta `registrado-retroactivo`: la fecha de cierre es de hoy, pero la fecha real de entrega está en cada descripción.»
- **No** prometer un burndown diario: no existe para los sprints anteriores y Azure DevOps no permite fechas en el pasado.

### 1.8 Cierre (30 s)
«Hecho y medido: …; **no hecho**: correo y canales por usuario, prueba con dos cámaras físicas, despliegue distribuido real, SonarCloud real. La entrega de alertas pasa de 5 s con 100 operadores o más: es el siguiente cuello (pool de conexiones y *snapshot* acotado).»

## 2. Cifras en una página (para no equivocarse)

| Atributo | Cifra | De dónde sale |
|---|---|---|
| Tiempo real | media **0,5 s**, p95 **0,9 s** del evento al panel; retiro confirmado ≈ 2,6 s; peor caso extremo a extremo ≈ 3,5 s (1 cámara) | `AlertLatencyBenchmark`, ADR 0003 |
| Carga (20 operadores, 2 réplicas) | P95 HTTP **726 → 48 ms**; umbrales **6 → 9 de 9**; 33,3 → 48,0 req/s (TPM 2 000 → 2 882) | [`rendimiento/informe-k6.md`](rendimiento/informe-k6.md) |
| `/report` | P95 **1 892 → 6,3 ms** | ídem |
| Pico (20 → 300) | **25 → 217 req/s**; P95 **10,3 → 0,6 s** | ídem |
| Estrés (hasta 600) | meseta **34–48 → 570–678 req/s**; alertas entregadas **5 967 → 185 756** | ídem |
| Límite de entrega | P95 de entrega > 5 s desde 100 operadores; 143 req/s y 100 alertas/s sostenibles a 50 operadores | ídem |
| Disponibilidad | **0/555** fallidas; reconexión ≤ 2,1 s; **99,5 → 99,9375 %**; 17/17 comprobaciones en 3 corridas | [`disponibilidad/`](disponibilidad/arquitectura-de-disponibilidad.md) |
| Seguridad | ES-1 35 pruebas; ES-3 12/12; 100 % de rutas protegidas → 401 | [`seguridad/`](seguridad/escenarios-de-seguridad.md) |
| Mantenibilidad | gate verde; A/A/A; backend 81,8 %, panel 99,3 %; **479 + 144** pruebas | [`mantenibilidad/`](mantenibilidad/escenario-y-resultados.md) |
| Observabilidad | **8** reglas de alerta; Grafana 13.3.1; k6 por *remote write* | `performance/monitoring/` |
| Concurrencia | `[200, 200]` → `[200, 409]`; permiso caído 200 → 503 | `docs/persistencia/` |
| Backlog | 33 de 38 HU hechas (128 de 145 puntos); 175 commits (9-sep → 20-nov) | [`backlog.md`](backlog.md) |

**Cómo decir los límites de la medición** (sin que suene a excusa): todo corrió en un solo equipo con el generador de carga incluido, así que son cifras *relativas* antes/después y no la capacidad
de la instancia de AWS; la base no se vació entre corridas (cada una empezó con más alertas pendientes que la anterior), lo que hace la mejora *conservadora*.

## 3. Qué quedó desfasado en la presentación

| # | Diapositiva | Lo que dice hoy | Lo que es verdad ahora |
|---|---|---|---|
| 1 | 9 · Riesgos | «la alerta se mantiene pero indica "puede ser el dueño que volvió"» | La alerta **baja a media** («Posible dueño que volvió»); no se silencia (ADR 0003 §8) |
| 2 | 9 · Riesgos | «Pendiente medir el rendimiento con 2 cámaras» | Ya se midió: 702 ms por fotograma con `yolov8m`, 348 ms con `yolov8s` (2 cámaras) |
| 3 | 9 · Riesgos | «acceso solo con inicio de sesión de Google» | **Es verdad ahora** (HU-33/34): lista blanca de correos, sesión en cookie `HttpOnly`/`SameSite=Strict` y 401 sin sesión. *No* cambiar a «pendiente» |
| 4 | 8 · Gherkin | «en menos de 5 segundos» sin evidencia | Medido: media 0,5 s del evento al panel; peor caso ≈ 3,5 s con 1 cámara |
| 5 | 8 · Release Plan | S8 como si todo estuviera hecho | S8 casi completo: navegador, Telegram (lista fija de chats) y login hechos; **correo y preferencias por usuario pendientes** |
| 6 | 7 · Componentes | «WebSocket / SSE» y avisos «por Telegram y correo… según los canales que elija cada usuario» | Solo WebSocket; Telegram hecho con lista fija; correo y canales por usuario pendientes |
| 7 | 5 · Título | «Diagrama de infrastuctura» | Falta una «e»: *infraestructura* |
| 8 | 4–9 · Pie | «Página 2», «2», «2», «4», «5», «6» | Renumerar de 1 a 9 (y más si se agregan diapositivas) |
| 9 | Todas | No hay nada del Release 2 | Agregar de 3 a 5 diapositivas de calidad (sección 4) |

## 4. Diapositivas nuevas del Release 2 (texto listo)

**A · Resultados de rendimiento (k6).** Título: *«Medimos, encontramos el cuello y lo corregimos»*. Figura: `docs/articulo/figuras/estres-comparacion.pdf` (req/s, alertas entregadas y latencia por escalón, antes y después).
> Misma prueba, mismo equipo, 2 réplicas. **Meseta HTTP: 34–48 → 570–678 req/s. P95 del pico: 10,3 → 0,6 s. Alertas entregadas: 5 967 → 185 756.** Causa: un servidor de 32 hilos con una sola conexión JDBC y
> una consulta por fila; la CPU nunca fue el límite (≤ 0,7 núcleos de 16). Siguiente cuello: la entrega de alertas supera 5 s desde 100 operadores.

**B · Disponibilidad y escalabilidad.** Título: *«Dos réplicas, una puede caer»*. Figura: vista de despliegue del modelo.
> Gateway sin estado (sesiones y alertas en la base), balanceador con vigilancia activa y pasiva. Matar una réplica: **0 de 555 peticiones fallidas**, reconexión ≤ 2,1 s, sin alertas perdidas ni duplicadas.
> Cálculo con los SLA publicados: **99,5 % → 99,9375 %** (3,6 h → 27 min al mes). *Límite: el despliegue público es de un solo servidor.*

**C · Seguridad.** Título: *«Tres escenarios aprobados, con validación ejecutable»*.
> ES-1 autenticación y autorización (Google + lista blanca, cookie `HttpOnly`, 401 sin sesión). ES-2 ataques web y ciclo de la sesión (`Origin`/`Host`, límites, cierre del WebSocket). ES-3 integridad de `/frames` (`forward_auth`; 12 de 12).
> Brechas declaradas: cookie sin `Secure`, sin registro de auditoría.

**D · Mantenibilidad, portabilidad y observabilidad.** Título: *«Cada cambio se mide»*.
> Pipeline con *reusable workflows*; el despliegue depende del control de calidad. SonarQube: gate verde, A/A/A, 0 errores y 0 vulnerabilidades; cobertura 81,8 % backend y 99,3 % panel; 479 + 144 pruebas.
> Mismos archivos Compose en el computador, el prototipo y AWS. Prometheus y Grafana con 8 reglas de alerta (latencia y entrega se dispararon; la CPU no).

**E · Lo que no está hecho.** Título: *«Lo que sabemos que falta»*.
> Correo y canales por usuario · prueba con dos cámaras físicas · despliegue distribuido real · corrida en SonarCloud · retención de fotos · reidentificar al dueño por apariencia.

## 5. Texto propuesto por diapositiva (las ya existentes)

### Diapositiva 3 · Alcance inicial (MoSCoW)
- **Should:** *Alerta en el panel con fotos, fecha, hora y cámara (sin video), dueño probable, reporte en tabla y aviso de texto por navegador y Telegram (correo pendiente).*
- **Could:** *Histórico de alertas por cancha; reidentificar al dueño por apariencia (hoy solo se compara el color de la ropa).*

Opcional, para el BPMN que piden los lineamientos: convertir el «Proceso simplificado» en tres carriles.

| Carril | Elementos |
|---|---|
| **Jugador** | Inicio → *Deja la maleta en la banca* |
| **Sistema** | *Registra el objeto en reposo (3 s quieto)* → *Detecta que se retiró* → **compuerta exclusiva** «¿quién estaba junto al objeto?»: *solo el dueño* → fin sin alerta; *otra persona* → *Genera alerta alta*; *se parece al dueño* → *Genera alerta media* → *Avisa por panel, navegador y Telegram* |
| **Seguridad** | *Revisa el panel y las fotos* → **compuerta** «¿es sospechoso?»: sí → *Interviene*; no → *Marca como revisada*; en ambos casos *Busca el momento en la grabación (±30 s)* |

### Diapositivas 4, 5 y 6 · Imágenes de los diagramas
- Re-exportar desde Archi las vistas del modelo `docs/diagramas/safemode-arquitectura.archimate` (o usar los PNG ya exportados en `docs/arquitectura/figuras/`).
- **Diapositiva 4**: **«Vista As-Is: MVP actual (un solo computador)»**. **Diapositiva 5**: **«Diagrama de infraestructura (To-Be)»**. **Diapositiva 6**: **«Vista de aplicación / componentes (To-Be)»**.
- Secuencia recomendada: As-Is → To-Be de componentes → To-Be de infraestructura. Las vistas 4 a 17 (contexto, componentes, clases, datos, secuencia, despliegue, actividades) están en el documento de arquitectura.

### Diapositiva 7 · Arquitectura To-Be: textos de los componentes

| Componente | Texto propuesto |
|---|---|
| **Alert Engine** | Reglas de severidad: posible robo (alta); dueño que volvió, ambiguo y desaparecido (media); sin dueño (baja) |
| **Security Dashboard** | Alertas, reporte, zonas, captura de la ventana de cámaras y avisos del navegador; acceso con Google |
| **Realtime Gateway** | WebSocket hacia el panel; sesiones y alertas en la base (replicable); entrega el panel y las fotos |

**Párrafo de responsabilidades (reemplaza al actual):**

> El Object Presence Tracker detecta que alguien se llevó el objeto (un seguimiento independiente por cámara), y el Alert Engine junto al Realtime Gateway generan y entregan la alerta. Si el Object Presence
> Tracker falla, no se generan alertas nuevas pero el video se sigue grabando en el software de cámaras; si un Realtime Gateway falla, las alertas quedan en la base y la otra réplica las entrega. El acceso
> exige inicio de sesión con Google y un correo autorizado. El Notification Service avisa de las alertas altas y medias solo con texto: **por Telegram ya está construido** (con una lista fija de chats) y el
> aviso del navegador lo muestra el propio panel; **el correo y los canales por usuario están pendientes**.

**Recuadro «Nube — canal en vivo»:** cambiar «por WebSocket/SSE» por «por WebSocket».

### Diapositiva 8 · Backlog trazable y Release Plan

**Criterio de aceptación (Gherkin), nuevo «Then»:**

> Given que una maleta fue marcada como en reposo en la banca
> When una persona la toma y la retira de esa zona
> Then el panel de seguridad recibe una alerta con fotos, fecha, hora y cámara en menos de 5 segundos
> *(medido: media de 0,5 s desde el evento y ≈ 3,5 s en el peor caso extremo a extremo con 1 cámara)*

**Release Plan — filas que cambian** (✔ hecho · ½ parcial):

| R | Sprint | Incremento funcional | Estado |
|---|---|---|---|
| R1 | S1 | Detección de personas y objetos en el video (sin alertas aún) | ✔ |
| R1 | S2 | Registro de objetos dejados en reposo (zona), sin alertas todavía | ✔ |
| R2 | S3 | Detección de que una persona toma un objeto y lo retira de la escena | ✔ |
| R2 | S4 | Generación de alerta en tiempo real con evidencia fotográfica (fotos, fecha, hora y cámara) | ✔ |
| R2 | S5 | Dashboard en tiempo real para seguridad + notificación (alarma sonora) | ✔ |
| R3 | S6 | Dos cámaras desde una ventana: zonas dibujadas, prueba previa y alertas por cámara | ✔ (falta probar con 2 cámaras físicas) |
| R3 | S7 | Reporte en tabla (fecha, hora, cámara, fotos) y alerta «posible dueño que volvió» | ✔ |
| R3 | S8 | Avisos por navegador y Telegram; **inicio de sesión con Google**; correo y preferencias por usuario pendientes | ½ |

Línea al pie: **«8 de 8 sprints con incremento demostrable · 33 de 38 HU hechas (128 de 145 puntos)»**.

### Diapositiva 9 · Concurrencia, tiempo real y riesgos

**Concurrencia** *(reemplaza)*:
> Dos cámaras se analizan en paralelo, cada una con su propio detector y seguimiento. La descripción por IA y los avisos externos corren en hilos aparte para no retrasar la alerta. Varios paneles (y
> varias réplicas del gateway) ven la misma alerta: se crea una sola vez (índice único por evento) y «revisada» se refleja en todos. La configuración compartida del puesto usa versión optimista: dos
> escrituras con la misma versión → una gana y la otra recibe 409.

**Tiempo real** *(reemplaza)*:
> El retiro se confirma en ≈ 2,6 s (3 fotogramas) y la alerta llega al panel por WebSocket en 0,5 s de media (p95 0,9 s; medido). Peor caso extremo a extremo: ≈ 3,5 s con 1 cámara. Debe llegar mientras el
> sospechoso sigue en la zona: un aviso diferido no sirve porque el objeto ya se fue. Cada alerta se genera una sola vez, sin perderse ni duplicarse (también al caer una réplica).

**Calidad + riesgos** *(reemplaza)*:
> Atributos prioritarios: baja latencia, precisión, privacidad, disponibilidad y seguridad. **Riesgos y mitigación:** (1) falsa alarma si el dueño sale de cuadro y vuelve: si quien retira se viste parecido,
> la alerta baja de «posible robo» (alta) a «posible dueño que volvió» (media), sin silenciarla; un ladrón vestido igual saldría en media (riesgo declarado). (2) Rendimiento con 2 cámaras: con 2 se usa
> `yolov8s` (348 ms frente a 702 ms). (3) Privacidad: no se guarda video, los avisos externos son solo texto y la descripción por IA externa es opcional. (4) Seguridad: acceso con Google y lista blanca,
> `Origin`/`Host` en cada cambio de estado, `/frames` protegido; brechas declaradas: cookie sin `Secure` y sin auditoría. (5) El tracker (detección) es un punto único por puesto.

**Uso de IA** *(añadir una línea de validación)*:
> YOLOv8 (visión por computador) corre en el propio computador para detectar personas y objetos. Un modelo de visión de NVIDIA, opcional, describe al dueño y a quien retira: el recorte de la persona sale a un
> servicio externo. Claude Code se usó como asistente de desarrollo y documentación. **Validación del equipo:** todo se revisó y se comprobó con pruebas automáticas (479 de Java y 144 de Node), mediciones y
> pruebas con cámara real; la responsabilidad del resultado es del equipo.

**Gate de aceptación** *(reemplaza)*:
> SafeMode está listo cuando demuestra el flujo de extremo a extremo con 1 y 2 cámaras: objeto en reposo → alguien lo retira → alerta con fotos, fecha, hora y cámara en el Security Dashboard en menos de
> 5 segundos (medido ≈ 3,5 s en el peor caso). Si quien retira se parece al dueño, la alerta lo indica como «posible dueño que volvió».

## 6. Chequeo contra la rúbrica

### 6.1 Release 2 (pesos del requerimiento)

| Criterio | Peso | Qué se muestra | Dónde |
|---|---|---|---|
| Promesa de valor | 20 % | Retiro → alerta con evidencia en < 5 s, en vivo | §1.1 |
| Documento de arquitectura | 20 % | 65 páginas, 11 capítulos, 17 vistas ArchiMate, tres escenarios de seguridad aprobados | PDF |
| Disponibilidad / escalabilidad | 10 % | Escenario, balanceador, cálculo de SLA, prototipo con caída de réplica | §1.4 |
| Rendimiento (k6) | 10 % | Carga, estrés y pico; TPS/TPM, P95, errores; correlación con Grafana | §1.3 |
| Seguridad | 10 % | Tres escenarios, tácticas y validación ejecutable | §1.5 |
| Mantenibilidad | 10 % | Sonar, cobertura ≥ 80 % por capa, *reusable workflows* | §1.6 |
| Portabilidad | 10 % | Contenedores y Compose en tres entornos | §1.6 |
| Concurrencia | 10 % | `[200, 200]` → `[200, 409]`, una sola alerta por evento | §1.2 |
| Contribución individual | ajusta | `git log`: Hever 130, Juli 29, Kevyn 16 commits | documento, cap. 9 |

### 6.2 Lineamientos del curso (Release 1; siguen valiendo para la presentación)

| Criterio | Peso | Evidencia que tienen hoy | Qué reforzar |
|---|---|---|---|
| Problema, persona/necesidad y propósito | 15 % | Diapositivas 1 y 2: jugador, maleta en la banca | Una cifra o un caso real; evitar que suene genérico |
| Producto, propuesta de valor y proceso/BPM | 15 % | Diapositivas 2 y 3 con MoSCoW | Pasar el proceso a **BPMN con carriles** (ver arriba) |
| Arquitectura To-Be: componentes e infraestructura | 25 % | Modelo ArchiMate (17 vistas) y las diapositivas 4 a 7 | Re-exportar las imágenes; ligar cada componente a una HU ([tabla](backlog.md#6-trazabilidad-necesidad--componente--release)) |
| Backlog: Épica → Feature → HU + criterios | 15 % | **38 HU con Gherkin**, DoR, DoD, MoSCoW y story map | Mostrar la jerarquía y un ejemplo |
| Release Plan / planificación incremental | 10 % | 8 sprints con incremento de valor y evidencia | Mostrar el estado real y qué sigue |
| Concurrencia y escenario en tiempo real | 10 % | Diapositiva 9 + tabla de concurrencia del backlog | Nombrar los mecanismos (estado por cámara, índice único, versión optimista, hilos aparte) |
| Claridad, argumentación y respuestas | 10 % | Las respuestas de abajo | Practicar el flujo necesidad → componente → release sin leer |

## 7. Preguntas de defensa y respuestas

### Producto y arquitectura

**¿Qué necesidad concreta justifica el producto?**
Quien juega en una cancha deja su maleta en la banca, con llaves del carro, billetera, documentos y a veces el computador, y no puede vigilarla. Las cámaras graban, pero nadie las mira en tiempo real: el robo se
descubre cuando ya no se puede hacer nada. SafeMode convierte esas mismas cámaras en un vigilante.

**¿Qué cambia en el proceso cuando exista la solución?**
Antes se descubría al terminar el partido y había que revisar horas de grabación. Ahora seguridad recibe una alerta en segundos con la cámara, la hora y las fotos del dueño y de quien se la llevó; puede intervenir
mientras el sospechoso sigue en la zona y, si no alcanza, sabe el minuto exacto (±30 s) para buscar en la grabación.

**¿Qué componente soporta una Feature/HU específica y por qué?**
- *Detectar el retiro (HU-05):* el **Object Presence Tracker**, el único que guarda el estado de cada objeto (nuevo → en reposo → retirado) y de cada persona, por cámara.
- *Decidir la severidad (HU-13):* el **Alert Engine**, separado del seguimiento para cambiar las reglas sin tocar la visión.
- *Mostrarla en vivo (HU-15):* el **Realtime Gateway**, que la empuja por WebSocket sin que el panel pregunte.
- *Avisar fuera del panel (HU-29, HU-30):* el **Notification Service** y el propio panel, en hilos aparte para no frenar la alerta.

**¿Por qué no microservicios para todo?**
Un equipo de tres personas y un requisito de menos de 5 s favorecen pocos procesos. Lo que escala por operadores (el gateway) se hizo sin estado para poder replicarlo; lo que no se replica (el tracker, por cámara, junto
a la pantalla) quedó aparte. Los servicios de apoyo (acceso, colaboración) existen porque cada motor de datos garantiza algo distinto: PostgreSQL lo que debe ser siempre correcto, MongoDB un documento versionado, Redis solo lo efímero.

### Concurrencia y tiempo real

**¿Dónde existe realmente concurrencia y qué debe coordinarse o protegerse?**
(1) **Dos cámaras en paralelo**, cada una con su detector y seguimiento, para que no se mezclen; (2) **el análisis de video junto a la IA y los avisos**, en hilos aparte para que un servicio externo lento no retrase la
alerta; (3) **varios paneles y réplicas sobre la misma alerta**: se crea una sola vez (índice único) y «revisada» se difunde; (4) **la configuración compartida**: versión en el filtro de la operación atómica → `[200, 409]`.

**¿Qué requiere tiempo real y qué ocurriría si la respuesta fuera diferida?**
La alerta del retiro: debe llegar mientras el sospechoso sigue en la zona. Medimos 0,5 s de media (p95 0,9 s). Diferida (un reporte al final del día) se descubriría el robo cuando ya no se puede intervenir; por eso el
reporte es solo un complemento.

### Rendimiento

**¿Qué mide cada cifra de k6 y cuál es el TPS?**
La *solicitud* es una petición HTTP; la *transacción de negocio* es una alerta entregada. TPS = transacciones / segundos; TPM = 60 · TPS. Con 20 operadores: 48 req/s (≈ 2 882 TPM de solicitudes). En el estrés, hasta 100 alertas/s
sostenibles con la meta de 5 s (50 operadores).

**¿Por qué mejoró tanto sin cambiar el hardware?**
Porque el límite no era el hardware: la CPU estaba en 0,7 núcleos de 16. Un servidor de 32 hilos con **una sola conexión JDBC compartida** y una consulta por fila (N+1) esperaba, no calculaba. La ley de Little lo reproduce
(32 hilos / ≈ 1 s ≈ 32 req/s por réplica, y medimos 34–48). Con consultas por lotes la meseta subió a 570–678.

**¿Es confiable la comparación antes/después?**
Misma prueba y mismo equipo; las gráficas por ventana se derivan de los contadores de k6 (que exporta percentiles acumulados) y se verificaron contra el resumen de k6. **Limitaciones:** un solo equipo con el generador incluido, ciclo
cerrado, y la base no se vació entre corridas (el estrés «después» empezó con 2 446 alertas pendientes contra 1 028 del «antes»: más duro, no más fácil).

**¿Cuál es el límite actual?**
La entrega de alertas: el P95 pasa de 5 s desde 100 operadores. La hipótesis (conexión compartida y *snapshot* de todas las alertas pendientes por socket nuevo) está identificada; el arreglo (pool y *snapshot* acotado) está
propuesto, no hecho.

### Disponibilidad

**¿Cómo se calculó 99,9375 %?**
Serie `A = A₁·A₂·…`, paralelo `A = 1 − (1−A₁)(1−A₂)`. Objetivo: Route 53 (100 %) × balanceador multi-AZ (99,99 %) × dos gateways en paralelo (1 − 0,005² = 99,9975 %) × RDS Multi-AZ (99,95 %) = **99,9375 %**
(27 min de caída al mes; hoy, un servidor: 99,5 % = 3,6 h). SLA publicados por AWS, consultados el 19-nov-2026. La base pesa ≈ 80 % de la indisponibilidad restante.

**¿Está desplegado así?**
No, y lo decimos: el servidor público es uno. El diseño distribuido está calculado y **validado como prototipo** (dos réplicas + balanceador + PostgreSQL: 17/17 comprobaciones en 3 corridas, 0 de 555 fallidas al matar una réplica);
el equipo decidió no desplegarlo físicamente.
**Ojo, es el punto más expuesto:** el requerimiento dice que la arquitectura «no puede ser monolítica ni desplegar todo en un solo servidor» y que varios contenedores en un solo host «no reemplazan la justificación de
despliegue distribuido». Por eso la respuesta es la *justificación*: el diseño distribuido (DNS → balanceador multi-AZ → 2 gateways en 2 zonas → base Multi-AZ), sus SLA publicados y el cálculo, **contrastados con las métricas del
prototipo** (que el requerimiento también pide, «reconociendo las limitaciones del entorno de prueba»). No decir que está desplegado en varios servidores.

**¿Y si cae la base?**
Falla cerrado: sin base no hay sesión ni alerta nueva; las conexiones se reabren solas cuando vuelve (`ReconnectingConnection`), sin reiniciar el gateway. La regla `BaseDeDatosInaccesible` lo avisa.

### Seguridad

**¿Cómo se autentica y autoriza?**
Google emite un ID token; el servidor lo verifica (emisor, audiencia, vigencia, correo verificado) y exige que el correo esté en la lista blanca. La sesión es una cookie de 256 bits (`HttpOnly`, `SameSite=Strict`, 12 h) de la que
el servidor guarda solo la huella SHA-256. Todo cambio de estado exige `Origin` y `Host` válidos; los WebSockets se cierran al terminar la sesión; `/frames` exige sesión en el proxy.

**¿Qué falta en seguridad?**
Cookie sin `Secure` y registro de auditoría (declarados en el documento). Las claves viven solo en variables de entorno.

### Mantenibilidad, portabilidad y observabilidad

**¿Qué demuestra Sonar y qué no?**
Que el código nuevo y el existente cumplen el control de calidad (A/A/A, 0 errores, 0 vulnerabilidades) con la configuración del repositorio. **No** hay corrida real en SonarCloud (requiere cuenta y token del equipo): lo medido es SonarQube
local con la misma configuración. Encontró cuatro errores reales (corregidos con prueba) y cuatro falsos positivos de SQL (justificados en el código).

**¿Qué haría falta para pasar de AWS a servidores propios?**
Solo lo operativo: dominio, volúmenes y claves; no hay servicios administrados propios del proveedor (bases en contenedores; DNS y certificados fuera de AWS). La migración **no se ejecutó** como prueba.

**¿Qué demuestra la observabilidad que no mostraría la CPU?**
Durante el estrés se dispararon las reglas de latencia y de entrega de alertas, y la de CPU no: el gateway usó ≤ 0,7 núcleos mientras el P95 llegaba a segundos. Por eso se vigila la experiencia y no solo el recurso.

### Estado, riesgos y honestidad

**¿Qué quedó funcionando por release?**
R1 (S1–S2): detectar personas y maletas y registrar los objetos dejados. R2 (S3–S5): la alerta al panel con evidencia. R3 (S6–S8): dos cámaras, reporte, «dueño que volvió», avisos y login. Release 2 del curso: persistencia, observabilidad,
despliegue en AWS, disponibilidad, rendimiento, seguridad y mantenibilidad medidos.

**¿Cuál es hoy el mayor riesgo técnico?**
(1) La **identidad de las personas** se compara por el color de la ropa: un ladrón vestido como el dueño se vería parecido (mitigado: baja a media, no se silencia, y el operador compara las fotos). (2) El **tracker** es un punto
único de detección por puesto. (3) El **rendimiento con dos cámaras reales** se resolvió con `yolov8s` pero falta comprobar su calidad en vivo.

**¿Usaron Azure DevOps como herramienta oficial? ¿Dónde está el burndown?**
Se creó al inicio con la épica, las cinco features, las historias HU-01 a HU-05 y los sprints 1 a 5. El seguimiento diario después se llevó en el repositorio (backlog con 38 HU y Gherkin, plan de releases y 175 commits verificables).
El 20 de noviembre se completó Azure DevOps con todo lo hecho (sprints 6 a 10, 92 elementos con puntos, criterios y la fecha real de su evidencia) y se cerraron las historias terminadas. Por eso el burndown diario de los sprints
anteriores **no existe**; lo que sí existe es la trazabilidad en Git. Es una limitación nuestra y la declaramos en el documento (capítulo 3 y bitácora).

**¿Qué no está hecho?**
Correo y canales por usuario, prueba con dos cámaras físicas, despliegue distribuido real, SonarCloud real, retención de fotos y reidentificación por apariencia. Está en el backlog con su HU y en el documento, capítulo 10.

## 8. Cierre del feedback (observaciones de la revisión interna)

> Son las observaciones que salieron al contrastar la presentación con los lineamientos; **no son comentarios del docente**. Si hubo comentarios en la sustentación, se agregan como filas nuevas con el mismo formato.

| Observación | Decisión / acción | Evidencia | Estado |
|---|---|---|---|
| El alcance no aclaraba qué es el «dueño» | Es un dueño **probable** por cercanía, no una identidad; la IA externa es opcional y su privacidad está declarada | ADR 0003 §9; diapositivas 2 y 3 | Cerrado |
| Se mezclaban la arquitectura actual y la futura | Vistas separadas en el modelo ArchiMate: As-Is, To-Be de componentes y de infraestructura, más 14 vistas del Release 2 | `docs/diagramas/safemode-arquitectura.archimate` | Cerrado (re-exportar las imágenes en la presentación) |
| La concurrencia no tenía escenarios concretos | Dos cámaras, IA y avisos en hilos aparte, varios paneles, versión optimista; con sus mecanismos | Backlog §7; diapositiva 9 | Cerrado |
| La evidencia prometía un «clip de video» | Alerta con fotos, fecha, hora y cámara; no se guarda video | ADR 0003 §5 | Cerrado |
| El uso de IA no declaraba el servicio externo ni el asistente de desarrollo | YOLOv8, NVIDIA (opcional) y Claude Code declarados, con validación del equipo | Diapositiva 9; artículo §IV-B | Cerrado |
| «Menos de 5 segundos» nunca se había medido | `AlertLatencyBenchmark` y `DetectionBenchmark` | ADR 0003, «Consecuencias» | Cerrado |
| Falsa alarma si el dueño sale de cuadro y vuelve | Opción B: baja a «posible dueño que volvió» (media) sin silenciar | ADR 0003 §8; `OwnerReturnTest` | Cerrado con riesgo residual declarado |
| El backlog solo tenía 2 HU | 38 HU con Gherkin, DoR, DoD, MoSCoW y story map | `docs/backlog.md` | Cerrado |
| El Release Plan no mostraba el estado | Estado y evidencia por sprint, y qué falta | `docs/release-plan.md` | Cerrado |
| Inicio de sesión con Google y protección de toda la superficie | Verificación del ID token en el servidor, lista blanca, sesión por cookie, 401 sin sesión | Backlog HU-33/34; `docs/seguridad/` | Cerrado |
| Correo y avisos por usuario | Pendientes de la cuenta de correo y de la vinculación por usuario | Backlog HU-31, HU-32 | **Abierto** |
| Probar con dos cámaras reales | Pendiente de la segunda cámara | Backlog HU-28 | **Abierto** |

## 9. Declaración de uso de IA (texto sugerido)

| Herramienta | Para qué se usó | Cómo se validó |
|---|---|---|
| **YOLOv8** (visión por computador, local) | Detectar personas y objetos y estimar la pose del dueño | Pruebas automáticas, calibración con fotos reales y pruebas con cámara |
| **Modelo de visión de NVIDIA** (servicio externo, opcional) | Describir al dueño y a quien retira el objeto | Se prueba contra un servidor falso; el recorte de la persona sale a un tercero, por eso es opcional y se declara |
| **Claude Code** (asistente de desarrollo) | Apoyo en análisis, código, pruebas, scripts de k6, flujos de CI, informes y documentación | El equipo revisó el código, ejecutó las pruebas, midió y decidió; la responsabilidad del resultado es del equipo |
