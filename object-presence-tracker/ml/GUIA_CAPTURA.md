# Guía de captura de datos reales (Fase 2)

Objetivo: reunir los eventos con los que se entrena y se prueba el modelo que decide **«¿sigue ahí o fue retirado?»** cuando
YOLO deja de ver una mochila, bolso o maleta. Tú provocas esas situaciones frente a la cámara y le dices al sistema la verdad
(`still` = la mochila NO se movió; `removed` = alguien se la llevó). El sistema anota las señales de la zona en cada fotograma.

## 1. Metas (las comprueba `ml/dataset_summary.py`)

El Hito 3 de PTIA **no exige un número de datos**: pide una prueba de concepto funcional (MVP), validada, con casos de prueba reales
(secuencias de entradas y salidas esperadas y logradas), conclusiones y una demo. Lo que sí hace falta para que la validación sea
honesta es probar el modelo en una escena que no vio al entrenar. Por eso hay dos planes:

| Qué | **Plan mínimo** (por defecto) | Ideal (`--ideal`) |
|---|---|---|
| Eventos **válidos** `removed` y `still` | ≥ 60 de cada uno en total | ≥ 150 de cada uno |
| Escenas (lugar y/o luz) distintas | ≥ 2 (A entrena, C prueba) | ≥ 3 (A y B entrenan, C prueba) |
| Bloques por escena de entrenamiento | 6 (3 `still` + 3 `removed`) | 8 (4 + 4) |
| Escena **C (reservada)** | ≥ 25 por etiqueta | ≥ 45 por etiqueta |

La escena C **no se mira hasta la Fase 5**. Con pocos eventos de prueba los intervalos de confianza salen anchos: se reportan tal cual.
Empieza por el plan mínimo; si sobra tiempo, amplías hacia el ideal sin rehacer nada.

**Evento válido** = una racha de fotogramas sin ver la mochila que (1) terminó bien, (2) llegó a **2 o más fotogramas sin verla**
y (3) tiene las señales de la zona en todas sus filas. Con yolov8m el ritmo es ~1,1 fotogramas por segundo, así que la mochila
tiene que quedar fuera de la vista de YOLO **más de ~2 s** para que cuente. Es normal que no todos tus intentos generen evento:
el resumen te dice cuántos llevas de verdad.

Escenas: **A** y **B** son de entrenamiento (un lugar o una luz cada una; p. ej. A = sala con luz de día, B = otro sitio o luz
artificial). **C** es la prueba final: otro lugar o franja de luz que el modelo nunca verá al entrenar.

## 2. Preparación (una vez por escena)

1. Cámara fija y sin tocarla durante toda la escena. Mochila, bolso o maleta que YOLO reconozca (en la consola tiene que salir
   `[DEBUG] Frame: 1 objeto(s)...`).
2. **Apaga la IA externa en esa terminal** (si no, las caras irían a NVIDIA y se gastarían tokens):
   ```powershell
   Remove-Item Env:NVIDIA_API_KEY -ErrorAction SilentlyContinue
   ```
   (solo afecta a esa terminal; tu clave guardada con `setx` no se toca).
3. Fuente de imagen: la de siempre (mitad izquierda de la pantalla, `Win`+flecha izquierda sobre la ventana de la cámara) o la
   ventana compartida desde el panel (`$env:SAFEMODE_FRAME_SOURCE = "browser"`, ver `CLAUDE.md`). Con la captura de pantalla el
   programa lee **píxeles literales**: si en esa mitad se mueve, se achica o se tapa una ventana (Teams, el explorador, la terminal), el
   sistema ve «un cambio» que no es un retiro y el evento sale como dato malo. La cámara ocupa toda la mitad izquierda y no tocas
   nada ahí durante el bloque; la terminal va en la mitad derecha. (Así pasó con la primera prueba real.)
4. Avisa a las personas que salgan en las capturas. Los recortes se quedan en tu computador (ver sección 8).
5. La primera vez, o si cambió código: `mvn -pl object-presence-tracker -am install -DskipTests` desde la raíz del repo.

## 2b. Prueba de 30 s ANTES del primer bloque (sin registrar nada)

