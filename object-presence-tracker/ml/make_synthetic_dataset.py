#!/usr/bin/env python
"""Genera un dataset SINTETICO (numeros inventados) con el mismo formato que escribe RemovalSampleLog.

    python object-presence-tracker/ml/make_synthetic_dataset.py --out C:/ruta/corta/sintetico

SOLO sirve para probar que load_dataset.py y train_removal.py funcionan de punta a punta sin esperar a tener datos reales. NO es
evidencia: los numeros los invento yo, asi que cualquier "resultado" sobre ellos no dice nada del sistema real. Por eso
  - se NIEGA a escribir en data/removal-dataset (el dataset real), y
  - cada evento lleva session "SINT-..." para que no se confunda con uno real.
"""
import argparse
import csv
import sys
from pathlib import Path

import numpy as np

import load_dataset

SAMPLES_HEADER = ("scene,session,run_label,scenario,camera_id,event_id,object_class,frames_unseen,x1_changed,x2_visible,"
                  "x3_changed_soft,x4_mean_abs_diff,x5_frame_changed,box_w,box_h,persons_in_frame,rule_action,model_p,model_action,ts").split(",")
EVENTS_HEADER = ("scene,session,run_label,label,label_source,scenario,camera_id,event_id,object_id,object_class,outcome_rule,"
                 "frames_unseen_at_end,max_frames_unseen,tail_end,ts_start,ts_end").split(",")

STILL_SCENARIOS = ["still_persona_delante", "still_pasa", "still_detector", "still_luz"]
REMOVED_SCENARIOS = ["removed_limpio", "removed_tapado", "removed_otro", "removed_poca_luz"]


def u(rng, low, high):
    return float(rng.uniform(low, high))


def frames_for(rng, scenario):
    """Lista de (x1, x2, x3, x4, x5, personas) por frame, k = 1..n. Inventado a partir de lo que se vio en el dataset real."""
    out = []
    if scenario == "still_persona_delante":  # persona tapa toda la zona y se va; la mochila sigue ahi
        for _ in range(int(rng.integers(2, 7))):
            out.append((0.0, u(rng, 0, 0.08), 0.0, 0.0, u(rng, 0, 0.25), 1))
        for _ in range(int(rng.integers(0, 3))):
            out.append((u(rng, 0, 0.08), 1.0, u(rng, 0, 0.1), u(rng, 0, 0.05), u(rng, 0, 0.25), 0))
    elif scenario == "still_pasa":  # alguien cruza: tapa un pedazo
        for _ in range(int(rng.integers(2, 5))):
            out.append((u(rng, 0, 0.1), u(rng, 0.3, 0.8), u(rng, 0, 0.15), u(rng, 0, 0.06), u(rng, 0, 0.3), 1))
    elif scenario == "still_detector":  # el detector falla con la zona a la vista e igual
        for _ in range(int(rng.integers(3, 16))):
            out.append((u(rng, 0, 0.1), u(rng, 0.9, 1.0), u(rng, 0, 0.12), u(rng, 0, 0.05), u(rng, 0, 0.1), 0))
    elif scenario == "still_luz":  # cambia la luz de toda la escena
        for _ in range(int(rng.integers(2, 7))):
            out.append((u(rng, 0.05, 0.45), u(rng, 0.9, 1.0), u(rng, 0.3, 0.9), u(rng, 0.05, 0.3), u(rng, 0.5, 1.0), 0))
    elif scenario in ("removed_limpio", "removed_otro"):  # la levantan y se van con ella: la zona queda a la vista y distinta
        persons = 1 if scenario == "removed_otro" else 0
        for _ in range(int(rng.integers(6, 16))):
            out.append((u(rng, 0.65, 0.95), u(rng, 0.9, 1.0), u(rng, 0.5, 0.95), u(rng, 0.15, 0.4), u(rng, 0, 0.2), persons))
    elif scenario == "removed_tapado":  # alguien se para delante, la toma y se va: primero tapada, luego a la vista y distinta
        for _ in range(int(rng.integers(2, 5))):
            out.append((0.0, u(rng, 0, 0.08), 0.0, 0.0, u(rng, 0, 0.25), 1))
        for _ in range(int(rng.integers(3, 9))):
            out.append((u(rng, 0.6, 0.95), u(rng, 0.9, 1.0), u(rng, 0.5, 0.95), u(rng, 0.15, 0.4), u(rng, 0, 0.2), 0))
    elif scenario == "removed_poca_luz":
        for _ in range(int(rng.integers(6, 16))):
            out.append((u(rng, 0.4, 0.8), u(rng, 0.9, 1.0), u(rng, 0.3, 0.7), u(rng, 0.05, 0.2), u(rng, 0, 0.15), 0))
    else:
        raise ValueError(scenario)
    return out


