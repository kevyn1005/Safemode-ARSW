#!/usr/bin/env python
"""Puente entre el entrenamiento (Python) y el tracker (Java): valida el modelo y genera los casos con los que una prueba comprueba
que Java calcula LO MISMO que Python (tolerancia 1e-9).

  python object-presence-tracker/ml/export_model.py toy
      Genera los archivos de prueba con modelos DE JUGUETE (pesos inventados, no entrenados; llevan "meta": {"toy": true}) y los casos de
      las senales de secuencia en object-presence-tracker/src/test/resources/removal-model/. Sirve para probar el calculo, el formato y la
      integracion sin haber entrenado nada. Nunca van a src/main.

  python object-presence-tracker/ml/export_model.py export [--model ml/results/model.json]
      Valida el modelo entrenado (el que escribe la Fase 3) y lo copia a object-presence-tracker/src/main/resources/removal-model/model.json
      (de ahi lo carga el tracker, ver RemovalModelLoader) y genera src/test/resources/removal-model/trained.cases.csv con las
      probabilidades de Python (>= 200 casos). Asi RemovalClassifierTest comprueba que Java da lo mismo que Python con el modelo de verdad.
      Se NIEGA a exportar un modelo de juguete, entrenado con sesiones sinteticas (SINT-) o que use frames_unseen (atajo aprendido). Despues hay que recompilar
      (mvn -pl object-presence-tracker -am install -DskipTests) y correr con SAFEMODE_REMOVAL_MODEL=shadow (ver ml/README.md).

Hace falta numpy (el entorno virtual de ml/README.md).
"""
import argparse
import csv
import json
import shutil
import sys
from pathlib import Path

import numpy as np

import removal_model
from removal_features import CATALOG, sequence_features

MODULE = Path(__file__).resolve().parent.parent
FIXTURES = MODULE / "src" / "test" / "resources" / "removal-model"
MAIN_RESOURCES = MODULE / "src" / "main" / "resources" / "removal-model"
DEFAULT_MODEL = Path(__file__).resolve().parent / "results" / "model.json"
CASES = 200
# no puede estar en un modelo final (aprende el guion de las capturas); los juguetes de prueba si pueden llevarla (toy-mlp8)
FORBIDDEN_IN_FINAL_MODEL = ("frames_unseen",)

# rango razonable de cada senal para generar casos de prueba
RANGES = {
    "x1_changed": (0.0, 1.0), "x2_visible": (0.0, 1.0), "x3_changed_soft": (0.0, 1.0), "x4_mean_abs_diff": (0.0, 1.0),
    "x5_frame_changed": (0.0, 1.0), "frames_unseen": (1, 15), "covered_frames": (0, 15), "last_visible_x1": (0.0, 1.0),
}
INTEGER = {"frames_unseen", "covered_frames"}


def make_cases(model, rng):
    features = model["features"]
    rows = []
    # extremos y ceros primero, despues casos al azar
    rows.append([RANGES[f][0] for f in features])
    rows.append([RANGES[f][1] for f in features])
    rows.append([(RANGES[f][0] + RANGES[f][1]) / 2 for f in features])
    while len(rows) < CASES:
        row = []
        for f in features:
            low, high = RANGES[f]
            row.append(float(rng.integers(low, high + 1)) if f in INTEGER else float(rng.uniform(low, high)))
        rows.append(row)
    return rows


def write_cases(model, path, rng):
    rows = make_cases(model, rng)
    probabilities = removal_model.probability(model, np.array(rows))
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(model["features"] + ["p"])
        for row, p in zip(rows, probabilities):
            writer.writerow([repr(float(v)) for v in row] + [repr(float(p))])


def _toy_meta(note):
    return {"toy": True, "note": note}


def toy_models(rng):
    note = "pesos inventados para probar el formato y la integracion; NO es el modelo entrenado"
    # regresion logistica con las 4 senales del plan y en un orden distinto al del catalogo (para probar que se eligen por nombre)
    features = ["x2_visible", "x1_changed", "x4_mean_abs_diff", "x3_changed_soft"]
    mean = [(RANGES[f][0] + RANGES[f][1]) / 2 for f in features]
    std = [(RANGES[f][1] - RANGES[f][0]) / 3.0 for f in features]
    logistic = {
        "format": 1, "kind": "logistic", "features": features, "mean": mean, "std": std, "threshold": 0.4,
        "logistic": {"weights": [float(w) for w in rng.normal(0, 1.5, len(features))], "bias": -0.3},
        "meta": _toy_meta(note),
    }
    # MLP 4-6-1 (37 parametros) con las 4 senales del plan: el tamano del modelo principal del plan
    features4 = ["x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff"]
    mlp4 = _toy_mlp(rng, features4, 0.35, note)
    # MLP con las 8 senales del catalogo (el experimento de la ablacion): el tamano MAXIMO previsto
    mlp8 = _toy_mlp(rng, list(CATALOG), 0.35, note)
    return {"toy-logistic": logistic, "toy-mlp": mlp4, "toy-mlp8": mlp8}


