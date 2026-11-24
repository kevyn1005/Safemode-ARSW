#!/usr/bin/env python
"""Fase 3: entrena y valida el modelo "sigue ahi o fue retirado" y escribe ml/results/model.json.

    python object-presence-tracker/ml/train_removal.py            # entrena (validacion cruzada) y escribe results/
    python object-presence-tracker/ml/train_removal.py --final    # UNA sola vez: prueba el modelo ya elegido en la escena reservada (C)
    python object-presence-tracker/ml/train_removal.py --signals  # que senales separan las clases (no entrena nada; sirve con pocos datos)

Que hace (todo con semilla fija; sale lo mismo si se repite):
  1. Carga los eventos validos (load_dataset.py) y comprueba el dataset (rangos, y que repetir la regla actual reproduce el
     `outcome_rule` de cada evento). La escena reservada (--reserved, por defecto C) se aparta AL EMPEZAR: el entrenamiento ni la ve.
  2. Validacion cruzada AGRUPADA POR SESION (hasta 5 pliegues; nunca frames de una misma sesion a los dos lados). La unidad de
     evaluacion es el EVENTO (una racha de frames sin ver el objeto), no el frame:
        retiro detectado  = algun frame k >= 2 con p >= umbral          (la politica del tracker en modo "active")
        falso retiro      = un evento `still` en el que eso ocurre
  3. Compara la regla actual con: perceptron, perceptron pocket, regresion logistica (L2) y MLP (4 y 6 neuronas), HECHOS A MANO con
     numpy (models_numpy.py), y SVM lineal y RBF de scikit-learn. La logistica a mano se comprueba contra la de sklearn.
     El modelo PRINCIPAL usa solo las 4 senales de la zona del plan (x1-x4: cuanto cambio, cuanto se ve, cambio suave, diferencia media).
     Las otras 4 (x5_frame_changed, covered_frames, last_visible_x1, frames_unseen) son un EXPERIMENTO de la ablacion: se miden y se
     reportan, pero NUNCA se adoptan solas; si ayudan claramente lo decide la persona (ver --signals y la tabla de ablacion).
     Se prueban dos formas de etiquetar los frames:
        all      todos los frames de un evento heredan su etiqueta
        visible  en un evento `removed`, los frames con la zona tapada (x2 < 0.35) NO se usan para entrenar: con la zona tapada no se
                 puede saber si la llevaron, y etiquetarlos "retirado" ensenaria "tapado = retiro", justo el error de la regla actual
     `frames_unseen` NUNCA es senal del modelo final (decision del 24-nov-2026): es un atajo aprendido. Los `still` de las capturas duran 2-6 s
     y los `removed` tienen cola larga, asi que el modelo "aprenderia" el guion de las capturas (k grande = retiro) en vez de mirar la zona, y en
     produccion un `still` largo (el detector falla 20 s con la mochila a la vista) saldria como retiro. Solo se mide en la ablacion y
     build_model_json se niega a exportar un modelo que la use (ver docs/adr/0007).
  4. El umbral se elige para no dejar pasar retiros: el MAYOR con recall de eventos >= --recall-target (0.98, FIJADO el 24-nov-2026 antes
     de ver la escena C y que no se cambia despues; el plan decia 0.95, pero con 0.95 el umbral cae en el borde del acantilado del recall:
     en validacion el recall sale ~0.95 por construccion y en datos nuevos casi seguro queda por debajo; 0.98 deja margen para que la meta
     M1 de 0.95 se mida con holgura) sobre las puntuaciones fuera de particion. Entre los modelos EXPORTABLES (perceptron / pocket / logistica / SVM lineal -> "logistic"; MLP -> "mlp") gana el MAS
     SIMPLE si su resultado esta a no mas de --tie-events falsos retiros del mejor. La SVM RBF solo se compara.
  5. Ablacion senal por senal (y las 4 senales extra), curva de aprendizaje, ROC/AUC, matrices de confusion; reentrena el elegido con todo el entrenamiento y
     escribe results/model.json (formato de removal_model.py), metrics.json y las figuras (si hay matplotlib).

--final lee results/model.json (NO reentrena), lo prueba en la escena reservada contra la regla, reporta las metas M1-M6 y escribe
results/final_<escena>.json. Se niega a repetirse (a menos que uses --force): mirar la escena reservada varias veces y ajustar entre
medias la convierte en otra escena de entrenamiento.

Honestidad: se prueban muchas combinaciones sobre la misma validacion cruzada, asi que el mejor sale algo optimista; por eso existe la
escena reservada, que es la cifra que se reporta. Con pocos eventos los intervalos de Wilson salen anchos y se reportan tal cual.

Requiere numpy y scikit-learn (el entorno virtual de ml/README.md); matplotlib es opcional (figuras).
"""
import argparse
import json
import math
import sys
import warnings
from datetime import datetime, timezone
from itertools import product
from pathlib import Path

import numpy as np
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, roc_curve
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.svm import SVC, LinearSVC

import load_dataset
import removal_model
from models_numpy import LogisticNP, MLPNP, PerceptronNP, PocketPerceptronNP
from removal_features import CATALOG, COVERED_BELOW

RESULTS = Path(__file__).resolve().parent / "results"
# Fijado el 24-nov-2026, ANTES de ver la escena reservada, y no se cambia despues (ver el docstring y docs/adr/0007).
RECALL_TARGET = 0.98
DECIDES_FROM = 2  # la politica del tracker decide desde el 2.o frame sin ver el objeto (RemovalClassifier.DECIDES_FROM_FRAME)
DEFAULT_SEED = 20261124

