# Asset generators

Every texture and sound the mod ships is produced by the two scripts in this directory. Nothing is drawn,
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
| `textures/particle/haze_0.png` .. `haze_3.png` | 32x32 | a white radial puff, alpha 0.9 at the centre falling to 0 at the edge, outline wobbled by low-frequency noise |
| `textures/particle/wind_dust_0.png`, `wind_dust_1.png` | 8x8 | 2-3 irregular white specks with solid cores and one-pixel soft edges |
| `textures/particle/wind_leaf_0.png`, `wind_leaf_1.png` | 8x8 | a grey leaf silhouette with a darker midrib; the particle tints it with the foliage colour |
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
file, the five expected sound events are present and the PNGs have the sizes the code expects. It runs with the
rest of the unit tests (`./gradlew test`).
