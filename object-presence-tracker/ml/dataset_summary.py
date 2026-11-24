#!/usr/bin/env python
"""Resumen del dataset del modelo de verificacion de retiro.

Lee los CSV que escribe RemovalSampleLog en object-presence-tracker/data/removal-dataset/ y muestra, por escena y etiqueta,
cuantos eventos VALIDOS hay y como termino cada uno segun la regla actual. Solo usa la biblioteca estandar de Python (no
necesita el entorno virtual): se corre despues de cada sesion de captura.

    python object-presence-tracker/ml/dataset_summary.py
    python object-presence-tracker/ml/dataset_summary.py --reserved C --dir otra/carpeta

Un evento es una racha de frames sin ver el mismo objeto en reposo. Es VALIDO si
  - termino bien (REMOVED, DROPPED_CAP o SEEN_AGAIN; no quedo abierto al cerrar la corrida),
  - llego a 2 o mas frames sin verse (la politica del modelo decide desde el segundo frame) y
  - todas sus filas tienen las senales de la zona (sin huella de reposo o sin imagen no se puede entrenar).

La escena RESERVADA (por defecto C) es la de la prueba final: aqui solo se cuenta cuantos eventos hay; no se muestra como
le fue a la regla en ella, para no mirar el conjunto de prueba antes de tiempo.
"""
import argparse
import csv
import sys
from collections import Counter, defaultdict
from pathlib import Path

DEFAULT_DIR = Path(__file__).resolve().parent.parent / "data" / "removal-dataset"
GOOD_OUTCOMES = ("REMOVED", "DROPPED_CAP", "SEEN_AGAIN")
LABELS = ("removed", "still")
KNOWN_SCENARIOS = {
    "still": ["still_persona_delante", "still_pasa", "still_mano_brazo", "still_luz", "still_detector"],
    "removed": ["removed_limpio", "removed_tapado", "removed_otro", "removed_poca_luz"],
}
SIGNAL_COLUMNS = ("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff")
# x5 (cambio del resto del cuadro) puede venir vacia sin invalidar el evento; a partir de este valor se marca para revisar
FRAME_CHANGE_REVIEW = 0.6


def read_csv(path):
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def to_int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def print_table(headers, rows):
    widths = [len(h) for h in headers]
    for row in rows:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], len(str(cell)))
    line = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers))
    print(line)
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print("  ".join(str(cell).ljust(widths[i]) for i, cell in enumerate(row)))


