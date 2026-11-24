# Resumen para el informe del Hito 3 (PTIA)

**Versión del 24-nov-2026, con resultados reales.** Las cifras vienen de `object-presence-tracker/ml/results/metrics.json` (validación cruzada),
de las corridas en vivo en modo sombra y de las pruebas automáticas. Donde un resultado no se puede afirmar con tan pocos datos, se dice. Las
secciones siguen los lineamientos del curso (Hito 3: secciones 7 y 8 del documento, prototipo funcional, fuentes y video con demo). Las metas
«M1–M6» y el «150 eventos por clase» eran metas del plan de trabajo, **no requisitos del enunciado del Hito**; se reportan aparte.

## Qué se construyó (MVP)

Safemode detecta mochilas, bolsos y maletas dejados sin vigilancia y avisa cuando alguien se los lleva. La pieza de inteligencia de este Hito es
el **modelo de verificación de retiro**: cuando el detector (YOLO) deja de ver un objeto que estaba quieto, decide si **sigue ahí** (alguien lo
tapa o el detector falló) o **fue retirado**. Hasta ahora lo decidía una regla fija sobre una huella de brillo de la zona.

- **El modelo**: un perceptrón *pocket* (5 parámetros) que lee **4 números de la zona del objeto**: cuánto cambió (`x1`), cuánto se pudo ver (`x2`),
  cuánto cambió con un umbral suave (`x3`) y la diferencia media de brillo (`x4`). Entrenado con datos reales de la propia cámara (abajo).
- **Integración**: se prende y apaga con `SAFEMODE_REMOVAL_MODEL=off|shadow|active`. `off` (por defecto del código) es la regla de siempre; `shadow`
  deja que el modelo opine por consola mientras **la regla sigue decidiendo** (no cambia ninguna alerta); `active` deja decidir al modelo.
- **No rompe nada**: si el modelo falta, está dañado, es de juguete en `active` o el modo está mal escrito, el tracker avisa y sigue con la regla
  (`RemovalModelLoader`). El modelo viaja en `src/main/resources/removal-model/model.json`; Java da la misma probabilidad que Python (1e-9, 200 casos).
- **Pruebas**: **278 de Java** (0 fallos, 0 omitidas) y **37 de Python**. Las pruebas anteriores al trabajo no se editaron (comprobado contra git: ningún archivo de pruebas existente cambió) y el código de producción existente solo creció (4 archivos, +254 líneas, −1).
- **Velocidad**: **0,22 ms** (p95) por decisión con fotogramas de 1280×1080 (meta: menos de 5 ms).

Decisión y alternativas: [`docs/adr/0007-modelo-verificacion-retiro.md`](../adr/0007-modelo-verificacion-retiro.md). Cómo reproducirlo:
[`object-presence-tracker/ml/README.md`](../../object-presence-tracker/ml/README.md).

**Fuentes del código (versión final):** `object-presence-tracker/src/main/java/com/safemode/presence/` (`RemovalSampleLog`, `RemovalFeatures`,
`RemovalClassifier`, `RemovalModelLoader`, `RemovalModelMode`, `RemovalClassifierBenchmark` y cambios mínimos en `ObjectTracker`,
`RegionFingerprint`, `TrackedObject`), `object-presence-tracker/ml/` (`load_dataset.py`, `models_numpy.py`, `train_removal.py`, `export_model.py`,
`dataset_summary.py`, pruebas) y `src/test/java/.../Removal*Test.java`. Datos reales: `object-presence-tracker/data/removal-dataset/` (local, fuera
de git; ver *Ética*).

## Los datos

Capturados con la cámara de la laptop (captura de pantalla), en **un solo lugar** (escena A) y con **un solo operador**. Cada bloque tiene una
etiqueta que pone quien captura (`removed`: se llevan la mochila; `still`: no se movió), así que no hubo que etiquetar a mano.

