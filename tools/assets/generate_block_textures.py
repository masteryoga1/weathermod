#!/usr/bin/env python3
"""Generates the textures of the weather mod's two blocks, the tornado siren and the weather deflector.

All images are built from fixed-seed noise and simple analytic shapes, so running the script twice produces
byte-identical files and nothing is copied or traced from any game or mod. The outputs are written straight into
``src/main/resources/assets/weathermod``; run ``python3 tools/assets/generate_block_textures.py`` from the
repository root (or pass ``--out`` to write elsewhere). The script has no inputs.

Outputs, all 16x16 and fully opaque (the block models are plain cubes drawn without blending):

* ``textures/block/tornado_siren_side.png``: a grey steel plate with a dark horn grille of three slats and, above
  it, a lamp band of dark unlit glass.
* ``textures/block/tornado_siren_side_active.png``: the same plate, texel for texel, with the lamp band lit red and
  a red glow on the steel next to it.
* ``textures/block/tornado_siren_top.png``: a steel plate with the round mouth of the horn and four rivets.
* ``textures/block/weather_deflector_side.png``: oxidised copper with bare patches, a pale ring and a pale stud in
  its middle.
* ``textures/block/weather_deflector_top.png``: oxidised copper with two pale rings around a dark core.

Requires Python 3, numpy and Pillow.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPOSITORY_ROOT / "src" / "main" / "resources" / "assets" / "weathermod"

MASTER_SEED = 20240921

SIZE = 16
CENTRE = (SIZE - 1) / 2.0

# Steel: a cool grey whose brightness wanders by a few levels from texel to texel.
STEEL_RGB = (148, 152, 158)
STEEL_SPREAD = 7.0
RIVET_RGB = (198, 202, 210)
RIVET_SHADE_RGB = (96, 99, 106)

# The plate's rim: the top and left edges catch the light, the bottom and right edges lie in shade.
RIM_LIGHT = 22.0
RIM_SHADE = -30.0

# The horn grille on the side of the siren: a dark housing with three lighter slats.
GRILLE_LEFT, GRILLE_RIGHT = 3, 12
GRILLE_TOP, GRILLE_BOTTOM = 6, 13
GRILLE_HOUSING_RGB = (58, 62, 68)
GRILLE_GAP_RGB = (22, 24, 28)
GRILLE_SLAT_RGB = (104, 108, 116)
GRILLE_SLAT_GLINT_RGB = (136, 140, 148)

# The lamp band above the grille: two rows of glass, dark while the siren is silent, red while it sounds.
LAMP_LEFT, LAMP_RIGHT = 2, 13
LAMP_TOP, LAMP_BOTTOM = 2, 3
LAMP_UNLIT_RGB = ((84, 40, 38), (62, 28, 28))
LAMP_LIT_RGB = ((255, 104, 76), (226, 42, 34))
LAMP_GLINT_RGB = (255, 196, 168)
LAMP_GLINT_COLUMNS = (4, 5)
LAMP_GLOW_RGB = (236, 60, 44)
LAMP_GLOW_SHARE = 0.3
LAMP_SHADE = -18.0

# The mouth of the horn on the top of the siren, as rings around the middle of the texture: (outer radius, colour).
HORN_RINGS = (
    (2.2, (24, 26, 30)),
    (3.4, (58, 62, 68)),
    (4.6, (190, 194, 202)),
    (5.4, (110, 114, 122)),
)

# Oxidised copper: verdigris with patches where the browner metal shows through.
VERDIGRIS_RGB = (84, 162, 134)
VERDIGRIS_SPREAD = 8.0
BARE_COPPER_RGB = (150, 106, 76)
BARE_COPPER_SHARE = 0.55
BARE_PATCH_CELLS = 4
BARE_PATCH_THRESHOLD = 0.66
PALE_RGB = (216, 242, 228)
RING_SHADE_RGB = (56, 120, 102)
CORE_RGB = (38, 84, 74)

# The ring on the side of the deflector: (outer radius, colour), innermost first; None keeps the copper.
DEFLECTOR_SIDE_RINGS = (
    (1.3, PALE_RGB),
    (3.3, None),
    (4.2, RING_SHADE_RGB),
    (5.6, PALE_RGB),
)

# The rings on the top of the deflector.
DEFLECTOR_TOP_RINGS = (
    (1.3, CORE_RGB),
    (2.4, RING_SHADE_RGB),
    (3.6, PALE_RGB),
    (5.3, None),
    (6.6, PALE_RGB),
)


def seeded(*stream: int) -> np.random.Generator:
    """Returns a generator for one texture; every texture has its own stream so edits to one never move another."""
    return np.random.default_rng([MASTER_SEED, *stream])


def value_noise(rng: np.random.Generator, cells: int) -> np.ndarray:
    """Bilinearly interpolated value noise in [0, 1] over a ``cells`` x ``cells`` lattice, one sample per texel."""
    lattice = rng.random((cells + 1, cells + 1))
    position = (np.arange(SIZE) + 0.5) / SIZE * cells
    index = np.minimum(position.astype(int), cells - 1)
    fraction = position - index
    top = lattice[index][:, index] * (1.0 - fraction)[None, :] + lattice[index][:, index + 1] * fraction[None, :]
    bottom = (lattice[index + 1][:, index] * (1.0 - fraction)[None, :]
              + lattice[index + 1][:, index + 1] * fraction[None, :])
    return top * (1.0 - fraction)[:, None] + bottom * fraction[:, None]


def distance_from_centre() -> np.ndarray:
    """Distance of every texel's middle from the middle of the texture, in texels."""
    y, x = np.mgrid[0:SIZE, 0:SIZE]
    return np.hypot(x - CENTRE, y - CENTRE)


