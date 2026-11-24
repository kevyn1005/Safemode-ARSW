# ADR 0007: modelo de verificación de retiro («¿sigue ahí o fue retirado?») con modo sombra

- Autor: Hever Barrera Batero (Hito 3 de PTIA, que también mejora Safemode)
- Estado: **aceptado para operar en modo `shadow`** (24-nov-2026). Hecho y probado: registro de muestras, señales, clasificador, modos OFF / SHADOW / ACTIVE,
  medición de velocidad, entrenamiento con datos reales (39 eventos, escena A) y validación cruzada por sesión; ver *Resultados*. **`active` no se
  recomienda todavía** (una sola escena, sin retiros con la zona tapada); el valor por defecto del código sigue siendo `off`.
- Rama: `heverpersistencia` · Guía de uso y reproducción: [`object-presence-tracker/ml/README.md`](../../object-presence-tracker/ml/README.md)

## Contexto

Cuando el detector (YOLO) deja de ver un objeto en reposo, `ObjectTracker` decide entre «sigue ahí» (alguien lo tapa o el detector falla) y
«fue retirado» con una regla fija sobre la huella de la zona (cuadrícula de 24×24 puntos de brillo, `RegionFingerprint`):

- Desde el 2.º frame sin verlo, si la parte visible de la zona cambió ≥ 60 %, retiro inmediato.
- A los 3 frames: si se puede ver menos del 35 % de la zona, **«no se puede comprobar, se da por retirado»**; si no, se compara con el umbral de
  15 % de cambio.
- Tras 15 frames con la zona igual, se deja de seguir el objeto sin alerta.

Esa regla es frágil de dos formas. Los umbrales se calibraron con pocas fotos (una silla vacía midió 0,184, muy cerca del 0,15). Y, sobre todo,
**cuando alguien tapa la mochila, la regla declara el retiro**. Es lo que ocurrió en la primera captura real (23-nov-2026, bloque
`still_persona_delante`, la mochila no se movió): **3 de 3 eventos dieron retiro falso** (el retiro genera un evento y, según quién esté cerca, una alerta). En los tres la persona
tapaba toda la zona (`x2_visible` ≈ 0), así que las señales de la zona valían 0 por «no comparable», no por «sin cambio», y la regla decidió a
los 3 frames. En los tres la mochila reapareció enseguida.

## Decisión

1. **Un modelo pequeño** (regresión logística o MLP de una capa oculta; si empata, el más simple) que lee **solo señales numéricas de la
   zona del objeto**. *(Actualizado el 24-nov-2026: el modelo principal usa solo 4 señales, x1–x4; ver «Decisiones del 24-nov-2026».)* El catálogo (`RemovalFeatures`, igual en `ml/removal_features.py`) tiene 8 señales: `x1_changed`, `x2_visible`,
   `x3_changed_soft`, `x4_mean_abs_diff`, `x5_frame_changed` (cambio del *resto* del cuadro: distingue un retiro de una ventana que se mueve o
   un cambio de luz), `frames_unseen`, `covered_frames` y `last_visible_x1` (las dos últimas dan memoria: permiten esperar mientras alguien
   tapa y decidir cuando la zona vuelve a verse). Cada modelo declara cuáles usa; la ablación decide.
2. **Política por frame**, desde el 2.º frame sin ver el objeto: si `p ≥ θ`, retirado; si no, esperar; mismo tope de 15 frames sin alerta que la
   regla. `θ` se elige para no dejar pasar retiros reales (recall ≥ 0,95 con puntuaciones fuera de partición), no a ojo.
3. **Tres modos** con `SAFEMODE_REMOVAL_MODEL`: `off` (por defecto; el tracker se comporta exactamente como antes), `shadow` (el modelo se
   calcula y se muestra por consola y en `samples.csv`, **pero decide la regla**: no cambia ninguna alerta) y `active` (decide el modelo). Sin
   imagen (`frame == null`) o sin huella de reposo, `active` decide como siempre.
4. **El modelo es un JSON** (formato 1, `ml/removal_model.py`) que `RemovalClassifier` lee en tiempo de ejecución (`SAFEMODE_REMOVAL_MODEL_FILE`).
   Una prueba comprueba que Java da la misma probabilidad que Python (tolerancia 1e-9) en 200 casos por modelo.