| Sesión | Escenario | `removed` | `still` |
|---|---|---|---|
| A-removed-limpio-01 | levantar la mochila y salir | 7 | 3 (el detector parpadeó y la mochila volvió) |
| A-removed-tapado-01 | persona delante que toma la mochila | 5 | 0 |
| A-removed-tapado-02 | persona delante que toma la mochila | 11 | 0 |
| A-still-persona-01 | persona delante, la mochila no se mueve | 0 | 3 |
| A-still-persona-02 | persona delante, la mochila no se mueve | 0 | 10 |
| **Total de entrenamiento y validación cruzada** | | **23** | **16** |
| V-removed-limpio-01 (validación en vivo, no se usó para entrenar) | | 1 | 1 |

No cuentan: 9 retiros por desplazamiento (el objeto siguió a la vista y lo decide la regla por desplazamiento, fuera del alcance del modelo) y
7 eventos de menos de 2 frames sin verse. El replay de la regla en Python coincide con lo que registró el tracker en el **100 %** de las filas y
de los desenlaces (128 filas, 39 eventos). Ningún dato es sintético (las pruebas de Python usan datos inventados en carpetas temporales, y el
entrenamiento y el exportador los rechazan).

## 7. Análisis de resultados

### 7a. Los dos mejores casos de prueba

Cada caso es un **evento**: una racha de frames sin ver el objeto. La entrada por frame son las señales de la zona; la salida es lo que decide
cada método. `p` es la puntuación del modelo (retiro si `p ≥ 0,526` desde el 2.º frame sin ver el objeto). Aquí `p` es la **«fuera de partición»**: la
da un modelo entrenado sin la sesión a la que pertenece el evento, así que no vio ese evento al entrenar.

**Caso A. Una persona se para delante de la mochila; la mochila no se mueve** (`A-still-persona-02`, evento `o4`). Esperado: **no dar retiro**.

| k | x1 | x2 | x3 | x4 | personas | regla | p | modelo |
|---|---|---|---|---|---|---|---|---|
| 1 | 0,00 | 0,00 | 0,00 | 0,00 | 1 | espera | 0,087 | espera |
| 2 | 0,00 | 0,00 | 0,00 | 0,00 | 1 | espera | 0,087 | espera |
| 3 | 0,00 | 0,00 | 0,00 | 0,00 | 1 | **retiro (falso)** | 0,087 | espera |
| 4–7 | 0,00 | 0,00 | 0,00 | 0,00 | 1 | (ya decidido) | 0,087 | espera |

Logrado: **el modelo no da retiro en ningún frame; la regla lo da a los 3**. En el conjunto, la regla da retiro falso en 10 de 16 eventos `still` y el
modelo en 1 de 16 (el caso de 7b). En vivo, en modo sombra (modelo final), el mismo caso se vio así (consola): `[MODELO] #4 k=3 p=0.288 -> espera
(umbral 0.526) | regla: REMOVE`. (La `p` con la zona tapada cambia entre modelos de distintos pliegues, de 0,09 a 0,42, pero siempre queda lejos del umbral.)

**Caso B. Retiro con un cambio pequeño de la zona** (`A-removed-tapado-02`, evento `o13`: la mochila es oscura y la cama también). Esperado: **dar retiro**.

| k | x1 | x2 | x3 | x4 | personas | regla | p | modelo |
|---|---|---|---|---|---|---|---|---|
| 1 | 0,13 | 1,00 | 0,18 | 0,09 | 1 | espera | 0,542 | (solo se anota) |
| 2 | 0,13 | 1,00 | 0,20 | 0,10 | 0 | espera | 0,567 | **retiro** |
| 3–11 | 0,13 | 1,00 | 0,20 | 0,10 | 0 | «sigue ahí» | ≈ 0,57 | retiro (ya decidido) |
| 12 | 0,19 | 1,00 | 0,30 | 0,12 | 1 | **retiro** | 0,685 | retiro (ya decidido) |

Logrado: el modelo da el retiro a los **2 frames** y la regla a los **12** (unos 1,8 s contra unos 11 s, con ~1,1 fotogramas por segundo). La regla
esperaba porque `x1 = 0,13` queda por debajo de su umbral de 0,15 y solo cambió de opinión cuando pasó una persona. **Ojo con el margen**: `p ≈ 0,57`
está a solo 0,04 del umbral; es un acierto frágil.

### 7b. El caso más significativo

**Una persona se para delante de la mochila: la regla da un retiro falso.** Primera captura real (23-nov-2026, `still_persona_delante`, la mochila
**no** se movió). Los 3 eventos terminaron en retiro falso por la regla. El evento `o8`, fotograma a fotograma:

