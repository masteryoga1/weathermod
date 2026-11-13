# Asset generators

Every texture and sound the mod ships is produced by the scripts in this directory. Nothing is drawn,
recorded, sampled, copied or traced from any game or mod: the images come from fixed-seed noise and simple
analytic shapes, the sounds from filtered noise and short decaying sines. The scripts are committed next to their
outputs, so a change to a texture or a loop is made by editing the script and rerunning it, never by editing a
PNG or OGG by hand.

All generated files are original work and are licensed under the MIT license like the rest of the repository
(see `LICENSE` at the root).

## Requirements

- Python 3.10 or newer
- `numpy` and `Pillow` (`pip install numpy Pillow`)
- `ffmpeg` with the `libvorbis` encoder on the `PATH` (sounds only); `ffprobe` is used to verify the result when
  it is present

## Running

From the repository root:

```
python3 tools/assets/generate_textures.py
python3 tools/assets/generate_sounds.py
```

Both scripts write straight into `src/main/resources/assets/weathermod/` (the location is derived from the
script's own path, so the working directory does not matter). Pass `--out DIR` to write somewhere else, and
`generate_sounds.py --keep-wav DIR` to keep the intermediate 16-bit WAV files for listening or inspection.

Each script prints what it wrote: the size and mode of every PNG, and for every OGG the codec, channel count,
sample rate and duration as read back by `ffprobe`, plus the peak and RMS of the rendered loop. The sound script
fails if a loop is not exactly 10.0 s of mono 44100 Hz Vorbis.

## What is generated

`generate_textures.py`:

| File | Size | Content |
| --- | --- | --- |
| `textures/environment/rain.png` | 64x64 | 7-9 thin vertical streaks, 1-2 px wide and 18-48 px long, pale blue-white, soft ends, tiling vertically |
| `textures/environment/snow.png` | 64x64 | 10-14 soft round flakes 2-4 px across, white, tiling vertically |
| `textures/particle/haze_0.png` .. `haze_3.png` | 32x32 | a white radial puff, alpha 0.6 at the centre falling to 0 at the edge, outline wobbled by low-frequency noise; the particle's vertex alpha carries the rest of the opacity |
| `textures/particle/wind_dust_0.png` .. `wind_dust_3.png` | 8x8 | a speckled puff of dust: a solid core thinning to scattered grains, 22-32 solid texels, alpha all or nothing, greys 255 and 205; the particle tints it against the ground |
| `textures/particle/wind_leaf_0.png`, `wind_leaf_1.png` | 8x8 | a grey leaf that fills the sprite (at least 24 solid texels), alpha all or nothing, body 240 with a darker rim (190) and midrib (140); the particle tints it with the colour of its leaf block |
| `particles/haze.json`, `wind_dust.json`, `wind_leaf.json` | | vanilla particle definitions listing the sprites above |

`generate_sounds.py` (OGG Vorbis, mono, 44100 Hz, quality 4, 10.0 s, seamless loop, peak -6 dBFS):

| File | Content |
| --- | --- |
| `sounds/ambient/rain_light.ogg` | hiss band-passed 1.5-7 kHz, modulated by +-20 % at 0.2-0.5 Hz, 6-10 droplet clicks per second |
| `sounds/ambient/rain_heavy.ogg` | denser hiss 300 Hz-9 kHz with a louder low end, +-10 % modulation, 25-40 clicks per second |
| `sounds/ambient/rain_roof.ogg` | the light rain low-passed at 1.2 kHz with 2-4 drip taps per second at 400-900 Hz |
| `sounds/ambient/snow.ogg` | a soft hiss 2-6 kHz breathing by +-40 % at 0.1-0.2 Hz |
| `sounds/ambient/wind.ogg` | brown noise 80-900 Hz in swells between 0.4 and 1.0 from LFOs at 0.08 and 0.21 Hz, plus a faint whistle at 600-1100 Hz that follows the swell |
| `sounds.json` | registers the five loops as `weathermod:ambient.<name>` in the weather category |

Each loop is rendered for 11 s; the last second is crossfaded (equal power) into the first so the 10 s file loops
without a click.

## Determinism

Every image and loop has its own random stream derived from a fixed master seed, so editing one output never
changes another, and a rerun on the same numpy, Pillow and ffmpeg builds reproduces the committed files byte for
byte (the Ogg encode runs with `-bitexact` and no metadata so the container carries no random serial number or
encoder tag). Other library builds may differ in the last bit of a few samples or in PNG compression, which is
harmless: the committed files are the ones that ship.

## Checking the result

`src/test/java/com/weathermod/client/ResourceConsistencyTest.java` reads the generated files from the classpath
and checks that every particle definition lists textures that exist, every entry in `sounds.json` has its OGG
file, the five expected sound events are present and the PNGs have the sizes the code expects. It also decodes
the particle sprites: the dust and leaf sprites, which are drawn without blending, must be all-or-nothing in
alpha and solid enough to be seen, and the haze sprites must peak at the centre alpha the haze particle assumes.
It runs with the rest of the unit tests (`./gradlew test`).

## Block textures

`generate_block_textures.py` (the textures of the tornado siren and the weather deflector; needs `numpy` and
`Pillow` only, takes no input files):

```
python3 tools/assets/generate_block_textures.py
```

Like the other scripts it writes straight into `src/main/resources/assets/weathermod/` (pass `--out DIR` to write
somewhere else) and prints the size and mode of every PNG it wrote. Every texture has its own random stream derived
from the script's master seed (20240921), so a rerun reproduces the committed files and editing one texture never
changes another. The block models are plain cubes (`minecraft:block/cube_bottom_top`) drawn without blending, so
every texel is fully opaque.

| File | Size | Content |
| --- | --- | --- |
| `textures/block/tornado_siren_side.png` | 16x16 | a grey steel plate with a lit rim and four rivets, a dark horn grille of three slats (8 x 6 texels inside its housing) and above it a lamp band of dark unlit glass, 12 x 2 texels |
| `textures/block/tornado_siren_side_active.png` | 16x16 | the same plate, texel for texel (both are painted from one random stream), with the lamp band lit red, a glint on it and a red glow on the row of steel above and below it; used while the siren sounds |
| `textures/block/tornado_siren_top.png` | 16x16 | a steel plate with the round mouth of the horn (a dark core inside a light rim) and four rivets; also the bottom of the block |
| `textures/block/weather_deflector_side.png` | 16x16 | oxidised copper (verdigris with patches of barer, browner metal from low-frequency noise) with a pale ring 4.2 to 5.6 texels from the middle, a shaded groove inside it and a pale stud in the middle |
| `textures/block/weather_deflector_top.png` | 16x16 | oxidised copper with two pale rings (2.4 to 3.6 and 5.3 to 6.6 texels from the middle) around a dark core; also the bottom of the block |

The blockstate, model, loot table, recipe and tag files of the two blocks are written by hand, not generated.
`src/test/java/com/weathermod/block/BlockResourceTest.java` reads all of them from the classpath and checks that
every blockstate names a model that exists, every model names textures that exist and are 16 x 16 RGBA, the five
textures are fully opaque, the active side of the siren is the silent one with a red lamp, the loot tables and
recipes name the right ids, and both blocks are listed in `minecraft:mineable/pickaxe` and
`minecraft:needs_stone_tool`.