def _toy_mlp(rng, features, threshold, note):
    mean = [(RANGES[f][0] + RANGES[f][1]) / 2 for f in features]
    std = [(RANGES[f][1] - RANGES[f][0]) / 3.0 for f in features]
    return {
        "format": 1, "kind": "mlp", "features": features, "mean": mean, "std": std, "threshold": threshold,
        "mlp": {"w1": [[float(w) for w in rng.normal(0, 1.0, len(features))] for _ in range(6)],
                "b1": [float(b) for b in rng.normal(0, 0.5, 6)],
                "w2": [float(w) for w in rng.normal(0, 1.0, 6)], "b2": 0.1},
        "meta": _toy_meta(note),
    }


def write_feature_cases(path, rng):
    """Secuencias de senales y las 8 caracteristicas esperadas (RemovalFeaturesTest las reproduce en Java)."""
    columns = ["case", "k", "x1", "x2", "x3", "x4", "x5_raw"] + ["f_" + name for name in CATALOG]
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(columns)
        for case in range(40):
            length = int(rng.integers(1, 16))
            steps = []
            for k in range(1, length + 1):
                x2 = float(rng.uniform(0, 0.34)) if rng.random() < 0.45 else float(rng.uniform(0.35, 1.0))
                x5 = -1.0 if rng.random() < 0.15 else float(rng.uniform(0, 1))
                steps.append((float(rng.uniform(0, 1)), x2, float(rng.uniform(0, 1)), float(rng.uniform(0, 1)), x5, k))
            for step, features in zip(steps, sequence_features(steps)):
                x1, x2, x3, x4, x5, k = step
                writer.writerow([case, k] + [repr(v) for v in (x1, x2, x3, x4, x5)] + [repr(float(v)) for v in features])


def command_toy(_args):
    FIXTURES.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261123)
    for name, model in toy_models(rng).items():
        removal_model.save(model, FIXTURES / (name + ".json"))
        write_cases(model, FIXTURES / (name + ".cases.csv"), rng)
        print("escrito", FIXTURES / (name + ".json"), "y", name + ".cases.csv")
    write_feature_cases(FIXTURES / "features.cases.csv", rng)
    print("escrito", FIXTURES / "features.cases.csv")


def command_export(args):
    source = Path(args.model)
    try:
        model = removal_model.load(source)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("El modelo %s no es valido: %s" % (source, error), file=sys.stderr)
        return 1
    meta = model.get("meta") or {}
    if meta.get("toy"):
        print("Me niego a exportar %s: es un modelo de JUGUETE (meta.toy). A src/main solo va un modelo entrenado con datos reales." % source,
              file=sys.stderr)
        return 1
    banned = [f for f in model["features"] if f in FORBIDDEN_IN_FINAL_MODEL]
    if banned:
        print("Me niego a exportar %s: usa %s, un atajo aprendido que solo se permite en la ablacion (ver docs/adr/0007)."
              % (source, ", ".join(banned)), file=sys.stderr)
        return 1
    fake = [s for s in meta.get("train_sessions", []) if str(s).startswith("SINT-")]
    if fake:
        print("Me niego a exportar %s: se entreno con sesiones SINTETICAS (%s...). A src/main solo va un modelo entrenado con datos reales."
              % (source, ", ".join(fake[:3])), file=sys.stderr)
        return 1
    MAIN_RESOURCES.mkdir(parents=True, exist_ok=True)
    FIXTURES.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, MAIN_RESOURCES / "model.json")
    write_cases(model, FIXTURES / "trained.cases.csv", np.random.default_rng(20261124))
    print("Modelo %s valido (%s, %d senales: %s, umbral %.3f)" % (source, model["kind"], len(model["features"]),
                                                                  model["features"], model["threshold"]))
    print("Modelo copiado a", MAIN_RESOURCES / "model.json", "(el tracker lo carga de ahi)")
    print("Casos escritos en", FIXTURES / "trained.cases.csv", "- corre: mvn -pl object-presence-tracker -am test")
    print("Para que viaje en el jar: mvn -pl object-presence-tracker -am install -DskipTests")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("toy", help="genera modelos de juguete y casos para probar el formato")
    export = sub.add_parser("export", help="valida el modelo entrenado y genera sus casos de prueba")
    export.add_argument("--model", default=str(DEFAULT_MODEL), help="ruta del modelo entrenado (ml/results/model.json)")
    args = parser.parse_args()
    if args.command == "toy":
        command_toy(args)
        return 0
    return command_export(args)


if __name__ == "__main__":
    sys.exit(main())