| k | x1 | x2 | x3 | x4 | x5 | personas | Regla | p (fuera de partición) | Modelo |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0,00 | 0,00 | 0,00 | 0,00 | 0,19 | 1 | espera | 0,415 | espera |
| 2 | 0,00 | 0,00 | 0,00 | 0,00 | 0,17 | 1 | espera | 0,415 | espera |
| 3 | 0,00 | 0,00 | 0,00 | 0,00 | 0,18 | 1 | **retiro (falso)** | 0,415 | espera |
| 4 | 0,44 | 1,00 | 0,65 | 0,24 | 0,25 | 0 | (ya decidido) | **0,829** | **retiro (falso)** |
| 5 | 0,00 | 0,00 | 0,00 | 0,00 | 0,18 | 1 | (ya decidido) | 0,415 | espera |

**Análisis.** Con `x2 ≈ 0` la persona tapa casi toda la zona y **no hay nada que comparar**: el 0 de `x1`, `x3` y `x4` significa «no se pudo medir», no «no
cambió». La regla tiene una cláusula para ese caso («no se puede comprobar, se da por retirado», porque quien se lleva un objeto suele taparlo) y
la aplica a los 3 frames, sin esperar a ver qué hay detrás. El modelo aprende lo contrario de los datos: con la zona tapada espera. Es lo que ocurre
en 9 de los 10 eventos donde la regla se equivoca.

**Este es también el único error del modelo (1 de 16).** En el frame 4 la persona ya no está, la zona se ve (`x2 = 1`) y cambió 0,44 aunque la mochila
seguía ahí (reapareció enseguida, `tail_end = RESEEN`). Esa fila se parece a un retiro de verdad y el modelo la trata como tal. La causa del cambio
de 0,44 no se pudo confirmar (el recorte del frame 4 no se guarda); pudo ser un cambio de luz o de posición. Es el tipo de ruido que ningún
umbral sobre estas 4 señales elimina.

**Lo que este caso no resuelve (límite importante).** Mientras la zona está tapada, las señales son iguales en «sigue ahí» y en «se la llevaron
detrás de alguien»: el modelo espera en los dos. En los datos **no hay ningún retiro con la zona tapada 3 o más frames al inicio** (las dos
sesiones `removed_tapado` salieron con la zona a la vista desde el primer frame), así que ese caso no está probado. En modo `active`, si la zona
quedara tapada hasta el tope de 15 frames, el objeto se dejaría de seguir **sin alerta**. Por eso se recomienda `shadow`.

### 7c. Resultados

Validación cruzada **agrupada por sesión** (3 pliegues; nunca frames de una misma sesión a los dos lados), unidad de evaluación = el evento,
intervalos de Wilson al 95 %. Se probaron 14 combinaciones (7 modelos × 2 formas de etiquetar los frames); el umbral se fijó con recall objetivo 0,98
(decidido antes de mirar cualquier prueba en vivo). Figuras en `object-presence-tracker/ml/results/`.

| | Retiros detectados | Falsos retiros | F1 | Decide a los (mediana) |
|---|---|---|---|---|
| Regla actual | 23/23 [0,86–1,00] | **10/16** [0,39–0,82] | 0,82 | 3 frames |
| **Modelo (pocket, 4 señales)** | 23/23 [0,86–1,00] | **1/16** [0,01–0,28] | **0,98** | **2 frames** |

AUC a nivel de evento: 0,96. Figuras: `confusion.png` (matrices), `umbral.png` (recall y falsos retiros según el umbral), `comparacion.png`, `roc.png`,
`ablacion.png`, `curva_aprendizaje.png`.

| Modelo (mejor configuración de cada uno) | Retiros | Falsos retiros |
|---|---|---|
| Perceptrón *pocket* / perceptrón simple | 23/23 | 1/16 |
| MLP 4 neuronas | 23/23 | 3/16 |
| Regresión logística · SVM lineal | 23/23 | 4/16 |
| SVM RBF (no se exporta) | 23/23 | 8/16 |
| MLP 6 neuronas | 22/23 | 8/16 |

