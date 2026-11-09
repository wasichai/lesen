#!/usr/bin/env python3
"""Genera el audio de demostración y sus tiempos por palabra, sin red y de forma determinista (ADR-0008).

Sintetiza cada oración con libespeak-ng (voces propias de eSpeak NG) por ctypes, con una marca SSML antes de cada
palabra: el motor informa el milisegundo en que llega a cada marca, así que el inicio de cada palabra no se estima, lo
dice quien la pronuncia. El fin de una palabra es el inicio de la siguiente (o el fin de su oración). Entre oraciones y
párrafos se añade un silencio fijo. Escribe src/main/resources/demo/audio/<slug>.mp3 y <slug>.timings.json.

Requisitos: apt install espeak-ng libespeak-ng1 ffmpeg.  Uso: python3 tools/generate_audio.py [slug…]
"""
import ctypes
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lesen_text import reading_sentences  # noqa: E402

DEMO = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "demo"
VOICES = {"de": ("de", "eSpeak NG Deutsch"), "en": ("en-us", "eSpeak NG English (US)")}
# palabras por minuto por nivel: más despacio al principio (el valor por defecto de eSpeak es 175)
RATE = {"A1": 125, "A2": 135, "B1": 145, "B2": 155, "C1": 165, "C2": 170}
LEAD_MS, SENTENCE_GAP_MS, PARAGRAPH_GAP_MS = 250, 350, 750
LICENSE = "eSpeak NG (GPL-3.0-or-later); el audio generado no tiene restricciones de uso"

SYNCHRONOUS, SSML, UTF8 = 2, 0x10, 1
EVENT_END, EVENT_MARK, EVENT_MSG_TERMINATED = 0, 3, 6
RATE_PARAMETER = 1


class Event(ctypes.Structure):
    _fields_ = [("type", ctypes.c_int), ("unique_identifier", ctypes.c_uint), ("text_position", ctypes.c_int),
                ("length", ctypes.c_int), ("audio_position", ctypes.c_int), ("sample", ctypes.c_int),
                ("user_data", ctypes.c_void_p), ("id", ctypes.c_void_p)]


class Speaker:
    def __init__(self) -> None:
        self.lib = ctypes.CDLL("libespeak-ng.so.1")
        self.sample_rate = self.lib.espeak_Initialize(SYNCHRONOUS, 0, None, 0)
        if self.sample_rate <= 0:
            raise RuntimeError("no se pudo iniciar eSpeak NG")
        self.samples = bytearray()
        self.marks: list[tuple[str, int]] = []
        callback_type = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.POINTER(ctypes.c_short), ctypes.c_int, ctypes.POINTER(Event))
        self.callback = callback_type(self._on_audio)
        self.lib.espeak_SetSynthCallback(self.callback)

    def _on_audio(self, wav, count, events) -> int:
        if wav and count > 0:
            self.samples.extend(ctypes.string_at(wav, count * 2))
        i = 0
        while events[i].type != EVENT_END:
            event = events[i]
            if event.type == EVENT_MARK:
                self.marks.append((ctypes.c_char_p(event.id).value.decode(), event.audio_position))
            i += 1
        return 0

    def say(self, voice: str, rate: int, ssml: str) -> tuple[bytes, list[tuple[str, int]]]:
        self.samples, self.marks = bytearray(), []
        self.lib.espeak_SetVoiceByName(voice.encode())
        self.lib.espeak_SetParameter(RATE_PARAMETER, rate, 0)
        data = ssml.encode("utf-8")
        if self.lib.espeak_Synth(data, len(data) + 1, 0, 0, 0, SSML | UTF8, None, None) != 0:
            raise RuntimeError("eSpeak NG rechazó la oración")
        self.lib.espeak_Synchronize()
        return bytes(self.samples), list(self.marks)


FRAME_MS, VOICED_RMS = 10, 250