ZONE = ["x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff"]
FEATURE_SETS = {"zona": ZONE}  # el modelo principal: SOLO las 4 senales del plan
# Las otras 4 del catalogo: experimento de la ablacion (se miden y se reportan; nunca se adoptan solas)
EXTRA_FEATURES = ["x5_frame_changed", "covered_frames", "last_visible_x1", "frames_unseen"]
EXTRA_MEANING = {
    "x5_frame_changed": "cuanto cambio el RESTO del cuadro (distingue un retiro de una ventana que se mueve o un cambio de luz)",
    "covered_frames": "cuantos frames seguidos lleva la zona casi toda tapada (memoria: esperar mientras alguien tapa)",
    "last_visible_x1": "cuanto habia cambiado la zona la ultima vez que se pudo ver (memoria: decidir al volver a verse)",
    "frames_unseen": "cuantos frames lleva sin verse el objeto (cuidado: puede 'hacer trampa', ver el docstring)",
}
# Senales que NO pueden estar en un modelo final aunque ayuden en validacion: aprenden el guion de las capturas, no el fenomeno.
FORBIDDEN_IN_FINAL_MODEL = ("frames_unseen",)
SYNTHETIC_PREFIX = "SINT-"  # make_synthetic_dataset.py marca asi sus sesiones; el entrenamiento real las rechaza
LABEL_MODES = ("all", "visible")
MODELS = ("perceptron", "pocket", "logistica", "svm_lineal", "svm_rbf", "mlp4", "mlp6")
EXPORTABLE = {"perceptron": "logistic", "pocket": "logistic", "logistica": "logistic", "svm_lineal": "logistic",
              "mlp4": "mlp", "mlp6": "mlp"}
PROBA_MODELS = ("logistica", "mlp4", "mlp6")  # los demas dan una puntuacion (decision_function) que se pasa por la sigmoide
LEARNING_FRACTIONS = (0.25, 0.5, 0.75, 1.0)
LOGISTIC_L2 = LogisticNP().l2


class TrainingError(Exception):
    pass


# --- estadistica ---------------------------------------------------------------------------------------------------------------
def wilson(successes, n, z=1.96):
    """Intervalo de Wilson al 95 % para una proporcion (sirve con pocos eventos, donde el normal falla)."""
    if n == 0:
        return (0.0, 1.0)
    p = successes / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


def event_metrics(events, detections):
    """events: lista de Event; detections[i] = k en que se dio el retiro o None. Metricas por EVENTO."""
    tp = sum(1 for e, d in zip(events, detections) if e.removed and d is not None)
    fn = sum(1 for e, d in zip(events, detections) if e.removed and d is None)
    fp = sum(1 for e, d in zip(events, detections) if not e.removed and d is not None)
    tn = sum(1 for e, d in zip(events, detections) if not e.removed and d is None)
    recall = tp / (tp + fn) if tp + fn else float("nan")
    frr = fp / (fp + tn) if fp + tn else float("nan")  # tasa de falsos retiros entre los eventos `still`
    precision = tp / (tp + fp) if tp + fp else float("nan")
    f1 = 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else float("nan")
    latencies = sorted(d for e, d in zip(events, detections) if e.removed and d is not None)
    median = float(np.median(latencies)) if latencies else None
    return {"tp": tp, "fn": fn, "fp": fp, "tn": tn, "recall": recall, "recall_ci": wilson(tp, tp + fn),
            "false_removal_rate": frr, "false_removal_ci": wilson(fp, fp + tn), "precision": precision, "f1": f1,
            "median_latency_frames": median}


# --- tabla de frames -----------------------------------------------------------------------------------------------------------
class Table:
    """Todos los frames de una lista de eventos en arreglos planos (event = indice del evento al que pertenece cada frame)."""

    def __init__(self, events):
        self.events = events
        self.X = np.vstack([e.X for e in events])
        self.k = np.concatenate([e.k for e in events])
        self.y = np.concatenate([np.full(len(e.k), 1 if e.removed else 0) for e in events])
        self.event = np.concatenate([np.full(len(e.k), i) for i, e in enumerate(events)])
        stops = np.cumsum([len(e.k) for e in events])
        self.slices = list(zip(np.concatenate([[0], stops[:-1]]), stops))


def training_mask(table, label_mode):
    """Que frames sirven para entrenar. Siempre k >= 2 (la politica no decide antes)."""
    mask = table.k >= DECIDES_FROM
    if label_mode == "visible":
        covered = table.X[:, CATALOG.index("x2_visible")] < COVERED_BELOW
        mask = mask & ~((table.y == 1) & covered)
    return mask


def frame_weights(table, mask):
    """Cada EVENTO pesa lo mismo (uno de 15 frames no domina a uno de 3) y las dos clases suman lo mismo."""
    w = np.zeros(len(table.y))
    for a, b in table.slices:
        idx = np.flatnonzero(mask[a:b]) + a
        if idx.size:
            w[idx] = 1.0 / idx.size
    for cls in (0, 1):
        chosen = (table.y == cls) & mask
        total = w[chosen].sum()
        if total > 0:
            w[chosen] *= 0.5 / total
    return w * mask.sum()


# --- modelos -------------------------------------------------------------------------------------------------------------------
def make_model(name, seed):
    if name == "perceptron":
        return PerceptronNP(eta=0.1, epochs=50, seed=seed)
    if name == "pocket":
        return PocketPerceptronNP(eta=0.1, epochs=50, seed=seed)
    if name == "logistica":
        return LogisticNP(l2=LOGISTIC_L2)
    if name == "svm_lineal":
        return LinearSVC(C=1.0, random_state=seed, max_iter=50000)
    if name == "svm_rbf":
        return SVC(kernel="rbf", C=1.0, gamma="scale")
    if name.startswith("mlp"):
        return MLPNP(hidden=int(name[3:]), seed=seed)
    raise ValueError(name)


def n_params(name, n_features):
    if name in ("perceptron", "pocket", "logistica", "svm_lineal"):
        return n_features + 1
    if name.startswith("mlp"):
        h = int(name[3:])
        return h * (n_features + 1) + h + 1
    return 10 ** 9  # SVM RBF: cientos de vectores de soporte; no se exporta


class Fitted:
    def __init__(self, model, name, cols, mean, std):
        self.model, self.name, self.cols, self.mean, self.std = model, name, cols, mean, std

    def standardize(self, X8):
        return (X8[:, self.cols] - self.mean) / self.std

    def predict(self, X8):
        Z = self.standardize(X8)
        if self.name in PROBA_MODELS:
            return self.model.predict_proba(Z)[:, 1]
        return removal_model.sigmoid(self.model.decision_function(Z))


