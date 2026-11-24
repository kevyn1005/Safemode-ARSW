# ml/ — modelo de verificación de retiro («¿sigue ahí o fue retirado?»)

Cuando YOLO deja de ver una mochila, bolso o maleta que estaba quieta, hay que decidir si **sigue ahí** (tapada o el detector falló) o
**se la llevaron**. Hoy lo decide una regla fija. Aquí está todo lo necesario para reemplazarla por un modelo pequeño entrenado con datos
reales de tu cámara, integrarlo en el tracker y validarlo. Decisión y alternativas: [`docs/adr/0007-modelo-verificacion-retiro.md`](../../docs/adr/0007-modelo-verificacion-retiro.md).

**Estado (24-nov-2026):** captura, entrenamiento con **datos reales** (39 eventos de la escena A), exportación a Java y modos `off/shadow/active`
**hechos y probados**. Resultados, casos por frame y limitaciones: [`docs/hito3/resumen_para_informe.md`](../../docs/hito3/resumen_para_informe.md).
Modo recomendado: **`shadow`** (por defecto del código: `off`; `active` todavía no).

## Mapa de archivos

| Archivo | Para qué |
|---|---|
| `GUIA_CAPTURA.md` | Cómo capturar los datos con la cámara (bloques, guiones, comandos, qué hacer si algo sale raro) |
| `dataset_summary.py` | Resumen del dataset después de cada sesión (solo biblioteca estándar de Python) |
| `removal_features.py` | Catálogo de las 8 señales del modelo; **misma definición que `RemovalFeatures.java`** |
| `removal_model.py` | Formato JSON del modelo y su cálculo en Python (el contrato entre entrenamiento y tracker) |
| `export_model.py` | Valida el modelo entrenado y genera los casos con los que se comprueba que Java calcula lo mismo que Python |
| `load_dataset.py`, `models_numpy.py`, `train_removal.py`, `make_synthetic_dataset.py`, `test_training.py` | Fase 3: carga y validación, modelos hechos a mano, entrenamiento, datos sintéticos de prueba y pruebas (ver «Entrenamiento») |
| `results/` | Resultado del entrenamiento con datos reales: `model.json`, `metrics.json` y 6 figuras (`final_<escena>.json` cuando se corra `--final`) |

Del lado de Java (`object-presence-tracker/src/main/java/com/safemode/presence/`): `RemovalSampleLog` (registro de muestras),
`RemovalFeatures` y `RemovalClassifier` (señales y modelo), `RemovalModelMode` (OFF / SHADOW / ACTIVE), `RemovalModelLoader` (busca y carga el
modelo **sin hacer fallar nunca al tracker**) y `RemovalClassifierBenchmark`. El modelo entrenado vive en
`object-presence-tracker/src/main/resources/removal-model/model.json` (todavía no existe: ver su `LEEME.txt`).

## Entorno de Python (una sola vez)

Va en una **ruta corta** (con una ruta larga `pip` falla por el límite de 260 caracteres de Windows) y no se sube al repo:

```powershell
python -m venv C:\Users\Home\AppData\Local\Temp\removal-env
C:\Users\Home\AppData\Local\Temp\removal-env\Scripts\python.exe -m pip install numpy pandas scikit-learn matplotlib
```

Después, `C:\Users\Home\AppData\Local\Temp\removal-env\Scripts\python.exe object-presence-tracker/ml/<script>.py`. `dataset_summary.py`
no necesita el entorno (solo la biblioteca estándar).

## El camino completo

```text
cámara ──► ObjectTracker + RemovalSampleLog ──► data/removal-dataset/*.csv ──► [Fase 3: entrenar] ──► ml/results/model.json
                (SAFEMODE_REMOVAL_LOG=1)             (fuera de git)                                          │
                                                                                                             ▼
                          ObjectTracker con SAFEMODE_REMOVAL_MODEL=shadow|active  ◄── ml/export_model.py ──► src/main/resources/removal-model/model.json
                                                                                       (valida, rechaza juguetes y datos sintéticos)
```

