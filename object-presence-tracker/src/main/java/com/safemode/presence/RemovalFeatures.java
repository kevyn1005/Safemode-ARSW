package com.safemode.presence;

import java.util.List;

/**
 * Catalogo de las senales (caracteristicas) que un {@link RemovalClassifier} puede usar para decidir si un objeto que el detector
 * dejo de ver sigue ahi o fue retirado. Cada modelo declara cuales usa y en que orden; el resto se ignora. El mismo catalogo, con la
 * misma definicion, esta en {@code ml/removal_features.py}: una prueba compara las dos implementaciones con casos generados en Python.
 *
 * <p>Las cinco primeras salen de {@link RegionFingerprint} y de la comparacion del cuadro completo (ver {@link RemovalSampleLog}):
 * {@code x1_changed}, {@code x2_visible}, {@code x3_changed_soft}, {@code x4_mean_abs_diff} y {@code x5_frame_changed} (si no se pudo
 * medir, vale 0). Las otras tres:
 * <ul>
 *   <li>{@code frames_unseen}: cuantos frames seguidos lleva sin verse el objeto (k).</li>
 *   <li>{@code covered_frames}: cuantos frames SEGUIDOS, hasta este inclusive, tuvo la zona casi toda tapada
 *       ({@code x2_visible} &lt; {@link #COVERED_BELOW}); 0 si ahora se ve.</li>
 *   <li>{@code last_visible_x1}: el {@code x1_changed} del ultimo frame (hasta este inclusive) en que la zona se pudo ver; 0 si todavia
 *       no se vio ninguno. Es la "memoria" que permite esperar mientras alguien tapa el objeto y decidir cuando la zona vuelve a verse.</li>
 * </ul>
 */
final class RemovalFeatures {

    static final List<String> CATALOG = List.of("x1_changed", "x2_visible", "x3_changed_soft", "x4_mean_abs_diff",
            "x5_frame_changed", "frames_unseen", "covered_frames", "last_visible_x1");

    // igual que MIN_VISIBLE_FRACTION de la regla: por debajo de esto la zona se considera tapada y no se puede comprobar
    static final double COVERED_BELOW = 0.35;

    private RemovalFeatures() {
    }

    /** Estado de UNA racha de frames sin ver un objeto: se crea al empezar la racha y se descarta cuando el objeto vuelve a verse. */
    static final class Run {
        private int covered;
        private double lastVisibleX1;

        /**
         * Suma un frame a la racha y devuelve el vector del catalogo (en el orden de {@link #CATALOG}).
         * {@code frameChanged} negativo significa "no se pudo medir" y se usa como 0.
         */
        double[] next(RegionFingerprint.RemovalSignals signals, double frameChanged, int framesUnseen) {
            if (signals.visibleFraction() < COVERED_BELOW) {
                covered++;
            } else {
                covered = 0;
                lastVisibleX1 = signals.changedFraction();
            }
            return new double[]{signals.changedFraction(), signals.visibleFraction(), signals.changedSoft(), signals.meanAbsDiff(),
                    frameChanged < 0 ? 0 : frameChanged, framesUnseen, covered, lastVisibleX1};
        }
    }
}