def fit(table, mask, features, name, seed):
    cols = [CATALOG.index(f) for f in features]
    y = table.y[mask]
    if y.size == 0 or y.min() == y.max():
        raise TrainingError("el conjunto de entrenamiento solo tiene una clase: faltan eventos de la otra etiqueta")
    X = table.X[mask][:, cols]
    mean = X.mean(axis=0)
    std = X.std(axis=0)
    std = np.where(std < 1e-9, 1.0, std)
    weights = frame_weights(table, mask)[mask]
    model = make_model(name, seed)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", ConvergenceWarning)
        model.fit((X - mean) / std, y.astype(float), sample_weight=weights)
    return Fitted(model, name, cols, mean, std)


def usable_features(table, mask, features):
    """Quita las senales que no varian en el conjunto de entrenamiento (std = 0): el formato del modelo exige std > 0."""
    cols = [CATALOG.index(f) for f in features]
    std = table.X[mask][:, cols].std(axis=0)
    kept = [f for f, s in zip(features, std) if s >= 1e-9]
    return kept, [f for f in features if f not in kept]


def logistic_vs_sklearn(table, mask, features, seed):
    """Comprobacion: la logistica HECHA A MANO da las mismas probabilidades que la de scikit-learn (mismo objetivo: C = 1 / (l2 * suma de pesos))."""
    mine = fit(table, mask, features, "logistica", seed)
    Z = mine.standardize(table.X[mask])
    y = table.y[mask]
    w = frame_weights(table, mask)[mask]
    reference = LogisticRegression(C=1.0 / (LOGISTIC_L2 * w.sum()), max_iter=20000, tol=1e-12).fit(Z, y, sample_weight=w)
    return float(np.max(np.abs(mine.model.predict_proba(Z)[:, 1] - reference.predict_proba(Z)[:, 1])))


# --- validacion cruzada y decision por evento -----------------------------------------------------------------------------------
def make_folds(events, seed):
    """Pliegues de EVENTOS agrupados por sesion y estratificados por etiqueta."""
    sessions_by_label = {label: {e.session for e in events if e.label == label} for label in ("removed", "still")}
    for label, sessions in sessions_by_label.items():
        if len(sessions) < 2:
            raise TrainingError("la validacion cruzada agrupada por sesion necesita al menos 2 sesiones con eventos '%s' "
                                "(hay %d): captura otro bloque" % (label, len(sessions)))
    n_splits = max(2, min(5, min(len(s) for s in sessions_by_label.values())))
    y = np.array([1 if e.removed else 0 for e in events])
    groups = np.array([e.session for e in events])
    splitter = StratifiedGroupKFold(n_splits=n_splits, shuffle=True, random_state=seed)
    return [(tr, te) for tr, te in splitter.split(np.zeros(len(events)), y, groups)]


def out_of_fold(table, folds, label_mode, features, name, seed, fraction=1.0):
    """Puntuacion de cada frame con un modelo que NO lo vio (su sesion estaba en el pliegue de prueba).

    fraction < 1: entrena con ese porcentaje de los eventos de cada etiqueta del pliegue (curva de aprendizaje)."""
    base = training_mask(table, label_mode)
    rng = np.random.default_rng(seed)
    p = np.full(len(table.y), np.nan)
    for train_events, test_events in folds:
        if fraction < 1.0:
            kept = []
            for label in (True, False):
                pool = [i for i in train_events if table.events[i].removed == label]
                take = max(1, int(round(fraction * len(pool))))
                kept.extend(rng.choice(pool, size=take, replace=False).tolist())
            train_events = np.array(sorted(kept))
        train_mask = base & np.isin(table.event, train_events)
        test_mask = np.isin(table.event, test_events)
        p[test_mask] = fit(table, train_mask, features, name, seed).predict(table.X[test_mask])
    return p


def detections(table, p, theta):
    """k del primer frame (>= 2) con p >= theta, por evento; None si nunca llega (el tracker lo dejaria seguir)."""
    out = []
    for a, b in table.slices:
        ks, pe = table.k[a:b], p[a:b]
        hit = np.flatnonzero((ks >= DECIDES_FROM) & (pe >= theta))
        out.append(int(ks[hit[0]]) if hit.size else None)
    return out


def event_scores(table, p):
    """Puntuacion de cada evento = el mayor p de sus frames k >= 2 (lo que decide si el modelo lo da por retirado a un umbral dado)."""
    out = []
    for a, b in table.slices:
        usable = p[a:b][table.k[a:b] >= DECIDES_FROM]
        out.append(float(usable.max()) if usable.size else 0.0)
    return np.array(out)


def choose_threshold(table, p, target=None):
    """El MAYOR umbral con recall de eventos >= target: de las puntuaciones maximas de los eventos `removed`, la mas baja que hay
    que conservar. Se redondea hacia abajo a 4 decimales (asi p >= umbral sigue siendo cierto tras guardar el JSON).

    OJO: con target = 0.95 el umbral cae justo en el borde del acantilado del recall (la figura umbral.png lo muestra): en validacion el
    recall sale ~0.95 por construccion y en datos nuevos puede quedar por debajo. --recall-target 0.98 (o 1.0) deja margen."""
    target = RECALL_TARGET if target is None else target
    scores = event_scores(table, p)
    best = sorted((s for s, e in zip(scores, table.events) if e.removed), reverse=True)
    if not best:
        raise TrainingError("no hay eventos 'removed' en el entrenamiento")
    need = max(1, math.ceil(target * len(best) - 1e-9))
    theta = best[need - 1]
    return min(0.999, max(0.001, math.floor(theta * 1e4) / 1e4))


def rule_detections(events):
    return [e.rule_removes() for e in events]


# --- una combinacion = un candidato --------------------------------------------------------------------------------------------
def evaluate_features(table, folds, label_mode, features, set_name, model_name, seed, fraction=1.0):
    p = out_of_fold(table, folds, label_mode, features, model_name, seed, fraction)
    theta = choose_threshold(table, p)
    metrics = event_metrics(table.events, detections(table, p, theta))
    row = {"model": model_name, "features_set": set_name, "label_mode": label_mode, "features": list(features),
           "n_params": n_params(model_name, len(features)), "exportable": model_name in EXPORTABLE, "threshold": theta}
    row.update(metrics)
    return row, p


def evaluate_candidate(table, folds, label_mode, set_name, model_name, seed):
    features, dropped = usable_features(table, training_mask(table, label_mode), FEATURE_SETS[set_name])
    if not features:
        return None, None
    row, p = evaluate_features(table, folds, label_mode, features, set_name, model_name, seed)
    row["dropped_constant"] = dropped
    return row, p


