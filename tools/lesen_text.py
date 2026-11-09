"""El tokenizador de Lesen en Python, igual que lesen.reading.UnicodeTokenizer (ADR-0004).

Lo usan tools/check_demo.py y tools/generate_audio.py para conocer los ids de token (p1s1t3) sin el backend.
DemoContentTest (Kotlin) comprueba que los tiempos de audio que se escriben con estos ids casan con los del backend.
"""
import re
import unicodedata

# la misma expresión que lesen.reading.Tokenizer.WORD_PATTERN
WORD = re.compile(r"[^\W_]+(?:['’\-][^\W_]+)*", re.UNICODE)


def normalize(form: str) -> str:
    return unicodedata.normalize("NFC", form).replace("’", "'").lower()


def tokenize(sentence_id: str, text: str) -> list[dict]:
    """Palabras y signos con su id; los espacios no son tokens (cada token dice si lo sigue uno)."""
    text = text.strip()
    tokens: list[dict] = []

    def add(start: int, end: int, kind: str) -> None:
        tokens.append({
            "id": f"{sentence_id}t{len(tokens) + 1}",
            "text": text[start:end],
            "kind": kind,
            "space": end < len(text) and text[end].isspace(),
        })

    def punctuation(start: int, end: int) -> None:
        for i in range(start, end):
            if not text[i].isspace():
                add(i, i + 1, "PUNCT")

    cursor = 0
    for match in WORD.finditer(text):
        punctuation(cursor, match.start())
        add(match.start(), match.end(), "WORD")
        cursor = match.end()
    punctuation(cursor, len(text))
    return tokens


def reading_sentences(reading: dict) -> list[tuple[str, str, list[dict]]]:
    """(id de párrafo, id de oración, tokens) en orden de lectura."""
    out = []
    for p, paragraph in enumerate(reading["paragraphs"], 1):
        for s, sentence in enumerate(paragraph["sentences"], 1):
            sid = f"p{p}s{s}"
            out.append((f"p{p}", sid, tokenize(sid, sentence["text"])))
    return out


if __name__ == "__main__":
    # imprime una lectura con sus ids (oraciones y palabras), para escribir preguntas o revisar tiempos:
    # python3 tools/lesen_text.py src/main/resources/demo/readings/der-alte-leuchtturm.json
    import json
    import sys

    data = json.loads(open(sys.argv[1], encoding="utf-8").read())
    for paragraph_id, sentence_id, tokens in reading_sentences(data):
        words = " ".join(f"{t['text']}[{t['id'].split('t')[-1]}]" for t in tokens if t["kind"] == "WORD")
        print(f"{sentence_id}: {words}")
