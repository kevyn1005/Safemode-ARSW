"""Catalogo de senales del modelo de verificacion de retiro.

Es la MISMA definicion que RemovalFeatures.java (hay una prueba que compara las dos con casos generados aqui:
`python export_model.py toy`). El entrenamiento (Python) y el tracker (Java) tienen que calcular lo mismo a partir de las mismas
columnas de samples.csv; si se cambia algo aqui, se cambia tambien alla.

Las cinco primeras salen directamente de samples.csv:
  x1_changed, x2_visible, x3_changed_soft, x4_mean_abs_diff, x5_frame_changed (vacia = no se pudo medir; se usa como 0)
Las otras tres se calculan por evento, frame a frame, en orden de frames_unseen:
  frames_unseen    k, cuantos frames seguidos lleva sin verse el objeto
  covered_frames   frames SEGUIDOS (hasta este inclusive) con la zona casi toda tapada (x2_visible < COVERED_BELOW); 0 si ahora se ve
  last_visible_x1  x1_changed del ultimo frame (hasta este inclusive) en que la zona se pudo ver; 0 si todavia no se vio ninguno
"""

CATALOG = [
    "x1_changed",
    "x2_visible",
    "x3_changed_soft",
    "x4_mean_abs_diff",
    "x5_frame_changed",
    "frames_unseen",
    "covered_frames",
    "last_visible_x1",
]

# igual que MIN_VISIBLE_FRACTION de la regla: por debajo de esto la zona se considera tapada y no se puede comprobar
COVERED_BELOW = 0.35


def sequence_features(steps):
    """steps: lista de (x1, x2, x3, x4, x5, k) de UN evento, en orden de k; x5 < 0 significa "no se pudo medir".

    Devuelve una lista de vectores de 8 numeros, en el orden de CATALOG.
    """
    covered = 0
    last_visible_x1 = 0.0
    out = []
    for x1, x2, x3, x4, x5, k in steps:
        if x2 < COVERED_BELOW:
            covered += 1
        else:
            covered = 0
            last_visible_x1 = x1
        out.append([x1, x2, x3, x4, 0.0 if x5 < 0 else x5, float(k), float(covered), last_visible_x1])
    return out


def step_from_csv_row(row):
    """Una fila (dict) de samples.csv -> (x1, x2, x3, x4, x5, k). Devuelve None si la fila no tiene las senales de la zona."""
    if any(row[c] == "" for c in ("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff")):
        return None
    x5 = float(row["x5_frame_changed"]) if row.get("x5_frame_changed", "") != "" else -1.0
    return (float(row["x1_changed"]), float(row["x2_visible"]), float(row["x3_changed_soft"]),
            float(row["x4_mean_abs_diff"]), x5, int(row["frames_unseen"]))
