# Azure DevOps: cómo quedó el proyecto y cómo repetirlo

Proyecto: `https://dev.azure.com/heverbarrera/SafeMode` (Boards → Sprints / Work items / Backlogs).

## Estado a 20-nov-2026

| Sprint | Fechas (2026) | Historias | Qué contiene |
|---|---|---|---|
| 1 | 7-12 sep | 1 (la original HU-01) | Detección de personas y objetos |
| 2 | 21-25 sep | 15 (la original HU-02 + 14) | Reposo, retiro, dueño, alertas con evidencia, panel en tiempo real, alarma sonora |
| 3 | 28 sep-2 oct | 2 (la original HU-03 + HU-36) | Back y front conectados con Docker (HU-36) |
| 4 | 5-9 oct | 1 (la original HU-04) | Sin commits esa semana |
| 5 | 12-16 oct | 15 (la original HU-05 + 14) | Ventana compartida, dos cámaras, reporte, «dueño que volvió», avisos, login con Google |
| 6 | 19-23 oct | 0 | Solo el ajuste de tiempos de captura del 17 oct (sin historia) |
| 7 | 26-30 oct | 1 | Observabilidad (R2-01) |
| 8 | 2-6 nov | 1 | Persistencia (R2-03) y Sonar |
| 9 | 9-13 nov | 3 | Reconexión, historial del puesto, despliegue en AWS (R2-04 a R2-06) |
| 10 | 16-20 nov | 15 | HU-34 y R2-02, R2-07 a R2-21: seguridad, disponibilidad, k6, mantenibilidad, documentos |

- **92 elementos nuevos** (IDs 12 a 103): 8 épicas, 29 features y 55 historias (34 del backlog, HU-05 a HU-38, y 21 del Release 2, `R2-01` a `R2-21`).
- **Los 11 originales** (IDs 1 a 11: la épica, 5 features y HU-01 a HU-05) se cerraron. Equivalencias con `docs/backlog.md`:
  HU-01 de Azure = HU-01 y HU-02 del backlog; HU-02 = HU-03 y HU-04; HU-03 = HU-05 y HU-06; HU-04 = HU-13 y HU-18 (la evidencia es fotográfica, no un clip de video); HU-05 = HU-15 a HU-17.
- **Estados:** 74 *Closed*, 9 *Active* (épicas y features con trabajo pendiente, y HU-30 Telegram, que está parcial) y el resto *New* (HU-20, 28, 31, 32, `R2-11` y `R2-18`).
- **Etiquetas útiles:** `hecho`, `parcial`, `backlog`, `release-2`, `registrado-retroactivo`, `Must`/`Should`/`Could`, `R1`/`R2`/`R3`.

- **Wiki:** el proyecto no tenía; el 20-nov se creó la Wiki (`SafeMode.wiki`, página *SafeMode*) con el propósito, el alcance, el estado de Boards y los enlaces al repositorio, la aplicación y los documentos.
- **Accesos:** el proyecto tiene **3 usuarios (los tres integrantes)**; **el docente no está agregado**. Para darle acceso: Organization settings → Users → *Add users* (con su correo institucional), o Project settings → Permissions.
  Hasta entonces no se debe afirmar en la presentación que el equipo evaluador tiene acceso. (Kevyn nunca había entrado a la organización y Juli entró por última vez el 12-sep: el uso real de la herramienta fue de Hever.)

## Lo que hay que decir con franqueza

- Los sprints 1 a 5 y HU-01 a HU-05 se crearon el 12 de septiembre (al inicio). **El resto se registró el 20-nov-2026** a partir de `docs/backlog.md` y del historial de Git.
- Por eso **la fecha de cierre de todo es el 20 de noviembre** y **no existe burndown diario** de los sprints anteriores (Azure DevOps no permite poner fechas en el pasado). La fecha real de entrega
  está en la descripción de cada elemento, con su evidencia.
- **Plan frente a realidad:** el plan original puso una historia por sprint (HU-01 a HU-05 en los sprints 1 a 5), pero el trabajo se adelantó: por ejemplo, la detección del retiro estaba
  planeada para el Sprint 3 y se hizo el 20-21 de septiembre (Sprint 2). Las historias originales se dejaron en su sprint de plan; las nuevas van al sprint donde cayó el trabajo.
- La asignación a sprints sigue una regla: el trabajo va al sprint de la semana en que se hizo y, si cayó en fin de semana, al de la semana siguiente. Los sprints 4 y 6 casi no tuvieron trabajo.

## Cómo se hizo (para repetirlo)

1. **Sprints 6 a 10:** Project settings → Boards → Team configuration → Iterations → *New* (nombre y fechas de lunes a viernes).
2. **CSV:** `python docs/azure-devops/generar-csv.py` escribe `importar-backlog.csv` (UTF-8 con BOM) con jerarquía épica → feature → historia (`Title 1`, `Title 2`, `Title 3`), puntos,
   criterios Gherkin (`Acceptance Criteria`), etiquetas y sprint (`Iteration Path`). Salen del backlog, de los documentos de `docs/` y de los commits.
3. **Importar:** Boards → Work items → *Import Work Items* → elegir el CSV → *Import* → *Save items*.
   **Azure Boards no deja crear elementos en `Closed` ni `Active`** («the value 'Closed' is not in the list of supported values»): se importan en `New` con la etiqueta `hecho` o `parcial`.
4. **Estados:** en la vista de consulta, filtrar por la etiqueta, seleccionar todo (Ctrl+A) → menú ⋮ → *Edit…* → `State` = `Closed` (etiqueta `hecho`) o `Active` (etiqueta `parcial`) → OK → *Save items*.
5. Los 11 originales se cerraron con la misma edición en bloque.

## Qué mostrar en la sustentación

Boards → Sprints → **Sprint 10** (pestaña *Backlog*: 15 historias cerradas) y **Sprint 2** (14); Boards → Work items para ver la jerarquía; y la etiqueta `release-2` para las historias del Release 2.
