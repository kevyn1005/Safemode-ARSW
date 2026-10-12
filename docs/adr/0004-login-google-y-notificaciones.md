# ADR 0004: inicio de sesion con Google y notificaciones (navegador, Telegram y correo)

## Estado
Parcialmente implementado (oct-2026): el **aviso del navegador** y el **servicio de notificaciones con el canal de Telegram**. Falta el
inicio de sesion con Google, las preferencias de canales por usuario, la vinculacion de Telegram por enlace de un solo uso, el canal de
correo y el registro `notification_log`. El resto del documento fija el diseno acordado por el equipo.

## Contexto
Hoy una alerta solo se entera quien esta mirando el panel, y el panel no tiene usuarios: la unica proteccion es que el
gateway escucha en `localhost` y revisa el `Origin` del navegador. Para un centro de vigilancia hace falta (1) saber
**quien** es el operador, y (2) que le **llegue el aviso aunque no este mirando el panel**, como cuando llega un mensaje
a WhatsApp Web o Instagram abiertos en el navegador. Ademas la pantalla de "Iniciar sesion como operador" del panel
rediseñado es hoy solo una maqueta.

## Decision

### 1. Inicio de sesion con Google
- Se usa **Google Identity Services (OpenID Connect)**. Es gratis para probar y acepta `http://localhost` como origen. El
  ID de cliente va en la variable de entorno `SAFEMODE_GOOGLE_CLIENT_ID` (no en el repo).
- El panel recibe el *ID token* de Google y el **gateway lo verifica en el servidor** (firma con las claves publicas de
  Google, `aud` igual a nuestro ID de cliente, no vencido y correo verificado). Con eso crea una **sesion corta** (cookie
  `HttpOnly`, `SameSite=Lax`). Un boton "Iniciar sesion" sin verificacion no seria seguridad.
- **Solo entran los correos autorizados**: lista blanca (`SAFEMODE_ALLOWED_EMAILS`, luego una tabla). Si no, cualquier
  persona con cuenta de Google podria ver las alertas.
- La sesion protege **toda** la superficie: la pagina, el WebSocket (se valida la sesion en el *handshake*, ademas del
  `Origin`), marcar una alerta como revisada, y las rutas `/tracker/*` (hoy solo protegidas por `Origin` y `Host`).
- Se guarda una tabla `app_user` (correo, nombre, fecha). No se guardan contrasenas: las maneja Google.

### 2. Servicio de notificaciones
- Un **Notification Service** se engancha al motor de alertas (`AlertEngine.addListener`, misma JVM del gateway) y avisa
  **solo de alertas nuevas** (no de las actualizaciones por IA ni de las marcadas como revisadas).
- **Solo severidad ALTA y MEDIA** notifican (`POSSIBLE_THEFT`, `AMBIGUOUS_REMOVAL`, `OBJECT_VANISHED`,
  `POSSIBLE_OWNER_RETURN`). La BAJA
  (`UNKNOWN_OWNER_REMOVAL`) queda solo en el panel, para no saturar al operador.
- Cada aviso se envia **una sola vez por alerta, usuario y canal** (registro `notification_log`, unico por esos tres
  datos), en un pool aparte: **un canal que falla o tarda nunca retrasa ni bloquea la alerta ni a los otros canales**
  (mismo principio que la IA en segundo plano). Reintentos limitados y el fallo queda registrado.

### 3. Canales (el usuario escoge cuales usar)
| Canal | Como funciona | Costo |
|---|---|---|
| **Navegador** | El panel, al recibir la alerta por WebSocket, muestra una notificacion del sistema (API de notificaciones) pidiendo permiso una vez. Funciona con la pestana abierta, aunque este en segundo plano. *Web Push* (con la pestana cerrada) queda como mejora futura | Gratis |
| **Telegram** | Un *bot* manda un mensaje al usuario. Se vincula con un enlace de un solo uso (`t.me/<bot>?start=<token>`) ligado a su sesion; se guarda su `chat_id` | Gratis |
| **Correo** | SMTP (por ejemplo Gmail con contrasena de aplicacion) al correo de su cuenta de Google, sin registro extra | Gratis para probar |
- Descartados: **SMS** (de pago o con pruebas muy limitadas) y **WhatsApp** (API de pago y tramites).
- Cada usuario activa o desactiva sus canales (tabla `notification_pref`). En el MVP recibe las alertas de **todas** las
  camaras (sin filtro por camara).

