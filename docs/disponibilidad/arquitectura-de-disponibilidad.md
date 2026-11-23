# Disponibilidad y escalabilidad de Safemode (Release 2, sección 2.1)

Qué pide el requerimiento y dónde está cada entregable:

| Entregable | Dónde |
|---|---|
| Escenario de calidad de disponibilidad y escalabilidad | [§1](#1-escenario-de-calidad-ed-1) |
| Componentes y SLA publicados por el proveedor cloud | [§3](#3-componentes-y-sla) |
| Componentes en serie y en paralelo, dependencias y puntos únicos de falla | [§4](#4-serie-paralelo-y-puntos-únicos-de-falla) |
| Disponibilidad total, downtime mensual y anual, interpretación de los «nueves» | [§5](#5-cálculo-de-disponibilidad) |
| Contraste teoría vs. métricas del prototipo, con sus limitaciones | [§6](#6-contraste-con-el-prototipo) |
| Prototipo funcional de escalado con balanceador de carga | [§7](#7-prototipo-qué-escala-y-qué-se-cambió-en-el-código) |
| Despliegue distribuido justificado | [§2](#2-despliegue-distribuido-justificado) |

**Estado, sin adornos.** Hoy el despliegue público es **un solo servidor EC2**; eso incumple «no desplegar todo en un solo servidor». Lo que
existe y está probado es un **prototipo de dos réplicas del gateway detrás de un balanceador con PostgreSQL**, en un solo equipo (Docker
Desktop): demuestra el *comportamiento* (reparto, sesiones compartidas, alertas a todos los operadores, caída y recuperación de una réplica),
no la separación física. La separación física está **diseñada y calculada (§2, §5)**, pero **no desplegada**: requiere recursos en AWS
(un balanceador, una segunda instancia en otra zona y una base administrada) y una decisión del equipo sobre costo.

---

## 1. Escenario de calidad ED-1

| Parte | Descripción |
|---|---|
| **Fuente** | Un fallo interno: una réplica del gateway termina (por error, por falta de memoria o porque se despliega una versión nueva). |
| **Estímulo** | Con operadores conectados por WebSocket y llegando alertas, una réplica deja de responder de golpe (SIGKILL) o termina sola. |
| **Artefacto** | Capa de gateway (HTTP, WebSocket y motor de alertas) detrás del balanceador, con la base compartida. |
| **Entorno** | Operación normal, con carga continua de peticiones y varios operadores con el panel abierto. |
| **Respuesta** | El balanceador la excluye por su vigilancia (activa: `/ready`; pasiva: conexión rechazada) y reintenta en la otra réplica; los operadores de la caída se reconectan solos por el balanceador, con la misma sesión; una alerta creada durante la caída llega a todos (en vivo o en el snapshot al reconectar); la réplica se reinicia sola y vuelve a la rotación. |
| **Medida** | **0** peticiones HTTP fallidas; reconexión de los operadores en **≤ 2,1 s** (incluye los 2 s de espera del panel); **0** alertas perdidas o duplicadas; réplica de nuevo en rotación en **≈ 5 s** si Docker la reinicia (≈ 8 s si hay que arrancarla a mano). |

**Escalabilidad (mismo escenario, otro estímulo).** El número de operadores crece: se añaden réplicas (`--scale realtime-gateway=N`) sin
cambiar código ni configuración; el balanceador las reparte, y cada alerta nueva llega a todos los operadores de todas las réplicas.

**Tácticas (Bass, Clements y Kazman).**

| Categoría | Táctica | Cómo se aplica |
|---|---|---|
| Detectar fallos | Ping / echo y monitor | Vigilancia activa de `/ready` cada 2 s (comprueba que la réplica llegue a la base); métrica `safemode_dependency_up` |
| Recuperarse de fallos | Redundancia activa | Dos réplicas atendiendo a la vez; la base decide la unicidad (`UNIQUE`) |
| Recuperarse de fallos | Reintento / reconexión | Reintento del balanceador en la otra réplica (5 s); el panel reconecta solo; las conexiones a la base se reabren solas |
| Recuperarse de fallos | Reinicio (reintroducción) | `restart: unless-stopped`: Docker reinicia el proceso y la vigilancia lo reincorpora |
| Prevenir fallos | Eliminar el estado local | Sesiones en la base y alertas por sondeo de la base: la réplica no guarda nada que no pueda perder |

---

## 2. Despliegue distribuido justificado

El requerimiento aclara que varios contenedores en un solo equipo no reemplazan la justificación de un despliegue distribuido. El diseño
objetivo, en una región con dos zonas de disponibilidad (AZ):

```
Internet ─► Route 53 ─► Application Load Balancer (multi-AZ)
                          ├──► AZ-a: EC2 con gateway (réplica 1) ─┐
                          └──► AZ-b: EC2 con gateway (réplica 2) ─┤
                                                                  ├──► RDS PostgreSQL Multi-AZ (principal AZ-a, en espera AZ-b)
   Borde (equipo del puesto): tracker + cámara ──── escribe eventos ┘
```

| Qué falla | Con un solo servidor (hoy) | Con el diseño distribuido |
|---|---|---|
| Un proceso del gateway | Panel y alertas caen hasta que Docker lo reinicia | La otra réplica atiende; sin cortes visibles (§6) |
| Una instancia EC2 | **Todo cae** (también la base, Grafana y el tracker en contenedor) | La otra instancia atiende |
| Una zona de disponibilidad | Todo cae si el servidor estaba en esa zona | La otra zona atiende; la base conmuta a la de espera |
| La base de datos | Todo cae y los datos dependen de un disco | RDS Multi-AZ conmuta a la réplica síncrona |
| Mantenimiento o despliegue de una versión | Corte | Se actualiza una réplica a la vez |

**Por qué este reparto y no más.** La capa de gateway es la única que escala por operadores; la base es el límite (§5: el 80 % de la
indisponibilidad restante) y por eso es la que va administrada y en dos zonas. Redis, MongoDB y el servicio de acceso quedan **fuera del
camino crítico de una alerta** (si caen, las alertas siguen funcionando) y no se replican en esta etapa. El tracker es por cámara y corre
en el borde (necesita la pantalla y los modelos): es un punto único **de detección** que ningún SLA de la nube cubre.

---

## 3. Componentes y SLA

SLA **publicados por AWS** (consultados el 19 de noviembre de 2026; ver *Fuentes*).

| Componente | Compromiso de AWS | Uso en el cálculo |
|---|---|---|
| Instancia EC2 individual | **99,5 %** (nivel de instancia) | Hoy, en serie; en el objetivo, cada réplica en paralelo |
| EC2 en dos o más zonas de la región | **99,99 %** (nivel de región) | Sensibilidad |
| Elastic Load Balancing (multi-AZ) | **99,99 %** | En serie (el balanceador mismo es único para el cliente) |
| RDS PostgreSQL Multi-AZ | **99,95 %** | En serie |
| Route 53 | **100 %** (plano de datos) | En serie. *Ver nota* |

Notas: (1) el SLA de ELB tiene además un compromiso de 99,9 % para un balanceador no multi-AZ; se usa el multi-AZ porque es el diseño. (2) De
Route 53 solo se pudo confirmar el texto del compromiso de 100 % en una versión histórica de la página, y la descripción vigente del servicio
habla de un plano de datos «diseñado para» 100 %; con 99,99 % en vez de 100 % el objetivo pasaría de 99,9375 % a 99,9275 % (unos 4 min más de caída al mes). (3) **DuckDNS**, el DNS que se usa
hoy, **no tiene SLA publicado**. (4) Las fotos compartidas entre réplicas necesitarán almacenamiento común (S3 o EFS); su SLA no se incluye
porque no está en el camino crítico de la alerta y no se verificó.

---

## 4. Serie, paralelo y puntos únicos de falla

```
HOY (un servidor):    [ EC2 99,5 % ( Caddy → gateway → PostgreSQL → Grafana → tracker ) ]          todo en serie dentro de una instancia

OBJETIVO (ruta de una alerta):

  Route 53 ──► ALB ──► ┌ EC2 réplica 1 (99,5 %) ┐ ──► RDS Multi-AZ
   100 %      99,99 %   └ EC2 réplica 2 (99,5 %) ┘      99,95 %
                          └─ paralelo ─┘
         serie           paralelo (redundancia)          serie
```

| Elemento | Relación | Dependencia | ¿Punto único de falla? |
|---|---|---|---|
| Route 53 / DNS | Serie | — | No con Route 53 (hoy **sí**: DuckDNS) |
| Balanceador | Serie | Réplicas sanas | Mitigado: servicio administrado multi-AZ. **En el prototipo, Caddy es único** |
| Gateway (2 réplicas) | **Paralelo** | Base de datos | No: cada una atiende todo (sesiones y alertas en la base) |
| PostgreSQL | Serie | Disco / zona | **Sí**, el de mayor peso: mitigado con Multi-AZ; en el prototipo es una sola instancia |
| Tracker (por cámara) | Serie para la detección | Cámara, pantalla, modelos, equipo del puesto | **Sí** (borde); mitigado solo con reinicio automático |
| Redis, MongoDB, acceso | Fuera del camino crítico | — | No para las alertas; sí para la configuración compartida del puesto |
| Almacenamiento de fotos | Fuera del camino crítico | — | Las fotos fallan, la alerta no |

---

## 5. Cálculo de disponibilidad

Fórmulas del requerimiento: serie `A = A₁·A₂·…`; paralelo `A = 1 − (1−A₁)(1−A₂)…`; downtime mensual `(1−A)·30 d`; anual `(1−A)·365 d`.
Reproducible con `node docs/disponibilidad/calcular-disponibilidad.mjs`.

| Arquitectura | Disponibilidad | Nueves | Downtime mensual | Downtime anual |
|---|---|---|---|---|
| **Hoy:** un servidor con todo | 99,5000 % | 2,30 | **3,6 h** | **43,8 h** |
| Hoy + 2 réplicas en el **mismo** servidor | 99,5000 % | 2,30 | 3,6 h | 43,8 h |
| **Objetivo:** DNS → ALB → 2 gateways (2 zonas) → RDS Multi-AZ | **99,9375 %** | **3,20** | **27,0 min** | **5,47 h** |
| ↳ capa de gateway con el SLA regional de EC2 (99,99 %) | 99,9300 % | 3,15 | 30,2 min | 6,13 h |
| ↳ tres réplicas del gateway | 99,9400 % | 3,22 | 25,9 min | 5,26 h |
| ↳ PostgreSQL en una sola instancia (sin Multi-AZ) | 99,4876 % | 2,29 | 3,7 h | 44,9 h |

Capa de gateway sola: 1 réplica 99,5 % (3,6 h/mes) → 2 réplicas 99,9975 % (1,1 min/mes) → 3 réplicas 99,99999 % (< 1 s/mes).

**Interpretación.**

- Pasar de **2,3 a 3,2 «nueves»** (99,5 % → 99,94 %) reduce el downtime de unas **3,6 h a unos 27 min al mes** (≈ 8 veces menos).
- **Dos réplicas en el mismo servidor no mejoran nada** (fila 2): comparten la instancia, la zona y el disco. Es la razón por la que el prototipo en un
  solo equipo **no** es la respuesta a la disponibilidad, solo la prueba de comportamiento.
- **El cuello de botella es la base de datos:** pesa el 80 % de la indisponibilidad del objetivo, el balanceador el 16 % y los gateways el 4 %. Una
  tercera réplica casi no cambia el total; con PostgreSQL sin Multi-AZ la mejora desaparece (fila 6: 99,49 %, peor que hoy).
- El cálculo trata los fallos como **independientes**; un error de software que tumbe a las dos réplicas a la vez (por ejemplo una versión
  mala) no está en los SLA y las derribaría juntas. Los SLA además miden la infraestructura, no que *nuestra* aplicación funcione.

---

## 6. Contraste con el prototipo

Medido el 19 de noviembre de 2026 con [`ScaleProbe`](validaciones/escalado/ScaleProbe.java) (tres corridas seguidas, 17 de 17 comprobaciones cada una; la
evidencia completa está en [`evidencias/escalado-gateway.txt`](evidencias/escalado-gateway.txt)). Caddy 2.8, PostgreSQL 16.6, 2 réplicas, 6 operadores.

| Medida | Resultado |
|---|---|
| Reparto de 30 peticiones con sesión (una sola cookie creada en la base) | 15 / 15 entre las dos réplicas, 30 de 30 con 200 |
| Sockets de los 6 operadores | 3 y 3 por réplica |
| Alerta nueva → los 6 operadores | exactamente una vez cada uno; 0,23 s a la mitad de los operadores y 0,42 s a la otra mitad (medido desde antes del `INSERT`, incluye ~0,3 s de `docker exec`); la diferencia de ~0,2 s coincide con el sondeo de 0,5 s de la réplica que no insertó (en otra corrida fue 0,21 s frente a 0,71 s) |
| Caída de golpe (SIGKILL) con tráfico | **0 de 555 peticiones fallaron**, p50 45 ms, p99 250 ms, la más lenta 0,25 s |
| Reconexión de los operadores de la réplica caída | 2,07 s el más lento (2 s son la espera del panel) |
| Alerta creada **durante** la caída | 3 la recibieron en vivo y 3 al reconectar (snapshot); ninguna perdida ni duplicada |
| Réplica de nuevo en rotación | 5,2 s tras la caída si Docker la reinicia (reinicios 0 → 1); 8,3 s si se arranca a mano |
| Registros de las réplicas durante la prueba | 0 líneas de error o aviso |
| Arranque en frío de las dos réplicas a la vez | 0 reinicios (ninguna falló al crear las tablas) |

**Disponibilidad observada: 100 %** durante los dos fallos. **No** es un cuarto o quinto «nueve»: con 0 fallos en 555 peticiones solo se puede afirmar,
con 95 % de confianza (regla de los tres), una disponibilidad de **al menos 99,46 %**. La muestra mide el *comportamiento ante fallos*, no la
disponibilidad de largo plazo; para eso haría falta observar semanas y registrar las caídas reales.

**Limitaciones del entorno de prueba (declaradas):**

1. **Un solo equipo.** Las réplicas comparten CPU, red y disco: no se prueba una caída de zona ni de máquina, solo de proceso. La red entre contenedores no tiene la latencia ni los fallos de una red real.
2. **El balanceador y la base son únicos** en el prototipo (Caddy y una instancia de PostgreSQL): son puntos únicos de falla que la arquitectura objetivo elimina con servicios administrados, pero que aquí no se probaron caer.
3. **Fallos inyectados por proceso** (`docker kill` y `SIGTERM`), no fallos de hardware, de red ni degradaciones parciales.
4. **Carga baja** (6 operadores, 4 usuarios de HTTP). No es una prueba de rendimiento; esa es la de k6.
5. **No se probó con inicio de sesión real de Google, con HTTPS ni con un ALB real:** la sesión de prueba es una fila insertada en la base del prototipo.
6. **El panel no se probó en un navegador contra este balanceador**; los operadores simulados reproducen su comportamiento de reconexión (espera de 2 s, `/auth/me`, reconexión).
7. **Tras volver, la réplica no recupera operadores:** los sockets no se redistribuyen (tras los dos fallos los 6 quedaron en una sola réplica); un nuevo fallo desconecta a todos hasta que se reconectan (≈ 2 s).

---

## 7. Prototipo: qué escala y qué se cambió en el código

Para que dos réplicas pudieran convivir hubo que corregir **tres defectos reales** (cada uno reproducido con una prueba que falló antes de arreglarlo;
detalle y alternativas descartadas en el [ADR 0006](../adr/0006-escalado-horizontal-del-gateway.md)):

| Defecto | Corrección | Prueba |
|---|---|---|
| Una réplica solo avisaba de las alertas que ella misma insertaba | Sondeo de la base cada 0,5 s (`AlertGateway.announceAlertsFromOtherReplicas`) y registro de lo ya anunciado | `AlertGatewayReplicasTest` (3) |
| Carrera `SELECT` + `INSERT` entre réplicas | Violación de unicidad (`23505`) = «ya existe» (`AlertStore.insertIfAbsent`) | `TwoReplicasTest` (fallaba 3 de 3) |
| Sesiones en memoria de cada proceso | `SAFEMODE_SESSION_STORE=database`: `operator_session` con la huella SHA-256 del identificador (`JdbcSessionStore`) | `SharedSessionsTest` (8) |

Sin configurar nada el producto se comporta exactamente como antes (la variable es opcional; el estado actual del despliegue público no cambia). Regresión
completa tras los cambios: 182 pruebas del gateway y las de `alert-engine`, 0 fallos (3 de integración con PostgreSQL omitidas: solo corren si se les da una base).

**Archivos:** [`docker-compose.escalado.yml`](../../docker-compose.escalado.yml) (se suma a los otros dos y corre aparte del stack normal) ·
[`Caddyfile.escalado`](../../Caddyfile.escalado) · [`validaciones/escalado/`](validaciones/escalado/).

**Cómo reproducirlo:**

```powershell
# Todo en uno: levanta el prototipo, corre la validación, guarda la evidencia y lo baja (≈ 2 min; la primera vez construye la imagen)
powershell -ExecutionPolicy Bypass -File docs\disponibilidad\validaciones\escalado\ejecutar.ps1 -Salida docs\disponibilidad\evidencias\escalado-gateway.txt
# Solo levantarlo (más réplicas: --scale realtime-gateway=3, y añadir la tercera en Caddyfile.escalado)
docker compose -p safemode-escalado -f docker-compose.yml -f docker-compose.persistencia.yml -f docker-compose.escalado.yml up -d --build --scale realtime-gateway=2 --wait postgres realtime-gateway balanceador
```

---

## 8. Pendientes y riesgos conocidos

- **Despliegue físico distribuido** (ALB, segunda instancia en otra zona, RDS Multi-AZ): sin hacer; decide el equipo (costo, cuenta de AWS).
- **Fotos compartidas:** hoy las lee un volumen del equipo; con réplicas en máquinas distintas necesitan almacenamiento común (S3 o EFS).
- **Prometheus con dos réplicas:** el objetivo `realtime-gateway:9464` se resuelve por DNS a dos direcciones; hay que pasarlo a descubrimiento por DNS (`dns_sd_configs`) para ver cada réplica por separado. No se tocó la observabilidad del equipo.
- **Aviso externo (Telegram):** lo emite solo la réplica que inserta la alerta; si muere justo entre insertar y avisar, ese aviso no sale (el panel sí la muestra).
- **Base caída:** con las sesiones en la base, los operadores quedan desconectados hasta que vuelve (falla cerrado, por seguridad).
- **Arranque simultáneo sobre una base vacía:** dos réplicas ejecutan `CREATE TABLE IF NOT EXISTS` a la vez; no falló en 3 arranques en frío, pero PostgreSQL no lo garantiza. Docker reiniciaría a la que falle.
- **Tracker:** un punto único por cámara, fuera del alcance de los SLA de la nube.
- **Redis y MongoDB** sin réplicas (fuera del camino crítico de la alerta).
- **Disponibilidad medida a largo plazo:** no existe; el 99,9375 % es teórico.

## Fuentes

- [Amazon Compute Service Level Agreement](https://aws.amazon.com/compute/sla/) (EC2: 99,99 % por región, 99,5 % por instancia)
- [Amazon Elastic Load Balancing Service Level Agreement](https://aws.amazon.com/elasticloadbalancing/sla/) (99,99 % multi-AZ; 99,9 % balanceador individual)
- [Amazon RDS Service Level Agreement — versiones anteriores](https://aws.amazon.com/rds/sla/historical/) (Multi-AZ 99,95 %; la página vigente debe confirmarse)
- [Amazon Route 53 SLA — versiones anteriores](https://aws.amazon.com/route53/sla/historical/) (100 %; texto vigente por confirmar)
- Bass, Clements, Kazman — *Software Architecture in Practice* (tácticas de disponibilidad)