1. **Capturar** (tú, con la cámara): sigue `GUIA_CAPTURA.md`. Cada sesión es un bloque con una etiqueta (`removed` o `still`) y un escenario.
2. **Resumir**: `python object-presence-tracker/ml/dataset_summary.py` (plan mínimo por defecto; `--ideal` para las metas grandes).
3. **Entrenar** (Fase 3, pendiente): escribe `ml/results/model.json` en el formato de `removal_model.py`.
4. **Exportar y comprobar**: `python object-presence-tracker/ml/export_model.py export` valida el modelo, lo copia a
   `src/main/resources/removal-model/model.json` (de ahí lo carga el tracker) y genera `src/test/resources/removal-model/trained.cases.csv`
   (200 casos); luego `mvn -pl object-presence-tracker -am test` comprueba que Java da la misma probabilidad que Python (tolerancia 1e-9).
   Se **niega** a exportar un modelo de juguete (`meta.toy`) o entrenado con sesiones sintéticas (`SINT-`). Para que viaje en el jar:
   `mvn -pl object-presence-tracker -am install -DskipTests`.
5. **Probar en vivo** en modo sombra y después, si conviene, en activo (abajo).

## Probar el modelo con el tracker

Variables de entorno (valen para `ObjectPresenceTrackerTest`, y también para el tracker que lanza el botón del panel si el gateway se
arrancó desde una terminal con ellas puestas: el proceso hijo hereda el entorno):

| Variable | Valor |
|---|---|
| `SAFEMODE_REMOVAL_MODEL` | `off` (por defecto: la regla de siempre) · `shadow` (el modelo se muestra por consola, **decide la regla**, no cambia ninguna alerta) · `active` (decide el modelo) |
| `SAFEMODE_REMOVAL_MODEL_FILE` | Ruta de un modelo para probarlo sin recompilar (p. ej. `object-presence-tracker/ml/results/model.json`). Sin la variable se usa el del proyecto, `src/main/resources/removal-model/model.json` |
| `SAFEMODE_REMOVAL_LOG=1` + las `SAFEMODE_SCENE/SESSION/LABEL/SCENARIO` | Opcional: junto con un modelo, las filas de `samples.csv` llevan `model_p` y `model_action` (también las de la «cola») |

```powershell
Remove-Item Env:NVIDIA_API_KEY -ErrorAction SilentlyContinue
$env:SAFEMODE_REMOVAL_MODEL = "shadow"
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest" "-Dexec.args=300" | Select-String "MODELO|EN REPOSO|objeto\(s\), [1-9]"
```

En consola salen líneas como `[MODELO] #1 k=3 p=0.018 -> espera (umbral 0.500) | regla: REMOVE`: lo que dice el modelo en ese frame, junto a lo
que hizo la regla. Para apagarlo: `off` o quitar la variable.

**El modelo nunca hace fallar al tracker** (`RemovalModelLoader`). Si el modelo falta, está dañado o incompleto, si el modo está mal escrito
(`sombra`) o si el modelo es **de juguete** y se pidió `active`, el programa NO falla: imprime `AVISO modelo de retiro: ...` y sigue con la regla
de siempre (modo `off`). Cubierto por `RemovalModelLoaderTest` (archivo ausente, directorio, JSON vacío / roto / con NaN / con campos de más o de
menos, y que con ese resultado el tracker decide exactamente como sin modelo).

**Modelos de juguete** (`src/test/resources/removal-model/toy-*.json`, pesos inventados, llevan `"meta": {"toy": true}`): sirven para ver la
integración antes de tener un modelo entrenado. Se pueden ver en `shadow` (la consola marca `[JUGUETE ...]`) y **nunca deciden**:
`active` con un juguete se rechaza. Para verlos en vivo: `$env:SAFEMODE_REMOVAL_MODEL = "shadow"` y
`$env:SAFEMODE_REMOVAL_MODEL_FILE = "object-presence-tracker/src/test/resources/removal-model/toy-mlp.json"`. Sus probabilidades no significan nada.

## Medir la velocidad (meta M5: menos de 5 ms por decisión)

```powershell
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.RemovalClassifierBenchmark"
```

Mide, con fotogramas de 1280×1080, la decisión completa de un frame sin ver el objeto (huella, señales, inferencia y, solo si el modelo usa
`x5`, el cambio del resto del cuadro, igual que el tracker real). Sin modelo entrenado mide **dos juguetes** (solo sirven para tiempos): el del plan
(4 señales, MLP 4-6-1, 37 parámetros) y el máximo previsto (8 señales). Resultado del 24-nov-2026 en este computador: **p95 = 0,16 ms y 0,12 ms**,
muy por debajo de la meta de 5 ms. Con el **modelo real** (24-nov-2026): **p95 = 0,22 ms** (media 0,11 ms). Mide el del proyecto si existe
`src/main/resources/removal-model/model.json`.