def plate(rng: np.random.Generator, rgb: tuple[int, int, int], spread: float) -> np.ndarray:
    """A plate of one colour whose brightness varies by up to ``spread`` levels per texel, with a lit rim."""
    image = np.empty((SIZE, SIZE, 3), dtype=np.float64)
    image[...] = rgb
    image += np.rint(rng.uniform(-spread, spread, (SIZE, SIZE)))[..., None]
    image[0, :] += RIM_LIGHT
    image[1:, 0] += RIM_LIGHT
    image[SIZE - 1, 1:] += RIM_SHADE
    image[1:SIZE - 1, SIZE - 1] += RIM_SHADE
    return image


def paint_rings(image: np.ndarray, rings: tuple[tuple[float, tuple[int, int, int] | None], ...]) -> None:
    """Paints concentric rings around the middle; each entry reaches from the previous radius to its own."""
    distance = distance_from_centre()
    inner = 0.0
    for outer, rgb in rings:
        if rgb is not None:
            image[(distance >= inner) & (distance < outer)] = rgb
        inner = outer


def paint_rivets(image: np.ndarray, texels: tuple[tuple[int, int], ...]) -> None:
    """Paints a rivet at each (x, y): one light texel with one shaded texel below it."""
    for x, y in texels:
        image[y, x] = RIVET_RGB
        image[y + 1, x] = RIVET_SHADE_RGB