5. **Registro de muestras opcional** (`RemovalSampleLog`, `SAFEMODE_REMOVAL_LOG=1`): CSV en `object-presence-tracker/data/removal-dataset/` con
   las señales de cada frame sin ver el objeto y lo que decidió la regla. Sigue anotando una **«cola»** hasta el tope de 15 frames aunque la regla
   ya haya decidido, para saber qué habría hecho otro criterio más adelante. Nunca lanza excepciones hacia el seguimiento.
6. **Validación honesta**: entrenar con unas escenas, validación cruzada agrupada por sesión (nunca frames del mismo evento a ambos lados) y una
   escena reservada que se usa una sola vez al final. La unidad de evaluación es el **evento** (una racha de frames sin ver), no el frame.

### Alternativas descartadas

| Alternativa | Por qué no |
|---|---|
| Ajustar los umbrales de la regla | No resuelve el problema de fondo: con la zona tapada no hay nada que comparar, y la regla seguirá teniendo que elegir entre «retiro» y «espera» sin información |
| Red convolucional o visión profunda sobre el recorte | Pide muchos más datos y cómputo, y expone más apariencia (manos, cuerpos, caras) a un modelo; el objetivo del Hito es un modelo pequeño con los temas del curso |
| SVM con núcleo RBF como modelo final | No se exporta de forma simple (cientos de vectores de soporte); se entrena para comparar, pero si empata gana el más simple |
| Constantes del modelo generadas dentro de una clase de Java | Habría que meter pesos en `src/main` antes de tener un modelo real, y cambiarlos exigiría recompilar. Con JSON se cambia el modelo sin tocar código y no hay pesos falsos en el repositorio |
| Etiquetas del operador desde el panel (3 respuestas: «era robo», «falsa alarma», «era el dueño») | Toca el gateway, el panel y la base; es la mejora futura natural, pero queda fuera de este Hito |

## Decisiones del 24-nov-2026 (tomadas antes de ver la escena reservada)

Las fijó Hever al revisar el avance; no se cambian después de ver la escena C.

1. **Alcance.** La comprobación externa con los videos de ABODA (Fase 5b) queda fuera por ahora. Se deja lista la Fase 4 con un modelo de
   **juguete** marcado como tal (`"meta": {"toy": true}`), que nunca decide: el cargador lo acepta en `shadow` y lo rechaza en `active`.
   Quedan listos los modos `off/shadow/active`, la carga del modelo, el benchmark M5 y la prueba Java = Python con ≥ 200 casos por modelo.
2. **El JSON vive en `object-presence-tracker/src/main/resources/removal-model/model.json`** (lo deja `ml/export_model.py export`; se carga con
   `RemovalModelLoader`; `SAFEMODE_REMOVAL_MODEL_FILE` permite probar otro sin recompilar). **Si el modelo falta, está dañado, es de juguete en
   `active` o el modo está mal escrito, el tracker no falla**: avisa y sigue con la regla de siempre. Hay prueba de equivalencia Java = Python
   (`RemovalClassifierTest`) y de que ese fallback decide igual que sin modelo (`RemovalModelLoaderTest`). Los modelos de juguete viven en
   `src/test/resources`, no en `src/main`: no se empaquetan pesos inventados. El exportador se niega a copiar un juguete o un modelo entrenado
   con sesiones sintéticas.
3. **Señales.** El modelo principal usa las **4 del plan** (`x1_changed`, `x2_visible`, `x3_changed_soft`, `x4_mean_abs_diff`). Las otras 4 del
   catálogo (`x5_frame_changed`, `covered_frames`, `last_visible_x1`, `frames_unseen`) son un **experimento de ablación** que se mide y se reporta
   pero nunca se adopta solo; si ayudan claramente lo decide el equipo con los números (`train_removal.py --signals` y la tabla de ablación).
   *Motivo original de las extra:* la primera captura real mostró que, con la zona tapada, `x1`, `x3` y `x4` valen 0 por «no comparable» y no por
   «sin cambio»; `x2_visible` ya lo distingue, así que un modelo sin memoria puede esperar si está tapada. Si las de memoria (`covered_frames`,
   `last_visible_x1`) mejoran los resultados, se verá en la ablación.
