"""Los modelos del Hito hechos A MANO con numpy (para entender la mecanica; sklearn solo se usa para comprobarlos).

Todos reciben senales ya ESTANDARIZADAS Z (n, d), etiquetas y en {0, 1} (1 = "retirado") y pesos por muestra, y exponen lo mismo que
sklearn (fit, decision_function, predict_proba) mas los atributos con los que train_removal.build_model_json los exporta a JSON:
  lineales (perceptron, pocket, logistica):  coef_ (1, d), intercept_ (1,)
  MLP:                                       coefs_ [(d, h), (h, 1)], intercepts_ [(h,), (1,)]

Ejemplo con ESTE proyecto: la senal x2 (cuanto de la zona se ve) y x1 (cuanto cambio). Una persona que tapa la mochila da x2 ~ 0 y x1 = 0;
un retiro real da x2 ~ 1 y x1 alto. Un modelo lineal da un peso a cada senal y las suma: score = b + w1*x1 + w2*x2 + ...; si score > 0,
"retirado". Lo que cambia entre perceptron, logistica y MLP es COMO se eligen esos pesos.
"""
import numpy as np

from removal_model import sigmoid


class _Linear:
    """Base de los modelos lineales: score = Z w + b."""

    coef_ = None
    intercept_ = None

    def decision_function(self, Z):
        return Z @ self.coef_[0] + self.intercept_[0]

    def predict_proba(self, Z):
        p = sigmoid(self.decision_function(Z))
        return np.column_stack([1.0 - p, p])


class PerceptronNP(_Linear):
    """Perceptron simple. Para cada muestra mal clasificada: w <- w + eta * (t - y_hat) * x  (y lo mismo para el sesgo b).

    y_hat = 1 si w.x + b > 0, si no 0; t es la etiqueta. Si t = 1 e y_hat = 0 el vector w se acerca a x; si t = 0 e y_hat = 1 se aleja.
    Se detiene cuando una epoca entera no comete errores (solo ocurre si los datos son linealmente separables); si no, para a las
    `epochs` y se queda con los ULTIMOS pesos (por eso puede dar un mal modelo con datos ruidosos: eso es lo que arregla el pocket).

    Al final los pesos se escalan a norma 1: el signo de w.x + b no cambia con la escala, y asi el score (que luego pasa por la sigmoide
    para tener algo entre 0 y 1) queda en un rango razonable en vez de decenas.
    """

    def __init__(self, eta=0.1, epochs=200, seed=0, pocket=False):
        self.eta, self.epochs, self.seed, self.pocket = eta, epochs, seed, pocket
        self.history_ = []  # errores ponderados por epoca (para explicar el aprendizaje)

    def fit(self, Z, y, sample_weight=None):
        n, d = Z.shape
        sw = np.ones(n) if sample_weight is None else np.asarray(sample_weight, dtype=float)
        rng = np.random.default_rng(self.seed)
        w, b = np.zeros(d), 0.0
        best_w, best_b, best_error = w.copy(), b, self._weighted_error(Z, y, sw, w, b)
        for _ in range(self.epochs):
            mistakes = 0
            for i in rng.permutation(n):
                y_hat = 1.0 if Z[i] @ w + b > 0 else 0.0
                if y_hat != y[i]:
                    step = self.eta * sw[i] * (y[i] - y_hat)
                    w = w + step * Z[i]
                    b = b + step
                    mistakes += 1
            error = self._weighted_error(Z, y, sw, w, b)
            self.history_.append(error)
            if self.pocket and error < best_error:   # pocket: guardar en el "bolsillo" los mejores pesos vistos
                best_w, best_b, best_error = w.copy(), b, error
            if mistakes == 0:
                break
        if self.pocket:
            w, b = best_w, best_b
        scale = np.linalg.norm(np.append(w, b))
        if scale > 0:
            w, b = w / scale, b / scale
        self.coef_, self.intercept_ = w.reshape(1, -1), np.array([b])
        return self

    @staticmethod
    def _weighted_error(Z, y, sw, w, b):
        predicted = (Z @ w + b > 0).astype(float)
        return float(np.sum(sw * (predicted != y)) / np.sum(sw))


class PocketPerceptronNP(PerceptronNP):
    """Perceptron *pocket*: igual que el simple pero se queda con los mejores pesos vistos (version por epoca)."""

    def __init__(self, eta=0.1, epochs=200, seed=0):
        super().__init__(eta=eta, epochs=epochs, seed=seed, pocket=True)


