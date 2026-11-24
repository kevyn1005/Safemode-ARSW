#!/usr/bin/env python
"""Carga el dataset del modelo de verificacion de retiro y repite en Python la regla actual del tracker.

    python object-presence-tracker/ml/load_dataset.py            # resumen + comprobacion de la regla
    python object-presence-tracker/ml/load_dataset.py --dir otra/carpeta

Lo usan el entrenamiento (train_removal.py) y las pruebas. Reutiliza la definicion de "evento valido" de dataset_summary.py (una sola
fuente de verdad) y la de las 8 senales de removal_features.py.

REPLAY DE LA REGLA. rule_action(x1, x2, k) es la misma decision que ObjectTracker.handleUnseenObjects toma en cada frame sin ver el
objeto (constantes copiadas de ObjectTracker.java). Sirve de linea base: "que habria hecho la regla en este evento". La comprobacion
(check_rule_replay) compara el replay con lo que el tracker REGISTRO de verdad en samples.csv (columna rule_action) y avisa de cada
fila distinta: si algun dia cambian los umbrales en Java y no aqui, esto lo delata.
"""
import argparse
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np

import dataset_summary
from removal_features import CATALOG, COVERED_BELOW, sequence_features, step_from_csv_row

DEFAULT_DIR = dataset_summary.DEFAULT_DIR

# --- regla actual (ObjectTracker.java) -----------------------------------------------------------------------------------------
MAX_FRAMES_UNSEEN = 3          # a los 3 frames sin ver el objeto la regla ya decide
CAP_FRAMES = 15                # 3 + 12 frames de espera: tras esto, con la zona igual, deja de seguirlo sin alerta
EARLY_FRAMES = 2               # confirmacion rapida desde el 2.o frame ...
EARLY_MIN_CHANGE = 0.6         # ... si la parte visible de la zona cambio al menos esto
STILL_THERE_MAX_CHANGE = 0.15  # con la zona visible, menos de esto = "sigue ahi"
MIN_VISIBLE_FRACTION = COVERED_BELOW  # menos de esto visible = "no se puede comprobar" (la regla da el retiro)

DECIDES = ("REMOVE_EARLY", "REMOVE", "DROP_CAP")
REMOVES = ("REMOVE_EARLY", "REMOVE")


def rule_action(x1, x2, k):
    """Lo que hace la regla actual en un frame sin ver el objeto: WAIT, REMOVE_EARLY, STILL, REMOVE o DROP_CAP."""
    if k < MAX_FRAMES_UNSEEN:
        if k >= EARLY_FRAMES and x2 >= MIN_VISIBLE_FRACTION and x1 >= EARLY_MIN_CHANGE:
            return "REMOVE_EARLY"
        return "WAIT"
    still_there = x2 >= MIN_VISIBLE_FRACTION and x1 < STILL_THERE_MAX_CHANGE
    if still_there:
        return "DROP_CAP" if k >= CAP_FRAMES else "STILL"
    return "REMOVE"


@dataclass
class Event:
    event_id: str
    scene: str
    session: str
    scenario: str
    label: str            # "removed" | "still" (la etiqueta final)
    label_source: str     # "run" | "auto_seen_again"
    outcome_rule: str     # como termino segun el tracker real
    max_frames_unseen: int
    k: np.ndarray         # (n,) frames sin ver, 1..n
    X: np.ndarray         # (n, 8) las 8 senales del catalogo, en el orden de CATALOG
    recorded: list        # rule_action que registro el tracker en cada fila (TAIL despues de decidir)

    @property
    def removed(self):
        return self.label == "removed"

    def rule_decision(self):
        """(k, accion) del primer frame en que la regla decide; (None, "SIN_DECISION") si el objeto volvio a verse antes."""
        for k, x1, x2 in zip(self.k, self.X[:, CATALOG.index("x1_changed")], self.X[:, CATALOG.index("x2_visible")]):
            action = rule_action(float(x1), float(x2), int(k))
            if action in DECIDES:
                return int(k), action
        return None, "SIN_DECISION"

    def rule_removes(self):
        """k en que la regla da el retiro (REMOVE_EARLY o REMOVE), o None si no lo da (espera, vuelve a verse o lo deja de seguir)."""
        k, action = self.rule_decision()
        return k if action in REMOVES else None


def load(directory=DEFAULT_DIR):
    """(eventos validos, informe). El informe lleva los no validos con su razon, los sin cerrar y los descartados."""
    directory = Path(directory)
    _events, valid_rows, invalid, incomplete, discarded, _per_event = dataset_summary.load(directory)
    samples = defaultdict(list)
    for row in dataset_summary.read_csv(directory / "samples.csv"):
        samples[row["event_id"]].append(row)
    events = []
    for row in valid_rows:
        rows = sorted(samples[row["event_id"]], key=lambda r: int(r["frames_unseen"]))
        steps = [step_from_csv_row(r) for r in rows]
        features = np.array(sequence_features(steps), dtype=float)
        events.append(Event(
            event_id=row["event_id"], scene=row["scene"], session=row["session"], scenario=row["scenario"], label=row["label"],
            label_source=row["label_source"], outcome_rule=row["outcome_rule"], max_frames_unseen=int(row["max_frames_unseen"]),
            k=np.array([int(r["frames_unseen"]) for r in rows]), X=features, recorded=[r["rule_action"] for r in rows]))
    report = {"invalid": [(e["event_id"], why) for e, why in invalid], "incomplete": incomplete, "discarded": len(discarded)}
    return events, report