Gana el más simple dentro de la tolerancia (los perceptrones empatan en 1/16). **Ablación**: quitar `x4` sube los falsos retiros a 4; quitar `x1`, `x2` o
`x3` no cambia el resultado. **Señales extra** (experimento, nunca adoptadas): `x5` y `last_visible_x1` no cambian nada; `covered_frames` suma 3 errores;
`frames_unseen` suma 2; las 4 juntas suman 4. **Curva de aprendizaje**: 5/16 falsos retiros con el 25 % de los datos, 4/16 con 50 y 75 %, 1/16 con todos.

**`frames_unseen` es un atajo aprendido**: los `still` de las capturas duran 2–6 s y los `removed` tienen una cola larga, así que «lleva muchos frames
sin verse = retiro» separa las clases sin mirar la zona. En producción, un `still` largo (el detector falla 20 s con la mochila a la vista) saldría
como retiro. Se prohibió como señal del modelo final en tres capas (entrenamiento, exportador y cargador de Java); ver el ADR.

**Metas del plan de trabajo** (no requisitos del Hito), en validación cruzada:

| Meta | Resultado |
|---|---|
| M1 recall ≥ 0,95 | **Cumplida** (23/23; límite inferior del intervalo 0,86) |
| M2 falsos retiros ≤ 15 % | Cumplida en la estimación (6,25 %); **no verificable**: el intervalo llega a 28 % con solo 16 `still` |
| M3 F1 ≥ el de la regla + 0,10 | **Cumplida** (0,98 contra 0,82, +0,16) |
| M4 ≥ 150 eventos por etiqueta y ≥ 3 escenas | **No cumplida** (23 y 16 eventos, 1 escena) |
| M5 decisión < 5 ms | **Cumplida** (p95 0,22 ms) |
| M6 latencia (informativa) | Mediana de 2 frames contra 3 de la regla (peor caso 7 contra 12) |

### Evidencia en vivo (modo sombra, modelo final, regla decidiendo)

- **Corrida 1, persona delante, 4 eventos** (registro reconstruido de la consola en `data/shadow-still-corrida1-reconstruido.log`): la regla dio **retiro
  falso en los 4** (a `k = 3`); el modelo esperó en los 4 (`p` máxima 0,390 con umbral 0,526).
- **Corrida 2, retiro de verdad**: un evento válido; modelo y regla dieron el retiro a `k = 2` (`p = 0,875`, margen 0,35 sobre el umbral). El resto fueron parpadeos
  de YOLO de 1 o 2 frames (`p` entre 0,29 y 0,44) donde el modelo esperó, correcto. Otros 3 retiros fueron por desplazamiento (los decide la regla).
- La escena `V` (validación en vivo) quedó con 1 `removed` y 1 `still` válidos: **no alcanza** para una prueba final con sentido, así que no se corrió.

### Lo que estos resultados NO prueban

1. **Con 16 `still` el intervalo de los falsos retiros llega a 28 %**: no se puede afirmar la meta de 15 %.
2. **Mejor de 14 combinaciones sobre los mismos datos**: la cifra sale algo optimista.
3. **El umbral está cerca del borde**: con umbral 0,6 el recall baja a 16/23 y con 0,7 a 10/23. Una mochila movida que vuelve da `p = 0,471`, a solo 0,055 de decidir «retiro».
4. **Una sola escena, una cámara, un operador, sin escena reservada distinta**: no se sabe cómo se comporta con otra luz u otra cámara. Todos los `still` con
   zona tapada son del mismo tipo (persona delante); faltan `still_detector`, `still_pasa` y `still_luz`.
5. **Sin retiros con la zona tapada al inicio** (ver 7b).

## 8. Conclusiones

### 8a. Dos aciertos y dos errores