def select_candidate(rows, tie_events):
    """Entre los exportables que alcanzan el recall objetivo: el mas simple dentro de `tie_events` falsos retiros del mejor.
    Si ninguno lo alcanza, el de mayor recall y menos falsos retiros (y se avisa)."""
    exportable = [r for r in rows if r["exportable"]]
    if not exportable:
        raise TrainingError("no hay candidatos exportables")
    reach = [r for r in exportable if r["recall"] >= RECALL_TARGET - 1e-9]
    if not reach:
        best = max(exportable, key=lambda r: (r["recall"], -r["fp"], -r["n_params"]))
        return best, False
    least_fp = min(r["fp"] for r in reach)
    close = [r for r in reach if r["fp"] <= least_fp + tie_events]
    # empate de simplicidad: la logistica (da una probabilidad con sentido) antes que el pocket y este antes que el perceptron simple
    order = ("logistica", "pocket", "perceptron", "svm_lineal", "mlp4", "mlp6")
    return min(close, key=lambda r: (r["n_params"], r["fp"], order.index(r["model"]), r["features_set"], r["label_mode"])), True


def ablation(table, folds, chosen, seed):
    """Quita cada senal del elegido por separado y, aparte, le AGREGA cada senal extra (y las 4 juntas = las 8 del catalogo).
    Mismo modelo, etiquetado y procedimiento. Es un experimento: nada de esto cambia el modelo elegido."""
    variants = [("(ninguna: el elegido)", chosen["features"])]
    variants += [("sin " + f, [g for g in chosen["features"] if g != f]) for f in chosen["features"] if len(chosen["features"]) > 1]
    variants += [("con " + f, chosen["features"] + [f]) for f in EXTRA_FEATURES]
    variants.append(("con las 4 extra (8 senales)", chosen["features"] + EXTRA_FEATURES))
    rows = []
    for label, features in variants:
        row, _ = evaluate_features(table, folds, chosen["label_mode"], features, label, chosen["model"], seed)
        rows.append(row)
    return rows


def signal_separation(table, mask=None):
    """Por cada senal del catalogo: promedio en los frames `still` y en los `removed`, y el AUC de ESA senal sola (0.5 = no separa nada,
    1.0 = separa perfecto; si el AUC sale bajo 0.5 la senal separa al reves, lo que tambien es informacion). Cada evento pesa lo mismo."""
    if mask is None:
        mask = training_mask(table, "visible")
    weights = frame_weights(table, mask)[mask]
    y = table.y[mask]
    out = []
    for name in CATALOG:
        column = table.X[mask][:, CATALOG.index(name)]
        out.append({"signal": name, "in_main_model": name in ZONE,
                    "mean_still": float(np.average(column[y == 0], weights=weights[y == 0])) if (y == 0).any() else None,
                    "mean_removed": float(np.average(column[y == 1], weights=weights[y == 1])) if (y == 1).any() else None,
                    "auc": float(roc_auc_score(y, column, sample_weight=weights)) if len(set(y)) == 2 else None})
    return out


def print_signal_separation(rows):
    print("\nQue senales separan las clases (cada senal SOLA; frames k >= 2; en los eventos `removed` no se cuentan los frames con la zona tapada)")
    print("%-18s %-12s %-12s %-14s %-6s %s" % ("senal", "en el modelo", "media still", "media removed", "AUC", "lectura"))
    for r in rows:
        if r["auc"] is None:
            reading = "faltan eventos de una de las dos etiquetas"
        else:
            strength = max(r["auc"], 1 - r["auc"])
            reading = "separa bien" if strength >= 0.85 else "separa algo" if strength >= 0.7 else "casi no separa"
            if r["auc"] < 0.5 and strength >= 0.7:
                reading += " (al reves: mas alta en still)"
        fmt = lambda v: "-" if v is None else "%.3f" % v
        print("%-18s %-12s %-12s %-14s %-6s %s" % (r["signal"], "si" if r["in_main_model"] else "extra", fmt(r["mean_still"]),
                                                     fmt(r["mean_removed"]), fmt(r["auc"]), reading))


def learning_curve(table, folds, chosen, seed):
    rows = []
    for fraction in LEARNING_FRACTIONS:
        row, _ = evaluate_features(table, folds, chosen["label_mode"], chosen["features"], "fraccion %.2f" % fraction, chosen["model"],
                                   seed, fraction)
        row["fraction"] = fraction
        row["train_events_per_fold"] = round(fraction * (len(table.events) * (len(folds) - 1) / len(folds)))
        rows.append(row)
    return rows


# --- modelo final ---------------------------------------------------------------------------------------------------------------
def build_model_json(fitted, features, row, meta):
    banned = [f for f in features if f in FORBIDDEN_IN_FINAL_MODEL]
    if banned:
        raise TrainingError("%s no puede ser senal del modelo final: es un atajo aprendido (aprende el guion de las capturas; ver el "
                            "ADR 0007). Solo se permite en la ablacion." % ", ".join(banned))
    mean = [float(v) for v in fitted.mean]
    std = [float(v) for v in fitted.std]
    kind = EXPORTABLE[fitted.name]
    model = {"format": removal_model.FORMAT, "kind": kind, "features": list(features), "mean": mean, "std": std,
             "threshold": row["threshold"], "meta": meta}
    if kind == "logistic":
        model["logistic"] = {"weights": [float(v) for v in fitted.model.coef_[0]], "bias": float(fitted.model.intercept_[0])}
    else:
        model["mlp"] = {"w1": [[float(v) for v in col] for col in fitted.model.coefs_[0].T],
                        "b1": [float(v) for v in fitted.model.intercepts_[0]],
                        "w2": [float(v) for v in fitted.model.coefs_[1][:, 0]],
                        "b2": float(fitted.model.intercepts_[1][0])}
    return model


def check_export_matches_trained(model_json, fitted, table):
    """El JSON que leera Java tiene que dar la misma probabilidad que el modelo entrenado (si no, algo se exporto mal)."""
    cols = [CATALOG.index(f) for f in model_json["features"]]
    exported = removal_model.probability(model_json, table.X[:, cols])
    trained = fitted.predict(table.X)
    worst = float(np.max(np.abs(exported - trained)))
    if worst > 1e-9:
        raise TrainingError("el modelo exportado difiere del entrenado (maxima diferencia %.3g)" % worst)
    return worst


