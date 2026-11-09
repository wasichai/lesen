#!/usr/bin/env python3
"""Valida el contenido de demostración de src/main/resources/demo (solo biblioteca estándar).

Comprueba la forma de cada archivo y que cada palabra de cada lectura tenga entrada en el léxico de su idioma, con la
misma regla de palabra que lesen.reading.Tokenizer (WORD_PATTERN). Sale con 1 y la lista de problemas si algo falla.
Uso: python3 tools/check_demo.py [--missing]   (--missing imprime solo las formas que faltan, una por línea)
"""
import json
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "demo"
# la misma expresión que lesen.reading.Tokenizer.WORD_PATTERN
WORD = re.compile(r"[^\W_]+(?:['’\-][^\W_]+)*", re.UNICODE)
LEVELS = ["A1", "A2", "B1", "B2", "C1", "C2"]
UI_LANGUAGES = ["es", "en"]
POS = {"noun", "verb", "adjective", "adverb", "pronoun", "article", "preposition", "conjunction", "numeral", "interjection",
       "particle", "determiner", "proper-noun", "phrase", "contraction"}


def norm(form: str) -> str:
    return unicodedata.normalize("NFC", form).replace("’", "'").lower()


def main() -> int:
    problems: list[str] = []
    topics = json.loads((ROOT / "topics.json").read_text())
    topic_slugs = set()
    for t in topics:
        topic_slugs.add(t["slug"])
        for c in t.get("children", []):
            topic_slugs.add(f'{t["slug"]}/{c["slug"]}')
            for lang in UI_LANGUAGES:
                if not c["labels"].get(lang):
                    problems.append(f"tema {t['slug']}/{c['slug']}: falta la etiqueta {lang}")
        for lang in UI_LANGUAGES:
            if not t["labels"].get(lang):
                problems.append(f"tema {t['slug']}: falta la etiqueta {lang}")

    lexicon: dict[str, dict[str, dict]] = {}
    all_keys: dict[str, set[str]] = {}
    for file in sorted((ROOT / "lexicon").glob("*.jsonl")):
        lang = file.stem
        forms: dict[str, dict] = {}
        keys = set()
        for n, line in enumerate(file.read_text().splitlines(), 1):
            if not line.strip():
                continue
            try:
                e = json.loads(line)
            except ValueError as error:
                problems.append(f"{file.name}:{n}: JSON inválido ({error})")
                continue
            key = e.get("key") or ""
            if not key.startswith(f"{lang}:"):
                problems.append(f"{file.name}:{n}: key debe empezar por '{lang}:'")
            if key in keys:
                problems.append(f"{file.name}:{n}: key repetida {key}")
            keys.add(key)
            if e.get("pos") not in POS:
                problems.append(f"{file.name}:{n}: pos desconocida {e.get('pos')!r}")
            if not e.get("senses"):
                problems.append(f"{file.name}:{n}: {key} sin acepciones")
            for s in e.get("senses", []):
                tr = s.get("translations", {})
                wanted = [l for l in UI_LANGUAGES if l != lang]
                for l in wanted:
                    if not tr.get(l):
                        problems.append(f"{file.name}:{n}: {key} sin traducción {l}")
            for form in [e.get("lemma", "")] + e.get("forms", []):
                forms.setdefault(norm(form), e)
        lexicon[lang] = forms
        all_keys[lang] = keys

    covers = {p.name for p in (ROOT / "covers").glob("*.svg")}
    slugs = set()
    missing: dict[str, set[str]] = {}
    for file in sorted((ROOT / "readings").glob("*.json")):
        r = json.loads(file.read_text())
        name = file.name
        slugs.add(r.get("slug"))
        if r.get("slug") + ".json" != name:
            problems.append(f"{name}: slug {r.get('slug')} no coincide con el archivo")
        lang = r.get("language")
        if lang not in lexicon:
            problems.append(f"{name}: no hay léxico para {lang}")
            continue
        if r.get("level") not in LEVELS:
            problems.append(f"{name}: nivel {r.get('level')}")
        for t in r.get("topics", []):
            if t not in topic_slugs:
                problems.append(f"{name}: tema desconocido {t}")
        if not r.get("topics"):
            problems.append(f"{name}: sin temas")
        if r.get("cover") not in covers:
            problems.append(f"{name}: falta la portada {r.get('cover')}")
        for field in ("title", "summary"):
            if not r.get(field):
                problems.append(f"{name}: falta {field}")
        targets = [l for l in UI_LANGUAGES if l != lang]
        for pi, p in enumerate(r.get("paragraphs", []), 1):
            for l in targets:
                if not p.get("translations", {}).get(l):
                    problems.append(f"{name}: párrafo {pi} sin traducción {l}")
            for si, s in enumerate(p.get("sentences", []), 1):
                for l in targets:
                    if not s.get("translations", {}).get(l):
                        problems.append(f"{name}: p{pi}s{si} sin traducción {l}")
                for override_form, key in (s.get("senses") or {}).items():
                    if key not in all_keys.get(lang, set()):
                        problems.append(f"{name}: p{pi}s{si} sentido {override_form} → {key} no existe")
                for w in WORD.findall(s.get("text", "")):
                    if norm(w) not in lexicon[lang]:
                        missing.setdefault(lang, set()).add(w)
    for c in json.loads((ROOT / "collections.json").read_text()):
        for s in c["readings"]:
            if s not in slugs:
                problems.append(f"colección {c['slug']}: lectura desconocida {s}")
    if "--missing" in sys.argv:
        for lang, words in sorted(missing.items()):
            for w in sorted(words, key=str.lower):
                print(f"{lang}\t{w}")
        return 1 if missing else 0
    for lang, words in sorted(missing.items()):
        problems.append(f"léxico {lang}: faltan {len(words)} formas: {', '.join(sorted(words, key=str.lower)[:40])}")
    for p in problems:
        print(p)
    print(f"{len(slugs)} lecturas, {sum(len(f) for f in lexicon.values())} formas, {len(problems)} problemas")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