4. **Umbral.** `--recall-target 0.98`, fijo. El plan decía «el umbral más alto con recall ≥ 0,95»; con 0,95 el umbral cae justo en el borde del
   acantilado del recall (en validación el recall sale ≈ 0,95 por construcción) y en datos nuevos casi seguro queda por debajo. 0,98 deja margen
   para que la meta M1 (recall ≥ 0,95) se mida con holgura. M1 sigue siendo ≥ 0,95. No se modifica después de ver la escena C.
5. **El dataset de entrenamiento no contiene nada sintético.** Verificado el 24-nov-2026: `data/removal-dataset/` tiene solo la sesión real
   `A-still-persona-01` (3 eventos); los datos sintéticos (`make_synthetic_dataset.py`) solo los usan las pruebas de Python en carpetas
   temporales, llevan sesiones `SINT-…`, y `train_removal.py` y `export_model.py` las rechazan.

6. **Se conservan los `still` reetiquetados solos (24-nov-2026, tras el primer bloque `removed` real).** Un evento que termina con el
   detector volviendo a ver el objeto dentro de una corrida `removed` se etiqueta `still` (`label_source = auto_seen_again`), **incluso si el
   objeto reaparece desplazado más de 25 px** (p. ej. `o11_r1`: la mochila volvió girada). Criterio: la etiqueta es la decisión operativa
   («¿debe alertar el sistema?»), y una mochila que se movió y volvió no es un retiro. Costo asumido: con pocos datos, 1 o 2 de los `still` son
   ambiguos; se cuentan como limitación en el informe y se pueden volver a mirar con sus recortes en `crops/` si algún resultado depende de ellos.

### Atajo aprendido: `frames_unseen` nunca es señal del modelo final (decisión del 24-nov-2026, segunda revisión)

`frames_unseen` (cuántos frames lleva sin verse el objeto) queda **solo en la ablación**. Es el ejemplo de manual de un **atajo aprendido**
(*shortcut learning*): una señal que separa las clases en los datos de entrenamiento por cómo se capturaron y no por el fenómeno que se quiere
medir.

- **Por qué en estos datos separa «gratis».** Los eventos `still` de las capturas duran 2–6 s (alguien se para delante unos segundos y el objeto
  reaparece), así que terminan con `k` bajo. Los `removed` tienen una cola larga (el objeto no vuelve y se anota hasta el tope de 15 frames), así
  que casi todos sus frames tienen `k` alto. Un modelo puede obtener un recall y unos falsos retiros excelentes con solo «`k` grande = retiro»,
  **sin mirar la zona del objeto**: aprende el guion de las capturas.
- **Qué pasaría en producción.** Un `still` real más largo que el guion (el detector falla 20 s con la mochila a la vista, o alguien se queda
  parado delante) tendría `k` alto y saldría como retiro, justo el falso robo que el modelo debía evitar. Y las métricas de validación no lo
  delatarían, porque la validación hereda el mismo guion.
- **Cómo se cumple (tres capas).** (1) `train_removal.py`: el modelo principal usa solo x1–x4 y `build_model_json` lanza error si alguna vez
  recibe `frames_unseen`; la ablación sí la mide («con frames_unseen») para poder enseñar el efecto. (2) `export_model.py export` se niega a
  exportar un modelo que la use. (3) `RemovalModelLoader` (Java) rechaza en cualquier modo un modelo que no sea de juguete y la use; el tracker
  sigue con la regla y avisa. Los juguetes (`toy-mlp8`) pueden llevarla porque existen para medir el tamaño máximo y la ablación, y no deciden.
  Pruebas: `FramesUnseenShortcutTest` (Python) y `RemovalModelLoaderTest` (Java).
- **Qué vigilar de la misma familia.** `covered_frames` (frames seguidos con la zona tapada) está muy correlacionada con `k` en los guiones
  donde alguien tapa la zona durante todo el evento, así que puede arrastrar parte del mismo atajo. Es una señal extra del experimento y no se
  adopta sola; si alguna vez se propone, hay que comprobar en la ablación que mejora **también** en `still` más largos que el guion (p. ej. el
  bloque `still_detector`), no solo en los de 2–6 s.