def check_rule_replay(events):
    """Compara el replay de la regla con lo que el tracker registro. Devuelve la lista de diferencias (vacia = la reproduce al 100 %)."""
    mismatches = []
    for e in events:
        for k, x1, x2, recorded in zip(e.k, e.X[:, 0], e.X[:, 1], e.recorded):
            if recorded == "TAIL":
                continue  # la cola es lo que paso DESPUES de que la regla decidio: no es una decision registrada
            replayed = rule_action(float(x1), float(x2), int(k))
            if replayed != recorded:
                mismatches.append((e.event_id, int(k), replayed, recorded))
    return mismatches


def validate_events(events):
    """Problemas de integridad del dataset (lista de textos; vacia = todo bien): NaN, senales fuera de 0-1, secuencias rotas, duplicados."""
    problems = []
    seen = Counter(e.event_id for e in events)
    for event_id, times in seen.items():
        if times > 1:
            problems.append("%s: evento duplicado (%d veces)" % (event_id, times))
    for e in events:
        if e.label not in ("removed", "still"):
            problems.append("%s: etiqueta invalida %r" % (e.event_id, e.label))
        if not np.all(np.isfinite(e.X)):
            problems.append("%s: hay valores NaN o infinitos en las senales" % e.event_id)
            continue
        for name in ("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff", "x5_frame_changed", "last_visible_x1"):
            column = e.X[:, CATALOG.index(name)]
            if column.min() < -1e-9 or column.max() > 1 + 1e-9:
                problems.append("%s: %s fuera de 0-1 (%.3f a %.3f)" % (e.event_id, name, column.min(), column.max()))
        if list(e.k) != list(range(1, len(e.k) + 1)):
            problems.append("%s: frames_unseen no es 1..n sin saltos ni repetidos (%s)" % (e.event_id, list(e.k)))
        if e.label_source == "auto_seen_again" and e.label != "still":
            problems.append("%s: reetiquetado automatico que no es 'still'" % e.event_id)
    return problems


def check_outcome_replay(events):
    """La prueba de integridad clave: repetir la regla en Python reproduce el `outcome_rule` que registro el tracker en CADA evento.

    Devuelve (event_id, lo que da el replay, lo que registro el tracker) por cada evento que no coincide (vacia = 100 %)."""
    expected = {"REMOVE_EARLY": "REMOVED", "REMOVE": "REMOVED", "DROP_CAP": "DROPPED_CAP", "SIN_DECISION": "SEEN_AGAIN"}
    return [(e.event_id, expected[e.rule_decision()[1]], e.outcome_rule) for e in events
            if expected[e.rule_decision()[1]] != e.outcome_rule]


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", default=str(DEFAULT_DIR), help="carpeta del dataset")
    args = parser.parse_args()
    events, report = load(args.dir)
    print("Eventos validos: %d (no validos: %d, sin cerrar: %d, descartados por desplazamiento: %d)"
          % (len(events), len(report["invalid"]), len(report["incomplete"]), report["discarded"]))
    counts = Counter((e.scene, e.label) for e in events)
    for (scene, label), n in sorted(counts.items()):
        print("  escena %s  %-8s %d" % (scene, label, n))
    status = 0
    problems = validate_events(events)
    if problems:
        status = 1
        print("\nOJO: %d problema(s) de integridad:" % len(problems))
        for problem in problems[:20]:
            print("  " + problem)
    else:
        print("\nIntegridad: sin NaN, senales dentro de 0-1, secuencias completas y sin eventos duplicados.")
    mismatches = check_rule_replay(events)
    rows = sum(1 for e in events for r in e.recorded if r != "TAIL")
    if mismatches:
        status = 1
        print("OJO: el replay de la regla NO coincide con lo que registro el tracker en %d de %d filas de decision:" % (len(mismatches), rows))
        for event_id, k, replayed, recorded in mismatches[:20]:
            print("  %s k=%d replay=%s tracker=%s" % (event_id, k, replayed, recorded))
    else:
        print("Replay de la regla: coincide con el tracker en las %d filas de decision de %d evento(s)." % (rows, len(events)))
    outcome_mismatches = check_outcome_replay(events)
    if outcome_mismatches:
        status = 1
        print("OJO: el replay NO reproduce el desenlace (outcome_rule) de %d de %d eventos:" % (len(outcome_mismatches), len(events)))
        for event_id, replayed, recorded in outcome_mismatches[:20]:
            print("  %s replay=%s tracker=%s" % (event_id, replayed, recorded))
    else:
        print("Desenlace: el replay reproduce el outcome_rule en el 100 %% de los %d evento(s)." % len(events))
    return status


if __name__ == "__main__":
    sys.exit(main())
