"""Pruebas del cargador y del entrenamiento (Fase 3). Se corren con el entorno de ml/README.md:

    python -m unittest discover -s object-presence-tracker/ml -p "test_*.py" -v

Usan datos SINTETICOS en una carpeta temporal (make_synthetic_dataset.py) y 3 eventos reales de la primera captura (solo numeros de
brillo de la zona, sin fotos ni personas). Nada de esto toca data/removal-dataset ni ml/results.
"""
import csv
import json
import tempfile
import unittest
import unittest.mock
from argparse import Namespace
from pathlib import Path

import numpy as np

import load_dataset
import make_synthetic_dataset
import models_numpy
import removal_model
import train_removal

# Primera captura real (23-nov-2026, bloque still_persona_delante): la mochila NO se movio y la regla dio retiro falso 3 de 3.
# (x1, x2, x3, x4, x5, k, regla registrada)
REAL_EVENTS = {
    "o1": [(0.0, 0.041667, 0.0, 0.016993, 0.0, 1, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.043578, 2, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.043578, 3, "REMOVE")],
    "o7": [(0.0, 0.0, 0.0, 0.0, 0.044737, 1, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.045024, 2, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.036697, 3, "REMOVE"),
           (0.0, 0.0, 0.0, 0.0, 0.035545, 4, "TAIL")],
    "o8": [(0.0, 0.0, 0.0, 0.0, 0.194313, 1, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.172986, 2, "WAIT"), (0.0, 0.0, 0.0, 0.0, 0.183824, 3, "REMOVE"),
           (0.444444, 1.0, 0.647569, 0.237085, 0.253704, 4, "TAIL"), (0.0, 0.0, 0.0, 0.0, 0.184211, 5, "TAIL")],
}


def write_real_like(directory):
    """Escribe samples.csv/events.csv (mismo esquema que el tracker) con los 3 eventos reales de arriba."""
    samples, events = [], []
    for name, rows in REAL_EVENTS.items():
        event_id = "A-still-persona-01_c1_%s_r1" % name
        for x1, x2, x3, x4, x5, k, action in rows:
            samples.append({"scene": "A", "session": "A-still-persona-01", "run_label": "still", "scenario": "still_persona_delante",
                            "camera_id": 1, "event_id": event_id, "object_class": "handbag", "frames_unseen": k, "x1_changed": x1,
                            "x2_visible": x2, "x3_changed_soft": x3, "x4_mean_abs_diff": x4, "x5_frame_changed": x5, "box_w": 290,
                            "box_h": 260, "persons_in_frame": 1, "rule_action": action, "model_p": "", "model_action": "", "ts": "t"})
        events.append({"scene": "A", "session": "A-still-persona-01", "run_label": "still", "label": "still", "label_source": "run",
                       "scenario": "still_persona_delante", "camera_id": 1, "event_id": event_id, "object_id": 1, "object_class": "handbag",
                       "outcome_rule": "REMOVED", "frames_unseen_at_end": 3, "max_frames_unseen": len(rows), "tail_end": "RESEEN",
                       "ts_start": "t", "ts_end": "t"})
    for file_name, header, rows in (("samples.csv", make_synthetic_dataset.SAMPLES_HEADER, samples),
                                    ("events.csv", make_synthetic_dataset.EVENTS_HEADER, events)):
        with open(Path(directory) / file_name, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=header)
            writer.writeheader()
            writer.writerows(rows)


SEVEN = train_removal.ZONE + ["x5_frame_changed", "covered_frames", "last_visible_x1"]