def load(directory):
    events = read_csv(directory / "events.csv")
    samples = read_csv(directory / "samples.csv")
    discarded = read_csv(directory / "discarded.csv")

    per_event = defaultdict(lambda: {"rows": 0, "max_k": 0, "blank": 0, "max_x5": -1.0})
    for row in samples:
        info = per_event[row["event_id"]]
        info["rows"] += 1
        info["max_k"] = max(info["max_k"], to_int(row["frames_unseen"]))
        if any(row[c] == "" for c in SIGNAL_COLUMNS):
            info["blank"] += 1
        if row.get("x5_frame_changed", "") != "":
            info["max_x5"] = max(info["max_x5"], float(row["x5_frame_changed"]))

    closed_ids = {e["event_id"] for e in events}
    incomplete = [event_id for event_id in per_event if event_id not in closed_ids]

    valid, invalid = [], []
    for event in events:
        info = per_event.get(event["event_id"])
        if event["outcome_rule"] not in GOOD_OUTCOMES:
            reason = "quedo abierto al cerrar (" + event["outcome_rule"] + ")"
        elif info is None or info["rows"] == 0:
            reason = "sin filas en samples.csv"
        elif info["max_k"] < 2:
            reason = "menos de 2 frames sin verse"
        elif info["blank"] > 0:
            reason = "filas sin senales (sin huella de reposo o sin imagen)"
        else:
            valid.append(event)
            continue
        invalid.append((event, reason))
    return events, valid, invalid, incomplete, discarded, per_event


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description="Resumen del dataset del modelo de verificacion de retiro")
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR, help="carpeta con events.csv y samples.csv")
    parser.add_argument("--reserved", default="C", help="escena reservada para la prueba final (por defecto C)")
    # Por defecto, el PLAN MINIMO (modelo que funcione y se valide en una escena aparte). El Hito 3 de PTIA no exige un numero de
    # datos; las metas grandes (150 por etiqueta, 3 escenas) eran del prompt de trabajo: se activan con --ideal.
    parser.add_argument("--min-per-class", type=int, default=60, help="eventos validos por etiqueta en total (60; ideal 150)")
    parser.add_argument("--min-reserved", type=int, default=25, help="eventos validos por etiqueta en la escena reservada (25; ideal 45)")
    parser.add_argument("--min-sessions", type=int, default=3, help="sesiones por etiqueta en las escenas de entrenamiento (3; ideal 8)")
    parser.add_argument("--min-scenes", type=int, default=2, help="escenas distintas con eventos validos (2; ideal 3)")
    parser.add_argument("--blocks-per-scene", type=int, default=6, help="bloques (sesiones) por escena de entrenamiento (6; ideal 8)")
    parser.add_argument("--ideal", action="store_true", help="usa las metas grandes: 150 por etiqueta, 45 en la reservada, 8 sesiones, 3 escenas")
    args = parser.parse_args()
    if args.ideal:
        args.min_per_class, args.min_reserved, args.min_sessions, args.min_scenes, args.blocks_per_scene = 150, 45, 8, 3, 8

    events, valid, invalid, incomplete, discarded, per_event = load(args.dir)
    print("DATASET DE RETIRO  (carpeta: %s)" % args.dir)
    if not events:
        print("\nTodavia no hay eventos. Captura una sesion siguiendo ml/GUIA_CAPTURA.md y vuelve a correr este resumen.")
        return 0
    sessions = {e["session"] for e in events}
    print("Sesiones: %d | eventos cerrados: %d | validos: %d | no validos: %d | sin cerrar (proceso cortado): %d"
          % (len(sessions), len(events), len(valid), len(invalid), len(incomplete)))

    reserved = args.reserved
    scenes = sorted({e["scene"] for e in valid} | {d["scene"] for d in discarded})
    discarded_count = Counter((d["scene"], d["run_label"]) for d in discarded)

    # 1) escena x etiqueta x desenlace de la regla
    print("\n1) Eventos validos por escena y etiqueta (la etiqueta es la final: un SEEN_AGAIN de una corrida removed cuenta como still)")
    rows = []
    for scene in scenes:
        for label in LABELS:
            of = [e for e in valid if e["scene"] == scene and e["label"] == label]
            discards = sum(n for (s, run_label), n in discarded_count.items() if s == scene and run_label == label)
            if not of and not discards:
                continue
            tag = scene + (" (reservada)" if scene == reserved else "")
            n_sessions = len({e["session"] for e in of})
            if scene == reserved:
                outcomes = ["-", "-", "-"]
            else:
                counts = Counter(e["outcome_rule"] for e in of)
                outcomes = [counts[o] for o in GOOD_OUTCOMES]
            rows.append([tag, label, n_sessions, len(of)] + outcomes + [discards])
    print_table(["Escena", "Etiqueta", "Sesiones", "Validos", "REMOVED", "DROPPED_CAP", "SEEN_AGAIN", "Desplaz.(desc.)"], rows)
    if any(r[0].endswith("(reservada)") for r in rows):
        print("   (en la escena reservada no se muestra el desenlace de la regla: se mira recien en la Fase 5)")
    auto = sum(1 for e in valid if e["label_source"] == "auto_seen_again")
    print("   Reetiquetados automaticamente (SEEN_AGAIN dentro de una corrida removed -> still): %d" % auto)

    # 2) por escenario, solo escenas de entrenamiento
    print("\n2) Eventos validos por escenario (escenas de entrenamiento)")
    by_scenario = Counter((e["label"], e["scenario"]) for e in valid if e["scene"] != reserved)
    rows = []
    for label in LABELS:
        names = list(KNOWN_SCENARIOS[label]) + sorted({s for (l, s) in by_scenario if l == label and s not in KNOWN_SCENARIOS[label]})
        for name in names:
            rows.append([label, name if name else "(sin escenario)", by_scenario[(label, name)]])
    print_table(["Etiqueta", "Escenario", "Validos"], rows)
    print("   (un 'still' dentro de un escenario removed_* es un parpadeo del detector: la mochila reaparecio antes del retiro real)")

    # 3) lo que no cuenta
    print("\n3) Lo que no cuenta")
    if invalid:
        reasons = Counter(reason for _, reason in invalid)
        for reason, n in reasons.most_common():
            print("   %3d evento(s): %s" % (n, reason))
    else:
        print("   Ningun evento cerrado fue descartado por no ser valido.")
    if incomplete:
        print("   %3d evento(s) con filas pero sin cerrar (el proceso se corto): no cuentan" % len(incomplete))
    total_discarded = sum(discarded_count.values())
    flagged = [e for e in valid if per_event[e["event_id"]]["max_x5"] >= FRAME_CHANGE_REVIEW]
    print("   %3d retiro(s) por desplazamiento (el objeto siguio a la vista y se movio): no entran al dataset" % total_discarded)
    print()
    print("   PARA REVISAR: %d evento(s) valido(s) en los que cambio TODO el cuadro (x5 >= %.1f)." % (len(flagged), FRAME_CHANGE_REVIEW))
    print("   Suele ser una ventana que se movio o se tapo (captura de pantalla) o un cambio de luz, no un retiro. Siguen contando;")
    print("   mira sus recortes en crops/ y dime si hay que descartarlos. Se decide en la Fase 3 con datos.")
    for e in flagged[:10]:
        outcome = "(escena reservada)" if e["scene"] == reserved else e["outcome_rule"]
        print("     - %s  [%s, etiqueta %s, %s, x5 max %.2f]" % (e["event_id"], e["scene"], e["label"], outcome, per_event[e["event_id"]]["max_x5"]))
    if len(flagged) > 10:
        print("     ... y %d mas" % (len(flagged) - 10))

    # 4) avance contra las metas
    print("\n4) Avance contra las metas")
    todo = []
    valid_scenes = {e["scene"] for e in valid}
    for label in LABELS:
        total = sum(1 for e in valid if e["label"] == label)
        print("   %-8s total validos: %d / %d" % (label, total, args.min_per_class))
        if total < args.min_per_class:
            todo.append("faltan %d eventos '%s' en total" % (args.min_per_class - total, label))
        train_sessions = len({e["session"] for e in valid if e["label"] == label and e["scene"] != reserved})
        print("   %-8s sesiones de entrenamiento: %d / %d" % (label, train_sessions, args.min_sessions))
        if train_sessions < args.min_sessions:
            todo.append("faltan %d sesiones '%s' en las escenas de entrenamiento" % (args.min_sessions - train_sessions, label))
        in_reserved = sum(1 for e in valid if e["label"] == label and e["scene"] == reserved)
        print("   %-8s escena reservada %s: %d / %d" % (label, reserved, in_reserved, args.min_reserved))
        if in_reserved < args.min_reserved:
            todo.append("faltan %d eventos '%s' en la escena reservada %s" % (args.min_reserved - in_reserved, label, reserved))
    print("   escenas con eventos validos: %d / %d (%s)" % (len(valid_scenes), args.min_scenes, ", ".join(sorted(valid_scenes)) or "ninguna"))
    if len(valid_scenes) < args.min_scenes:
        todo.append("faltan %d escena(s) o condicion(es) de luz distintas" % (args.min_scenes - len(valid_scenes)))

    print("\nFalta:" if todo else "\nSe cumplen todos los minimos de captura.")
    for item in todo:
        print("   - " + item)

    # 5) sugerencia de siguiente bloque
    if todo:
        # se termina cada escena (8 bloques: 4 still + 4 removed) antes de pasar a la siguiente: A, luego B, luego la reservada
        sessions_by_scene = defaultdict(set)
        for e in valid:
            sessions_by_scene[e["scene"]].add(e["session"])
        candidates = [s for s in ("A", "B") if s != reserved] or ["A"]
        scene = next((s for s in candidates if len(sessions_by_scene[s]) < args.blocks_per_scene), reserved)
        in_scene = Counter(e["label"] for e in valid if e["scene"] == scene)
        label = min(LABELS, key=lambda l: in_scene[l])
        by_scenario_in_scene = Counter(e["scenario"] for e in valid if e["scene"] == scene and e["label"] == label)
        scenario = min(KNOWN_SCENARIOS[label], key=lambda name: by_scenario_in_scene[name])
        print("\nSiguiente bloque sugerido: escena=%s (%d/%d bloques), etiqueta=%s, escenario=%s (~10-15 eventos)"
              % (scene, len(sessions_by_scene[scene]), args.blocks_per_scene, label, scenario))
    return 0


if __name__ == "__main__":
    sys.exit(main())
