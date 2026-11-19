# Validación del escenario ES-3: integridad del canal de frames (`/frames`)

**Qué demuestra.** El receptor de frames del tracker (`BrowserFrameSource`) **no autentica a nadie**: solo rechaza un `Origin` *presente* y
distinto, así que un cliente que no manda `Origin` (o lo falsifica) puede inyectar imágenes. Como el despliegue público publica
`wss://<dominio>/frames` a través de Caddy, la táctica es **autenticar en el proxy** antes de reenviar: `forward_auth` pregunta al gateway
(`GET /auth/me`, que solo mira la cookie de sesión) y solo deja pasar a quien tiene sesión.

**Qué hace esta prueba (todo en loopback, sin tocar ningún servidor remoto).**

```
cliente  ->  Caddy 2.8 (contenedor, :18080)  --forward_auth-->  /auth/me   (servicio que imita al gateway: 200 solo con cookie buena)
                                             --reverse_proxy->  BrowserFrameSource REAL (:18091)
```

Hay 3 rutas en `Caddyfile.prueba`: `/frames-open` (sin protección, como estaba), `/frames` (con `forward_auth` contra el servicio que imita
`/auth/me`) y `/frames-real` (con `forward_auth` contra el **gateway real** del stack, puerto 8080, si está levantado).

## Cómo correrla

Requisitos: Docker en marcha, Java 17+, Node, y las dependencias de Maven en `~/.m2`. Desde la raíz del repo:

```powershell
powershell -ExecutionPolicy Bypass -File docs\seguridad\validaciones\frames-forward-auth\ejecutar.ps1 -Salida docs\seguridad\evidencias\es3-frames-forward-auth.txt
```

El script descarga `caddy:2.8-alpine` si falta, levanta todo, ejecuta los seis escenarios, **comprueba** los resultados esperados, limpia
(contenedor y procesos) y devuelve código de salida 1 si algo falla. Usa los puertos 18080, 18081 y 18091.

## Resultado esperado

| | Cliente | Resultado esperado |
|---|---|---|
| A | atacante sin `Origin` ni cookie, por `/frames` | bloqueado por Caddy, HTTP 401 |
| B | atacante con `Origin` falsificado, sin cookie | bloqueado, HTTP 401 |
| C | atacante con `Origin` falso y cookie inventada | bloqueado, HTTP 401 |
| D | operador: `Origin` del panel + cookie válida | conecta (101) y su frame llega al receptor |
| E | control: atacante por `/frames-open` (sin `forward_auth`) | **conecta y su frame llega** (el hueco original) |
| F | atacante contra el gateway real como autenticador, por `/frames-real` | bloqueado, HTTP 401 |

## Límites (para no sobrevender la evidencia)

- El servicio de sesión de D es un **simulacro** con el contrato de `/auth/me` (200 con cookie buena, 401 sin ella). No se probó un inicio de
  sesión real con Google; con el gateway real solo se demuestra el caso negativo (F).
- Se probó en Docker Desktop (el contenedor llega al host por `host.docker.internal`). En Linux, el receptor y el gateway tendrían que escuchar
  en una dirección que el contenedor alcance; en el servidor real Caddy usa la red del host (`network_mode: host`) y apunta a `127.0.0.1`.
- No se probó contra el servidor desplegado. Antes de la sustentación, quien redespliegue debe comprobar con una sesión real que
  «Compartir ventana» sigue funcionando.