Sirve para comprobar que la cámara, el detector y la consola funcionan antes de gastar 7 minutos en un bloque. **No guarda nada en
`data/removal-dataset/`** porque no pone `SAFEMODE_REMOVAL_LOG`. (Sí borra, como siempre, las fotos de `data/frames/` y la tabla de eventos.)

```powershell
cd C:\Users\Home\Desktop\Safemode-ARSW
Remove-Item Env:NVIDIA_API_KEY, Env:SAFEMODE_REMOVAL_LOG, Env:SAFEMODE_SCENE, Env:SAFEMODE_SESSION, Env:SAFEMODE_LABEL, Env:SAFEMODE_SCENARIO, Env:SAFEMODE_REMOVAL_MODEL, Env:SAFEMODE_REMOVAL_MODEL_FILE -ErrorAction SilentlyContinue
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest" "-Dexec.args=30" | Select-String "Camaras|Duracion|Registro de muestras|Modelo de verificacion|AVISO|EN REPOSO|objeto\(s\), [1-9]"
```

Qué tienes que ver: `Camaras: 1 | modelo de deteccion: ...`, `Duracion de captura: 30 s` y líneas `Frame: 1 objeto(s)` mientras la mochila está a la vista
(y `marcado EN REPOSO` si la dejas quieta más de 3 s). Qué **no** debe salir: `Registro de muestras de retiro: ACTIVADO` ni `[MODELO]` ni `AVISO modelo de retiro`.
Si nunca aparece `objeto(s), 1`, YOLO no ve la mochila: acomoda la cámara antes del bloque.

## 3. Comando de un bloque

Un **bloque** = una ejecución con una sola etiqueta y un solo escenario, de ~10 a 15 eventos (unos 6 a 8 minutos). En PowerShell,
desde la raíz del repo:

```powershell
Remove-Item Env:NVIDIA_API_KEY -ErrorAction SilentlyContinue
$env:SAFEMODE_REMOVAL_LOG = "1"
$env:SAFEMODE_SCENE       = "A"                       # A, B o C
$env:SAFEMODE_SESSION     = "A-still-persona-01"      # ÚNICA por bloque (letras, números, . _ -)
$env:SAFEMODE_LABEL       = "still"                   # still o removed (la verdad de TODO el bloque)
$env:SAFEMODE_SCENARIO    = "still_persona_delante"   # el guion de la sección 5
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest" "-Dexec.args=420"
```

- La consola es una pared de líneas `Frame #N capturado`. Para ver solo lo importante, agrega al final del comando
  `| Select-String "MUESTRAS|EN REPOSO|objeto\(s\), [1-9]|ACTIVADO"` (muestra los fotogramas donde YOLO ve algún objeto, cuando uno
  queda en reposo y los eventos que se cierran).
- `-Dexec.args=420` = 7 minutos; después el programa se cierra solo y anota los eventos que quedaban abiertos. Prefiere siempre este
  tiempo fijo: cortar con `Ctrl+C` funciona (probado el 24-nov-2026: los eventos quedaron cerrados y los datos íntegros), pero se pierde la cola del último evento.
- Si te olvidas de la escena o de la etiqueta, o repites un nombre de sesión, el programa **se niega a empezar** y te dice qué falta
  (así no pierdes una captura entera).
- Para el siguiente bloque cambia `SAFEMODE_SESSION` (siempre), y `SAFEMODE_LABEL` / `SAFEMODE_SCENARIO` si cambia.
- Cada ejecución borra `data/frames/` y la tabla de eventos de la base (como siempre). **`data/removal-dataset/` no se borra nunca.**
- Tamaño de los nombres que sugiero: `A-still-persona-01`, `A-still-pasa-01`, `B-removed-limpio-02`, …

## 4. Cómo se hace UN evento (todos los bloques)

1. Pon la mochila y déjala quieta. Mira la consola y **espera a que salga `Objeto #N marcado EN REPOSO`** (tarda ≥ 3 s). Sin eso el
   sistema todavía no la vigila y la acción no genera evento.