class RuleReplayTest(unittest.TestCase):
    def test_replay_reproduces_what_the_real_tracker_recorded(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            events, _ = load_dataset.load(tmp)
        self.assertEqual(3, len(events))
        self.assertEqual([], load_dataset.check_rule_replay(events))

    def test_the_real_capture_shows_the_rule_removing_a_bag_that_never_moved(self):
        # es el hallazgo del Hito: persona delante tapa todo (x2 = 0) y la regla "no puede comprobar, retiro" a los 3 frames
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            events, _ = load_dataset.load(tmp)
        self.assertEqual([3, 3, 3], [e.rule_removes() for e in events])
        self.assertTrue(all(not e.removed for e in events))

    def test_rule_thresholds(self):
        r = load_dataset.rule_action
        self.assertEqual("WAIT", r(0.9, 1.0, 1))                 # antes del 2.o frame nunca decide
        self.assertEqual("REMOVE_EARLY", r(0.6, 0.35, 2))        # confirmacion rapida: zona visible y cambiada >= 60 %
        self.assertEqual("WAIT", r(0.59, 1.0, 2))
        self.assertEqual("WAIT", r(0.9, 0.34, 2))                # con la zona tapada no hay confirmacion rapida
        self.assertEqual("REMOVE", r(0.0, 0.34, 3))              # tapada a los 3 frames: "no se puede comprobar" -> retiro
        self.assertEqual("STILL", r(0.149, 0.35, 3))             # visible e igual: sigue ahi
        self.assertEqual("REMOVE", r(0.15, 1.0, 3))
        self.assertEqual("DROP_CAP", r(0.0, 1.0, 15))            # tope: se deja de seguir sin alerta


class LoaderTest(unittest.TestCase):
    def test_open_and_short_events_are_not_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            with open(Path(tmp) / "events.csv", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            rows[0]["outcome_rule"] = "OPEN_AT_END"
            with open(Path(tmp) / "events.csv", "w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
            events, report = load_dataset.load(tmp)
        self.assertEqual(2, len(events))
        self.assertEqual(1, len(report["invalid"]))

    def test_sequence_features_are_attached(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            events, _ = load_dataset.load(tmp)
        covered = events[0].X[:, load_dataset.CATALOG.index("covered_frames")]
        self.assertEqual([1.0, 2.0, 3.0], list(covered))  # x2 < 0.35 en los 3 frames seguidos (el 1.o tiene x2 = 0.04)


class StatsTest(unittest.TestCase):
    def test_wilson_interval(self):
        lo, hi = train_removal.wilson(0, 0)
        self.assertEqual((0.0, 1.0), (lo, hi))
        lo, hi = train_removal.wilson(10, 10)
        self.assertAlmostEqual(1.0, hi, places=9)
        self.assertLess(lo, 0.75)       # con 10 de 10 el limite inferior sigue siendo bajo: pocos datos
        lo, hi = train_removal.wilson(5, 10)
        self.assertTrue(0.2 < lo < 0.5 < hi < 0.8)


class EndToEndTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.data = Path(cls.tmp.name) / "data"
        cls.out = Path(cls.tmp.name) / "results"
        make_synthetic_dataset.write_dataset(cls.data, seed=7, sessions_per_scenario=1, events_per_session=8)
        cls.events, _ = load_dataset.load(cls.data)
        cls.args = Namespace(data=str(cls.data), out=str(cls.out), reserved="C", seed=train_removal.DEFAULT_SEED, tie_events=1,
                             final=False, force=False, allow_synthetic=True)
        cls.train_code = train_removal.command_train(cls.args, cls.events)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_synthetic_replay_is_consistent(self):
        self.assertEqual([], load_dataset.check_rule_replay(self.events))

    def test_synthetic_generator_refuses_the_real_dataset(self):
        with self.assertRaises(SystemExit):
            make_synthetic_dataset.write_dataset(load_dataset.DEFAULT_DIR)

    def test_training_writes_a_valid_model_and_metrics(self):
        self.assertEqual(0, self.train_code)
        model = removal_model.load(self.out / "model.json")   # valida el formato que Java tambien valida
        self.assertIn(model["kind"], ("logistic", "mlp"))
        self.assertTrue(0 < model["threshold"] < 1)
        metrics = json.loads((self.out / "metrics.json").read_text(encoding="utf-8"))
        self.assertEqual(model["meta"]["model"], metrics["selected"]["model"])
        self.assertGreaterEqual(metrics["selected"]["recall"], train_removal.RECALL_TARGET)

    def test_reserved_scene_is_not_used_for_training(self):
        model = removal_model.load(self.out / "model.json")
        self.assertNotIn("C", model["meta"]["train_scenes"])
        self.assertTrue(all("-C-" not in s for s in model["meta"]["train_sessions"]))

    def test_model_beats_the_rule_on_false_removals_in_cross_validation(self):
        metrics = json.loads((self.out / "metrics.json").read_text(encoding="utf-8"))
        # en los datos sinteticos la regla da retiro falso cada vez que una persona tapa la zona: el modelo tiene que aprender a esperar
        self.assertLess(metrics["selected"]["false_removal_rate"], metrics["rule"]["false_removal_rate"])

    def test_exported_json_matches_the_trained_model_for_every_exportable_model(self):
        table = train_removal.Table([e for e in self.events if e.scene != "C"])
        mask = train_removal.training_mask(table, "visible")
        for name in train_removal.EXPORTABLE:
            features = SEVEN
            fitted = train_removal.fit(table, mask, features, name, 1)
            candidate = {"threshold": 0.5}
            model = train_removal.build_model_json(fitted, features, candidate, {})
            self.assertLess(train_removal.check_export_matches_trained(model, fitted, table), 1e-9, name)

    def test_hand_made_logistic_matches_scikit_learn_on_the_dataset(self):
        table = train_removal.Table([e for e in self.events if e.scene != "C"])
        mask = train_removal.training_mask(table, "visible")
        diff = train_removal.logistic_vs_sklearn(table, mask, SEVEN, 1)
        self.assertLess(diff, 1e-3)

    def test_main_model_uses_only_the_four_zone_signals(self):
        self.assertEqual(["x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff"], train_removal.ZONE)
        self.assertEqual({"zona": train_removal.ZONE}, train_removal.FEATURE_SETS)
        model = removal_model.load(self.out / "model.json")
        self.assertEqual(train_removal.ZONE, model["features"])        # lo que se exporta (y lee Java) son las 4 del plan
        metrics = json.loads((self.out / "metrics.json").read_text(encoding="utf-8"))
        self.assertEqual("zona", metrics["selected"]["features_set"])

    def test_the_four_extra_signals_are_only_an_experiment_in_the_ablation(self):
        metrics = json.loads((self.out / "metrics.json").read_text(encoding="utf-8"))
        names = [r["features_set"] for r in metrics["ablation"]]
        for extra in train_removal.EXTRA_FEATURES:
            self.assertIn("con " + extra, names)
            self.assertNotIn(extra, metrics["selected"]["features"])   # no se adoptan solas
        self.assertIn("con las 4 extra (8 senales)", names)
        # el elegido + una fila por cada senal quitada + una por cada extra + las 4 juntas
        self.assertEqual(1 + len(metrics["selected"]["features"]) + len(train_removal.EXTRA_FEATURES) + 1, len(metrics["ablation"]))

    def test_recall_target_is_fixed_at_098_and_recorded_in_the_model(self):
        self.assertEqual(0.98, train_removal.RECALL_TARGET)
        self.assertEqual(0.98, removal_model.load(self.out / "model.json")["meta"]["recall_target"])

    def test_signal_separation_tells_which_signals_separate_the_classes(self):
        rows = {r["signal"]: r for r in train_removal.signal_separation(
            train_removal.Table([e for e in self.events if e.scene != "C"]))}
        self.assertEqual(set(load_dataset.CATALOG), set(rows))
        self.assertGreater(rows["x1_changed"]["auc"], 0.9)             # en lo sintetico el retiro cambia mucho la zona
        self.assertGreater(rows["x1_changed"]["mean_removed"], rows["x1_changed"]["mean_still"])
        self.assertTrue(rows["x1_changed"]["in_main_model"])
        self.assertFalse(rows["x5_frame_changed"]["in_main_model"])

    def test_signals_command_needs_no_training_and_writes_nothing(self):
        out = Path(self.tmp.name) / "no-escribe"
        args = Namespace(**{**vars(self.args), "out": str(out)})
        self.assertEqual(0, train_removal.command_signals(args, self.events))
        self.assertFalse(out.exists())

    def test_training_also_writes_the_extra_reports(self):
        metrics = json.loads((self.out / "metrics.json").read_text(encoding="utf-8"))
        self.assertEqual(len(train_removal.LEARNING_FRACTIONS), len(metrics["learning_curve"]))
        self.assertIn("at_threshold_0.5", metrics)
        self.assertTrue(0.0 <= metrics["auc"] <= 1.0)

    def test_final_runs_once_and_refuses_to_repeat(self):
        args = Namespace(**{**vars(self.args), "final": True})
        self.assertEqual(0, train_removal.command_final(args, self.events))
        final = json.loads((self.out / "final_C.json").read_text(encoding="utf-8"))
        self.assertEqual(len([e for e in self.events if e.scene == "C"]), len(final["events"]))
        self.assertEqual({"M1_recall_de_retiros", "M2_falsos_retiros", "M3_F1_contra_la_regla", "M4_datos", "M5_velocidad",
                          "M6_latencia_frames"}, set(final["goals"]))
        self.assertEqual(1, train_removal.command_final(args, self.events))      # segunda vez: se niega
        self.assertEqual(0, train_removal.command_final(Namespace(**{**vars(args), "force": True}), self.events))

    def test_threshold_keeps_the_recall_target(self):
        train = [e for e in self.events if e.scene != "C"]
        table = train_removal.Table(train)
        folds = train_removal.make_folds(train, 1)
        p = train_removal.out_of_fold(table, folds, "visible", SEVEN, "logistica", 1)
        theta = train_removal.choose_threshold(table, p)
        m = train_removal.event_metrics(train, train_removal.detections(table, p, theta))
        self.assertGreaterEqual(m["recall"], train_removal.RECALL_TARGET)

    def test_a_higher_recall_target_gives_a_lower_or_equal_threshold_with_full_recall(self):
        train = [e for e in self.events if e.scene != "C"]
        table = train_removal.Table(train)
        folds = train_removal.make_folds(train, 1)
        p = train_removal.out_of_fold(table, folds, "visible", SEVEN, "logistica", 1)
        loose, strict = train_removal.choose_threshold(table, p, 0.95), train_removal.choose_threshold(table, p, 1.0)
        self.assertLessEqual(strict, loose)
        self.assertEqual(1.0, train_removal.event_metrics(train, train_removal.detections(table, p, strict))["recall"])


class NumpyModelsTest(unittest.TestCase):
    """Los modelos hechos a mano: aprenden lo que deben y se comportan como sklearn donde se puede comparar."""

    @staticmethod
    def blobs(seed=3, n=200, gap=3.0):
        rng = np.random.default_rng(seed)
        Z = np.vstack([rng.normal(-gap / 2, 1.0, (n, 2)), rng.normal(gap / 2, 1.0, (n, 2))])
        y = np.concatenate([np.zeros(n), np.ones(n)])
        return Z, y

    @staticmethod
    def accuracy(model, Z, y):
        return float(np.mean((model.decision_function(Z) > 0) == (y == 1)))

    def test_perceptron_learns_a_separable_problem(self):
        Z, y = self.blobs(gap=8.0)
        model = models_numpy.PerceptronNP(epochs=100).fit(Z, y)
        self.assertEqual(1.0, self.accuracy(model, Z, y))
        self.assertAlmostEqual(1.0, float(np.linalg.norm(np.append(model.coef_[0], model.intercept_[0]))), places=9)

    def test_pocket_never_ends_worse_than_the_plain_perceptron(self):
        rng = np.random.default_rng(5)
        Z, y = self.blobs(gap=2.0)
        y = np.where(rng.random(len(y)) < 0.15, 1 - y, y)   # 15 % de etiquetas equivocadas: no hay separacion perfecta
        plain = models_numpy.PerceptronNP(epochs=40, seed=1).fit(Z, y)
        pocket = models_numpy.PocketPerceptronNP(epochs=40, seed=1).fit(Z, y)
        self.assertLessEqual(1 - self.accuracy(pocket, Z, y), 1 - self.accuracy(plain, Z, y) + 1e-12)

    def test_logistic_matches_scikit_learn(self):
        from sklearn.linear_model import LogisticRegression
        Z, y = self.blobs(gap=2.0)
        weights = np.random.default_rng(1).uniform(0.5, 2.0, len(y))
        mine = models_numpy.LogisticNP(l2=1e-2).fit(Z, y, weights)
        reference = LogisticRegression(C=1.0 / (1e-2 * weights.sum()), max_iter=20000, tol=1e-12).fit(Z, y, sample_weight=weights)
        self.assertLess(float(np.max(np.abs(mine.predict_proba(Z)[:, 1] - reference.predict_proba(Z)[:, 1]))), 1e-4)

    def test_mlp_backpropagation_matches_a_numerical_gradient(self):
        rng = np.random.default_rng(9)
        Z, y = rng.normal(size=(12, 3)), (rng.random(12) > 0.5).astype(float)
        sw = rng.uniform(0.5, 2.0, 12)
        net = models_numpy.MLPNP(hidden=4, l2=1e-2)
        params = [rng.normal(size=(3, 4)), rng.normal(size=4), rng.normal(size=4), rng.normal(size=1)]
        _, analytic = net.loss_and_grads(params, Z, y, sw, sw.sum())
        for index, grad in enumerate(analytic):
            numeric = np.zeros_like(params[index])
            for position in np.ndindex(params[index].shape):
                original = params[index][position]
                params[index][position] = original + 1e-6
                up, _ = net.loss_and_grads(params, Z, y, sw, sw.sum())
                params[index][position] = original - 1e-6
                down, _ = net.loss_and_grads(params, Z, y, sw, sw.sum())
                params[index][position] = original
                numeric[position] = (up - down) / 2e-6
            self.assertLess(float(np.max(np.abs(numeric - grad))), 1e-6, "parametro %d" % index)

    def test_mlp_solves_xor_and_a_linear_model_cannot(self):
        rng = np.random.default_rng(2)
        corners = np.array([[0, 0], [0, 1], [1, 0], [1, 1]], dtype=float)
        Z = np.repeat(corners, 60, axis=0) + rng.normal(0, 0.08, (240, 2))
        y = np.repeat([0.0, 1.0, 1.0, 0.0], 60)
        Z = (Z - Z.mean(axis=0)) / Z.std(axis=0)
        mlp = models_numpy.MLPNP(hidden=6, seed=0).fit(Z, y)
        linear = models_numpy.LogisticNP().fit(Z, y)
        self.assertGreater(self.accuracy(mlp, Z, y), 0.97)
        self.assertLess(self.accuracy(linear, Z, y), 0.8)     # ejemplo de por que existe el MLP: una recta no separa el XOR

    def test_mlp_exports_in_the_format_java_reads(self):
        Z, y = self.blobs(gap=3.0, n=50)
        net = models_numpy.MLPNP(hidden=4, epochs=200).fit(Z, y)
        model = {"format": 1, "kind": "mlp", "features": ["x1_changed", "x2_visible"], "mean": [0.0, 0.0], "std": [1.0, 1.0],
                 "threshold": 0.5, "mlp": {"w1": net.coefs_[0].T.tolist(), "b1": net.intercepts_[0].tolist(),
                                           "w2": net.coefs_[1][:, 0].tolist(), "b2": float(net.intercepts_[1][0])}}
        removal_model.validate(model)
        self.assertLess(float(np.max(np.abs(removal_model.probability(model, Z) - net.predict_proba(Z)[:, 1]))), 1e-12)


class DatasetChecksTest(unittest.TestCase):
    def test_validate_flags_out_of_range_duplicates_and_broken_sequences(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            events, _ = load_dataset.load(tmp)
        self.assertEqual([], load_dataset.validate_events(events))
        events[0].X[1, 0] = 1.7                                   # x1 fuera de 0-1
        events[1].k = np.array([1, 2, 4])                         # salto en frames_unseen
        events.append(events[2])                                  # evento duplicado
        problems = " | ".join(load_dataset.validate_events(events))
        self.assertIn("fuera de 0-1", problems)
        self.assertIn("sin saltos", problems)
        self.assertIn("duplicado", problems)

    def test_outcome_replay_reproduces_the_real_events_and_detects_a_difference(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)
            events, _ = load_dataset.load(tmp)
        self.assertEqual([], load_dataset.check_outcome_replay(events))
        events[0].outcome_rule = "SEEN_AGAIN"
        self.assertEqual(1, len(load_dataset.check_outcome_replay(events)))

    def test_training_refuses_a_dataset_that_fails_the_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "d"
            make_synthetic_dataset.write_dataset(data, seed=7, sessions_per_scenario=1, events_per_session=3)
            events, _ = load_dataset.load(data)
            events[0].outcome_rule = "DROPPED_CAP" if events[0].outcome_rule != "DROPPED_CAP" else "REMOVED"
            args = Namespace(data=str(data), out=str(Path(tmp) / "r"), reserved="C", seed=1, tie_events=1, final=False, force=False,
                             allow_synthetic=True)
            with self.assertRaises(train_removal.TrainingError) as caught:
                train_removal.command_train(args, events)
        self.assertIn("arreglalos antes de entrenar", str(caught.exception))

    def test_training_refuses_synthetic_sessions_unless_a_test_allows_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "d"
            make_synthetic_dataset.write_dataset(data, seed=7, sessions_per_scenario=1, events_per_session=3)
            events, _ = load_dataset.load(data)
            self.assertTrue(all(e.session.startswith(train_removal.SYNTHETIC_PREFIX) for e in events))
            args = Namespace(data=str(data), out=str(Path(tmp) / "r"), reserved="C", seed=1, tie_events=1, final=False, force=False)
            for command in (train_removal.command_train, train_removal.command_signals):
                with self.assertRaises(train_removal.TrainingError) as caught:
                    command(args, events)
                self.assertIn("SINTETICAS", str(caught.exception))
            self.assertFalse((Path(tmp) / "r").exists(), "no debe escribir nada")


class FramesUnseenShortcutTest(unittest.TestCase):
    """frames_unseen es un atajo aprendido (aprende el guion de las capturas): solo se permite en la ablacion, nunca en un modelo final."""

    @staticmethod
    def model_dict(features, meta=None):
        n = len(features)
        return {"format": 1, "kind": "logistic", "features": features, "mean": [0.0] * n, "std": [1.0] * n, "threshold": 0.5,
                "logistic": {"weights": [0.5] * n, "bias": 0.0}, "meta": meta or {}}

    def run_export(self, tmp, model):
        import export_model
        source = Path(tmp) / "model.json"
        removal_model.save(model, source)
        main, tests = Path(tmp) / "main", Path(tmp) / "tests"
        with unittest.mock.patch.object(export_model, "MAIN_RESOURCES", main), unittest.mock.patch.object(export_model, "FIXTURES", tests):
            code = export_model.command_export(Namespace(model=str(source)))
        return code, main, tests

    def test_training_refuses_to_build_a_final_model_that_uses_frames_unseen(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "d"
            make_synthetic_dataset.write_dataset(data, seed=7, sessions_per_scenario=1, events_per_session=3)
            events, _ = load_dataset.load(data)
            table = train_removal.Table([e for e in events if e.scene != "C"])
            mask = train_removal.training_mask(table, "visible")
            features = train_removal.ZONE + ["frames_unseen"]
            fitted = train_removal.fit(table, mask, features, "logistica", 1)
            with self.assertRaises(train_removal.TrainingError) as caught:
                train_removal.build_model_json(fitted, features, {"threshold": 0.5}, {})
            self.assertIn("atajo aprendido", str(caught.exception))
            # el mismo modelo SIN frames_unseen si se construye
            ok = train_removal.fit(table, mask, train_removal.ZONE, "logistica", 1)
            self.assertEqual(train_removal.ZONE, train_removal.build_model_json(ok, train_removal.ZONE, {"threshold": 0.5}, {})["features"])

    def test_export_refuses_a_model_that_uses_frames_unseen_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, main, tests = self.run_export(tmp, self.model_dict(train_removal.ZONE + ["frames_unseen"]))
            self.assertEqual(1, code)
            self.assertFalse(main.exists())
            self.assertFalse(tests.exists())

    def test_export_still_refuses_toys_and_synthetic_models_and_accepts_a_clean_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, main, _ = self.run_export(tmp, self.model_dict(train_removal.ZONE, {"toy": True}))
            self.assertEqual(1, code)
            self.assertFalse(main.exists())
        with tempfile.TemporaryDirectory() as tmp:
            code, main, _ = self.run_export(tmp, self.model_dict(train_removal.ZONE, {"train_sessions": ["SINT-A-x-01"]}))
            self.assertEqual(1, code)
            self.assertFalse(main.exists())
        with tempfile.TemporaryDirectory() as tmp:
            code, main, tests = self.run_export(tmp, self.model_dict(train_removal.ZONE, {"train_sessions": ["A-still-persona-01"]}))
            self.assertEqual(0, code)                       # en carpetas temporales: nunca toca src/main de verdad
            self.assertTrue((main / "model.json").exists())
            self.assertTrue((tests / "trained.cases.csv").exists())

    def test_toy_fixtures_may_carry_it_but_the_main_toy_does_not(self):
        toy_dir = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "removal-model"
        eight = json.loads((toy_dir / "toy-mlp8.json").read_text(encoding="utf-8"))
        self.assertIn("frames_unseen", eight["features"])      # el juguete del tamano maximo (ablacion) la lleva
        self.assertTrue(eight["meta"]["toy"])
        for name in ("toy-logistic", "toy-mlp"):
            principal = json.loads((toy_dir / (name + ".json")).read_text(encoding="utf-8"))
            self.assertNotIn("frames_unseen", principal["features"], name)


class FailureModesTest(unittest.TestCase):
    def test_only_one_label_gives_a_clear_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_real_like(tmp)                       # solo 3 eventos still, como el dataset real de hoy
            events, _ = load_dataset.load(tmp)
            args = Namespace(data=tmp, out=str(Path(tmp) / "r"), reserved="C", seed=1, tie_events=1, final=False, force=False)
            with self.assertRaises(train_removal.TrainingError) as caught:
                train_removal.command_train(args, events)
        self.assertIn("removed", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