def siren_side_texture(rng: np.random.Generator, lit: bool) -> np.ndarray:
    """The side of the siren; ``lit`` only changes the lamp band and the steel right next to it."""
    image = plate(rng, STEEL_RGB, STEEL_SPREAD)

    image[GRILLE_TOP:GRILLE_BOTTOM + 1, GRILLE_LEFT:GRILLE_RIGHT + 1] = GRILLE_HOUSING_RGB
    for row in range(GRILLE_TOP + 1, GRILLE_BOTTOM):
        slat = (row - GRILLE_TOP) % 2 == 0
        image[row, GRILLE_LEFT + 1:GRILLE_RIGHT] = GRILLE_SLAT_RGB if slat else GRILLE_GAP_RGB
        if slat:
            image[row, GRILLE_LEFT + 1] = GRILLE_SLAT_GLINT_RGB
    paint_rivets(image, ((1, GRILLE_TOP), (SIZE - 2, GRILLE_TOP), (1, GRILLE_BOTTOM - 1),
                         (SIZE - 2, GRILLE_BOTTOM - 1)))

    glass = LAMP_LIT_RGB if lit else LAMP_UNLIT_RGB
    for offset, row in enumerate(range(LAMP_TOP, LAMP_BOTTOM + 1)):
        image[row, LAMP_LEFT:LAMP_RIGHT + 1] = glass[offset]
    if lit:
        for column in LAMP_GLINT_COLUMNS:
            image[LAMP_TOP, column] = LAMP_GLINT_RGB
        glow = np.array(LAMP_GLOW_RGB, dtype=np.float64)
        for row in (LAMP_TOP - 1, LAMP_BOTTOM + 1):
            band = image[row, LAMP_LEFT:LAMP_RIGHT + 1]
            image[row, LAMP_LEFT:LAMP_RIGHT + 1] = band * (1.0 - LAMP_GLOW_SHARE) + glow * LAMP_GLOW_SHARE
    else:
        image[LAMP_BOTTOM + 1, LAMP_LEFT:LAMP_RIGHT + 1] += LAMP_SHADE
    return image


def siren_top_texture(rng: np.random.Generator) -> np.ndarray:
    """The top and bottom of the siren: the mouth of the horn in a riveted steel plate."""
    image = plate(rng, STEEL_RGB, STEEL_SPREAD)
    paint_rings(image, HORN_RINGS)
    paint_rivets(image, ((2, 2), (SIZE - 3, 2), (2, SIZE - 4), (SIZE - 3, SIZE - 4)))
    return image


def oxidised_copper(rng: np.random.Generator) -> np.ndarray:
    """A verdigris plate with patches of barer, browner copper."""
    image = plate(rng, VERDIGRIS_RGB, VERDIGRIS_SPREAD)
    bare = value_noise(rng, BARE_PATCH_CELLS) > BARE_PATCH_THRESHOLD
    copper = np.array(BARE_COPPER_RGB, dtype=np.float64)
    image[bare] = image[bare] * (1.0 - BARE_COPPER_SHARE) + copper * BARE_COPPER_SHARE
    return image


def deflector_side_texture(rng: np.random.Generator) -> np.ndarray:
    """The side of the deflector: a pale ring with a stud in its middle on oxidised copper."""
    image = oxidised_copper(rng)
    paint_rings(image, DEFLECTOR_SIDE_RINGS)
    return image


def deflector_top_texture(rng: np.random.Generator) -> np.ndarray:
    """The top and bottom of the deflector: two pale rings around a dark core on oxidised copper."""
    image = oxidised_copper(rng)
    paint_rings(image, DEFLECTOR_TOP_RINGS)
    return image


def save_opaque(path: Path, rgb: np.ndarray) -> None:
    """Writes an RGBA PNG from per-texel colours, every texel fully opaque."""
    height, width, _ = rgb.shape
    rgba = np.full((height, width, 4), 255, dtype=np.uint8)
    rgba[..., :3] = np.clip(np.rint(rgb), 0, 255).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(path, format="PNG", optimize=True)


def generate(out: Path) -> list[Path]:
    """Writes every block texture below ``out`` and returns the paths written."""
    block = out / "textures" / "block"
    # Both sides of the siren are painted from the same stream, so they differ in the lamp and nowhere else.
    textures = {
        "tornado_siren_side.png": siren_side_texture(seeded(1), lit=False),
        "tornado_siren_side_active.png": siren_side_texture(seeded(1), lit=True),
        "tornado_siren_top.png": siren_top_texture(seeded(2)),
        "weather_deflector_side.png": deflector_side_texture(seeded(3)),
        "weather_deflector_top.png": deflector_top_texture(seeded(4)),
    }
    written: list[Path] = []
    for name, rgb in textures.items():
        save_opaque(block / name, rgb)
        written.append(block / name)
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="assets directory to write into (default: the repository's assets/weathermod)")
    args = parser.parse_args()
    out = args.out.resolve()
    for path in generate(out):
        label = path.relative_to(out)
        with Image.open(path) as image:
            print(f"{label}  {image.width}x{image.height} {image.mode}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
