"""M2 Gunshot detector: small 2D CNN, 3 conv blocks, depthwise-separable,
global pooling. Input log-mel (98, 64, 1), output P(gunshot).

Also used (architecturally) by M5 with 4-class softmax.
"""
from __future__ import annotations

import numpy as np


def small_cnn(n_classes: int = 1, frames: int = 98, n_mels: int = 64,
              name: str = "small_cnn", final_act: str | None = "sigmoid"):
    """3 conv blocks (separable) + global pooling + dense head.

    Kept deliberately small for CPU inference and int8 quantization.
    """
    import tensorflow as tf
    from tensorflow.keras import layers, models

    inp = layers.Input(shape=(frames, n_mels, 1), name="logmel")
    x = inp
    for ch in (16, 32, 48):
        x = layers.SeparableConv2D(ch, (3, 3), padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.SeparableConv2D(ch, (3, 3), padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.MaxPooling2D((2, 2))(x)
        x = layers.Dropout(0.1)(x)
    x = layers.GlobalAveragePooling2D()(x)
    x = layers.Dense(48, activation="relu")(x)
    x = layers.Dropout(0.2)(x)
    out = layers.Dense(n_classes, activation=final_act, name="probs")(x)
    m = models.Model(inp, out, name=name)
    return m


def build_m2() -> "tf.keras.Model":
    return small_cnn(n_classes=1, name="m2_gunshot", final_act="sigmoid")


def build_m5() -> "tf.keras.Model":
    """Weather/noise rejector: same family, 4-way softmax."""
    return small_cnn(n_classes=4, name="m5_weather", final_act="softmax")


def focal_loss(gamma: float = 2.0, alpha: float | None = 0.25):
    """Binary focal loss for class imbalance."""
    import tensorflow as tf

    def loss(y_true, y_pred):
        y_true = tf.cast(y_true, tf.float32)
        y_pred = tf.clip_by_value(y_pred, 1e-7, 1 - 1e-7)
        pt = tf.where(tf.equal(y_true, 1.0), y_pred, 1.0 - y_pred)
        alpha_t = tf.where(tf.equal(y_true, 1.0), alpha, 1.0 - alpha) if alpha else 1.0
        return -tf.reduce_mean(alpha_t * tf.pow(1.0 - pt, gamma) * tf.math.log(pt))

    return loss
