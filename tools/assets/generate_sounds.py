#!/usr/bin/env python3
"""Generates the weather mod's five ambience loops and the sounds.json that registers them.

Every loop is synthesised from fixed-seed noise, filters and simple decaying sines, so running the script twice
produces byte-identical files and nothing is recorded, sampled or copied from any game or mod. Each loop is
rendered for 11 seconds, the last second is crossfaded into the first so the 10-second result loops seamlessly,
the peak is normalised to -6 dBFS, and ffmpeg encodes it as mono 44100 Hz OGG Vorbis at quality 4. The outputs
are written straight into ``src/main/resources/assets/weathermod``; run ``python3 tools/assets/generate_sounds.py``
from the repository root (or pass ``--out`` to write elsewhere).

Outputs:

* ``sounds/ambient/rain_light.ogg``: band-passed hiss with slow modulation and sparse droplet clicks.
* ``sounds/ambient/rain_heavy.ogg``: wider, denser hiss with a louder low end and many clicks.
* ``sounds/ambient/rain_roof.ogg``: the light rain muffled below 1.2 kHz, with drips tapping on the roof.
* ``sounds/ambient/snow.ogg``: a quiet high hiss with a slow breathing swell.
* ``sounds/ambient/wind.ogg``: low brown noise in gusting swells with a faint whistle that follows them.
* ``sounds.json``.

Requires Python 3 and numpy, plus ``ffmpeg`` (with libvorbis) on the PATH; ``ffprobe`` is used to check the
result when it is available.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPOSITORY_ROOT / "src" / "main" / "resources" / "assets" / "weathermod"

MASTER_SEED = 20240918
SAMPLE_RATE = 44100
LOOP_SECONDS = 10.0
RENDER_SECONDS = 11.0
CROSSFADE_SECONDS = 1.0
PEAK_DBFS = -6.0
VORBIS_QUALITY = "4"
DURATION_TOLERANCE_SECONDS = 0.001

LOOP_NAMES = ("rain_light", "rain_heavy", "rain_roof", "snow", "wind")


def seeded(*stream: int) -> np.random.Generator:
    """Returns a generator for one loop; every loop has its own stream so edits to one never move another."""
    return np.random.default_rng([MASTER_SEED, *stream])


def render_samples() -> int:
    return int(round(RENDER_SECONDS * SAMPLE_RATE))


def time_axis(count: int) -> np.ndarray:
    return np.arange(count) / SAMPLE_RATE


def rms(signal: np.ndarray) -> float:
    return float(math.sqrt(float(np.mean(signal * signal))))


def normalise_rms(signal: np.ndarray, target: float = 1.0) -> np.ndarray:
    level = rms(signal)
    if level == 0.0:
        raise ValueError("cannot normalise a silent signal")
    return signal * (target / level)


def normalise_peak(signal: np.ndarray, dbfs: float) -> np.ndarray:
    peak = float(np.max(np.abs(signal)))
    if peak == 0.0:
        raise ValueError("cannot normalise a silent signal")
    return signal * (10.0 ** (dbfs / 20.0) / peak)


def butterworth(freqs: np.ndarray, cutoff: float, order: int, highpass: bool = False) -> np.ndarray:
    """Magnitude response of a Butterworth low-pass (or high-pass) filter, used as a mask on an FFT."""
    with np.errstate(divide="ignore"):
        ratio = (cutoff / freqs) if highpass else (freqs / cutoff)
    return 1.0 / np.sqrt(1.0 + ratio ** (2 * order))


def band_pass(signal: np.ndarray, low: float | None = None, high: float | None = None,
              low_order: int = 4, high_order: int = 4) -> np.ndarray:
    """Keeps the band between ``low`` and ``high`` Hz (either may be omitted) with Butterworth-shaped skirts."""
    spectrum = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(signal.size, 1.0 / SAMPLE_RATE)
    if low is not None:
        spectrum *= butterworth(freqs, low, low_order, highpass=True)
    if high is not None:
        spectrum *= butterworth(freqs, high, high_order)
    return np.fft.irfft(spectrum, n=signal.size)


def spectral_tilt(signal: np.ndarray, db_per_octave: float, pivot_hz: float) -> np.ndarray:
    """Tilts the spectrum by ``db_per_octave`` around ``pivot_hz`` (negative values favour the low end)."""
    spectrum = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(signal.size, 1.0 / SAMPLE_RATE)
    octaves = np.log2(np.maximum(freqs, 1.0) / pivot_hz)
    spectrum *= 10.0 ** (db_per_octave * octaves / 20.0)
    return np.fft.irfft(spectrum, n=signal.size)


def white(rng: np.random.Generator, count: int) -> np.ndarray:
    return rng.standard_normal(count)


def brown(rng: np.random.Generator, count: int) -> np.ndarray:
    """1/f-amplitude (brown) noise at unit RMS, shaped in the frequency domain so it has no drift."""
    spectrum = np.fft.rfft(white(rng, count))
    freqs = np.fft.rfftfreq(count, 1.0 / SAMPLE_RATE)
    spectrum *= 1.0 / np.maximum(freqs, 20.0)
    spectrum[0] = 0.0
    return normalise_rms(np.fft.irfft(spectrum, n=count))


def lfo(t: np.ndarray, hz: float, phase: float) -> np.ndarray:
    return np.sin(2.0 * np.pi * hz * t + phase)


def decaying_sine(duration: float, frequency: float, attack: float) -> np.ndarray:
    """A sine burst that reaches about -52 dB at ``duration`` seconds, with a short linear attack."""
    count = max(1, int(round(duration * SAMPLE_RATE)))
    tau = time_axis(count)
    envelope = np.exp(-tau * 6.0 / duration)
    if attack > 0.0:
        envelope *= np.minimum(1.0, tau / attack)
    return np.sin(2.0 * np.pi * frequency * tau) * envelope


def scatter(rng: np.random.Generator, target: np.ndarray, rate_per_second: float, make_event) -> None:
    """Adds ``rate_per_second`` events per second at uniformly random times, clipping any that run off the end."""
    count = int(round(rate_per_second * target.size / SAMPLE_RATE))
    for start in rng.integers(0, target.size, size=count):
        event = make_event()
        end = min(target.size, int(start) + event.size)
        target[start:end] += event[: end - int(start)]


def resonate(source: np.ndarray, centre_hz: np.ndarray, radius: float) -> np.ndarray:
    """A two-pole resonator whose centre frequency moves per sample: a narrow band that follows a control curve."""
    coefficient1 = (2.0 * radius * np.cos(2.0 * np.pi * centre_hz / SAMPLE_RATE)).tolist()
    coefficient2 = radius * radius
    values = source.tolist()
    output = [0.0] * len(values)
    previous = 0.0
    before = 0.0
    for index, value in enumerate(values):
        current = value + coefficient1[index] * previous - coefficient2 * before
        output[index] = current
        before = previous
        previous = current
    return np.asarray(output)


def droplet_clicks(rng: np.random.Generator, count: int, rate: float, level: float) -> np.ndarray:
    """Sparse 2-4 ms sine bursts at 2-5 kHz: individual drops hitting hard ground."""
    clicks = np.zeros(count)

    def click() -> np.ndarray:
        duration = float(rng.uniform(0.002, 0.004))
        frequency = float(rng.uniform(2000.0, 5000.0))
        return decaying_sine(duration, frequency, attack=0.0003) * float(rng.uniform(0.5, 1.0)) * level

    scatter(rng, clicks, rate, click)
    return clicks


def light_rain(rng: np.random.Generator, count: int) -> np.ndarray:
    """Band-passed hiss 1.5-7 kHz, modulated by +-20 % at 0.2-0.5 Hz, with 6-10 droplet clicks per second."""
    t = time_axis(count)
    hiss = normalise_rms(band_pass(white(rng, count), 1500.0, 7000.0))
    rates = rng.uniform(0.2, 0.5, size=2)
    phases = rng.uniform(0.0, 2.0 * np.pi, size=2)
    modulation = 1.0 + 0.2 * (0.65 * lfo(t, rates[0], phases[0]) + 0.35 * lfo(t, rates[1], phases[1]))
    clicks = droplet_clicks(rng, count, float(rng.uniform(6.0, 10.0)), 3.5)
    return hiss * modulation + clicks


def heavy_rain(rng: np.random.Generator, count: int) -> np.ndarray:
    """Denser hiss 300 Hz-9 kHz tilted toward the low end, +-10 % modulation and 25-40 clicks per second."""
    t = time_axis(count)
    wide = normalise_rms(spectral_tilt(band_pass(white(rng, count), 300.0, 9000.0), -2.0, 1000.0))
    low = normalise_rms(band_pass(white(rng, count), 300.0, 1200.0, low_order=2, high_order=2))
    hiss = normalise_rms(wide + 0.6 * low)
    rates = rng.uniform(0.2, 0.5, size=2)
    phases = rng.uniform(0.0, 2.0 * np.pi, size=2)
    modulation = 1.0 + 0.1 * (0.65 * lfo(t, rates[0], phases[0]) + 0.35 * lfo(t, rates[1], phases[1]))
    clicks = droplet_clicks(rng, count, float(rng.uniform(25.0, 40.0)), 2.5)
    return hiss * modulation + clicks


def roof_rain(rng: np.random.Generator, count: int) -> np.ndarray:
    """The light rain heard through a roof: low-passed at 1.2 kHz, with 2-4 drip taps per second on top."""
    muffled = normalise_rms(band_pass(light_rain(rng, count), high=1200.0))
    drips = np.zeros(count)

    def drip() -> np.ndarray:
        duration = float(rng.uniform(0.08, 0.12))
        frequency = float(rng.uniform(400.0, 900.0))
        return decaying_sine(duration, frequency, attack=0.002) * float(rng.uniform(0.6, 1.0)) * 3.0

    scatter(rng, drips, float(rng.uniform(2.0, 4.0)), drip)
    return muffled + drips


def snow(rng: np.random.Generator, count: int) -> np.ndarray:
    """A soft hiss 2-6 kHz that breathes by +-40 % at 0.1-0.2 Hz."""
    t = time_axis(count)
    hiss = normalise_rms(band_pass(white(rng, count), 2000.0, 6000.0))
    rates = rng.uniform(0.1, 0.2, size=2)
    phases = rng.uniform(0.0, 2.0 * np.pi, size=2)
    modulation = 1.0 + 0.4 * (0.7 * lfo(t, rates[0], phases[0]) + 0.3 * lfo(t, rates[1], phases[1]))
    return hiss * modulation


def wind(rng: np.random.Generator, count: int) -> np.ndarray:
    """Brown noise 80-900 Hz in swells between 0.4 and 1.0 driven by LFOs at 0.08 and 0.21 Hz, plus a faint whistle."""
    t = time_axis(count)
    body = normalise_rms(band_pass(brown(rng, count), 80.0, 900.0, low_order=2, high_order=4))
    phases = rng.uniform(0.0, 2.0 * np.pi, size=2)
    shape = 0.5 + 0.5 * (0.6 * lfo(t, 0.08, phases[0]) + 0.4 * lfo(t, 0.21, phases[1]))
    swell = 0.4 + 0.6 * shape ** 1.5
    whistle = resonate(white(rng, count), 600.0 + 500.0 * shape, 0.996)
    whistle = normalise_rms(whistle) * 0.12 * shape ** 2
    return body * swell + whistle


RECIPES = {
    "rain_light": light_rain,
    "rain_heavy": heavy_rain,
    "rain_roof": roof_rain,
    "snow": snow,
    "wind": wind,
}


def make_loop(rendered: np.ndarray) -> np.ndarray:
    """Cuts the first LOOP_SECONDS and crossfades the following second into its start so the loop is seamless."""
    loop_count = int(round(LOOP_SECONDS * SAMPLE_RATE))
    fade_count = int(round(CROSSFADE_SECONDS * SAMPLE_RATE))
    if rendered.size < loop_count + fade_count:
        raise ValueError("rendered signal is shorter than the loop plus its crossfade")
    ramp = (np.arange(fade_count) + 0.5) / fade_count
    fade_in = np.sin(ramp * np.pi / 2.0)
    fade_out = np.cos(ramp * np.pi / 2.0)
    loop = rendered[:loop_count].copy()
    loop[:fade_count] = rendered[:fade_count] * fade_in + rendered[loop_count:loop_count + fade_count] * fade_out
    return loop


def write_wav(path: Path, samples: np.ndarray) -> None:
    pcm = np.clip(np.rint(samples * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())


def encode_ogg(ffmpeg: str, wav: Path, ogg: Path) -> None:
    """Encodes mono 44100 Hz OGG Vorbis at the fixed quality, with every non-deterministic header field removed."""
    ogg.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(wav),
        "-ac", "1", "-ar", str(SAMPLE_RATE),
        "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY,
        "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1",
        str(ogg),
    ], check=True)


def probe(ffprobe: str, ogg: Path) -> dict:
    """Reads the codec, channel count, sample rate and duration back with ffprobe."""
    result = subprocess.run([
        ffprobe, "-v", "error",
        "-show_entries", "format=duration:stream=codec_name,sample_rate,channels",
        "-of", "json", str(ogg),
    ], check=True, capture_output=True, text=True)
    data = json.loads(result.stdout)
    stream = data["streams"][0]
    return {
        "codec": stream["codec_name"],
        "channels": int(stream["channels"]),
        "sample_rate": int(stream["sample_rate"]),
        "duration": float(data["format"]["duration"]),
    }


def write_sounds_json(path: Path) -> None:
    """Registers the five loops under ``ambient.<name>`` in the weather category, loaded into memory rather than streamed."""
    data = {
        f"ambient.{name}": {
            "category": "weather",
            "sounds": [{"name": f"weathermod:ambient/{name}", "stream": False}],
        }
        for name in LOOP_NAMES
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def generate(out: Path, wav_dir: Path, ffmpeg: str, ffprobe: str | None) -> list[Path]:
    """Renders, loops, encodes and checks every loop, writes sounds.json and returns the written paths."""
    written: list[Path] = []
    count = render_samples()
    for index, name in enumerate(LOOP_NAMES):
        rendered = RECIPES[name](seeded(index + 1), count)
        loop = normalise_peak(make_loop(rendered), PEAK_DBFS)
        wav = wav_dir / f"{name}.wav"
        write_wav(wav, loop)
        ogg = out / "sounds" / "ambient" / f"{name}.ogg"
        encode_ogg(ffmpeg, wav, ogg)
        written.append(ogg)
        if ffprobe is not None:
            info = probe(ffprobe, ogg)
            if abs(info["duration"] - LOOP_SECONDS) > DURATION_TOLERANCE_SECONDS:
                raise RuntimeError(f"{ogg.name} is {info['duration']:.4f} s long, expected {LOOP_SECONDS}")
            if info["codec"] != "vorbis" or info["channels"] != 1 or info["sample_rate"] != SAMPLE_RATE:
                raise RuntimeError(f"{ogg.name} is not mono {SAMPLE_RATE} Hz Vorbis: {info}")
            print(f"{ogg.relative_to(out)}  {info['codec']} {info['channels']} ch {info['sample_rate']} Hz "
                  f"{info['duration']:.3f} s  peak {20 * math.log10(float(np.max(np.abs(loop)))):.1f} dBFS "
                  f"rms {20 * math.log10(rms(loop)):.1f} dBFS")
        else:
            print(f"{ogg.relative_to(out)}  (ffprobe not found, duration unchecked)")
    sounds_json = out / "sounds.json"
    write_sounds_json(sounds_json)
    written.append(sounds_json)
    print(sounds_json.relative_to(out))
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="assets directory to write into (default: the repository's assets/weathermod)")
    parser.add_argument("--keep-wav", type=Path, default=None, metavar="DIR",
                        help="also keep the intermediate 16-bit WAV files in this directory")
    args = parser.parse_args()
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        parser.error("ffmpeg was not found on the PATH; it is needed to encode OGG Vorbis")
    ffprobe = shutil.which("ffprobe")
    out = args.out.resolve()
    if args.keep_wav is not None:
        wav_dir = args.keep_wav.resolve()
        wav_dir.mkdir(parents=True, exist_ok=True)
        generate(out, wav_dir, ffmpeg, ffprobe)
    else:
        with tempfile.TemporaryDirectory(prefix="weathermod-sounds-") as temporary:
            generate(out, Path(temporary), ffmpeg, ffprobe)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