## Pruebas

`mvn -pl object-presence-tracker -am test`: **278 pruebas, 0 fallos, 0 omitidas** (las dos del modelo entrenado ya corren). Línea base antes de la
Fase 4: 242 con 1 omitida. Las que tocan este trabajo: `RemovalSampleLogTest`, `RegionFingerprintSignalsTest`,
`RemovalFeaturesTest` (Java contra Python en 40 historias), `RemovalClassifierTest` (**Java contra Python en 200 casos por modelo**: tres
juguetes y, cuando exista, el modelo de `src/main/resources`), `RemovalModelLoaderTest` (el tracker no falla por el modelo) y
`RemovalModelModeTest` (OFF / SHADOW / ACTIVE sobre el tracker). Los archivos de `src/test/resources/removal-model/` con `toy` en el nombre son
de **juguete** (pesos inventados; el principal de 4 señales, uno MLP 4-6-1 y uno de 8 señales para el tamaño máximo); se regeneran con
`python object-presence-tracker/ml/export_model.py toy`.

## Entrenamiento (Fase 3): código listo, falta el dato real

**Estado:** entrenado el 24-nov-2026 con los 39 eventos reales de la escena A (23 `removed`, 16 `still`); sus resultados están en `results/` (modelo,
`metrics.json` y 6 figuras) y el modelo elegido está exportado a `src/main/resources/removal-model/model.json`. Los datos sintéticos solo los usan las
pruebas (carpetas temporales); el entrenamiento y el exportador los rechazan. **`--final` todavía no se corrió**: la escena `V` (validación en vivo,
misma carpeta del dataset) tiene 1 y 1 eventos válidos, no alcanza. **Si se reentrena, usar `--reserved V`** (o la escena de prueba que se capture),
porque `V` está en el mismo dataset.

```powershell
$py = "C:\Users\Home\AppData\Local\Temp\removal-env\Scripts\python.exe"
& $py object-presence-tracker/ml/load_dataset.py        # cuenta eventos y comprueba que el replay de la regla == lo que hizo el tracker
& $py object-presence-tracker/ml/train_removal.py --signals   # que senales separan las clases (no entrena; sirve apenas haya removed y still)
& $py object-presence-tracker/ml/train_removal.py       # valida (por sesión) y escribe ml/results/model.json, metrics.json y 6 figuras
& $py object-presence-tracker/ml/export_model.py export # valida el JSON, lo deja en src/main/resources y prepara la prueba Java == Python
& $py object-presence-tracker/ml/train_removal.py --final   # UNA vez: prueba en la escena reservada C contra la regla
```

| Archivo | Para qué |
|---|---|
| `load_dataset.py` | Carga eventos válidos (reutiliza `dataset_summary.py`), valida (sin NaN, señales en 0–1, secuencias sin saltos, sin duplicados) y repite la regla actual (`rule_action`). Comprobado con datos reales: **coincide con el tracker en las 9 filas de decisión y reproduce el `outcome_rule` en el 100 %** de los 3 eventos capturados |
| `models_numpy.py` | **Hechos a mano con numpy**: perceptrón simple, perceptrón *pocket*, regresión logística con L2 (descenso del gradiente) y MLP d–h–1 con ReLU, sigmoide, entropía cruzada y Adam (retropropagación comprobada con un gradiente numérico). Exponen lo mismo que sklearn, así que se exportan igual |
| `train_removal.py` | Validación cruzada agrupada por sesión, comparación de modelos, umbral, ablación señal por señal, curva de aprendizaje, ROC/AUC, exportación y `--final` (con la tabla M1–M6) |
| `make_synthetic_dataset.py` | Datos inventados **solo para probar el código** (se niega a escribir en `data/removal-dataset`) |
| `test_training.py` | 33 pruebas (incluye el gradiente numérico del MLP, el XOR que el MLP resuelve y la logística no, logística a mano = sklearn, y que el entrenamiento rechace datos sintéticos): `python -m unittest discover -s object-presence-tracker/ml -p "test_*.py"` |

Cómo decide (detalle en el docstring de `train_removal.py`):

- **Unidad = el evento**, no el frame: retiro detectado = algún frame `k ≥ 2` con `p ≥ θ`; falso retiro = un evento `still` donde eso pasa.
  Se reportan recall y falsos retiros con intervalo de Wilson al 95 %, y la latencia (frames hasta dar el retiro) frente a la regla.
