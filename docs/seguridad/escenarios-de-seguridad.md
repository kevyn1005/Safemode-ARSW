# Escenarios de calidad de seguridad — Safemode (Release 2)

Tres escenarios de seguridad del Release 2, **aprobados por el profesor**. Cada uno se expresa en el formato de escenario de calidad
(fuente, estímulo, artefacto, entorno, respuesta y medida de la respuesta), nombra las tácticas de seguridad que lo atienden
(*Software Architecture in Practice*, Bass, Clements y Kazman) y trae una **validación ejecutable**: un comando que se puede correr y
que comprueba la medida de la respuesta.

| | Escenario | Atributo | Tácticas principales | Estado | Validación |
|---|---|---|---|---|---|
| **ES-1** | Solo operadores autorizados entran al panel, a las fotos y al canal de alertas | Autenticación y autorización | Identificar, autenticar y autorizar actores; limitar acceso | Implementado | 4 clases de prueba (JUnit), 35 pruebas |
| **ES-2** | Una página o conexión hostil no puede usar la sesión del operador ni degradar el servicio, y una sesión terminada deja de valer | Protección frente a ataques | Limitar acceso y exposición; revocar acceso; limitar el efecto de conexiones retenidas | Implementado | 4 clases de prueba (JUnit), 58 pruebas, + 6 del panel (Node) |
| **ES-3** | Nadie sin sesión puede inyectar imágenes falsas en el detector | Integridad | Autenticar actores y limitar acceso en el proxy (`forward_auth`) | Implementado en el repo; **falta redesplegar** | Script reproducible, 12 comprobaciones |

Entorno común: sistema en operación normal; en producción detrás de Caddy con HTTPS (AWS) y, para las pruebas, en loopback.

---

## ES-1 — Autenticación y autorización del operador

| Parte | Descripción |
|---|---|
| **Fuente** | Persona sin cuenta, o titular de una cuenta de Google válida pero no autorizada para este puesto. |
| **Estímulo** | Intenta abrir el panel de alertas, leer fotos o el reporte, iniciar o detener la vigilancia, abrir el WebSocket de alertas o cambiar la configuración del puesto. |
| **Artefacto** | Gateway (`DashboardHttpServer`, `AlertGateway`, `AuthService`) y servicio de acceso (roles). |
| **Entorno** | Operación normal, producción por HTTPS. |
| **Respuesta** | El navegador entrega el ID token de Google; el servidor lo verifica (emisor, audiencia, vigencia y correo verificado), exige que el correo esté en la lista autorizada y solo entonces crea una sesión (identificador aleatorio de 256 bits, cookie `HttpOnly` y `SameSite=Strict`, 12 h). Toda ruta sensible y el WebSocket exigen sesión; los cambios del puesto exigen además el rol de operador. |
| **Medida** | 100 % de las rutas protegidas responden 401 sin sesión **antes** de ejecutar su manejador; token inválido → 401; cuenta fuera de la lista → 403 sin cookie; cuenta autorizada sin rol de operador → no puede configurar; 0 fotos o reportes entregados sin sesión; WebSocket sin cookie válida → rechazado. |

**Tácticas.** Identificar actores (correo verificado de Google), autenticar actores (OIDC, ADR 0004), autorizar actores (lista autorizada +
rol en el servicio de acceso), limitar acceso (compuerta `requiresSession`).

**Validación ejecutable.**