# --- salida ---------------------------------------------------------------------------------------------------------------------
def _fmt(m, key):
    lo, hi = m[key + "_ci"]
    return "%d/%d [%.2f-%.2f]" % ((m["tp"], m["tp"] + m["fn"], lo, hi) if key == "recall" else (m["fp"], m["fp"] + m["tn"], lo, hi))


def print_table(title, rows, limit=None, name_key="features_set"):
    print("\n" + title)
    print("%-22s %-11s %-7s %6s  %-20s %-20s %6s %5s" % (name_key if name_key != "features_set" else "senales", "modelo", "etiq.",
                                                          "param.", "recall (IC95)", "falsos retiros (IC95)", "umbral", "F1"))
    for r in rows[:limit]:
        print("%-22s %-11s %-7s %6s  %-20s %-20s %6.3f %5.2f" % (
            r[name_key][:22], r["model"], r["label_mode"], r["n_params"] if r["n_params"] < 10 ** 9 else "-", _fmt(r, "recall"),
            _fmt(r, "false_removal"), r["threshold"], r["f1"]))


def make_figures(out_dir, table, p, chosen, rule_metrics, rows, ablation_rows, curve_rows):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("(matplotlib no esta instalado: se omiten las figuras)")
        return []
    green, red, grey = "#1f6f4a", "#b3261e", "#444444"
    written = []

    def save(fig, name):
        fig.tight_layout()
        path = out_dir / name
        fig.savefig(path, dpi=140)
        plt.close(fig)
        written.append(path)

    title = "%s (%s, %s), validacion cruzada por sesion" % (chosen["model"], chosen["features_set"], chosen["label_mode"])
    grid = np.linspace(0.01, 0.99, 99)
    recall, frr = [], []
    for theta in grid:
        m = event_metrics(table.events, detections(table, p, theta))
        recall.append(m["recall"])
        frr.append(m["false_removal_rate"])
    fig, ax = plt.subplots(figsize=(6.4, 4))
    ax.plot(grid, recall, label="recall de retiros (eventos)", color=green)
    ax.plot(grid, frr, label="falsos retiros (eventos still)", color=red)
    ax.axvline(chosen["threshold"], color=grey, linestyle="--", linewidth=1, label="umbral elegido %.3f" % chosen["threshold"])
    ax.axhline(rule_metrics["false_removal_rate"], color=red, linestyle=":", linewidth=1, label="falsos retiros de la regla actual")
    ax.set_xlabel("umbral sobre p")
    ax.set_ylabel("proporcion de eventos")
    ax.set_title(title, fontsize=9)
    ax.set_ylim(-0.02, 1.02)
    ax.legend(fontsize=8)
    save(fig, "umbral.png")

    best_by_model = {}
    for r in rows:
        if r["model"] not in best_by_model or (r["recall"], -r["fp"]) > (best_by_model[r["model"]]["recall"], -best_by_model[r["model"]]["fp"]):
            best_by_model[r["model"]] = r
    names = ["regla actual"] + list(best_by_model)
    ms = [rule_metrics] + list(best_by_model.values())
    fig, ax = plt.subplots(figsize=(7.4, 4))
    x = np.arange(len(names))
    ax.bar(x - 0.2, [m["recall"] for m in ms], 0.4, label="recall", color=green)
    ax.bar(x + 0.2, [m["false_removal_rate"] for m in ms], 0.4, label="falsos retiros", color=red)
    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=20, fontsize=8)
    ax.set_ylim(0, 1.05)
    ax.set_title("Regla actual contra la mejor configuracion de cada modelo (validacion cruzada)", fontsize=9)
    ax.legend(fontsize=8)
    save(fig, "comparacion.png")

    model_metrics = event_metrics(table.events, detections(table, p, chosen["threshold"]))
    fig, axes = plt.subplots(1, 2, figsize=(7, 3.4))
    for ax, name, m in zip(axes, ("regla actual", "modelo (%s)" % chosen["model"]), (rule_metrics, model_metrics)):
        matrix = np.array([[m["tn"], m["fp"]], [m["fn"], m["tp"]]])
        ax.imshow(matrix, cmap="Blues")
        for (i, j), value in np.ndenumerate(matrix):
            ax.text(j, i, str(value), ha="center", va="center", fontsize=12, color="black" if value < matrix.max() * 0.6 else "white")
        ax.set_xticks([0, 1])
        ax.set_xticklabels(["sigue ahi", "retirado"], fontsize=8)
        ax.set_yticks([0, 1])
        ax.set_yticklabels(["still", "removed"], fontsize=8)
        ax.set_xlabel("lo que dice")
        ax.set_ylabel("la verdad")
        ax.set_title(name, fontsize=9)
    save(fig, "confusion.png")

    y_event = np.array([1 if e.removed else 0 for e in table.events])
    fpr, tpr, _ = roc_curve(y_event, event_scores(table, p))
    auc = roc_auc_score(y_event, event_scores(table, p))
    fig, ax = plt.subplots(figsize=(4.8, 4.4))
    ax.plot(fpr, tpr, color=green, label="modelo (AUC %.3f)" % auc)
    ax.plot([0, 1], [0, 1], color="#999", linestyle=":", linewidth=1)
    ax.scatter([rule_metrics["false_removal_rate"]], [rule_metrics["recall"]], color=red, zorder=3, label="regla actual")
    ax.set_xlabel("falsos retiros (tasa)")
    ax.set_ylabel("recall de retiros")
    ax.set_title("ROC a nivel de evento", fontsize=9)
    ax.legend(fontsize=8, loc="lower right")
    save(fig, "roc.png")

    fig, ax = plt.subplots(figsize=(7.4, 3.8))
    x = np.arange(len(ablation_rows))
    ax.bar(x - 0.2, [r["recall"] for r in ablation_rows], 0.4, label="recall", color=green)
    ax.bar(x + 0.2, [r["false_removal_rate"] for r in ablation_rows], 0.4, label="falsos retiros", color=red)
    ax.set_xticks(x)
    ax.set_xticklabels([r["features_set"] for r in ablation_rows], rotation=25, ha="right", fontsize=7)
    ax.set_ylim(0, 1.05)
    ax.set_title("Ablacion de senales (se quita una y se reentrena)", fontsize=9)
    ax.legend(fontsize=8)
    save(fig, "ablacion.png")

    fig, ax = plt.subplots(figsize=(6, 3.8))
    ax.plot([r["fraction"] for r in curve_rows], [r["f1"] for r in curve_rows], marker="o", color=green, label="F1")
    ax.plot([r["fraction"] for r in curve_rows], [r["false_removal_rate"] for r in curve_rows], marker="o", color=red, label="falsos retiros")
    ax.set_xlabel("fraccion de los eventos de entrenamiento usados")
    ax.set_ylim(0, 1.05)
    ax.set_title("Curva de aprendizaje: bastan estos eventos?", fontsize=9)
    ax.legend(fontsize=8)
    save(fig, "curva_aprendizaje.png")
    return written


