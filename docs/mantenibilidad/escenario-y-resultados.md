# Mantenibilidad de Safemode (Release 2, sección 2.3)

Qué pide el requerimiento y dónde está cada entregable:

| Entregable | Dónde |
|---|---|
| Escenario de calidad: inspección continua mediante análisis estático | [§1](#1-escenario-de-calidad-mt-1) |
| SonarQube/SonarCloud integrado al CI/CD con GitHub Actions y *reusable workflows* | [§2](#2-el-pipeline-y-sus-workflows-reutilizables) |
| Cobertura ≥ 80 % en backend y en frontend, **la de las pruebas que se ejecutan** | [§3](#3-cobertura-medida) |
| Estado general A / Verde, evidencia del pipeline y controles aplicados | [§4](#4-análisis-estático-y-control-de-calidad), [§6](#6-lo-que-falta-y-solo-puede-hacer-el-equipo) |
| Defectos que las pruebas encontraron | [§5](#5-defectos-reales-que-las-pruebas-encontraron) |

**Estado, sin adornos.** La cobertura está **medida y cumple** en las dos capas (backend 81,7 % de líneas; panel 99,3 %), el pipeline con *reusable workflows*
está escrito y validado (`actionlint`), y el control de calidad corta el despliegue si falla. **Medido con un SonarQube local (20-nov-2026): control de calidad en
VERDE, cobertura 85,9 %, mantenibilidad A, duplicación 0 %, pero fiabilidad C (4 errores) y seguridad C (4 vulnerabilidades): el requerimiento pide A y está
pendiente de corregir** ([§4](#4-análisis-estático-y-control-de-calidad)). **Lo que no está hecho:** una corrida real en SonarCloud (requiere la cuenta, el token y dos
variables del repositorio, que solo puede crear el equipo).

---

## 1. Escenario de calidad MT-1

| Parte | Descripción |
|---|---|
| **Fuente** | Una persona del equipo (o una herramienta) que sube un cambio al repositorio |
| **Estímulo** | `git push` a cualquier rama o ejecución manual del *workflow* |
| **Artefacto** | El código fuente: backend Java (8 módulos Maven) y panel en JavaScript |
| **Entorno** | Integración continua en GitHub Actions, antes de cualquier despliegue |
| **Respuesta** | El pipeline compila, ejecuta las pruebas y mide la cobertura de cada capa; un análisis estático (SonarCloud) evalúa errores, vulnerabilidades, olores y duplicación sobre el código y la cobertura **de esa corrida**; si el control de calidad no pasa, el job **falla** y el despliegue (`deploy`, que depende de `quality`) no se ejecuta |
| **Medida** | Cobertura **≥ 80 %** en backend y **≥ 80 %** en panel; fiabilidad, seguridad y mantenibilidad en **A**; control de calidad en **verde**; retroalimentación en el mismo `push` |

**Por qué estas medidas.** Son las que el requerimiento fija. La cobertura sale de las pruebas que *de verdad* se ejecutan en el pipeline (JaCoCo en
`mvn verify`; c8 con `--all`, que también cuenta los archivos que ninguna prueba carga), no de un número escrito a mano: el informe de Sonar usa los mismos
`jacoco.xml` y `lcov.info` que se generaron en esa corrida.

## 2. El pipeline y sus workflows reutilizables

```
push ─► pipeline.yml ─┬─► backend   (reusable-backend.yml)   mvn verify: compila + pruebas + JaCoCo   ─► artefacto backend-analisis
                      ├─► frontend  (reusable-frontend.yml)  npm ci + c8: pruebas del panel + lcov    ─► artefacto frontend-cobertura
                      └─► quality   (reusable-sonar.yml)     descarga ambos artefactos y analiza ─► control de calidad ─► deploy
```

| Workflow | Qué hace | Entradas |
|---|---|---|
| [`pipeline.yml`](../../.github/workflows/pipeline.yml) | Orquesta: llama a los tres reutilizables con `uses:` y encadena `deploy` (el de Juli, sin cambios) después de `quality` | — |
| [`reusable-backend.yml`](../../.github/workflows/reusable-backend.yml) | Java 17 (Temurin) y `mvn -B verify`; sube los resultados de las pruebas, los `jar` y lo que necesita el análisis | `java-version` |
| [`reusable-frontend.yml`](../../.github/workflows/reusable-frontend.yml) | Node 24, `npm ci`, `npm run test:coverage`; sube `lcov.info` | `node-version` |
| [`reusable-sonar.yml`](../../.github/workflows/reusable-sonar.yml) | Trae las clases compiladas y la cobertura de las otras dos etapas y ejecuta el análisis con `sonar.qualitygate.wait=true` | `organization`, `project-key`, `host-url`; secreto `SONAR_TOKEN` |

Los tres son *reusable workflows* (`on: workflow_call`): cualquier otro *workflow* del repositorio los puede invocar. La configuración del análisis es un solo archivo,
[`sonar-project.properties`](../../sonar-project.properties), que sirve igual para SonarCloud y para un SonarQube propio. **Control de calidad aplicado:** con la configuración
completa el job `quality` **falla** si el control de calidad no pasa; sin ella, se omite con un aviso (no rompe a quien aún no configuró la cuenta).

## 3. Cobertura medida

### Backend (JaCoCo, `mvn -o verify`: todas las pruebas en verde)

| Módulo | Líneas cubiertas | % líneas | Ramas cubiertas | % ramas |
|---|---|---|---|---|
| object-presence-tracker | 1.525 / 1.635 | 93,3 % | 699 / 832 | 84,0 % |
| realtime-gateway | 1.100 / 1.193 | 92,2 % | 526 / 600 | 87,7 % |
| alert-engine | 147 / 169 | 87,0 % | 39 / 45 | 86,7 % |
| camera-feed-service | 77 / 86 | 89,5 % | 23 / 28 | 82,1 % |
| vision-detection-service | 143 / 183 | 78,1 % | 46 / 54 | 85,2 % |
| service-common | 192 / 204 | 94,1 % | 68 / 84 | 81,0 % |
| collaboration-service | 171 / 482 | 35,5 % | 138 / 213 | 64,8 % |
| access-service | 8 / 164 | 4,9 % | 6 / 42 | 14,3 % |
| **Total** | **3.363 / 4.116** | **81,7 %** | **1.545 / 1.898** | **81,4 %** |

El **núcleo del producto** (los cinco primeros módulos del Release 1) está en **91,6 %** de líneas. Los dos módulos de persistencia del Release 2 tienen poca cobertura *por pruebas
unitarias* porque sus adaptadores (`AccessRepository` → PostgreSQL, `CollaborationApi`/`PuestoStore` → MongoDB, `RedisPresenceStore` → Redis) solo se prueban de verdad contra las bases
reales; esa verificación existe (laboratorio de consistencia y concurrencia, `docs/persistencia/`) pero **no corre en el pipeline**, así que no cuenta para este porcentaje. El margen sobre el
80 % es de **1,7 puntos**: ajustado; es honesto decir que depende de que esos adaptadores no crezcan sin pruebas.

### Qué NO se cuenta y por qué

Se excluyen de la cobertura (en `pom.xml` para JaCoCo y en `sonar-project.properties` para Sonar: **la misma lista**, para que ambas cuenten lo mismo) únicamente los programas de arranque y las
herramientas manuales, que necesitan cámara, pantalla, claves o red y no contienen lógica del producto: `GatewayRunner`, `AccessServiceMain`, `CollaborationMain`, `AlertEngineRunner`,
`ObjectPresenceTrackerTest` (prueba manual con cámara), `DetectionBenchmark`, `AlertLatencyBenchmark`, `FrameCapturerTest`, `ScreenCaptureTest`, `VisionDetectionTest`, `TelegramChatIds`,
`TelegramTestMessage`, `NvidiaCropCheck` y `MousePositionFinder`. Ninguna clase del núcleo se excluye por tener poca cobertura.

### Panel (JavaScript, c8 con `--all` y jsdom)

| Métrica | Resultado |
|---|---|
| Líneas / sentencias | **99,3 %** (1.636 / 1.647) |
| Ramas | 91,2 % (676 / 741) |
| Funciones | 99,2 % (125 / 126) |

Antes de este trabajo el pipeline ejecutaba `npm test --if-present`, que **no hacía nada** (el `package.json` no definía el script): no había ninguna medición. La primera medición real, con
`--all`, dio **41 %**; con las pruebas nuevas (control del tracker, nombres de cámara, reporte, avisos del navegador y compartir cámara, sobre jsdom) subió a 99,3 %.
**Límite honesto:** el *script* en línea de `index.html` (≈650 líneas) no está medido porque no es un módulo que se pueda cargar en una prueba; el cálculo cubre los archivos `.js` del panel.

## 4. Análisis estático y control de calidad

- `sonar-project.properties` define fuentes, pruebas, exclusiones y las rutas de cobertura (`**/target/site/jacoco/jacoco.xml` y `security-dashboard/coverage/lcov.info`) para Java y JavaScript en un solo proyecto.
- **Corrida local** para ver calificaciones y control de calidad sin esperar al pipeline: [`analizar-local.ps1`](analizar-local.ps1) (contra un SonarQube propio con `docker-compose.sonar.yml`; el token lo crea quien
  la ejecuta y no se guarda en ningún archivo ni se imprime). Imprime el estado del control de calidad, la cobertura, las calificaciones de fiabilidad, seguridad y mantenibilidad y **la lista de problemas** (tipo, gravedad,
  regla, archivo y línea); con `-SoloProblemas` solo lista, sin volver a analizar.
- **Resultado de la corrida local** (SonarQube Community Build 26.11, perfil «Sonar way», 164 archivos, 10.588 líneas de código; el análisis importó los 8 informes de JaCoCo y el `lcov.info` del panel):

| Medida | Resultado | ¿Cumple? |
|---|---|---|
| Control de calidad | **OK (verde)** | Sí |
| Cobertura (backend + panel, como la calcula Sonar) | **85,9 %** (líneas 86,7 %) | Sí (≥ 80 %) |
| Mantenibilidad (deuda técnica) | **A** (5 «olores de código») | Sí |
| Duplicación | 0,0 % | Sí |
| Puntos de seguridad por revisar (*hotspots*) | 0 | Sí |
| **Fiabilidad** | **C** (4 errores) | **No: se pide A** |
| **Seguridad** | **C** (4 vulnerabilidades) | **No: se pide A** |

  El control de calidad pasa porque el «Sonar way» exige las calificaciones A solo sobre el código *nuevo*; el requerimiento del curso pide estado general A, así que **hay que
  corregir los 4 errores y las 4 vulnerabilidades**. Se listan con `analizar-local.ps1 -SoloProblemas` (regla, archivo, línea y mensaje) y su corrección se anota aquí.
- La cobertura de Sonar (85,9 %) es mayor que la de §3 (81,7 % del backend) porque Sonar mezcla el backend con el panel (99,3 %); por capa, las cifras de §3 son las que corresponden a cada requisito.

## 5. Defectos reales que las pruebas encontraron

Medir la cobertura del panel obligó a probar comportamiento que nadie había ejercitado, y apareció código roto:

| Dónde | Defecto | Arreglo |
|---|---|---|
| `tracker-control.js` | Al fallar una acción (iniciar o parar la vigilancia), el mensaje de error se **borraba** al volver a pintar el botón: el operador no veía por qué no pasó nada | El error se queda visible hasta el siguiente clic |
| `camera-share.js` | Si el navegador cortaba la captura con el diálogo de zonas abierto, el diálogo **seguía abierto** con el video muerto y cada clic lanzaba un `TypeError` | `stopSharing` cierra el diálogo |
| `AlertGateway` (backend) | Al optimizar el barrido de alertas se introdujo una regresión (se construía el JSON *antes* de comprobar si la alerta ya se conocía); la detectó la medición de rendimiento | Se comprueba primero lo barato (ver `docs/rendimiento/informe-k6.md`) |

## 6. Lo que falta y solo puede hacer el equipo

1. **Crear el proyecto en SonarCloud** (organización y proyecto), generar un **token** y guardarlo como secreto del repositorio `SONAR_TOKEN`.
2. Crear las **variables de repositorio** `SONAR_ORGANIZATION` y `SONAR_PROJECT_KEY`.
3. Hacer un `push` y comprobar el job **Quality** en GitHub Actions; ese es el enlace/evidencia del pipeline y del análisis que pide el requerimiento. Si el control de calidad marca algo en rojo, corregirlo antes de la sustentación.
4. Corregir los 4 errores y 4 vulnerabilidades que dejaron fiabilidad y seguridad en C (el análisis local ya las muestra, [§4](#4-análisis-estático-y-control-de-calidad)) y volver a analizar hasta ver A.
5. Las **pruebas de integración contra PostgreSQL, MongoDB y Redis** (tres pruebas de `alert-engine` se omiten sin base de datos) se podrían correr en el pipeline con *service containers*; hoy no corren ahí.
