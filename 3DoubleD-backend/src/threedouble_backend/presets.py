"""Built-in themed preset and asset library for 3Double D.

These mirror the design brief's presets (Vampire, Medieval, Demon, Cyberpunk)
so the Android client can pull the same definitions from the backend.
"""

from __future__ import annotations

from .models import Asset, Preset

PRESETS: list[Preset] = [
    Preset(
        id="vampire",
        name="Vampire",
        primary_material="Pale",
        base_color="#F1E9DF",
        accent_color="#B32424",
        morphs={"skin_tone": 0.15, "body_build": 0.45},
    ),
    Preset(
        id="medieval",
        name="Medieval",
        primary_material="Iron",
        base_color="#9AA0A6",
        accent_color="#8A5A3B",
        morphs={"skin_tone": 0.4, "body_build": 0.55},
    ),
    Preset(
        id="demon",
        name="Demon",
        primary_material="Crimson",
        base_color="#B32424",
        accent_color="#1E1B1B",
        morphs={"skin_tone": 0.6, "body_build": 0.7},
    ),
    Preset(
        id="cyberpunk",
        name="Cyberpunk",
        primary_material="Neon",
        base_color="#7B2BE0",
        accent_color="#38BDF8",
        morphs={"skin_tone": 0.5, "body_build": 0.6},
    ),
]

ASSETS: list[Asset] = [
    Asset(id="sample-female", name="Female Base (Sample)", uri="assets/models/female.glb", category="female", sample=True),
    Asset(id="sample-male", name="Male Base (Sample)", uri="assets/models/male.glb", category="male", sample=True),
    Asset(id="sample-cyborg", name="Cyborg Base (Sample)", uri="assets/models/cyborg.glb", category="cyborg", sample=True),
]


def get_presets() -> list[Preset]:
    return list(PRESETS)


def get_assets() -> list[Asset]:
    return list(ASSETS)