def _clean(value):
    """Para json.dump: NaN -> None, tuplas -> listas, numpy -> numeros de Python."""
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean(v) for v in value]
    if isinstance(value, (np.floating, float)):
        return None if math.isnan(value) else float(value)
    if isinstance(value, np.integer):
        return int(value)
    return value


def write_json(path, data):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(_clean(data), handle, indent=2, ensure_ascii=False)
        handle.write("\n")


def check_dataset(events, allow_synthetic=False):
    """Validacion del dataset antes de entrenar (rangos, secuencias y que repetir la regla reproduce el outcome registrado).
    Rechaza cualquier sesion marcada como SINTETICA (make_synthetic_dataset.py): solo las pruebas de Python pueden usarlas."""
    if not allow_synthetic:
        fake = sorted({e.session for e in events if e.session.startswith(SYNTHETIC_PREFIX)})
        if fake:
            raise TrainingError("hay sesiones SINTETICAS en el dataset (%s...): el entrenamiento real solo acepta datos capturados con la "
                                "camara. Quita esas sesiones o apunta --data al dataset real." % ", ".join(fake[:3]))
    problems = load_dataset.validate_events(events) + [
        "%s: la regla repetida en Python da %s pero el tracker registro %s" % item for item in load_dataset.check_outcome_replay(events)]
    problems += ["%s k=%d: replay %s, tracker %s" % item for item in load_dataset.check_rule_replay(events)]
    if problems:
        shown = "\n  ".join(problems[:15])
        raise TrainingError("el dataset tiene %d problema(s); arreglalos antes de entrenar:\n  %s" % (len(problems), shown))


# --- comandos -------------------------------------------------------------------------------------------------------------------
def command_signals(args, events):
    """Solo informa: que senales separan las clases (sin mirar la escena reservada). No entrena ni escribe nada."""
    check_dataset(events, getattr(args, "allow_synthetic", False))
    train = [e for e in events if e.scene != args.reserved]
    if not train:
        raise TrainingError("no hay eventos validos fuera de la escena reservada (%s)" % args.reserved)
    n_removed = sum(1 for e in train if e.removed)
    print("Eventos (sin la escena reservada %s): %d (%d removed, %d still) en %d sesion(es)." % (
        args.reserved, len(train), n_removed, len(train) - n_removed, len({e.session for e in train})))
    print_signal_separation(signal_separation(Table(train)))
    print("\nLas 4 del modelo principal son x1-x4. Las 4 'extra' son un experimento: si alguna ayuda claramente, lo decides tu; "
          "el entrenamiento completo (sin --signals) mide cuanto mejora cada una al agregarla.")
    for name in EXTRA_FEATURES:
        print("  %-18s %s" % (name, EXTRA_MEANING[name]))
    if n_removed == 0 or n_removed == len(train):
        print("\n(Con una sola etiqueta no hay AUC: faltan eventos '%s'.)" % ("removed" if n_removed == 0 else "still"))
    return 0


