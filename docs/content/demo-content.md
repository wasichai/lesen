# Contenido de demostración

El contenido de `src/main/resources/demo/` es original, escrito para Lesen, y se publica al arrancar con
`lesen.demo.enabled=true` a través del mismo `ReadingPublisher` que usará el estudio editorial (fase 6): se valida, se
tokeniza y se congela una versión inmutable. Nada de esto está en el código: son datos.

`python3 tools/check_demo.py` valida la forma de los archivos y que cada palabra de cada lectura tenga entrada en el
léxico de su idioma (la misma regla de palabra que `lesen.reading.Tokenizer`). Corre en CI.

## Archivos

| Archivo                | Contenido                                                                                  |
| ---------------------- | ------------------------------------------------------------------------------------------ |
| `topics.json`          | Árbol de temas: `[{slug, labels: {es, en}, children: [{slug, labels}]}]`                   |
| `collections.json`     | `[{slug, labels: {es, en}, descriptions: {es, en}, readings: [slug…]}]`                    |
| `readings/<slug>.json` | Una lectura (forma abajo)                                                                  |
| `lexicon/<idioma>.jsonl` | Diccionario curado del idioma de estudio, una entrada JSON por línea (forma abajo)       |
| `covers/<archivo>.svg` | Portada original, 600×800, sin texto incrustado                                            |

## Lectura

```json
{
  "slug": "der-alte-leuchtturm",
  "language": "de",
  "level": "A2",
  "topics": ["travel/coasts", "nature"],
  "featured": true,
  "cover": "der-alte-leuchtturm.svg",
  "accent": "#2F5D62",
  "title": "Der alte Leuchtturm",
  "subtitle": "Eine Nacht an der Nordsee",
  "summary": "Resumen corto en el idioma de la lectura.",
  "paragraphs": [
    {
      "translations": { "es": "…", "en": "…" },
      "sentences": [
        { "text": "Mia wohnt am Meer.", "translations": { "es": "Mia vive junto al mar.", "en": "Mia lives by the sea." } },
        { "text": "Sie sitzt auf der Bank.", "translations": { "es": "…", "en": "…" }, "senses": { "Bank": "de:bank#seat" } }
      ]
    }
  ]
}
```

- Las oraciones las fija la autora: no se segmenta texto automáticamente.
- Las traducciones son contextuales y por unidad (oración y párrafo), escritas para cada idioma de interfaz distinto del
  idioma de la lectura. Nunca se arman palabra por palabra.
- `senses` elige la entrada del léxico para una forma ambigua en esa oración (por defecto, la primera entrada que la
  declara).
- `summary`, `title` y `subtitle` van en el idioma de la lectura.

## Entrada del léxico

```json
{
  "key": "de:leuchtturm",
  "lemma": "Leuchtturm",
  "pos": "noun",
  "ipa": "ˈlɔɪ̯çtˌtʊʁm",
  "grammar": { "gender": "m", "plural": "Leuchttürme" },
  "forms": ["Leuchtturms", "Leuchttürme", "Leuchttürmen"],
  "senses": [
    {
      "translations": { "es": ["faro"], "en": ["lighthouse"] },
      "definition": { "es": "Torre con una luz que guía a los barcos.", "en": "A tower with a light that guides ships." },
      "examples": [{ "text": "Der Leuchtturm steht auf dem Felsen.", "translations": { "es": "…", "en": "…" } }],
      "synonyms": [],
      "antonyms": [],
      "related": ["de:licht"],
      "note": { "es": "…", "en": "…" }
    }
  ]
}
```

- `key`: `<idioma>:<lema en minúsculas>`, con sufijo `#algo` para homógrafos (`de:bank#seat`, `de:bank#money`).
- `pos`: noun, verb, adjective, adverb, pronoun, article, preposition, conjunction, numeral, interjection, particle,
  determiner, proper-noun, phrase, contraction.
- `grammar` es libre por idioma: el modelo no supone que todos los idiomas tengan género, casos o aspecto.
- `forms` incluye cada forma flexionada que aparece en las lecturas (la búsqueda ignora mayúsculas).
- `translations` es obligatoria para cada idioma de interfaz distinto del idioma de la entrada; lo demás es opcional y
  solo se escribe cuando aporta.
