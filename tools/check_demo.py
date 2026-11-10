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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lesen_text import reading_sentences  # noqa: E402

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
        check_pages(name, r, problems)
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
    # el audio de cada lectura (tools/generate_audio.py, ADR-0008): un tiempo por palabra, en orden, sin solaparse, dentro
    # del audio, y nunca en un signo. Los ids son los del tokenizador (tools/lesen_text.py, igual que el del backend)
    for file in sorted((ROOT / "readings").glob("*.json")):
        r = json.loads(file.read_text())
        meta_path = ROOT / "audio" / f"{r['slug']}.timings.json"
        if not meta_path.exists():
            problems.append(f"{r['slug']}: sin audio")
            continue
        meta = json.loads(meta_path.read_text())
        if not (ROOT / "audio" / meta["file"]).exists():
            problems.append(f"{r['slug']}: falta {meta['file']}")
        if meta["voice"]["language"] != r["language"]:
            problems.append(f"{r['slug']}: la voz no habla {r['language']}")
        timings = meta["timings"]
        words = [t["id"] for _, _, tokens in reading_sentences(r) for t in tokens if t["kind"] == "WORD"]
        if set(timings) != set(words):
            problems.append(f"{r['slug']}: tiempos para {len(timings)} tokens, la lectura tiene {len(words)} palabras")
        previous_end = 0
        for word in words:
            start, end = timings.get(word, (previous_end, previous_end + 1))
            if start < previous_end or end <= start or end > meta["durationMs"]:
                problems.append(f"{r['slug']}: tiempo inválido en {word} ({start}–{end})")
                break
            previous_end = end

    check_assessments(problems)

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


# el catálogo de maquetas de lesen.reading.PageLayout: los anchos que admite, si lleva ilustración y si va sin texto
LAYOUTS = {
    "IMAGE_TOP": ({1}, True, False),
    "IMAGE_BOTTOM": ({1}, True, False),
    "TEXT_OVER_IMAGE": ({1, 2}, True, False),
    "VIGNETTE": ({1}, True, False),
    "TEXT_ONLY": ({1}, False, False),
    "IMAGE_ONLY": ({1, 2}, True, True),
    "SPREAD_IMAGE_LEFT": ({2}, True, False),
    "SPREAD_IMAGE_RIGHT": ({2}, True, False),
    "PANORAMA": ({2}, True, False),
}


def check_pages(name: str, r: dict, problems: list[str]) -> None:
    """Un libro ilustrado (ADR-0014): sus páginas reparten todos los párrafos, cada maqueta cabe en su ancho y cada
    ilustración es un SVG de demo/illustrations con su texto alternativo en cada idioma de interfaz."""
    fmt = r.get("format", "TEXT")
    if fmt == "TEXT":
        if r.get("pages") or r.get("layout"):
            problems.append(f"{name}: un texto no lleva páginas ni maqueta")
        return
    if fmt != "PICTURE_BOOK":
        problems.append(f"{name}: formato desconocido {fmt}")
        return
    default = r.get("layout")
    if default not in LAYOUTS:
        problems.append(f"{name}: maqueta del libro desconocida {default}")
        return
    pages = r.get("pages") or []
    if sum(p.get("paragraphs", 0) for p in pages) != len(r.get("paragraphs", [])):
        problems.append(f"{name}: las páginas no reparten todos los párrafos")
    illustrations = {p.name for p in (ROOT / "illustrations").glob("*.svg")}
    for gi, page in enumerate(pages, 1):
        layout = page.get("layout") or default
        if layout not in LAYOUTS:
            problems.append(f"{name}: página {gi} con maqueta desconocida {layout}")
            continue
        spans, illustrated, textless = LAYOUTS[layout]
        if page.get("span", 1) not in spans:
            problems.append(f"{name}: página {gi}: {layout} no cabe en {page.get('span', 1)} página(s)")
        if textless != (page.get("paragraphs", 0) == 0):
            problems.append(f"{name}: página {gi}: {layout} {'va sin' if textless else 'necesita'} texto")
        if illustrated:
            ill = page.get("illustration") or {}
            if ill.get("file") not in illustrations:
                problems.append(f"{name}: página {gi} sin ilustración en demo/illustrations ({ill.get('file')})")
            for lang in UI_LANGUAGES:
                if not (ill.get("alt") or {}).get(lang, "").strip():
                    problems.append(f"{name}: página {gi} sin texto alternativo {lang}")


CHOICE = {"MULTIPLE_CHOICE", "MAIN_IDEA", "INFERENCE", "DETAIL", "VOCABULARY_IN_CONTEXT"}
TYPES = CHOICE | {"TRUE_FALSE", "COMPLETION", "MATCHING", "ORDERING", "SHORT_ANSWER"}


def localized(value) -> bool:
    """Un texto de pregunta: en el idioma de la lectura (cadena) o en cada idioma de interfaz ({es, en})."""
    if isinstance(value, str):
        return bool(value.strip())
    return isinstance(value, dict) and all(isinstance(value.get(lang), str) and value[lang].strip() for lang in UI_LANGUAGES)


def items_ok(items, minimum: int) -> bool:
    ids = [i.get("id") for i in items] if isinstance(items, list) else []
    return len(ids) >= minimum and len(set(ids)) == len(ids) and all(isinstance(i, str) and i for i in ids) and all(localized(i.get("text")) for i in items)