def command_train(args, events):
    check_dataset(events, getattr(args, "allow_synthetic", False))
    reserved = [e for e in events if e.scene == args.reserved]
    train = [e for e in events if e.scene != args.reserved]  # la escena reservada se aparta AQUI y no se vuelve a tocar
    if not train:
        raise TrainingError("no hay eventos validos fuera de la escena reservada (%s)" % args.reserved)
    n_removed = sum(1 for e in train if e.removed)
    print("Eventos de entrenamiento: %d (%d removed, %d still) en %d sesion(es), escenas %s. Escena reservada %s: %d eventos (sin mirar)."
          % (len(train), n_removed, len(train) - n_removed, len({e.session for e in train}),
             ",".join(sorted({e.scene for e in train})), args.reserved, len(reserved)))
    if n_removed == 0 or n_removed == len(train):
        raise TrainingError("falta una etiqueta completa: hay que capturar eventos '%s' (ver GUIA_CAPTURA.md)"
                            % ("removed" if n_removed == 0 else "still"))
    folds = make_folds(train, args.seed)
    print("Validacion cruzada agrupada por sesion: %d pliegues." % len(folds))
    table = Table(train)

    rule = event_metrics(train, rule_detections(train))
    print("\nRegla actual (replay) sobre estos eventos: recall %s | falsos retiros %s | latencia mediana %s frames"
          % (_fmt(rule, "recall"), _fmt(rule, "false_removal"), rule["median_latency_frames"]))

    rows, scores = [], {}
    for label_mode, set_name, model_name in product(LABEL_MODES, FEATURE_SETS, MODELS):
        row, p = evaluate_candidate(table, folds, label_mode, set_name, model_name, args.seed)
        if row is not None:
            rows.append(row)
            scores[(label_mode, set_name, model_name)] = p
    rows.sort(key=lambda r: (-r["recall"], r["fp"], r["n_params"]))
    print_table("Mejores 12 de %d combinaciones (orden: recall, falsos retiros, simplicidad)" % len(rows), rows, 12)

    chosen, reached = select_candidate(rows, args.tie_events)
    if not reached:
        print("\nAVISO: ningun modelo exportable llega a recall %.2f; se elige el de mayor recall." % RECALL_TARGET)
    p_chosen = scores[(chosen["label_mode"], chosen["features_set"], chosen["model"])]
    print("\nElegido: %s | senales %s (%s) | etiquetado %s | umbral %.4f | recall %s | falsos retiros %s"
          % (chosen["model"], chosen["features_set"], ", ".join(chosen["features"]), chosen["label_mode"], chosen["threshold"],
             _fmt(chosen, "recall"), _fmt(chosen, "false_removal")))
    at_half = event_metrics(table.events, detections(table, p_chosen, 0.5))
    print("Con umbral 0.5 (sin ajustar): recall %s | falsos retiros %s | F1 %.2f" % (_fmt(at_half, "recall"), _fmt(at_half, "false_removal"), at_half["f1"]))
    y_event = np.array([1 if e.removed else 0 for e in train])
    auc = float(roc_auc_score(y_event, event_scores(table, p_chosen)))
    print("AUC a nivel de evento: %.3f" % auc)

    ablation_rows = ablation(table, folds, chosen, args.seed)
    print_table("Ablacion y experimento de senales extra (se quita o se agrega UNA senal y se reentrena; la 1.a fila es el elegido)",
                ablation_rows)
    base = ablation_rows[0]
    print("Errores por evento (retiros perdidos + falsos retiros) del elegido: %d. Cambio al agregar una senal extra (negativo = mejora):"
          % (base["fn"] + base["fp"]))
    for row in ablation_rows:
        if row["features_set"].startswith("con "):
            print("  %-30s %+d eventos mal clasificados" % (row["features_set"], (row["fn"] + row["fp"]) - (base["fn"] + base["fp"])))
    print("  (la decision de adoptar alguna senal extra es tuya; el modelo que se escribe usa solo las 4 del plan)")
    signals = signal_separation(table)
    print_signal_separation(signals)
    curve_rows = learning_curve(table, folds, chosen, args.seed)
    print("\nCurva de aprendizaje (mismo modelo; entrena con una fraccion de los eventos de cada etiqueta de cada pliegue)")
    print("%-9s %-14s %-20s %-20s %5s" % ("fraccion", "eventos/pliegue", "recall (IC95)", "falsos retiros (IC95)", "F1"))
    for r in curve_rows:
        print("%-9.2f %-14d %-20s %-20s %5.2f" % (r["fraction"], r["train_events_per_fold"], _fmt(r, "recall"), _fmt(r, "false_removal"), r["f1"]))
    by_mode = sorted((r for r in rows if r["model"] == chosen["model"] and r["features_set"] == chosen["features_set"]), key=lambda r: r["label_mode"])
    print_table("Etiquetado de frames (mismo modelo y senales que el elegido)", by_mode)

    base = training_mask(table, chosen["label_mode"])
    fitted = fit(table, base, chosen["features"], chosen["model"], args.seed)
    sk_diff = logistic_vs_sklearn(table, base, chosen["features"], args.seed)
    print("\nComprobacion: la logistica hecha a mano difiere de la de scikit-learn en %.1e (maxima diferencia de probabilidad)" % sk_diff)

    meta = {"trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "seed": args.seed, "model": chosen["model"],
            "features_set": chosen["features_set"], "label_mode": chosen["label_mode"], "recall_target": RECALL_TARGET,
            "train_events": {"removed": n_removed, "still": len(train) - n_removed}, "train_sessions": sorted({e.session for e in train}),
            "train_scenes": sorted({e.scene for e in train}), "reserved_scene": args.reserved,
            "cv": {"folds": len(folds), "recall": chosen["recall"], "recall_ci": list(chosen["recall_ci"]),
                   "false_removal_rate": chosen["false_removal_rate"], "false_removal_ci": list(chosen["false_removal_ci"]),
                   "median_latency_frames": chosen["median_latency_frames"], "auc": auc},
            "rule_on_same_events": {"recall": rule["recall"], "false_removal_rate": rule["false_removal_rate"]}}
    model_json = build_model_json(fitted, chosen["features"], chosen, meta)
    removal_model.validate(model_json)
    worst = check_export_matches_trained(model_json, fitted, table)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    removal_model.save(model_json, out / "model.json")
    write_json(out / "metrics.json", {"meta": meta, "rule": rule, "selected": chosen, "at_threshold_0.5": at_half, "auc": auc,
                                      "logistic_vs_sklearn_max_diff": sk_diff, "ablation": ablation_rows, "signal_separation": signals,
                                      "learning_curve": curve_rows,
                                      "candidates": rows})
    figures = make_figures(out, table, p_chosen, chosen, rule, rows, ablation_rows, curve_rows)
    print("\nEscrito %s (el JSON da lo mismo que el modelo entrenado, diferencia maxima %.1e)" % (out / "model.json", worst))
    print("Escrito %s y %d figura(s) en %s" % (out / "metrics.json", len(figures), out))
    print("Siguiente: python export_model.py export  (valida y prepara la prueba Java==Python) y, despues, --final UNA vez.")
    return 0


def goals_report(events, held, model_m, rule_m):
    """Las metas M1-M6 del prompt del Hito. OJO: son metas del plan de trabajo, no requisitos del enunciado del Hito."""
    per_label = {label: sum(1 for e in events if e.label == label) for label in ("removed", "still")}
    scenes = sorted({e.scene for e in events})
    f1_gain = model_m["f1"] - rule_m["f1"]
    return {
        "M1_recall_de_retiros": {"criterio": ">= 0.95", "valor": model_m["recall"], "ic95": list(model_m["recall_ci"]),
                                 "cumplida": bool(model_m["recall"] >= 0.95)},
        "M2_falsos_retiros": {"criterio": "<= 0.15", "valor": model_m["false_removal_rate"], "ic95": list(model_m["false_removal_ci"]),
                              "cumplida": bool(model_m["false_removal_rate"] <= 0.15)},
        "M3_F1_contra_la_regla": {"criterio": "F1 modelo >= F1 regla + 0.10", "modelo": model_m["f1"], "regla": rule_m["f1"],
                                  "diferencia": f1_gain, "cumplida": bool(f1_gain >= 0.10)},
        "M4_datos": {"criterio": ">= 150 eventos por etiqueta y >= 3 escenas (meta del plan, NO del Hito)", "eventos": per_label,
                     "escenas": scenes, "cumplida": bool(min(per_label.values()) >= 150 and len(scenes) >= 3)},
        "M5_velocidad": {"criterio": "< 5 ms por decision", "valor": None,
                         "nota": "se mide en Java: mvn -pl object-presence-tracker exec:java -Dexec.mainClass=com.safemode.presence.RemovalClassifierBenchmark"},
        "M6_latencia_frames": {"criterio": "informativa", "modelo": model_m["median_latency_frames"], "regla": rule_m["median_latency_frames"]},
    }