def write_dataset(out_dir, seed=7, sessions_per_scenario=1, events_per_session=10):
    out_dir = Path(out_dir)
    if out_dir.resolve() == load_dataset.DEFAULT_DIR.resolve():
        raise SystemExit("Me niego a escribir datos sinteticos en el dataset real (%s)." % load_dataset.DEFAULT_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    samples, events = [], []
    for scene in ("A", "B", "C"):
        for scenario in STILL_SCENARIOS + REMOVED_SCENARIOS:
            label = "removed" if scenario.startswith("removed") else "still"
            for s in range(sessions_per_scenario):
                session = "SINT-%s-%s-%02d" % (scene, scenario, s + 1)
                for n in range(events_per_session):
                    event_id = "%s_c1_o%d_r1" % (session, n + 1)
                    frames = frames_for(rng, scenario)
                    decided = None
                    for k, (x1, x2, x3, x4, x5, persons) in enumerate(frames, start=1):
                        action = load_dataset.rule_action(x1, x2, k)
                        recorded = "TAIL" if decided else action
                        if decided is None and action in load_dataset.DECIDES:
                            decided = (k, action)
                        samples.append({"scene": scene, "session": session, "run_label": label, "scenario": scenario, "camera_id": 1,
                                        "event_id": event_id, "object_class": "backpack", "frames_unseen": k,
                                        "x1_changed": "%.6f" % x1, "x2_visible": "%.6f" % x2, "x3_changed_soft": "%.6f" % x3,
                                        "x4_mean_abs_diff": "%.6f" % x4, "x5_frame_changed": "%.6f" % x5, "box_w": 290, "box_h": 260,
                                        "persons_in_frame": persons, "rule_action": recorded, "model_p": "", "model_action": "",
                                        "ts": "2026-01-01T00:00:00Z"})
                    outcome = {"REMOVE": "REMOVED", "REMOVE_EARLY": "REMOVED", "DROP_CAP": "DROPPED_CAP"}.get(decided[1], "SEEN_AGAIN") if decided else "SEEN_AGAIN"
                    events.append({"scene": scene, "session": session, "run_label": label, "label": label, "label_source": "run",
                                   "scenario": scenario, "camera_id": 1, "event_id": event_id, "object_id": n + 1, "object_class": "backpack",
                                   "outcome_rule": outcome, "frames_unseen_at_end": decided[0] if decided else len(frames),
                                   "max_frames_unseen": len(frames), "tail_end": "RESEEN" if label == "still" else "RUN_END",
                                   "ts_start": "2026-01-01T00:00:00Z", "ts_end": "2026-01-01T00:00:10Z"})
    for name, header, rows in (("samples.csv", SAMPLES_HEADER, samples), ("events.csv", EVENTS_HEADER, events)):
        with open(out_dir / name, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=header)
            writer.writeheader()
            writer.writerows(rows)
    return len(events)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, help="carpeta de salida (NO la del dataset real)")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--sessions-per-scenario", type=int, default=1)
    parser.add_argument("--events-per-session", type=int, default=10)
    args = parser.parse_args()
    n = write_dataset(args.out, args.seed, args.sessions_per_scenario, args.events_per_session)
    print("Escritos %d eventos SINTETICOS en %s (no son datos reales)." % (n, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