def check_assessments(problems: list[str]) -> None:
    """Las preguntas de comprensión (docs/content/demo-content.md §Preguntas): forma de cada tipo, referencias a oraciones
    y palabras que existen en la lectura, de 5 a 7 preguntas con al menos 3 tipos, y los 10 tipos en el conjunto."""
    seen_types: set[str] = set()
    for file in sorted((ROOT / "readings").glob("*.json")):
        r = json.loads(file.read_text())
        path = ROOT / "assessments" / f"{r['slug']}.json"
        if not path.exists():
            problems.append(f"{r['slug']}: sin preguntas")
            continue
        a = json.loads(path.read_text())
        where = f"preguntas {r['slug']}"
        if a.get("reading") != r["slug"]:
            problems.append(f"{where}: reading no es {r['slug']}")
        sentences = {sid: tokens for _, sid, tokens in reading_sentences(r)}
        words = {t["id"]: sid for sid, tokens in sentences.items() for t in tokens if t["kind"] == "WORD"}
        questions = a.get("questions") or []
        if not 5 <= len(questions) <= 7:
            problems.append(f"{where}: {len(questions)} preguntas (de 5 a 7)")
        types = {q.get("type") for q in questions}
        if len(types) < 3:
            problems.append(f"{where}: solo {len(types)} tipos (al menos 3)")
        ids = [q.get("id") for q in questions]
        if len(set(ids)) != len(ids) or not all(isinstance(i, str) and re.fullmatch(r"q\d{1,2}", i) for i in ids):
            problems.append(f"{where}: ids de pregunta repetidos o inválidos (q1, q2…)")
        for q in questions:
            at = f"{where} {q.get('id')}"
            kind = q.get("type")
            seen_types.add(kind)
            spec = q.get("spec") or {}
            if kind not in TYPES:
                problems.append(f"{at}: tipo desconocido {kind}")
                continue
            if q.get("difficulty") not in (1, 2, 3):
                problems.append(f"{at}: difficulty de 1 a 3")
            for field in ("prompt", "explanation"):
                value = q.get(field)
                if not (isinstance(value, dict) and localized(value)):
                    problems.append(f"{at}: {field} necesita es y en")
            refs = q.get("refs") or []
            if not refs or any(ref not in sentences for ref in refs):
                problems.append(f"{at}: refs debe nombrar oraciones de la lectura ({refs})")
            if kind in CHOICE:
                options = spec.get("options")
                correct = spec.get("correct") or []
                if not items_ok(options, 3) or len(options) > 5:
                    problems.append(f"{at}: de 3 a 5 opciones con id y texto")
                elif not correct or not set(correct) <= {o["id"] for o in options}:
                    problems.append(f"{at}: correct debe nombrar opciones")
                if kind == "VOCABULARY_IN_CONTEXT" and words.get(spec.get("token")) not in refs:
                    problems.append(f"{at}: token debe ser una palabra de una oración de refs")
            elif kind == "TRUE_FALSE":
                if not isinstance(spec.get("correct"), bool):
                    problems.append(f"{at}: correct debe ser true o false")
            elif kind == "COMPLETION":
                sentence = spec.get("sentence")
                blanks = spec.get("blanks") or []
                if sentence not in refs:
                    problems.append(f"{at}: sentence debe estar en refs")
                if not 1 <= len(blanks) <= 3 or any(words.get(b) != sentence for b in blanks):
                    problems.append(f"{at}: de 1 a 3 huecos, palabras de esa oración")
                for token, variants in (spec.get("accepted") or {}).items():
                    if token not in blanks or not all(isinstance(v, str) and v.strip() for v in variants):
                        problems.append(f"{at}: accepted solo para huecos, con cadenas")
                if "bank" in spec and not all(localized(b) and isinstance(b, str) for b in spec["bank"]):
                    problems.append(f"{at}: bank es una lista de palabras")
            elif kind == "MATCHING":
                left, right, pairs = spec.get("left"), spec.get("right"), spec.get("pairs") or {}
                if not items_ok(left, 3) or not items_ok(right, 3) or len(right) < len(left):
                    problems.append(f"{at}: left y right con al menos 3 elementos, right no más corto")
                elif set(pairs) != {i["id"] for i in left} or not set(pairs.values()) <= {i["id"] for i in right} or len(set(pairs.values())) != len(pairs):
                    problems.append(f"{at}: pairs une cada elemento de left con uno distinto de right")
            elif kind == "ORDERING":
                items, order = spec.get("items"), spec.get("order") or []
                if not items_ok(items, 3) or len(items) > 6:
                    problems.append(f"{at}: de 3 a 6 elementos")
                elif sorted(order) != sorted(i["id"] for i in items) or order == [i["id"] for i in items]:
                    problems.append(f"{at}: order es una permutación de los elementos, distinta de como se presentan")
            elif kind == "SHORT_ANSWER":
                for field in ("model", "rubric"):
                    value = spec.get(field)
                    if not (isinstance(value, dict) and localized(value)):
                        problems.append(f"{at}: {field} necesita es y en")
    for kind in sorted(TYPES - seen_types):
        problems.append(f"preguntas: ningún ejemplo de {kind}")


if __name__ == "__main__":
    sys.exit(main())