def command_final(args, events):
    out = Path(args.out)
    target = out / ("final_%s.json" % args.reserved)
    if target.exists() and not args.force:
        print("Ya existe %s: la escena reservada se mira UNA sola vez. Si de verdad hay que repetir (modelo nuevo), usa --force." % target,
              file=sys.stderr)
        return 1
    model_path = out / "model.json"
    try:
        model = removal_model.load(model_path)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("No se pudo leer el modelo %s: %s (entrena primero, sin --final)" % (model_path, error), file=sys.stderr)
        return 1
    held = [e for e in events if e.scene == args.reserved]
    if not held or all(e.removed for e in held) or not any(e.removed for e in held):
        print("La escena reservada %s necesita eventos de las dos etiquetas (hay %d). Captura mas antes de la prueba final."
              % (args.reserved, len(held)), file=sys.stderr)
        return 1
    check_dataset(held, getattr(args, "allow_synthetic", False))
    cols = [CATALOG.index(f) for f in model["features"]]
    model_detect = []
    for e in held:
        p = removal_model.probability(model, e.X[:, cols])
        hit = np.flatnonzero((e.k >= DECIDES_FROM) & (p >= model["threshold"]))
        model_detect.append(int(e.k[hit[0]]) if hit.size else None)
    rule_detect = rule_detections(held)
    model_m, rule_m = event_metrics(held, model_detect), event_metrics(held, rule_detect)
    goals = goals_report(events, held, model_m, rule_m)
    print("PRUEBA FINAL en la escena %s (%d eventos: %d removed, %d still), modelo %s con umbral %.4f (recall objetivo fijado al entrenar: %s)"
          % (args.reserved, len(held), sum(e.removed for e in held), sum(not e.removed for e in held), model["meta"].get("model"),
             model["threshold"], model["meta"].get("recall_target")))
    print("  regla actual: recall %s | falsos retiros %s | F1 %.2f | latencia mediana %s" % (_fmt(rule_m, "recall"), _fmt(rule_m, "false_removal"), rule_m["f1"], rule_m["median_latency_frames"]))
    print("  modelo      : recall %s | falsos retiros %s | F1 %.2f | latencia mediana %s" % (_fmt(model_m, "recall"), _fmt(model_m, "false_removal"), model_m["f1"], model_m["median_latency_frames"]))
    print("  metas (las M son del plan de trabajo; con pocos eventos los intervalos son anchos):")
    for name, goal in goals.items():
        if "cumplida" in goal:
            print("    %-24s %s  (%s)" % (name, "CUMPLIDA" if goal["cumplida"] else "no cumplida", goal["criterio"]))
        else:
            print("    %-24s %s" % (name, goal.get("nota") or "modelo %s frames | regla %s frames" % (goal.get("modelo"), goal.get("regla"))))
    per_event = [{"event_id": e.event_id, "session": e.session, "scenario": e.scenario, "label": e.label, "rule_k": r, "model_k": m}
                 for e, r, m in zip(held, rule_detect, model_detect)]
    scenarios = {}
    for row in per_event:
        slot = scenarios.setdefault(row["scenario"], {"label": row["label"], "events": 0, "rule_removes": 0, "model_removes": 0})
        slot["events"] += 1
        slot["rule_removes"] += row["rule_k"] is not None
        slot["model_removes"] += row["model_k"] is not None
    print("  por escenario (eventos en los que ALGUIEN da el retiro):")
    for name, slot in sorted(scenarios.items()):
        print("    %-24s %-8s eventos %2d | regla %2d | modelo %2d" % (name, slot["label"], slot["events"], slot["rule_removes"], slot["model_removes"]))
    disagreements = [r for r in per_event if (r["rule_k"] is None) != (r["model_k"] is None) or (r["label"] == "removed") != (r["model_k"] is not None)]
    write_json(target, {"scene": args.reserved, "model_meta": model["meta"], "threshold": model["threshold"],
                        "recall_target_used": model["meta"].get("recall_target"), "rule": rule_m,
                        "model": model_m, "goals": goals, "by_scenario": scenarios, "events": per_event,
                        "errors_or_disagreements": disagreements})
    print("Escrito", target, "(los eventos donde el modelo falla o discrepa de la regla estan en errors_or_disagreements, para el analisis de errores)")
    return 0


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default=str(load_dataset.DEFAULT_DIR), help="carpeta del dataset (data/removal-dataset)")
    parser.add_argument("--out", default=str(RESULTS), help="carpeta de resultados (ml/results)")
    parser.add_argument("--reserved", default="C", help="escena reservada para la prueba final (C)")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--recall-target", type=float, default=RECALL_TARGET,
                        help="recall de eventos que debe tener el umbral en validacion cruzada (0.95 como pide el plan; 0.98 deja margen)")
    parser.add_argument("--tie-events", type=int, default=1, help="falsos retiros de diferencia dentro de los que gana el mas simple (1)")
    parser.add_argument("--signals", action="store_true", help="solo informa que senales separan las clases (no entrena)")
    parser.add_argument("--allow-synthetic", action="store_true", help=argparse.SUPPRESS)  # solo para las pruebas de Python
    parser.add_argument("--final", action="store_true", help="prueba el modelo ya entrenado en la escena reservada (una sola vez)")
    parser.add_argument("--force", action="store_true", help="con --final: repetir aunque ya exista el resultado")
    args = parser.parse_args()
    if not 0.5 <= args.recall_target <= 1.0:
        parser.error("--recall-target debe estar entre 0.5 y 1.0")
    globals()["RECALL_TARGET"] = args.recall_target
    events, _report = load_dataset.load(args.data)
    try:
        if args.signals:
            return command_signals(args, events)
        return command_final(args, events) if args.final else command_train(args, events)
    except TrainingError as error:
        print("No se puede entrenar: %s" % error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