2. Haz la acción del guion (sección 5).
3. Espera a ver la línea `[MUESTRAS] ...` en la consola: es el evento cerrado y te dice cómo terminó y con qué etiqueta quedó. Un evento
   `removed` sigue anotando una «cola» hasta ~17 s o hasta que la mochila reaparezca: no pasa nada si coincide con tu siguiente paso.
4. Vuelve a la situación inicial (en `removed`: pon otra vez la mochila) y **espera de nuevo `marcado EN REPOSO`** antes del
   siguiente evento.

No hagas esto, porque ensucia los datos:
- Tapar la mochila con tela (es otra situación).
- Mover la mochila (> 25 px) en un bloque `still`: eso lo toma el sistema como retiro por desplazamiento; no entra al dataset (el resumen
  te dice cuántos se descartaron).
- Cambiar de etiqueta a mitad de un bloque: para eso, cierra el bloque y abre otro.

## 5. Guiones por tipo de bloque

**`SAFEMODE_LABEL=still`** (la mochila NO se mueve; el detector deja de verla un rato):

| `SAFEMODE_SCENARIO` | Qué hacer |
|---|---|
| `still_persona_delante` | Alguien se para **delante** de la mochila 2–6 s y se va. Tiene que **taparla por completo** (pegado delante de ella, más cerca de la cámara): si YOLO sigue viendo un pedazo de la mochila no hay evento. En la primera prueba real salieron 3 eventos en ~5 minutos; apunta a 10 o más por bloque. |
| `still_pasa` | Alguien **cruza** caminando entre la cámara y la mochila. |
| `still_mano_brazo` | Un brazo o una mano pasa **encima o delante** de la mochila 2–4 s, sin tocarla. |
| `still_luz` | Cambio de luz o de exposición: encender/apagar una lámpara o tapar y destapar una ventana. La mochila no se mueve. |
| `still_detector` | La mochila **rotada** sobre sí misma o **parcialmente fuera del encuadre** hasta que YOLO falla, sin desplazarla más de 25 px. |

**`SAFEMODE_LABEL=removed`** (alguien se la lleva de verdad):

| `SAFEMODE_SCENARIO` | Qué hacer |
|---|---|
| `removed_limpio` | La levantas y sales de cuadro con ella. |
| `removed_tapado` | Una persona se para **delante, tapando la mochila (en la consola, `Frame: 0 objeto(s)` con la persona encima de la zona)**, la toma y **se queda delante 3 s o más** antes de irse. Lo que se busca: que el sistema pase por frames con la zona **tapada** y solo después la vea cambiada. Si la persona se va enseguida con la mochila, la zona queda a la vista desde el primer frame y el evento sale igual que `removed_limpio` (le pasó a `A-removed-tapado-01`: `x2≈1,00` en los 5 eventos). Es el reverso exacto de `still_persona_delante` (misma pose, pero al final se lleva la mochila). |
| `removed_otro` | La lleva **otra persona** (no la que la dejó). |
| `removed_poca_luz` | Como `removed_limpio`, pero con poca luz. |

Plan sugerido por escena (~1 hora): 4 bloques `still` + 4 bloques `removed` de ~10–12 eventos. Así cada escena de entrenamiento aporta
~45 eventos por etiqueta y 8 sesiones. La escena C necesita ≥ 45 por etiqueta (4–5 bloques por etiqueta). Un parpadeo del detector
dentro de un bloque `removed` (la mochila reaparece antes del retiro real) se reetiqueta solo como `still`; no tienes que hacer nada.

## 6. Después de cada sesión

```powershell
python object-presence-tracker/ml/dataset_summary.py
```

Solo usa la biblioteca estándar de Python (no necesita el entorno virtual). Muestra, por escena y etiqueta, cuántos eventos válidos
llevas y cómo terminó cada uno según la regla actual; qué no cuenta y por qué; el avance contra las metas y el siguiente bloque que
conviene capturar. De la escena C solo cuenta cuántos hay (sin el desenlace de la regla). Pega esa tabla en el chat y te digo qué
bloque sigue.

## 7. Si algo sale raro

