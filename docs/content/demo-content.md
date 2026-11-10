# Contenido de demostración

El contenido de `src/main/resources/demo/` es original, escrito para Lesen, y se publica al arrancar con
`lesen.demo.enabled=true` a través del mismo `ReadingPublisher` que usará el estudio editorial (fase 6): se valida, se
tokeniza y se congela una versión inmutable. Nada de esto está en el código: son datos.

`python3 tools/check_demo.py` valida la forma de los archivos y que cada palabra de cada lectura tenga entrada en el
léxico de su idioma (la misma regla de palabra que `lesen.reading.Tokenizer`). Corre en CI.

## Archivos

| Archivo                       | Contenido                                                                                  |
| ----------------------------- | ------------------------------------------------------------------------------------------ |
| `topics.json`                 | Árbol de temas: `[{slug, labels: {es, en}, children: [{slug, labels}]}]`                   |
| `collections.json`            | `[{slug, labels: {es, en}, descriptions: {es, en}, readings: [slug…]}]`                    |
| `readings/<slug>.json`        | Una lectura (forma abajo)                                                                  |
| `lexicon/<idioma>.jsonl`      | Diccionario curado del idioma de estudio, una entrada JSON por línea (forma abajo)         |
| `covers/<archivo>.svg`        | Portada original, 600×800, sin texto incrustado                                            |
| `illustrations/<archivo>.svg` | Ilustración de una página de un libro (ADR-0014), sin texto; mismas reglas que una portada |
| `audio/<slug>.mp3`            | Narración generada con eSpeak NG (`tools/generate_audio.py`, ADR-0008)                     |
| `audio/<slug>.timings.json`   | Voz, duración y `timings`: id de token → `[inicio, fin]` en milisegundos                   |
| `assessments/<slug>.json`     | Preguntas de comprensión de la lectura (forma abajo)                                       |

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

## Audio

`python3 tools/generate_audio.py [slug…]` (necesita `apt install espeak-ng libespeak-ng1 ffmpeg`) vuelve a generar la
narración y sus tiempos, idénticos byte a byte en cada ejecución. Velocidad por nivel: de 125 palabras por minuto en A1 a
165 en C1. Al cambiar el texto de una lectura, hay que regenerar su audio: `tools/check_demo.py` y `DemoContentTest`
fallan si los tiempos no casan con el texto.

## Preguntas

`assessments/<slug>.json` tiene las preguntas de comprensión de una lectura (fase 4, ADR-0009). Se publican contra la
versión vigente: los ids de oración y de palabra son los de esa versión (`python3 tools/lesen_text.py <lectura.json>`
los imprime).

```json
{
  "reading": "der-alte-leuchtturm",
  "questions": [
    {
      "id": "q1",
      "type": "DETAIL",
      "difficulty": 1,
      "prompt": { "es": "¿Dónde pasa Mia el verano?", "en": "Where does Mia spend the summer?" },
      "explanation": { "es": "La primera oración lo dice: an der Nordsee.", "en": "The first sentence says so: an der Nordsee." },
      "refs": ["p1s1"],
      "spec": {
        "options": [
          { "id": "a", "text": { "es": "En la montaña", "en": "In the mountains" } },
          { "id": "b", "text": { "es": "En el mar del Norte", "en": "By the North Sea" } },
          { "id": "c", "text": { "es": "En la ciudad", "en": "In the city" } }
        ],
        "correct": ["b"]
      }
    }
  ]
}
```

- De 5 a 7 preguntas por lectura, con al menos 3 tipos; entre todas las lecturas, los 10 tipos.
- `prompt` y `explanation` en cada idioma de interfaz. Un texto de opción o elemento es una cadena si va en el idioma
  de la lectura y `{es, en}` si va en el de la persona.
- `refs`: las oraciones donde está la respuesta. La explicación y la corrección asistida se apoyan en ellas.
- `difficulty`: 1 (literal), 2 (relacionar), 3 (inferir).

| Tipo                                                  | `spec`                                                                                                                                                                                 | Respuesta                     |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------- |
| `MULTIPLE_CHOICE`, `MAIN_IDEA`, `INFERENCE`, `DETAIL` | `options` (3–5, `{id, text}`) y `correct` (ids; más de uno pide marcar todas)                                                                                                          | `{"options": ["b"]}`          |
| `VOCABULARY_IN_CONTEXT`                               | Lo mismo y `token`, la palabra de una oración de `refs` por la que se pregunta                                                                                                         | `{"options": ["b"]}`          |
| `TRUE_FALSE`                                          | `correct`: true o false (`prompt` es la afirmación)                                                                                                                                    | `{"value": true}`             |
| `COMPLETION`                                          | `sentence` (de `refs`), `blanks` (1–3 palabras de esa oración que se ocultan), `accepted` (variantes por hueco, además de la palabra del texto) y `bank` (palabras a elegir, opcional) | `{"blanks": {"p2s3t4": "…"}}` |
| `MATCHING`                                            | `left` y `right` (`{id, text}`, al menos 3) y `pairs` (id de `left` → id de `right`)                                                                                                   | `{"pairs": {"1": "x"}}`       |
| `ORDERING`                                            | `items` (3–6, presentados en un orden que no es el correcto) y `order` (ids en el orden correcto)                                                                                      | `{"order": ["c", "a", "b"]}`  |
| `SHORT_ANSWER`                                        | `model` (respuesta modelo) y `rubric` (qué debe contener), los dos en cada idioma de interfaz                                                                                          | `{"text": "…"}`               |

## Libros ilustrados

Una lectura puede ser un libro ilustrado (ADR-0014): `"format": "PICTURE_BOOK"`, la maqueta del libro en `layout` y sus
páginas en `pages`, cada una con `span` (1 o 2), su `layout` si no es la del libro, cuántos `paragraphs` consecutivos
lleva y su `illustration` (`file` en `illustrations/` y `alt` en es y en). El texto, sus traducciones, el audio y las
preguntas son los de siempre. «Ein Sonntag bei Oma» es el ejemplo: los mismos personajes (Lena con abrigo verde
azulado, la abuela con chaqueta terracota y gafas) y la paleta de su portada en las cinco ilustraciones.

## Imágenes entre párrafos

Un texto puede llevar `images` (ADR-0018): cada una con `after` (cuántos párrafos la preceden), `file` en
`illustrations/`, `width` y `height` iguales a su `viewBox`, `alt` y, si se quiere, `caption`, los dos en el idioma de la
lectura. «Der alte Leuchtturm» lleva el faro en la tormenta tras su tercer párrafo.