### 4. Contenido del aviso: solo texto, sin datos de personas
- Ejemplo: `SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.`
- **No se envian fotos, descripciones de personas ni identidades** por Telegram ni por correo: pasarian por un tercero.
  El detalle y la evidencia se ven solo en el panel, ya con sesion iniciada.

### 5. Secretos
- `SAFEMODE_GOOGLE_CLIENT_ID`, `SAFEMODE_TELEGRAM_BOT_TOKEN`, `SAFEMODE_SMTP_HOST/USER/PASSWORD` y la lista blanca van en
  **variables de entorno**, nunca en el codigo, los registros ni el repo (igual que `NVIDIA_API_KEY`).

### 6. Estructura del codigo
- Interfaz `NotificationChannel` (como `FrameSource`): `TelegramChannel` y `EmailChannel` implementan el envio; el aviso
  del navegador lo genera el panel. Asi un canal nuevo no toca el resto y las pruebas usan un canal falso.
- Vive en `realtime-gateway`, junto al motor de alertas. En la arquitectura To-Be se dibuja como componente
  **Notification Service**, con Google, Telegram y el correo como **servicios externos**.

## Criterio de aceptacion (Gherkin) de ejemplo
```
Given un usuario con sesion iniciada que activo el canal Telegram
When se genera una alerta de severidad alta
Then recibe un mensaje de texto sin fotos que le dice que revise el panel para verificar
 And no recibe un segundo mensaje por la misma alerta
```

## Consecuencias
- Aparecen **datos personales** (correo, `chat_id`): hay que decidir retencion y borrado, y no registrarlos en los logs.
- Dependencia de servicios externos (Google, Telegram, SMTP): pueden fallar o tener limites (Gmail limita envios diarios;
  en Telegram el usuario debe pulsar "Iniciar" una vez). Las pruebas se hacen con servidores falsos locales, sin red, como
  ya se hace con NVIDIA.
- El login con Google necesita registrar en Google Cloud los origenes `http://localhost:8080` (y el de Docker si aplica).
- Se cierra la brecha de seguridad actual del panel (acciones solo protegidas por `Origin`).
- Cambia la sustentacion: componente nuevo, sistemas externos y HU nuevas en el Release Plan (sprint 8).

## Implementacion (que quedo hecho)
- **Navegador**: `security-dashboard/browser-notifications.js` (boton "Avisos del navegador", permiso pedido con un clic, preferencia en
  `localStorage`). Los paneles lo llaman solo con los mensajes `alert` del WebSocket (no con `snapshot` ni `update`). Con la pestana
  abierta; Web Push sigue como mejora futura. La logica de formato es pura y se prueba con Node.
- **Servicio** (`realtime-gateway`, paquete `com.safemode.gateway.notify`): `NotificationService` se engancha con
  `AlertEngine.addListener`; solo avisan ALTA y MEDIA; un aviso por alerta, canal y destinatario; todo en un grupo de hilos aparte
  (`onAlert` vuelve al instante y un canal lento no frena a los demas); hasta 3 intentos con espera entre ellos sin bloquear hilos;
  el fallo se registra sin claves, URLs ni destinatarios.
- **Canal Telegram** (`NotificationChannel` -> `TelegramChannel`): `sendMessage` en texto plano (sin `parse_mode`), solo `chat_id`, `text` y
  `disable_web_page_preview`. `SAFEMODE_TELEGRAM_BOT_TOKEN` y `SAFEMODE_TELEGRAM_CHAT_IDS` solo por variables de entorno; la clave se
  valida (`numeros:letras`) porque va dentro de la URL, y los mensajes de error nunca la llevan. `TelegramChatIds` ayuda a averiguar el
  numero del chat. **Hasta que exista el login, los chats son una lista fija** (no una vinculacion por usuario).
- **Texto**: `SafeMode: posible robo en CAM 1 a las 15:42. Revisa el panel para verificar.` Nada de fotos, descripciones, rutas ni el
  mensaje de la alerta.
- **Pruebas**: servidor falso local (sin red ni claves reales), canal falso para el servicio y navegador falso para Node. Verificado con el
  panel real: la alerta alta y la media mostraron su notificacion y la baja no.

## Pendientes
- El equipo crea el proyecto de Google Cloud, el bot de Telegram y la contrasena de aplicacion de correo.
- Decidir si algun operador sin cuenta de Google necesita otra forma de entrar.
- Mejora futura: Web Push, filtro por camara y por severidad por usuario.