| Qué ves | Qué significa |
|---|---|
| `La sesion "..." ya tiene datos` | Cambia `SAFEMODE_SESSION`: ya usaste ese nombre. |
| `SAFEMODE_SCENE es obligatoria` / `SAFEMODE_LABEL debe ser removed o still` | Falta una variable o tiene un valor inválido. |
| `[MUESTRAS] ... AVISO: cambio TODO el cuadro (x5=...)` | Durante ese evento no cambió solo la zona de la mochila sino casi todo el cuadro: casi seguro se movió o tapó una ventana, o cambió la luz de toda la escena. Ese evento es sospechoso de ser dato malo: no lo borres, el resumen lo lista en «PARA REVISAR» y se decide después. Si lo provocaste a propósito (`still_luz`), es lo esperado. |
| `[MUESTRAS] Aviso: el objeto no tiene huella de reposo` | No debería salir con este script (activa la verificación de retiro); avísame. |
| `[MUESTRAS] El registro de muestras se desactivo por un error` | El registro falló (p. ej. disco lleno) pero el seguimiento sigue normal; esa sesión queda incompleta. |
| Un bloque `still` da 0 eventos | YOLO nunca perdió la mochila: tápala más tiempo, o prueba `still_detector`. |
| El resumen dice «sin cerrar (proceso cortado)» | La corrida se cortó con el último evento abierto; esos eventos no cuentan. |
| Quieres descartar una sesión mala | Dímelo y quito sus filas de los CSV; no los edites a mano. |

## 8. Privacidad: qué se guarda y dónde

Todo queda en `object-presence-tracker/data/removal-dataset/` (carpeta **ignorada por git**, no se sube al repositorio ni a ninguna
API): `samples.csv` y `events.csv` (números: señales de brillo de la zona, nombres de sesión y escena), `discarded.csv` (un conteo) y
`crops/` (recortes de ≤ 320 px **de la zona del objeto**, solo para revisar errores; si alguien tapa la mochila pueden verse una mano o
parte del cuerpo). El modelo se entrena **solo con 4 números de brillo de la zona del objeto**: ninguna cara ni identidad. Las
descripciones por IA externa quedan apagadas durante la captura (sección 2, paso 2).

## 9. Columnas de los CSV

`samples.csv` (una fila por fotograma sin ver el objeto): `scene`, `session`, `run_label` (la etiqueta que tú pusiste; la final está en
`events.csv`), `scenario`, `camera_id`, `event_id`, `object_class`, `frames_unseen` (k = cuántos fotogramas seguidos sin verlo),
`x1_changed` (fracción de puntos de la zona que cambiaron de forma clara), `x2_visible` (parte de la zona que se pudo comparar; lo
que tapa una persona no cuenta), `x3_changed_soft` (como x1 con un umbral más suave: detecta cambios de luz),
`x4_mean_abs_diff` (diferencia media de brillo de la zona), `x5_frame_changed` (fracción de una cuadrícula gruesa de **todo el cuadro**,
sin la zona del objeto ni las personas, que cambió respecto al último fotograma en que se vio la mochila quieta; un retiro la deja
en ~0, una ventana que se mueve o un cambio de luz la suben; vacía si no se pudo medir), `box_w`, `box_h`, `persons_in_frame`, `rule_action` (qué hizo la regla
actual: `WAIT`, `REMOVE_EARLY`, `STILL`, `REMOVE`, `DROP_CAP`; `TAIL` = fotogramas posteriores a su decisión, solo anotados),
`model_p` / `model_action` (se llenan solo si el tracker corre con un modelo, ver `ml/README.md`; vacías en las capturas normales) y `ts`.

`events.csv` (una fila por evento): `label` (la etiqueta final), `label_source` (`run` o `auto_seen_again`), `outcome_rule`
(`REMOVED`, `DROPPED_CAP`, `SEEN_AGAIN`, u `OPEN_AT_END` si la corrida terminó con el evento abierto), `frames_unseen_at_end` (k en que
la regla decidió), `max_frames_unseen` (hasta dónde llegó la cola) y `tail_end` (`CAP`, `RESEEN`, `RUN_END` o vacío).