## Consecuencias

- **Latencia de decisión**: mientras la zona está tapada el modelo no decide; en un retiro hecho por alguien que se para delante y toma la
  mochila, el retiro se declara cuando la zona vuelve a verse, **más tarde que la regla** (que lo daba a los 3 frames). Se mide en la Fase 5.
- **Peor caso de `active`**: si la zona queda tapada hasta el tope de 15 frames, el objeto se deja de seguir **sin alerta**. Por eso se recomienda
  empezar en `shadow` y `active` solo si la validación lo respalda.
- **Velocidad**: la decisión completa de un frame sin ver el objeto (huella, señales, cambio del cuadro, inferencia) tarda **0,146 ms en p95** con
  el modelo más grande previsto (8 señales, 6 neuronas ocultas), con fotogramas de 1280×1080 (meta: menos de 5 ms; `RemovalClassifierBenchmark`).
  Medido con un modelo de juguete del mismo tamaño; se repite con el real.
- **Compatibilidad**: sin la variable el comportamiento es el de siempre; las más de 190 pruebas anteriores siguen pasando sin editarse.

## Privacidad

El modelo usa **solo señales numéricas de brillo de la zona del objeto**; ninguna cara ni identidad. Lo que se guarda va a
`object-presence-tracker/data/removal-dataset/` (ignorada por git, no sale a ninguna API): CSV con números y recortes de ≤ 320 px **de la zona
del objeto**, solo para revisar errores (si alguien tapa la mochila puede verse una mano o parte del cuerpo). Durante las capturas se apaga la
descripción por IA externa (`Remove-Item Env:NVIDIA_API_KEY` en esa terminal). Las personas que salen en las capturas lo saben.

## Cómo apagarlo

`SAFEMODE_REMOVAL_MODEL=off` o quitar la variable: el tracker vuelve a decidir con la regla. El registro de muestras se apaga quitando
`SAFEMODE_REMOVAL_LOG`. Nada de esto cambia el esquema de la base de datos ni el panel.

## Límites conocidos

- Datos de una sola cámara (la de la laptop), pocas escenas y un solo operador: el modelo se valida en una escena que no vio al entrenar, pero
  no se sabe cómo se comporta con otra cámara u otra iluminación.
- Con la captura de pantalla, una ventana que se mueve o se tapa cambia los píxeles que se leen. `x5` y el aviso de consola
  (`AVISO: cambio TODO el cuadro`) lo detectan y esos eventos se revisan; no se arreglan solos.
- Con pocos eventos de prueba los intervalos de confianza salen anchos y así se reportan.

## Implementación (qué quedó hecho)

- `RegionFingerprint.signals` (señales `x1`–`x4` en una pasada; `compare` intacto), `RemovalSampleLog` (con `x5`, cola, reetiquetado de
  `SEEN_AGAIN` en corridas `removed`, aviso de ventana y columnas `model_p` / `model_action`), `RemovalFeatures`, `RemovalClassifier`,
  `RemovalModelMode`, `ObjectTracker.withRemovalSampleLog` y `withRemovalClassifier`, `RemovalClassifierBenchmark`.
- `ml/`: guía de captura, resumen del dataset, catálogo de señales, formato del modelo y exportador con casos de comparación.
- 242 pruebas del módulo al empezar la Fase 4 (1 omitida a propósito); **276 con 2 omitidas** (las del modelo entrenado) al terminarla, todas verdes.
  Fase 4: `RemovalModelLoader`, marca `toy` en `RemovalClassifier`, `src/main/resources/removal-model/`, benchmark M5 (p95 ≈ 0,16 ms con el
  juguete del plan de 4 señales y ≈ 0,12 ms con el de 8), exportador a `src/main/resources`.
- **Código de entrenamiento (24-nov-2026)**: `ml/load_dataset.py` (carga y replay de la regla; **coincide con lo que registró el tracker en las
  9 filas de decisión** de los 3 eventos reales), `ml/train_removal.py` (validación cruzada agrupada por sesión, perceptrón / logística / SVM
  lineal y RBF / MLP, ablación de señales, umbral por recall, exportación y `--final` una sola vez sobre la escena reservada) y 15 pruebas de
  Python con datos **sintéticos** (`make_synthetic_dataset.py`, solo para probar el código; no son evidencia). Sobre el dataset real
  (3 eventos `still`) se niega a entrenar y dice qué falta.
