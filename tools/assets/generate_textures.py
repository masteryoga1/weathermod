#!/usr/bin/env python3
"""Generates every texture the weather mod's client draws, plus the particle definition files that list them.

All images are built from fixed-seed noise and simple analytic shapes, so running the script twice produces
byte-identical files and nothing is copied or traced from any game or mod. The outputs are written straight into
``src/main/resources/assets/weathermod``; run ``python3 tools/assets/generate_textures.py`` from the repository
root (or pass ``--out`` to write elsewhere).

Outputs:

* ``textures/environment/rain.png`` 64x64: thin vertical streaks for the precipitation renderer, tiling vertically.
* ``textures/environment/snow.png`` 64x64: soft round flakes, tiling vertically.
* ``textures/particle/haze_0.png`` .. ``haze_3.png`` 32x32: soft radial puffs broken up by low-frequency noise.
* ``textures/particle/wind_dust_0.png``, ``wind_dust_1.png`` 8x8: a few irregular specks.
* ``textures/particle/wind_leaf_0.png``, ``wind_leaf_1.png`` 8x8: a grey leaf silhouette the foliage colour tints.
* ``particles/haze.json``, ``particles/wind_dust.json``, ``particles/wind_leaf.json``.

Requires Python 3, numpy and Pillow.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPOSITORY_ROOT / "src" / "main" / "resources" / "assets" / "weathermod"

MASTER_SEED = 20240917

ENVIRONMENT_SIZE = 64
HAZE_SIZE = 32
SMALL_SIZE = 8

RAIN_COLOR = (230, 236, 245)
SNOW_COLOR = (255, 255, 255)
WHITE = (255, 255, 255)
LEAF_BODY_GREY = 200
LEAF_MIDRIB_GREY = 150


def seeded(*stream: int) -> np.random.Generator:
    """Returns a generator for one texture; every texture has its own stream so edits to one never move another."""
    return np.random.default_rng([MASTER_SEED, *stream])


def smoothstep(edge0: float, edge1: float, x: np.ndarray) -> np.ndarray:
    """Hermite ramp from 0 at ``edge0`` to 1 at ``edge1``."""
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def value_noise(rng: np.random.Generator, size: int, cells: int) -> np.ndarray:
    """Smoothly interpolated value noise in [0, 1] over a ``cells`` x ``cells`` lattice, sampled at ``size`` pixels."""
    lattice = rng.random((cells + 1, cells + 1))
    coords = (np.arange(size) + 0.5) / size * cells
    index = np.minimum(np.floor(coords).astype(int), cells - 1)
    frac = coords - index
    weight = frac * frac * (3.0 - 2.0 * frac)
    iy, ix = np.meshgrid(index, index, indexing="ij")
    wy, wx = np.meshgrid(weight, weight, indexing="ij")
    top = lattice[iy, ix] * (1.0 - wx) + lattice[iy, ix + 1] * wx
    bottom = lattice[iy + 1, ix] * (1.0 - wx) + lattice[iy + 1, ix + 1] * wx
    return top * (1.0 - wy) + bottom * wy


def save_rgba(path: Path, grey: np.ndarray, alpha: np.ndarray) -> None:
    """Writes an RGBA PNG; ``grey`` is either an (r, g, b) tuple or a per-pixel grey array, ``alpha`` is 0..1."""
    height, width = alpha.shape
    rgba = np.zeros((height, width, 4), dtype=np.uint8)
    if isinstance(grey, tuple):
        rgba[..., 0] = grey[0]
        rgba[..., 1] = grey[1]
        rgba[..., 2] = grey[2]
    else:
        level = np.clip(np.rint(grey), 0, 255).astype(np.uint8)
        rgba[..., 0] = level
        rgba[..., 1] = level
        rgba[..., 2] = level
    rgba[..., 3] = np.clip(np.rint(alpha * 255.0), 0, 255).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgba, "RGBA").save(path, format="PNG", optimize=True)


def rain_texture(rng: np.random.Generator) -> np.ndarray:
    """7-9 vertical streaks, 1-2 px wide and 18-48 px long, that wrap vertically and never touch each other."""
    size = ENVIRONMENT_SIZE
    alpha = np.zeros((size, size))
    occupied = np.zeros(size, dtype=bool)
    wanted = int(rng.integers(7, 10))
    placed = 0
    attempts = 0
    while placed < wanted and attempts < 10_000:
        attempts += 1
        width = int(rng.integers(1, 3))
        x = int(rng.integers(0, size - width + 1))
        columns = [x + k for k in range(width)]
        guard = [(x - 1) % size, (x + width) % size]
        if any(occupied[c] for c in columns + guard):
            continue
        length = int(rng.integers(18, 49))
        start = int(rng.integers(0, size))
        peak = float(rng.uniform(0.55, 0.9))
        fade = float(rng.uniform(3.0, 6.0))
        along = np.arange(length) + 0.5
        envelope = smoothstep(0.0, fade, along) * smoothstep(0.0, fade, length - along)
        rows = (start + np.arange(length)) % size
        for k, column in enumerate(columns):
            column_factor = 1.0 if k == 0 else 0.8
            alpha[rows, column] = np.maximum(alpha[rows, column], peak * column_factor * envelope)
        for column in columns:
            occupied[column] = True
        placed += 1
    if placed < 7:
        raise RuntimeError(f"could only place {placed} rain streaks")
    return alpha


def snow_texture(rng: np.random.Generator) -> np.ndarray:
    """10-14 soft round flakes 2-4 px across with a raised-cosine profile, wrapping vertically, never touching."""
    size = ENVIRONMENT_SIZE
    alpha = np.zeros((size, size))
    yy, xx = np.mgrid[0:size, 0:size] + 0.5
    wanted = int(rng.integers(10, 15))
    flakes: list[tuple[float, float, float]] = []
    attempts = 0
    while len(flakes) < wanted and attempts < 10_000:
        attempts += 1
        diameter = float(rng.uniform(2.0, 4.0))
        radius = diameter / 2.0
        cx = float(rng.integers(math.ceil(radius), size - math.ceil(radius))) + 0.5
        cy = float(rng.integers(0, size)) + 0.5
        too_close = False
        for ox, oy, oradius in flakes:
            dy = abs(cy - oy)
            dy = min(dy, size - dy)
            if math.hypot(cx - ox, dy) < radius + oradius + 1.5:
                too_close = True
                break
        if too_close:
            continue
        peak = float(rng.uniform(0.7, 1.0))
        dy = np.abs(yy - cy)
        dy = np.minimum(dy, size - dy)
        distance = np.hypot(xx - cx, dy) / radius
        profile = np.where(distance < 1.0, 0.5 + 0.5 * np.cos(np.pi * np.minimum(distance, 1.0)), 0.0)
        alpha = np.maximum(alpha, peak * profile)
        flakes.append((cx, cy, radius))
    if len(flakes) < 10:
        raise RuntimeError(f"could only place {len(flakes)} snow flakes")
    return alpha


def haze_texture(rng: np.random.Generator) -> np.ndarray:
    """A soft radial blob, alpha 0.9 at the centre and 0 at the edge, with its outline wobbled by low-frequency noise."""
    size = HAZE_SIZE
    yy, xx = (np.mgrid[0:size, 0:size] + 0.5) / size * 2.0 - 1.0
    radius = np.hypot(xx, yy)
    outline = value_noise(rng, size, 3) - 0.5
    body = value_noise(rng, size, 5) - 0.5
    wobbled = np.clip(radius * (1.0 + 0.45 * outline) + 0.12 * body * radius, 0.0, 1.0)
    alpha = 0.9 * (0.5 + 0.5 * np.cos(np.pi * wobbled))
    alpha *= 1.0 - 0.35 * radius * (0.5 - body)
    alpha *= np.clip((1.0 - radius) / 0.08, 0.0, 1.0)
    return np.clip(alpha, 0.0, 0.9)


def dust_texture(rng: np.random.Generator) -> np.ndarray:
    """2-3 irregular specks: one or two solid pixels each, ringed by a one-pixel soft edge, never overlapping."""
    size = SMALL_SIZE
    alpha = np.zeros((size, size))
    footprint = np.zeros((size, size), dtype=bool)
    wanted = int(rng.integers(2, 4))
    placed = 0
    attempts = 0
    while placed < wanted and attempts < 2_000:
        attempts += 1
        shape = int(rng.integers(0, 4))
        y, x = (int(v) for v in rng.integers(1, 7, size=2))
        core = {(y, x)}
        if shape == 1:
            core.add((y, x + 1))
        elif shape == 2:
            core.add((y + 1, x))
        elif shape == 3:
            core.add((y + 1, x + 1))
        if any(not (1 <= cy <= 6 and 1 <= cx <= 6) for cy, cx in core):
            continue
        orthogonal = set()
        diagonal = set()
        for cy, cx in core:
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    cell = (cy + dy, cx + dx)
                    if cell in core:
                        continue
                    if dy == 0 or dx == 0:
                        orthogonal.add(cell)
                    else:
                        diagonal.add(cell)
        diagonal -= orthogonal
        cells = core | orthogonal | diagonal
        if any(footprint[c] for c in cells):
            continue
        for cell in core:
            alpha[cell] = 1.0
        for cell in orthogonal:
            alpha[cell] = float(rng.uniform(0.4, 0.6))
        for cell in diagonal:
            alpha[cell] = float(rng.uniform(0.15, 0.35))
        for cell in cells:
            footprint[cell] = True
        placed += 1
    if placed < 2:
        raise RuntimeError(f"could only place {placed} dust specks")
    return alpha


def leaf_texture(rng: np.random.Generator, diagonal: bool) -> tuple[np.ndarray, np.ndarray]:
    """A leaf silhouette: an elliptic base tapering to a point, with a one-pixel darker midrib; hard alpha edge."""
    size = SMALL_SIZE
    supersample = 16
    coords = (np.arange(size * supersample) + 0.5) / supersample
    yy, xx = np.meshgrid(coords, coords, indexing="ij")
    if diagonal:
        base = np.array([1.5, 6.5])
        tip = np.array([6.5, 1.5])
        half_width = float(rng.uniform(1.55, 1.75))
    else:
        base = np.array([4.5, 7.0])
        tip = np.array([4.5, 1.0])
        half_width = float(rng.uniform(1.6, 1.85))
    widest = float(rng.uniform(0.36, 0.44))
    taper = float(rng.uniform(1.4, 1.8))
    axis = tip - base
    length = float(np.hypot(*axis))
    ux, uy = axis / length
    nx, ny = -uy, ux
    along = ((xx - base[0]) * ux + (yy - base[1]) * uy) / length
    across = (xx - base[0]) * nx + (yy - base[1]) * ny
    base_part = np.sqrt(np.clip(1.0 - ((widest - along) / widest) ** 2, 0.0, 1.0))
    tip_part = 1.0 - np.clip((along - widest) / (1.0 - widest), 0.0, 1.0) ** taper
    width = half_width * np.where(along < widest, base_part, tip_part)
    inside = (along >= 0.0) & (along <= 1.0) & (np.abs(across) <= width)
    midrib = inside & (np.abs(across) <= 0.5) & (along >= 0.05) & (along <= 0.82)

    def coverage(mask: np.ndarray) -> np.ndarray:
        return mask.reshape(size, supersample, size, supersample).mean(axis=(1, 3))

    alpha = (coverage(inside) >= 0.5).astype(float)
    rib = (coverage(midrib) >= 0.5) & (alpha > 0.0)
    grey = np.full((size, size), float(LEAF_BODY_GREY))
    grey[rib] = LEAF_MIDRIB_GREY
    if alpha.sum() < 8:
        raise RuntimeError("leaf silhouette came out too small")
    return grey, alpha


def write_particle_definition(path: Path, texture_names: list[str]) -> None:
    """Writes a vanilla particle definition listing the mod's sprites in order."""
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {"textures": [f"weathermod:{name}" for name in texture_names]}
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def generate(out: Path) -> list[Path]:
    """Generates every texture and particle definition under ``out`` and returns the written paths."""
    written: list[Path] = []
    environment = out / "textures" / "environment"
    particle = out / "textures" / "particle"
    definitions = out / "particles"

    save_rgba(environment / "rain.png", RAIN_COLOR, rain_texture(seeded(1)))
    written.append(environment / "rain.png")
    save_rgba(environment / "snow.png", SNOW_COLOR, snow_texture(seeded(2)))
    written.append(environment / "snow.png")

    haze_names = []
    for index in range(4):
        name = f"haze_{index}"
        save_rgba(particle / f"{name}.png", WHITE, haze_texture(seeded(3, index)))
        written.append(particle / f"{name}.png")
        haze_names.append(name)

    dust_names = []
    for index in range(2):
        name = f"wind_dust_{index}"
        save_rgba(particle / f"{name}.png", WHITE, dust_texture(seeded(4, index)))
        written.append(particle / f"{name}.png")
        dust_names.append(name)

    leaf_names = []
    for index in range(2):
        name = f"wind_leaf_{index}"
        grey, alpha = leaf_texture(seeded(5, index), diagonal=index == 1)
        save_rgba(particle / f"{name}.png", grey, alpha)
        written.append(particle / f"{name}.png")
        leaf_names.append(name)

    write_particle_definition(definitions / "haze.json", haze_names)
    written.append(definitions / "haze.json")
    write_particle_definition(definitions / "wind_dust.json", dust_names)
    written.append(definitions / "wind_dust.json")
    write_particle_definition(definitions / "wind_leaf.json", leaf_names)
    written.append(definitions / "wind_leaf.json")
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="assets directory to write into (default: the repository's assets/weathermod)")
    args = parser.parse_args()
    out = args.out.resolve()
    for path in generate(out):
        label = path.relative_to(out)
        if path.suffix == ".png":
            with Image.open(path) as image:
                print(f"{label}  {image.width}x{image.height} {image.mode}")
        else:
            print(f"{label}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
