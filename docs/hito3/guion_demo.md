# Guion de la demo del Hito 3 (video de hasta 10 minutos)

**Versión del 24-nov-2026.** La demo usa el modo **`shadow`** (recomendado): el modelo opina por consola y la regla sigue decidiendo, así que se ve la
diferencia sin cambiar ninguna alerta. Los resultados que se muestran vienen de
[`resumen_para_informe.md`](resumen_para_informe.md). Los tiempos son una guía.

## Antes de grabar (checklist)

- [ ] `mvn -pl object-presence-tracker -am install -DskipTests` hecho (el modelo viaja en `src/main/resources/removal-model/model.json`).
- [ ] La cámara ocupa la **mitad izquierda** de la pantalla (`Win` + flecha izquierda) y no hay otras ventanas encima (Teams cerrado). La terminal va en la mitad derecha.
- [ ] En la terminal de la demo: `Remove-Item Env:NVIDIA_API_KEY -ErrorAction SilentlyContinue` (no se mandan caras a un servicio externo).
- [ ] Un ensayo completo antes de grabar. **Plan B si falla la cámara**: mostrar `data/shadow-still-corrida1-reconstruido.log` (consola real de la corrida 1) y las
      figuras de `object-presence-tracker/ml/results/`.
- [ ] Cada corrida guarda su consola en un archivo con **nombre distinto** (`Tee-Object` sobrescribe el archivo: así se perdió el log original de la corrida 1).
- [ ] Tener a mano las figuras: `confusion.png`, `umbral.png`, `comparacion.png` en `object-presence-tracker/ml/results/`.

## Recorrido

| Min | Qué se muestra | Qué se dice |
|---|---|---|
| 0:00–1:00 | Una diapositiva o el README: el problema | Safemode avisa cuando alguien se lleva una mochila. Cuando YOLO deja de verla hay que decidir si sigue ahí o se la llevaron. Hasta ahora lo decidía una regla fija |
| 1:00–2:30 | El caso 7b del resumen (tabla del evento `o8`) | Si alguien tapa la mochila no hay nada que comparar, y la regla «da por retirado». En los datos reales da retiro falso en **10 de 16** eventos donde la mochila no se movió |
| 2:30–5:00 | **Modo sombra en vivo, corrida 1** (comando de abajo): una persona se para delante de la mochila y la tapa 3–6 s, sin tocarla | En consola: `[MODELO] #N k=3 p=0.288 -> espera ... \| regla: REMOVE`. La regla da el retiro falso; el modelo espera. En sombra **decide la regla**: el modelo no cambia ninguna alerta |
| 5:00–6:15 | **Modo sombra en vivo, corrida 2**: alguien se lleva la mochila | `[MODELO] ... k=2 p=0.8xx -> RETIRADO ... \| regla: REMOVE`: ante un retiro claro el modelo y la regla coinciden |
| 6:15–7:15 | Dónde duda o falla: el evento `o8` (frame 4, `x1 = 0,44`) y `umbral.png` | El único error del modelo en validación; y el umbral cerca del borde: una mochila movida que vuelve da `p = 0,471` contra 0,526. Ser honestos |
| 7:15–8:30 | Resultados: `confusion.png` y la tabla regla frente al modelo; velocidad (comando de abajo); `mvn test` (278 pruebas) | Regla 10/16 falsos retiros; modelo 1/16, ambos 23/23 retiros; decide en 2 frames contra 3; 0,22 ms por decisión. Con intervalos de confianza anchos |
| 8:30–10:00 | Conclusiones, ética y trabajo futuro | Solo 4 números de la zona del objeto, ninguna cara. Prometedor pero no concluyente (una escena, 16 `still`): por eso `shadow`, no `active`. Futuro: etiquetas del operador desde el panel y una escena distinta |

## Comandos exactos (PowerShell, desde la raíz del repo)

**Corrida 1: modo sombra, persona delante** (la regla decide; el modelo se muestra):

```powershell
cd C:\Users\Home\Desktop\Safemode-ARSW
Remove-Item Env:NVIDIA_API_KEY, Env:SAFEMODE_REMOVAL_LOG, Env:SAFEMODE_SCENE, Env:SAFEMODE_SESSION, Env:SAFEMODE_LABEL, Env:SAFEMODE_SCENARIO, Env:SAFEMODE_REMOVAL_MODEL_FILE -ErrorAction SilentlyContinue
$env:SAFEMODE_REMOVAL_MODEL = "shadow"
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest" "-Dexec.args=150" | Select-String "Modelo de verificacion|MODELO|EN REPOSO|AVISO" | Tee-Object -FilePath object-presence-tracker\data\demo-sombra-persona-delante.log
```

**Corrida 2: modo sombra, retiro de verdad** (mismo comando; cambia solo el nombre del archivo, para no sobrescribir el anterior):

```powershell
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.ObjectPresenceTrackerTest" "-Dexec.args=150" | Select-String "Modelo de verificacion|MODELO|EN REPOSO|AVISO" | Tee-Object -FilePath object-presence-tracker\data\demo-sombra-retiro.log
```

Al arrancar debe salir `Modelo de verificacion de retiro: SHADOW (regresion logistica con 4 senales ...) [el recurso /removal-model/model.json]`. Si sale
`AVISO modelo de retiro`, el modelo no cargó y el tracker sigue con la regla (comportamiento previsto): revisar `src/main/resources/removal-model/model.json`.

**Velocidad** (para mostrar el número en vivo):

```powershell
mvn -pl object-presence-tracker exec:java "-Dexec.mainClass=com.safemode.presence.RemovalClassifierBenchmark"
```

**Pruebas** (278 de Java; la de Java = Python con el modelo real está incluida):

```powershell
mvn -pl object-presence-tracker -am test
```

**Para apagar el modelo:** `$env:SAFEMODE_REMOVAL_MODEL = "off"` (o `Remove-Item Env:SAFEMODE_REMOVAL_MODEL`): vuelve la regla de siempre.

**Modo activo** (`$env:SAFEMODE_REMOVAL_MODEL = "active"`): existe y decide el modelo, pero **no se recomienda ni se usa en la demo** hasta validar en otra escena
y con retiros con la zona tapada (ver «Modo recomendado» en el resumen).

## Qué decir si algo falla en vivo

- *Falta el archivo del modelo o está dañado:* el tracker **no falla**: imprime `AVISO modelo de retiro: ...` y sigue con la regla de siempre. Es el comportamiento previsto.
- *La mochila no se detecta:* confirmar que en consola sale `objeto(s), 1`; si no, acomodar la cámara o la mochila antes de seguir.
- *Aparece `AVISO: cambio TODO el cuadro`:* se movió o tapó una ventana; sirve para explicar `x5`.
- *En la corrida 1 no sale ningún `regla: REMOVE`:* la persona no tapó la mochila el tiempo suficiente; hay que pararse más cerca de la cámara que la mochila y tapar de 3 a 6 s.