```powershell
mvn -o -pl realtime-gateway -am test "-Dtest=DashboardHttpServerAuthTest,AuthServiceTest,PuestoRoutesTest,AuditLeadWebSocketSessionTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Pruebas clave: `sinSesionLasRutasProtegidasDan401`, `unTokenQueGoogleNoValidaDa401`, `unaCuentaValidaPeroFueraDeLaListaDa403YNoDejaCookie`,
`sinSesionTodasLasRutasDelPuestoDan401YNoLlegaNadaAlServicio`, `unaCuentaAutorizadaQueNoEsOperadorPuedeVerPeroNoConfigurarNiTenerPresencia`,
`laSesionVenceConElTiempo`, `sinCookieElWebSocketSeRechazaConAutenticacionActivada`. Evidencia: `evidencias/es1-es2-pruebas-gateway-y-panel.txt`.

**Uso seguro de JWT.** El ID token de Google es un JWT que el servidor **nunca decodifica ni acepta por su cuenta**: lo valida Google y el servidor
comprueba `iss`, `aud`, `exp` y `email_verified`; si falta cualquier dato, falla cerrado. La sesión propia **no es un JWT** (es un identificador
opaco en memoria), así que no hay un token firmado que robar o reutilizar tras cerrar sesión. Mejoras conocidas y pendientes: `nonce` contra
repetición, usar el identificador estable `sub` en lugar del correo, y verificar con las claves públicas de Google en vez del endpoint `tokeninfo`.

---

## ES-2 — Protección frente a ataques web y control del ciclo de vida de la sesión

| Parte | Descripción |
|---|---|
| **Fuente** | Una página web maliciosa que el operador tiene abierta, un enlace manipulado, un cliente que retiene conexiones, o una sesión que ya terminó (cierre de sesión o vencimiento). |
| **Estímulo** | (a) Petición que cambia estado (iniciar vigilancia, renombrar cámara, guardar el puesto) o WebSocket desde otro origen o con un `Host` raro (CSRF, DNS rebinding); (b) un enlace con `?ws=` que intenta cambiar el servidor de alertas del panel; (c) una conexión a medias, una descarga que no se lee o un inicio de sesión lento; (d) un socket abierto cuya sesión termina. |
| **Artefacto** | Gateway (servidor HTTP y WebSocket) y panel (`index.html`, `realtime-session.js`). |
| **Entorno** | Operación normal, producción por HTTPS. |
| **Respuesta** | Los cambios exigen `Origin` **y** `Host` del propio panel; el WebSocket rechaza orígenes ajenos; la cookie es `SameSite=Strict`; el panel ignora `?ws=` y fija su servidor por el protocolo y el nombre de la página; el servidor atiende cada petición en un pool de 32 hilos con límites de tiempo del JDK (30 s para recibir una petición, 60 s para entregar una respuesta); al terminar la sesión el servidor cierra los sockets (código 1008), no procesa más comandos y el panel pide iniciar sesión de nuevo. |
| **Medida** | 0 cambios de estado desde un origen ajeno (403 y sin efecto); la URL del WebSocket nunca sale de la query; con una conexión retenida abierta, otro cliente recibe respuesta (las pruebas lo exigen dentro de 3 s y midieron entre 5 y 288 ms; antes del arreglo no hubo respuesta en 3 s) y una ruta barata no espera detrás de inicios de sesión lentos (menos de 1 s; antes, 3,6 s); tras cerrar sesión o vencer, 0 alertas entregadas y 0 `ack` aceptados, y el socket se cierra como máximo en el siguiente barrido (cada 2 s; la prueba admite hasta 8 s). |

**Tácticas.** Limitar acceso (Origin + Host + `SameSite`), limitar exposición (servicios publicados solo en `127.0.0.1`, Caddy como única entrada),
revocar acceso (cierre de sockets al terminar la sesión), limitar el efecto de conexiones retenidas (pool y tiempos).

**Validación ejecutable.**

```powershell
mvn -o -pl realtime-gateway -am test "-Dtest=DashboardHttpServerTest,AlertGatewayTest,AuditLeadWebSocketSessionTest,AuditLeadHttpDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
node --test "security-dashboard/test/realtime-session.test.js"
```

Pruebas clave: `unPostSinOrigenOConOrigenAjenoSeRechazaYNoHaceNada`, `unPostConHostRaroSeRechazaContraDnsRebinding`, `rechazaUnaPaginaConOrigenNoAutorizado`,
`pista2_trasCerrarSesionNoDebeLlegarNingunaAlertaAlSocketYaAbierto`, `pista2_trasCerrarSesionNoDebePoderMarcarAlertasComoRevisadas`,
`pista2_elSocketSeCierraSoloTrasCerrarSesionAunqueNoLleguenAlertas`, `pista3_unaPeticionIncompletaNoDebeBloquearAOtrosClientes`,
`pista3_unaDescargaSinLeerElMp4NoDebeBloquearAOtrosClientes`, y en el panel «un parámetro ?ws= … NO cambia el destino del WebSocket».
Evidencia: `evidencias/es1-es2-pruebas-gateway-y-panel.txt`. Además se verificó a mano en Chrome contra el gateway real que, con `?ws=ws://127.0.0.1:9999`
en la URL, el único socket abierto fue el del propio gateway y que una sesión vencida abre el login con aviso y sin bucle.

---

## ES-3 — Integridad de la evidencia de vídeo (`/frames`)

