#!/usr/bin/env python3
"""Generates the structure templates the game tests of the weather mod are run on.

The tests under ``com.weathermod.gametest`` build their scenes in code, so the templates are bare: a floor of
bedrock, which no storm can rip, and nothing above it. Two sizes are written straight into
``src/main/resources/data/weathermod/structure``:

* ``platform.nbt``: 17 x 8 x 17, for the tests that need a few blocks of room.
* ``arena.nbt``: 43 x 12 x 43, wide enough to hold everything a parked EF3 tornado reaches.

Run ``python3 tools/gametest/generate_structures.py`` from the repository root (or pass ``--out`` to write
elsewhere, or ``--check`` to compare the files on disk with what the script would write). The script has no inputs
and uses nothing outside the Python 3 standard library. The NBT is written by hand in a fixed order and wrapped in
a gzip member with a zeroed time stamp, so two runs produce byte-identical files.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib
from pathlib import Path

DATA_VERSION = 3955
"""Data version of Minecraft 1.21.1, the version the templates are saved for."""

FLOOR_BLOCK = "minecraft:bedrock"

TEMPLATES = {
    "platform": (17, 8, 17),
    "arena": (43, 12, 43),
}

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10

DEFAULT_OUT = Path("src/main/resources/data/weathermod/structure")


def _name(name: str) -> bytes:
    raw = name.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def _named(tag: int, name: str) -> bytes:
    return bytes([tag]) + _name(name)


def _int(name: str, value: int) -> bytes:
    return _named(TAG_INT, name) + struct.pack(">i", value)


def _string(name: str, value: str) -> bytes:
    return _named(TAG_STRING, name) + _name(value)


def _int_list(name: str, values: tuple[int, ...]) -> bytes:
    body = b"".join(struct.pack(">i", value) for value in values)
    return _named(TAG_LIST, name) + bytes([TAG_INT]) + struct.pack(">i", len(values)) + body


def _compound_list(name: str, compounds: list[bytes]) -> bytes:
    element = TAG_COMPOUND if compounds else TAG_END
    return _named(TAG_LIST, name) + bytes([element]) + struct.pack(">i", len(compounds)) + b"".join(compounds)


def template_nbt(size: tuple[int, int, int]) -> bytes:
    """The uncompressed NBT of a template of this size whose lowest layer is the floor block."""
    size_x, _, size_z = size
    palette = [_string("Name", FLOOR_BLOCK) + bytes([TAG_END])]
    blocks = [
        _int_list("pos", (x, 0, z)) + _int("state", 0) + bytes([TAG_END])
        for x in range(size_x)
        for z in range(size_z)
    ]
    root = (
        _int_list("size", size)
        + _compound_list("palette", palette)
        + _compound_list("blocks", blocks)
        + _compound_list("entities", [])
        + _int("DataVersion", DATA_VERSION)
    )
    return _named(TAG_COMPOUND, "") + root + bytes([TAG_END])


def gzip_member(raw: bytes) -> bytes:
    """Wraps bytes in one gzip member without a name or a time stamp."""
    deflater = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    body = deflater.compress(raw) + deflater.flush()
    header = b"\x1f\x8b\x08\x00" + struct.pack("<I", 0) + b"\x02\xff"
    trailer = struct.pack("<II", zlib.crc32(raw) & 0xFFFFFFFF, len(raw) & 0xFFFFFFFF)
    return header + body + trailer


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="directory the templates are written to")
    parser.add_argument("--check", action="store_true", help="compare the files on disk instead of writing them")
    arguments = parser.parse_args()

    stale = []
    for name, size in sorted(TEMPLATES.items()):
        raw = template_nbt(size)
        path = arguments.out / f"{name}.nbt"
        if arguments.check:
            on_disk = zlib.decompress(path.read_bytes(), zlib.MAX_WBITS | 16) if path.is_file() else None
            if on_disk != raw:
                stale.append(str(path))
            continue
        arguments.out.mkdir(parents=True, exist_ok=True)
        path.write_bytes(gzip_member(raw))
        print(f"wrote {path} ({size[0]} x {size[1]} x {size[2]})")
    if stale:
        print("out of date: " + ", ".join(stale), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