class LogisticNP(_Linear):
    """Regresion logistica con L2, por descenso del gradiente de lote completo.

    p = sigmoide(w.x + b). Se minimiza la entropia cruzada ponderada  L = -(1/S) sum_i s_i [t_i ln p_i + (1-t_i) ln(1-p_i)] + (l2/2)|w|^2
    (S = suma de pesos; el sesgo b no se penaliza). Su gradiente es  dL/dw = (1/S) Z^T (s * (p - t)) + l2 w  y  dL/db = (1/S) sum s (p - t):
    "cuanto me equivoque, multiplicado por la senal". La L2 evita que, con datos separables, los pesos crezcan sin limite.
    """

    def __init__(self, l2=1e-2, lr=0.5, max_iter=20000, tol=1e-9):
        self.l2, self.lr, self.max_iter, self.tol = l2, lr, max_iter, tol
        self.iterations_ = 0

    def fit(self, Z, y, sample_weight=None):
        n, d = Z.shape
        sw = np.ones(n) if sample_weight is None else np.asarray(sample_weight, dtype=float)
        total = sw.sum()
        w, b = np.zeros(d), 0.0
        for it in range(1, self.max_iter + 1):
            p = sigmoid(Z @ w + b)
            error = sw * (p - y) / total
            grad_w = Z.T @ error + self.l2 * w
            grad_b = error.sum()
            w, b = w - self.lr * grad_w, b - self.lr * grad_b
            if np.linalg.norm(grad_w) + abs(grad_b) < self.tol:
                break
        self.iterations_ = it
        self.coef_, self.intercept_ = w.reshape(1, -1), np.array([b])
        return self


class MLPNP:
    """Red de una capa oculta d - h - 1: ReLU en la oculta, sigmoide a la salida, entropia cruzada ponderada y Adam (lote completo).

    Hacia delante:  a = relu(Z W1 + b1);  p = sigmoide(a w2 + b2).
    Hacia atras (retropropagacion): d_score = s (p - t) / S;  dw2 = a^T d_score;  d_a = d_score w2^T * (a > 0);  dW1 = Z^T d_a.
    Adam lleva un promedio movil del gradiente (m) y de su cuadrado (v) y da pasos lr * m_hat / (sqrt(v_hat) + eps).
    Con 4 senales y 6 neuronas son 4*6 + 6 + 6 + 1 = 37 parametros.
    """

    def __init__(self, hidden=6, lr=0.02, epochs=1500, l2=1e-3, seed=0, beta1=0.9, beta2=0.999, eps=1e-8):
        self.hidden, self.lr, self.epochs, self.l2, self.seed = hidden, lr, epochs, l2, seed
        self.beta1, self.beta2, self.eps = beta1, beta2, eps
        self.loss_ = []

    def fit(self, Z, y, sample_weight=None):
        n, d = Z.shape
        h = self.hidden
        sw = np.ones(n) if sample_weight is None else np.asarray(sample_weight, dtype=float)
        total = sw.sum()
        rng = np.random.default_rng(self.seed)
        params = [rng.normal(0.0, np.sqrt(2.0 / d), (d, h)), np.zeros(h), rng.normal(0.0, np.sqrt(1.0 / h), h), np.zeros(1)]
        m = [np.zeros_like(p) for p in params]
        v = [np.zeros_like(p) for p in params]
        for step in range(1, self.epochs + 1):
            loss, grads = self.loss_and_grads(params, Z, y, sw, total)
            for i, (param, grad) in enumerate(zip(params, grads)):
                m[i] = self.beta1 * m[i] + (1 - self.beta1) * grad
                v[i] = self.beta2 * v[i] + (1 - self.beta2) * grad * grad
                m_hat = m[i] / (1 - self.beta1 ** step)
                v_hat = v[i] / (1 - self.beta2 ** step)
                param -= self.lr * m_hat / (np.sqrt(v_hat) + self.eps)  # in situ: params[i] es el mismo arreglo
            if step % 100 == 0 or step == 1:
                self.loss_.append(float(loss))
        W1, b1, w2, b2 = params
        self.coefs_ = [W1, w2.reshape(-1, 1)]
        self.intercepts_ = [b1, b2]
        return self

    def loss_and_grads(self, params, Z, y, sw, total):
        """Perdida (entropia cruzada ponderada + L2) y su gradiente respecto a [W1, b1, w2, b2], por retropropagacion."""
        W1, b1, w2, b2 = params
        hidden_pre = Z @ W1 + b1
        a = np.maximum(0.0, hidden_pre)
        p = sigmoid(a @ w2 + b2[0])
        loss = (-np.sum(sw * (y * np.log(p + 1e-12) + (1 - y) * np.log(1 - p + 1e-12))) / total
                + 0.5 * self.l2 * (np.sum(W1 * W1) + np.sum(w2 * w2)))
        d_score = sw * (p - y) / total
        d_hidden = (d_score[:, None] * w2[None, :]) * (hidden_pre > 0)
        grads = [Z.T @ d_hidden + self.l2 * W1, d_hidden.sum(axis=0), a.T @ d_score + self.l2 * w2, np.array([d_score.sum()])]
        return float(loss), grads

    def decision_function(self, Z):
        W1, w2 = self.coefs_
        return np.maximum(0.0, Z @ W1 + self.intercepts_[0]) @ w2[:, 0] + self.intercepts_[1][0]

    def predict_proba(self, Z):
        p = sigmoid(self.decision_function(Z))
        return np.column_stack([1.0 - p, p])