def voiced_bounds(samples: bytes, start_ms: int, end_ms: int, rate: int) -> tuple[int, int]:
    """La parte con voz de [start_ms, end_ms) de una oración: la marca de una palabra tras una coma llega al empezar la
    pausa, no la voz. Se recortan los silencios de los bordes en tramos de 10 ms; sin voz, el tramo tal cual"""
    pcm = memoryview(samples).cast("h")
    frame = rate * FRAME_MS // 1000
    voiced = []
    for at in range(start_ms * rate // 1000, end_ms * rate // 1000, frame):
        chunk = pcm[at : min(at + frame, end_ms * rate // 1000, len(pcm))]
        if len(chunk) and (sum(x * x for x in chunk) / len(chunk)) ** 0.5 > VOICED_RMS:
            voiced.append(at)
    if not voiced:
        return start_ms, end_ms
    return voiced[0] * 1000 // rate, min(end_ms, (voiced[-1] + frame) * 1000 // rate)


def silence(ms: int, rate: int) -> bytes:
    return b"\x00\x00" * (rate * ms // 1000)


def sentence_ssml(tokens: list[dict]) -> str:
    parts = []
    for token in tokens:
        if token["kind"] == "WORD":
            parts.append(f'<mark name="{token["id"]}"/>')
        parts.append(escape(token["text"]) + (" " if token["space"] else ""))
    return "<speak>" + "".join(parts).strip() + '<mark name="end"/></speak>'


def generate(speaker: Speaker, path: Path) -> dict:
    reading = json.loads(path.read_text())
    voice, voice_name = VOICES[reading["language"]]
    rate = RATE[reading["level"]]
    sr = speaker.sample_rate
    audio = bytearray(silence(LEAD_MS, sr))
    timings: dict[str, list[int]] = {}
    previous_paragraph = None
    for paragraph_id, _sentence_id, tokens in reading_sentences(reading):
        if previous_paragraph is not None:
            audio.extend(silence(PARAGRAPH_GAP_MS if paragraph_id != previous_paragraph else SENTENCE_GAP_MS, sr))
        previous_paragraph = paragraph_id
        offset = len(audio) // 2 * 1000 // sr
        samples, marks = speaker.say(voice, rate, sentence_ssml(tokens))
        positions = dict(marks)
        words = [t["id"] for t in tokens if t["kind"] == "WORD"]
        missing = [w for w in words if w not in positions]
        if missing or "end" not in positions:
            raise RuntimeError(f"{path.name}: el motor no informó las marcas {missing or ['end']}")
        bounds = [positions[w] for w in words] + [positions["end"]]
        spans = [voiced_bounds(samples, bounds[i], max(bounds[i + 1], bounds[i] + 1), sr) for i in range(len(words))]
        # una pasada en orden con un cursor: cada palabra empieza cuando terminó la anterior o después y termina antes de que
        # empiece la siguiente. Una palabra breve que el motor funde con la siguiente («a», «of») queda de 1 ms y no pisa nada
        cursor = 0
        for i, word in enumerate(words):
            start = max(spans[i][0], cursor)
            end = max(spans[i][1], start + 1)
            if i + 1 < len(spans):
                end = min(end, max(spans[i + 1][0], start + 1))
            timings[word] = [offset + start, offset + end]
            cursor = end
        audio.extend(samples)
    audio.extend(silence(LEAD_MS, sr))
    duration_ms = len(audio) // 2 * 1000 // sr
    slug = reading["slug"]
    out = DEMO / "audio"
    out.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / f"{slug}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(bytes(audio))
        # MP3 mono: lo reproducen todos los navegadores. -write_xing con el retardo del codificador: el navegador lo
        # descuenta y los tiempos siguen casando con el WAV
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-ac", "1", "-codec:a", "libmp3lame", "-b:a", "48k",
                        "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", str(out / f"{slug}.mp3")], check=True)
    meta = {
        "slug": slug,
        "file": f"{slug}.mp3",
        "format": "audio/mpeg",
        "durationMs": duration_ms,
        "voice": {"id": f"espeak-ng:{voice}", "provider": "espeak-ng", "language": reading["language"], "name": voice_name, "license": LICENSE},
        "rate": rate,
        "timings": timings,
    }
    (out / f"{slug}.timings.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n")
    return meta


def main() -> int:
    slugs = sys.argv[1:] or [path.stem for path in sorted((DEMO / "readings").glob("*.json"))]
    if len(slugs) > 1:
        # eSpeak NG guarda estado entre oraciones de distintas voces: cada lectura en su propio proceso, para que el
        # resultado no dependa del orden ni de qué otras se generaron antes (determinista byte a byte)
        for slug in slugs:
            subprocess.run([sys.executable, __file__, slug], check=True)
        return 0
    meta = generate(Speaker(), DEMO / "readings" / f"{slugs[0]}.json")
    print(f"{meta['slug']}: {meta['durationMs'] / 1000:.1f} s, {len(meta['timings'])} palabras, {meta['rate']} ppm")
    return 0


if __name__ == "__main__":
    sys.exit(main())
