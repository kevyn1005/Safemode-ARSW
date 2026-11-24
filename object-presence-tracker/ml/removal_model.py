"""Formato del modelo de verificacion de retiro (JSON, formato 1) y su calculo en Python.

Es el contrato entre el entrenamiento (Fase 3, escribe este JSON) y el tracker (RemovalClassifier.java, lo lee). El calculo es el
mismo en los dos lados: estandarizar con (x - mean) / std y luego

  kind = "logistic":  p = sigmoide(bias + w . z)
  kind = "mlp":       p = sigmoide(b2 + w2 . relu(W1 z + b1))     (una capa oculta; W1[oculta][entrada])

Un perceptron o una SVM lineal tambien se exportan como "logistic" (es la misma cuenta; su "p" es solo una puntuacion entre 0 y 1).
Una SVM con nucleo RBF no se puede exportar: por eso, si empata con uno de estos, gana el mas simple.

JSON:
  {"format": 1, "kind": "logistic" | "mlp", "features": [...], "mean": [...], "std": [...], "threshold": 0.37,
   "logistic": {"weights": [...], "bias": 0.0}  |  "mlp": {"w1": [[...]], "b1": [...], "w2": [...], "b2": 0.0},
   "meta": {...}}     # meta es libre (con que se entreno, fecha, metricas): Java lo ignora
"""
import json
import math

import numpy as np

from removal_features import CATALOG

FORMAT = 1


def validate(model):
    """Lanza ValueError con un mensaje claro si el modelo no cumple el formato (las mismas reglas que valida Java)."""
    if model.get("format") != FORMAT:
        raise ValueError("format debe ser %d" % FORMAT)
    kind = model.get("kind")
    if kind not in ("logistic", "mlp"):
        raise ValueError('kind debe ser "logistic" o "mlp", no %r' % (kind,))
    features = model.get("features")
    if not features or not isinstance(features, list):
        raise ValueError("features debe ser una lista con al menos una senal")
    for name in features:
        if name not in CATALOG:
            raise ValueError("senal desconocida %r; las validas son %s" % (name, CATALOG))
    if len(set(features)) != len(features):
        raise ValueError("hay senales repetidas")
    n = len(features)
    _check_vector(model, "mean", n)
    _check_vector(model, "std", n)
    if any(s <= 0 for s in model["std"]):
        raise ValueError("std debe ser mayor que 0 (si una senal no varia, quitala del modelo)")
    threshold = model.get("threshold")
    if not isinstance(threshold, (int, float)) or not (0 < threshold < 1):
        raise ValueError("threshold debe estar entre 0 y 1 (sin incluirlos)")
    if kind == "logistic":
        block = model.get("logistic") or {}
        _check_vector(block, "weights", n, "logistic.")
        _check_number(block, "bias", "logistic.")
    else:
        block = model.get("mlp") or {}
        w1 = block.get("w1")
        if not w1:
            raise ValueError("mlp.w1 no puede estar vacia")
        for j, row in enumerate(w1):
            if len(row) != n or not all(_finite(v) for v in row):
                raise ValueError("mlp.w1[%d] debe tener %d numeros finitos" % (j, n))
        _check_vector(block, "b1", len(w1), "mlp.")
        _check_vector(block, "w2", len(w1), "mlp.")
        _check_number(block, "b2", "mlp.")


def load(path):
    with open(path, encoding="utf-8") as handle:
        model = json.load(handle)
    validate(model)
    return model


def save(model, path):
    validate(model)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(model, handle, indent=2)
        handle.write("\n")


def sigmoid(x):
    """Sigmoide estable, igual que RemovalClassifier.sigmoid en Java (acepta un numero o un arreglo)."""
    x = np.asarray(x, dtype=float)
    positive = x >= 0
    out = np.empty_like(x)
    out[positive] = 1.0 / (1.0 + np.exp(-x[positive]))
    e = np.exp(x[~positive])
    out[~positive] = e / (1.0 + e)
    return out


def probability(model, values):
    """Probabilidad de "retirado". values: arreglo (n,) o (m, n) con las senales en el orden de model["features"]."""
    values = np.atleast_2d(np.asarray(values, dtype=float))
    z = (values - np.asarray(model["mean"])) / np.asarray(model["std"])
    if model["kind"] == "logistic":
        score = model["logistic"]["bias"] + z @ np.asarray(model["logistic"]["weights"])
    else:
        mlp = model["mlp"]
        hidden = np.maximum(0.0, z @ np.asarray(mlp["w1"]).T + np.asarray(mlp["b1"]))
        score = mlp["b2"] + hidden @ np.asarray(mlp["w2"])
    return sigmoid(score)


def _finite(value):
    return isinstance(value, (int, float)) and math.isfinite(value)


def _check_number(block, key, prefix=""):
    if not _finite(block.get(key)):
        raise ValueError("%s%s debe ser un numero finito" % (prefix, key))


def _check_vector(block, key, length, prefix=""):
    vector = block.get(key)
    if not isinstance(vector, list) or len(vector) != length or not all(_finite(v) for v in vector):
        raise ValueError("%s%s debe ser una lista de %d numeros finitos" % (prefix, key, length))
