"""M6 Anomaly detector: convolutional autoencoder on log-mel, trained ONLY on
benign ambience. Anomaly score = reconstruction MSE, z-scored against the
node's rolling baseline (median/MAD) at inference time.
"""
from __future__ import annotations

import numpy as np


def build_m6(frames: int = 98, n_mels: int = 64, latent: int = 32) -> "tf.keras.Model":
    """Conv AE: encoder 4 stride-2 convs -> latent; mirrored decoder."""
    import tensorflow as tf
    from tensorflow.keras import layers, models

    inp = layers.Input(shape=(frames, n_mels, 1), name="logmel")
    x = inp
    chs = (16, 24, 32, 48)
    skips = []
    for ch in chs:
        x = layers.Conv2D(ch, (3, 3), strides=2, padding="same", activation="relu")(x)
        skips.append(x)
    x = layers.Flatten()(x)
    z = layers.Dense(latent, name="latent")(x)
    # decoder: dense back up, then mirrored Conv2DTranspose
    # 98x64 -> 4 stride-2 convs -> 7x4 (ceil)
    x = layers.Dense(7 * 4 * 48, activation="relu")(z)
    x = layers.Reshape((7, 4, 48))(x)
    for ch in (48, 32, 24, 16):
        x = layers.Conv2DTranspose(ch, (3, 3), strides=2, padding="same",
                                   activation="relu")(x)
    out = layers.Conv2D(1, (3, 3), padding="same", name="reconstruction")(x)
    # exact size fix if rounding drifted
    if out.shape[1:] != (frames, n_mels, 1):
        out = layers.Resizing(frames, n_mels)(out)
    m = models.Model(inp, out, name="m6_anomaly_ae")
    m.compile(optimizer="adam", loss="mse")
    return m


def reconstruction_error(model, logmel: np.ndarray) -> float:
    """MSE between input and reconstruction for one window (float)."""
    x = np.asarray(logmel, dtype=np.float32)
    if x.ndim == 2:
        x = x.T
    x = x[None, ..., None]
    rec = model.predict(x, verbose=0)
    return float(np.mean(np.square(x - rec)))