| | Qué pasó | Aprendizaje |
|---|---|---|
| Acierto | **Registrar y validar antes de cambiar**: el registro de muestras y el modo sombra anotan lo que decide la regla sin tocar ninguna alerta; el replay de la regla en Python reproduce al 100 % lo que hizo el tracker; la validación es por sesión y el cargador nunca hace fallar al tracker. El sistema siguió funcionando durante todo el trabajo | Medir primero permite probar con la cámara sin poner en riesgo lo que ya funciona |
| Acierto | **Rechazar el atajo `frames_unseen`** aunque separaba bien (AUC 0,76 sola, +2 errores al agregarla): se detectó antes de entrenar y se bloqueó en el entrenamiento, el exportador y Java | Una señal que «ayuda» en validación puede estar aprendiendo el guion de las capturas; hay que preguntarse por qué separa |
| Error | **El guion `removed_tapado` no capturó el caso «tapado»**: en las dos sesiones (16 eventos) la persona se fue enseguida y la zona quedó a la vista desde el primer frame. Se notó después del primer bloque, no antes | Definir el guion por una señal observable (frames con `x2 ≈ 0` antes del retiro) y comprobarlo con `train_removal.py --signals` durante el primer bloque, no al final |
| Error | Se dimensionó la captura con **metas del prompt de trabajo** (150 eventos por clase, 3 escenas) como si fueran requisitos del curso. Los lineamientos del Hito no piden un número de datos | Contrastar el alcance con el enunciado antes de planear horas; se pasó a un plan mínimo que sí cabía |

### 8b. Tres conclusiones y trabajo futuro

1. **La regla actual falla cuando alguien tapa la mochila**: da retiro falso en 10 de 16 eventos `still` (62 %), porque con la zona tapada «no se puede comprobar»
   y se da por retirado.
2. **Un modelo pequeño (perceptrón de 4 señales de la zona, 5 parámetros) baja los falsos retiros de 10/16 a 1/16 sin perder retiros (23/23), decide antes
   (2 contra 3 frames de mediana) y cuesta 0,22 ms por decisión**, en validación cruzada con datos reales de una sola escena.
3. **Más señales y modelos más grandes no ayudaron**: ganó lo simple. El resultado es **prometedor pero no concluyente**; por eso se recomienda operarlo en modo
   `shadow` y validar en otra escena y con retiros con la zona tapada antes de dejar decidir al modelo (`active`).

**Trabajo futuro:** (a) **etiquetas del operador desde el panel** (3 respuestas: «era robo», «falsa alarma: seguía ahí», «era el dueño») para seguir mejorando
el modelo con datos reales sin capturas manuales; (b) una **escena distinta** (otro lugar o luz, y otra cámara) y retiros con la zona tapada para una prueba final
honesta; (c) **reidentificar al dueño por apariencia** (hoy, si el dueño sale de cuadro y vuelve, su retiro sale como de otra persona).

## Modo recomendado

**Código por defecto: `off`** (la regla de siempre). **Modo recomendado para demostrar y operar: `shadow`**: muestra al modelo sin cambiar ninguna alerta.
**`active` no se recomienda todavía** por los puntos 3–5 de «Lo que estos resultados NO prueban». Apagar: `SAFEMODE_REMOVAL_MODEL=off`.

## Ética, datos y privacidad

- El modelo usa **solo 4 números** (señales de brillo de la zona del objeto); **ninguna cara ni identidad**.
- Se guardan en `object-presence-tracker/data/removal-dataset/` (ignorada por git, no sale a ninguna API): CSV con esos números y recortes de
  ≤ 320 px **de la zona del objeto** para revisar errores a mano (si alguien tapa la mochila puede verse una mano o parte del cuerpo).
- La descripción de personas con IA externa (que sí envía recortes con caras) se apagó durante todas las capturas y corridas.
- Las personas que aparecen en las capturas lo saben y consintieron.

## Limitaciones honestas

- Una sola cámara (la de la laptop), una escena y un solo operador; ver «Lo que estos resultados NO prueban».
- La captura de pantalla es sensible a ventanas que se mueven (se detecta con `x5` y un aviso, no se evita).
- Con la zona tapada el modelo espera, igual que ante un robo hecho desde detrás de alguien; en `active` eso puede dejar de alertar tras 15 frames.
- Los 3 `still` que el sistema reetiquetó solo dentro de un bloque `removed` (la mochila volvió, a veces movida) se conservaron con la etiqueta «no alertar»;
  con tan pocos datos, 1 o 2 son ambiguos.
- Los retiros por desplazamiento (la mochila sigue a la vista mientras se la llevan) los decide la regla actual; el modelo no participa.
