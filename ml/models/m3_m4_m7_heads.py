"""M3 Chainsaw / M4 Vehicle / M7 Human-presence: MLP heads on the frozen
YAMNet 1024-d embedding (shared with M1).

M7 additionally concatenates 8 selected YAMNet class scores (speech, shout,
footsteps, human voice classes...) per the spec.
"""
from __future__ import annotations


def mlp_head(n_in: int, n_classes: int, name: str, hidden: int = 256,
             dropout: float = 0.35, final_act: str = "sigmoid"):
    """2 dense layers + dropout (spec: MLP head)."""
    import tensorflow as tf
    from tensorflow.keras import layers, models

    inp = layers.Input(shape=(n_in,), name="emb")
    x = layers.Dense(hidden, activation="relu")(inp)
    x = layers.Dropout(dropout)(x)
    x = layers.Dense(hidden // 2, activation="relu")(x)
    x = layers.Dropout(dropout)(x)
    out = layers.Dense(n_classes, activation=final_act, name="probs")(x)
    return models.Model(inp, out, name=name)


def build_m3() -> "tf.keras.Model":
    return mlp_head(1024, 1, "m3_chainsaw")


def build_m4() -> "tf.keras.Model":
    return mlp_head(1024, 1, "m4_vehicle")


# M7 input: 1024-d embedding + 8 selected YAMNet class scores (verified names)
M7_EXTRA_CLASSES = [
    "Speech",
    "Conversation",
    "Shout",
    "Chatter",
    "Screaming",
    "Whispering",
    "Walk, footsteps",
    "Laughter",
]
M7_N_IN = 1024 + len(M7_EXTRA_CLASSES)


def build_m7() -> "tf.keras.Model":
    return mlp_head(M7_N_IN, 1, "m7_human")