- **Modelos hechos a mano** (`ml/models_numpy.py`: perceptrón, *pocket*, logística con L2, MLP con Adam), pedido del plan para entender la
  mecánica; la logística se comprueba contra sklearn (diferencia de probabilidad ~1e-8) y la retropropagación contra un gradiente numérico.
  `frames_unseen` queda solo en la ablación. Validaciones del dataset: rangos, secuencias y que el replay reproduzca el `outcome_rule`.
  Se mantiene a propósito el modelo como **JSON** (no una clase Java generada con pesos embebidos) y el catálogo de **8 señales** (no 4),
  por lo ya decidido arriba.
- Cambio respecto a lo planeado: la «opción B» de etiquetado («solo los últimos 2 frames del retiro») se sustituyó por `visible` (no entrenar
  con los frames de zona tapada de un `removed`), porque la primera no se puede definir sin apoyarse en la regla que se quiere reemplazar.

## Resultados (24-nov-2026)

Detalle, tablas por frame y figuras en [`docs/hito3/resumen_para_informe.md`](../hito3/resumen_para_informe.md). En resumen, con 39 eventos reales de la escena A
(23 `removed`, 16 `still`, 5 sesiones) y validación cruzada agrupada por sesión (3 pliegues, `--recall-target 0.98` fijado antes de ver ninguna prueba en vivo):

| | Retiros | Falsos retiros | F1 | Decide a los |
|---|---|---|---|---|
| Regla actual | 23/23 | **10/16** (IC95 0,39–0,82) | 0,82 | 3 frames (mediana) |
| Modelo elegido (perceptrón *pocket*, x1–x4, 5 parámetros, umbral 0,5258) | 23/23 | **1/16** (IC95 0,01–0,28) | 0,98 | 2 frames |

- Las 4 señales extra del catálogo no ayudaron (`x5` y `last_visible_x1`: 0 eventos; `covered_frames`: +3 errores; `frames_unseen`: +2, el atajo aprendido de arriba). Ninguna se adoptó.
- Velocidad: p95 = 0,22 ms por decisión (meta < 5 ms). Pruebas: 278 de Java (0 omitidas; Java = Python en 200 casos del modelo real) y 37 de Python.
- En vivo, en modo sombra: persona delante, 4 eventos → la regla dio retiro falso en los 4 y el modelo esperó en los 4; un retiro real → modelo y regla coinciden a `k = 2`.
- **Salvedades**: con 16 `still` el IC de los falsos retiros llega a 28 % (no se puede afirmar ≤ 15 %); es el mejor de 14 combinaciones sobre los mismos datos; el umbral está cerca del
  borde (recall 16/23 con umbral 0,6); una sola escena, cámara y operador; **0 retiros con la zona tapada 3 o más frames al inicio**; la escena de validación en vivo (`V`) quedó con 1 y 1
  eventos válidos y no se corrió la prueba final.

### Decisión sobre el modo (24-nov-2026)

**Por defecto `off`; recomendado `shadow`; `active` no todavía.** En `shadow` el modelo opina y la regla decide, así que se puede operar y medir sin riesgo. `active` deja de
alertar si la zona queda tapada hasta el tope de 15 frames, un caso que los datos no cubren.

## Pendientes

1. Una **escena distinta** (otro lugar o luz, otra cámara), ≥ 25 eventos por etiqueta, para una prueba final honesta con `ml/train_removal.py --final` (la escena `V` actual no alcanza).
2. **Retiros con la zona tapada 3 o más frames al inicio** (guion `removed_tapado` corregido en `GUIA_CAPTURA.md`) y más `still` de otros tipos (`still_detector`, `still_pasa`, `still_luz`).
3. Con eso, volver a decidir si `active` es seguro. Reentrenar siempre con `--reserved` apuntando a la escena de prueba (hoy `V` está en la misma carpeta del dataset).
4. Etiquetas del operador desde el panel (3 respuestas) para seguir mejorando con datos reales.