- **Escena reservada** (`--reserved`, por defecto `C`): se aparta al cargar; el entrenamiento no la ve. `--final` lee `model.json` (no
  reentrena), escribe `final_C.json` y **se niega a repetirse** sin `--force`.
- **Señales**: el modelo **principal usa solo las 4 del plan** (`x1_changed`, `x2_visible`, `x3_changed_soft`, `x4_mean_abs_diff`, todas de la zona del
  objeto). Las otras 4 del catálogo son un **experimento** que el entrenamiento mide pero **nunca adopta solo**: `x5_frame_changed` (cuánto cambió
  el resto del cuadro), `covered_frames` (cuántos frames seguidos lleva la zona tapada), `last_visible_x1` (cuánto había cambiado la última vez
  que se vio) y `frames_unseen` (cuántos frames lleva sin verse). La tabla de ablación muestra cuántos eventos cambian de clasificación al agregar
  cada una; **si alguna ayuda claramente, lo decides tú**. `train_removal.py --signals` dice, con los datos reales, cuáles separan las clases
  (promedio por clase y AUC de cada señal sola); con una sola etiqueta no hay AUC.
- **Candidatos**: perceptrón, perceptrón *pocket*, regresión logística y MLP de 4 y 6 neuronas (**a mano**, `models_numpy.py`), SVM lineal y
  SVM RBF (sklearn; la RBF solo se compara, no se exporta) × 2 formas de etiquetar los frames:
  `all` (todos heredan la etiqueta del evento) o `visible` (en un `removed` no se entrena con los frames de zona tapada, `x2 < 0.35`, porque
  ahí no se puede saber si se la llevaron; etiquetarlos «retirado» enseñaría «tapado = retiro», el error de la regla actual).
  Reemplaza la «opción B» del ADR («solo los últimos 2 del retiro»), que no se podía definir sin apoyarse en la regla que se quiere sustituir.
- **Umbral θ**: el mayor con recall de eventos ≥ **0,98** (`--recall-target`) sobre puntuaciones fuera de partición. **Decisión fijada el
  24-nov-2026, antes de ver la escena reservada, y no se cambia después**: el plan decía 0,95, pero con 0,95 el umbral cae justo en el borde del
  acantilado del recall (`umbral.png` lo muestra): en validación el recall sale ~0,95 por construcción y en datos nuevos casi seguro queda por
  debajo. 0,98 deja margen para que la meta M1 (recall ≥ 0,95) se mida con holgura. M1 sigue siendo ≥ 0,95. Se reporta también θ = 0,5.
  **Gana el modelo más simple** (exportable) si queda a ≤ 1 falso retiro del mejor (`--tie-events`); en empate de parámetros: logística, *pocket*, perceptrón.
- **Ablación**: se quita cada señal de las 4 y se reentrena (una fila por señal), y se **agrega** cada señal extra (y las 4 juntas = las 8). **Curva de aprendizaje**:
  entrena con 25/50/75/100 % de los eventos de cada pliegue. Con pocos eventos y un perceptrón (inestable) la curva sale irregular: no la leas
  como una línea recta. **Figuras** en `results/`: `umbral`, `comparacion`, `confusion`, `roc`, `ablacion`, `curva_aprendizaje`.
- Cada evento pesa lo mismo y las dos clases suman lo mismo (uno de 15 frames no domina a uno de 3).
- **`frames_unseen` nunca es señal del modelo final** (atajo aprendido): los `still` de las capturas duran 2–6 s y los `removed` tienen cola
  larga, así que el modelo «aprendería» que `k` grande = retiro sin mirar la zona, y en producción un `still` largo saldría como retiro. Solo se
  mide en la ablación («con frames_unseen»); `train_removal.py`, `export_model.py` y `RemovalModelLoader` (Java) se niegan a usarla en un modelo
  final. Detalle y razones en el ADR 0007.
- **Sesgo de selección**: se prueban 14 combinaciones (7 modelos × 2 etiquetados) sobre la misma validación cruzada, así que el mejor sale algo optimista. La cifra
  que se reporta es la de la escena reservada.
- `--final` imprime y guarda la tabla de metas M1–M6 del plan de trabajo (**no son requisitos del enunciado del Hito**) y deja en
  `final_C.json` → `errors_or_disagreements` los eventos donde el modelo falla o discrepa de la regla, para el análisis de errores.