| Parte | Descripción |
|---|---|
| **Fuente** | Atacante en internet, sin cuenta. |
| **Estímulo** | Se conecta a `wss://<dominio>/frames` y envía imágenes falsas o vacías mientras el tracker vigila, para esconder un retiro o provocar alertas falsas. |
| **Artefacto** | Canal de frames: Caddy → receptor del tracker (`BrowserFrameSource`). |
| **Entorno** | Producción por HTTPS, tracker vigilando con fuente de navegador. |
| **Respuesta** | Caddy pregunta al gateway (`forward_auth` contra `/auth/me`) antes de reenviar; sin sesión responde 401 y la conexión nunca llega al tracker. El receptor sigue rechazando orígenes de navegador ajenos. |
| **Medida** | 100 % de las conexiones sin sesión (con o sin `Origin`, incluso falsificado, y con cookie inventada) → 401; 0 frames de no autenticados llegan al detector; el operador con sesión conecta (101) y su frame llega, sin cambios para él. |

**Hallazgo que motivó el escenario.** El receptor solo rechaza un `Origin` *presente* y distinto: un cliente que no lo manda, o lo falsifica,
inyecta frames (se comprobó con la clase real). El despliegue público publicaba `/frames` sin autenticación.

**Tácticas.** Autenticar actores y limitar acceso en el proxy (`forward_auth`); el receptor conserva su comprobación de `Origin` como segunda capa.

**Validación ejecutable** (Caddy 2.8 en Docker, receptor real, todo en loopback):

```powershell
powershell -ExecutionPolicy Bypass -File docs\seguridad\validaciones\frames-forward-auth\ejecutar.ps1 -Salida docs\seguridad\evidencias\es3-frames-forward-auth.txt
```

Resultado de la última ejecución: **12/12 comprobaciones** (`evidencias/es3-frames-forward-auth.txt`): 7 sobre `/frames` y 5 sobre `/grafana`, que se protege con la misma regla (sin sesión, 401 y la petición **no llega** a Grafana; con sesión, pasa). Incluye el control sin `forward_auth`, donde el atacante sí pasa.
Implementación: bloque `/frames` del `Caddyfile`. **Límites:** el inicio de sesión real con Google y el servidor desplegado no se probaron; antes de la
sustentación hay que comprobar con una sesión real que «Compartir ventana» sigue funcionando después de redesplegar.

---

## Mapa de tácticas (Bass) y brechas conocidas

| Categoría | Táctica | Situación |
|---|---|---|
| Resistir | Identificar / autenticar / autorizar actores | ✔ ES-1 |
| Resistir | Limitar acceso | ✔ ES-1, ES-2, ES-3 |
| Resistir | Limitar exposición | ✔ puertos de la pila publicados solo en `127.0.0.1` y Caddy como única entrada pública; ✔ el panel de métricas (`/grafana`) exige sesión de operador (validado, ES-3); ⚠ pendiente revisar en el despliegue los servicios auxiliares |
| Resistir | Cifrar datos | ✔ HTTPS con Caddy en el despliegue público; ⚠ la cookie no lleva el atributo `Secure` |
| Resistir | Cambiar la configuración por defecto | ✗ pendiente: las credenciales de laboratorio de los compose deben sustituirse en el entorno desplegado; con `/grafana` detrás de la sesión ya no están al alcance de internet, solo de operadores autorizados |
| Reaccionar | Revocar acceso | ✔ ES-2 (cierre de sockets al terminar la sesión) |
| Detectar | Detectar denegación de servicio | ⚠ se limita su efecto (ES-2) pero no hay alertas; pendiente con las reglas de Prometheus |
| Recuperar | Mantener un registro de auditoría | ✗ no se registra quién reconoció una alerta o inició la vigilancia; es la brecha de trazabilidad |

## Origen de los escenarios

Una revisión de seguridad del código del gateway y del panel (18–19 de noviembre, perfil rápido, solo lectura de código) produjo cinco pistas.
Cuatro se reprodujeron con pruebas y se corrigieron (WebSocket sin revocación de sesión, servidor HTTP de un solo hilo, `?ws=` en el panel y puerto
de métricas abierto por defecto). La quinta (el panel envía los frames a cualquier proceso local que escuche en el puerto 8091) sigue abierta y solo
importa en un equipo compartido. ES-3 nace de un hallazgo posterior, al leer el despliegue público: `/frames` expuesto por el proxy y un receptor que no
autentica. La revisión **no** es un análisis exhaustivo: su cobertura es parcial y está declarada en su informe.
