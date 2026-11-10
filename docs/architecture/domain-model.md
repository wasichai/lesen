# Modelo de dominio

«Hecho» está implementado (migración y código). «Diseñado» es el modelo acordado para su fase; puede afinarse al
construirlo, con una ADR si cambia una decisión.

## Idiomas

| Entidad        | Estado | Notas                                                                                          |
| -------------- | ------ | ---------------------------------------------------------------------------------------------- |
| `language`     | Hecho  | Código, nombre nativo, `interface` (puede ser idioma de la app) y `study` (se puede estudiar)  |
| `cefr_level`   | Hecho  | A1–C2 con las palabras por minuto con que se estima la duración (60, 75, 95, 115, 135, 150)    |
| Par de idiomas | Hecho  | No es tabla: lo forman el idioma de la entrada y la clave de cada traducción en sus acepciones |

## Lector

`learner_profile` (hecho), clave (organización, usuario): idioma de interfaz ≠ idioma de estudio, nivel, si el nivel es
una estimación, objetivos, temas, minutos diarios y dificultad (`COMFORTABLE`, `BALANCED`, `CHALLENGING`). Varios
idiomas de estudio a la vez separarán `learner_language` del perfil cuando haga falta; el panel de progreso ya acepta
un idioma.

## Lecturas

| Entidad               | Estado | Notas                                                                                                                                                  |
| --------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `topic`               | Hecho  | Árbol: el slug es la ruta (`travel/coasts`), etiquetas por idioma de interfaz                                                                          |
| `reading`             | Hecho  | Slug, idioma, nivel, destacada, versión vigente. Su idioma no cambia                                                                                   |
| `reading_topic`       | Hecho  | Temas en orden                                                                                                                                         |
| `reading_version`     | Hecho  | Inmutable (disparador). Título, subtítulo, resumen, portada, acento, palabras, minutos, contenido, huella y formato (`TEXT`, `PICTURE_BOOK`, ADR-0014) |
| `reading_translation` | Hecho  | Por unidad (`SENTENCE`, `PARAGRAPH`), referencia e idioma; origen `AUTHORED` o `PROVIDER`                                                              |
| `reading_entry`       | Hecho  | Entradas del diccionario de cada versión y cuántas veces aparecen                                                                                      |
| `collection`          | Hecho  | Etiquetas y descripciones por idioma, lecturas en orden                                                                                                |

### El contenido de una versión

```text
content
└── paragraphs[]  id: p3
    └── sentences[]  id: p3s2, text
        └── tokens[]  id: p3s2t5, text, kind (WORD | PUNCT), start, end, space, entry
format  TEXT | PICTURE_BOOK
layout  la maqueta del libro (solo en un libro)
pages[]  id: g2, span (1 | 2), layout (ya resuelta), paragraphs [p3, p4], illustration {file, alt{es, en}}
```

**Libro ilustrado (ADR-0014).** Las páginas reparten los párrafos consecutivos: el texto y sus ids son los mismos que en
un texto. Cada página o doble página tiene una maqueta del catálogo cerrado `PageLayout` (la del libro o la suya). Una
página con maqueta ilustrada lleva su ilustración y su texto alternativo en cada idioma de interfaz.

Los ids son estables dentro de la versión y todo lo que guarda una persona apunta a (versión, id). `entry` es la
entrada del diccionario que la publicación resolvió para esa forma (o la elegida por la autora para un homógrafo), o
null si no hay. `start` y `end` son posiciones en el texto de la oración: el alineador de audio las usará para casar lo
escrito con lo hablado.

**Tokenización (ADR-0004).** Una palabra es una secuencia de letras, marcas o dígitos que puede llevar dentro
apóstrofos o guiones (`don't`, `E-Mail`, `Nord-Ostsee-Kanal`). Los signos son tokens propios; los espacios no. Las
oraciones las fija la autora. Un idioma con otra regla (sin espacios, por ejemplo) registra su tokenizador.

## Audio (hecho, ADR-0008)

| Entidad                  | Estado | Notas                                                                                 |
| ------------------------ | ------ | ------------------------------------------------------------------------------------- |
| `audio_voice`            | Hecho  | Proveedor, idioma, nombre y licencia de la voz                                        |
| `audio_asset`            | Hecho  | Versión de lectura, voz, archivo, formato, duración, origen (`GENERATED`, `UPLOADED`) |
| `word_timing`            | Hecho  | Activo, id de token, inicio y fin en milisegundos                                     |
| `audio_alignment_report` | Hecho  | Palabras sin tiempo y avisos de la validación                                         |

Los tiempos viven aparte del archivo y se anclan a los ids de token de una versión concreta: una versión nueva pide
audio nuevo. Las oraciones y los párrafos toman el inicio de su primera palabra y el fin de la última; lo hablado que no
está escrito (o al revés) queda en el informe, nunca se fuerza. La generación de audio desde el estudio (cola de
trabajos) queda para cuando haya un proveedor de voz: el estudio sube audio con sus tiempos (ADR-0012).

## Diccionario

| Entidad                  | Estado | Notas                                                                                                        |
| ------------------------ | ------ | ------------------------------------------------------------------------------------------------------------ |
| `dictionary_entry`       | Hecho  | Clave (`de:leuchtturm`, `de:bank#seat`), lema, clase, IPA, `grammar` libre por idioma, acepciones            |
| Acepción                 | Hecho  | En `senses` (jsonb): traducciones por idioma, definición, ejemplos, sinónimos, antónimos, relacionadas, nota |
| `dictionary_form`        | Hecho  | Forma flexionada → entradas, con la preferida primero                                                        |
| `dictionary_translation` | Hecho  | Términos de traducción normalizados para buscar desde el idioma de la persona                                |
| `dictionary_search`      | Hecho  | Historial personal (las 50 últimas)                                                                          |
| `dictionary_favorite`    | Hecho  | Favoritas personales                                                                                         |

`grammar` no supone conceptos comunes a todos los idiomas: el alemán guarda género, plural, auxiliar o si el verbo es
separable; el inglés, pasado y participio irregulares; otro idioma, lo suyo.

## Vocabulario

`vocabulary_entry` (hecho): persona, entrada, idioma, idioma de traducción, forma tal como apareció, oración de
contexto, lectura, versión y token de origen, estado, nota, fechas. Una entrada por persona y palabra.

**Estados (hecho, ADR-0010).** `SAVED` → `LEARNING` → `FAMILIAR` → `MASTERED`, movidos solo por el repaso: sin
repasar, `SAVED`; en aprendizaje o con menos de 7 días de estabilidad, `LEARNING`; desde 7, `FAMILIAR`; desde 60,
`MASTERED`. «Encontrada» no es un estado del cuaderno: es un `vocabulary_encounter` (hecho: persona, entrada, idioma,
lectura, versión, primer token, fecha) que se anota al terminar una lectura, y permite resaltar palabras nuevas sin
confundir ver con recordar.

**Repaso (hecho).** FSRS-5 con sus parámetros por defecto publicados (`Fsrs`, pura, con pruebas contra los valores de
sus fórmulas). La tarjeta vive en `vocabulary_entry` (estado, paso, estabilidad, dificultad, próxima fecha, último
repaso, repasos, olvidos); `vocabulary_review` guarda cada respuesta (de 1 a 4), idempotente por el id del cliente.

## Comprensión (hecho, ADR-0009)

| Entidad              | Campos                                                                                                                                                                        |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `assessment`         | Versión de lectura (las preguntas son de un texto concreto), preguntas (`jsonb`) y huella del origen                                                                          |
| Pregunta             | Id, tipo (los diez), dificultad, enunciado y explicación por idioma, oraciones de referencia y la forma de su tipo (`spec`)                                                   |
| `assessment_attempt` | Persona, evaluación, idioma de interfaz, fecha                                                                                                                                |
| `assessment_answer`  | Intento, pregunta, tipo, respuesta, resultado (`CORRECT`, `PARTIAL`, `INCORRECT`, `PENDING`, `UNGRADED`), evaluador (`RULE`, `AI`, `SELF`), justificación y oraciones citadas |
| `evaluation_job`     | La cola de la corrección asistida: estado, intentos, próxima ejecución, error                                                                                                 |

La evaluación determinista corrige todo lo cerrado. La asistida solo corrige respuestas abiertas, siempre con las
oraciones de referencia en la entrada y la cita en la salida; sin proveedor, o al pasar el límite, la respuesta abierta
queda como autoevaluación con la respuesta modelo a la vista.

## Progreso (hecho, ADR-0011)

`reading_progress`: versión, posición (oración), porcentaje máximo de esa versión, segundos leídos (total que cuenta el
lector), inicio, actualización y fin. Repetir un informe deja el mismo estado. `reading_bookmark` y `reading_note`.

| Tabla                 | Qué guarda                                                                                                                  |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `learning_activity`   | Un evento por fila, solo inserciones: tipo, momento, idioma, lectura, segundos nuevos, palabras, clave de idempotencia      |
| `daily_progress`      | Proyección por (persona, día local, idioma): segundos, empezadas, terminadas, palabras leídas, guardadas, repasos, intentos |
| `progress_settings`   | Días de lectura por semana (1–7), zona horaria IANA y si se quiere el resumen semanal                                       |
| `achievement`         | Logro como dato: código, qué cuenta (`READINGS_COMPLETED`, `MINUTES`, `WORDS_SAVED`…) y desde cuánto                        |
| `learner_achievement` | Logro conseguido y cuándo; una vez, nunca se retira                                                                         |
| `reading_list`        | Lista propia con nombre (único por persona sin distinguir mayúsculas); hasta 50                                             |
| `reading_list_item`   | Lectura de una lista y cuándo se añadió; hasta 200 por lista                                                                |

Un día cuenta como activo si se leyó, se terminó algo, se guardó o repasó una palabra o se respondió un cuestionario.
La racha es semanal y nunca se muestra como fracaso: la semana en curso solo suma y los días que la persona no eligió
son de descanso.

## Estudio editorial (hecho, ADR-0012)

| Tabla          | Qué guarda                                                                                                                                 |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `studio_draft` | Lectura (slug, idioma, versión de partida), estado (`DRAFT`, `IN_REVIEW`, `PUBLISHED`, `DISCARDED`), documento, preguntas, audio, revisión |
| `studio_event` | Solo inserciones: borrador, acción (`CREATED`, `SAVED`, `AUDIO_ATTACHED`, `SUBMITTED`, `RETURNED`, `PUBLISHED`…), quién, nota, versión     |
| `media`        | Archivo subido (audio o portada): tipo comprobado por su firma, tamaño, huella SHA-256 y clave en el almacén de Wasichai                   |

Un borrador abierto por lectura. Su documento tiene la forma de `ReadingDraft`; su audio, el archivo y los tiempos ya
casados con los ids de palabra que tendrá la versión. Publicar crea la versión (si el texto cambió), carga las preguntas
y adjunta el audio contra ella; el historial de una lectura son sus versiones y los eventos de sus borradores.

**Encargos (ADR-0015).** `generation_job`: quien lo pidió (organización, usuario), el destino (`STUDIO`, `PRIVATE`), el
formato y la petición, el estado (`QUEUED → WRITING → ILLUSTRATING → READY | FAILED`), lo que salió (el borrador o la
lectura a medida), el motivo y los hallazgos si falló, la biblia visual con que se ilustró, quién dirigió el arte y lo
que costó.

## Recomendación

Recomendación v1 (hecha, `Recommender`): solo lecturas del idioma que se estudia y aún no terminadas. Suma cercanía a la
banda de nivel (el nivel propio y, según la dificultad preferida, uno por debajo o por encima; la banda se mueve un
nivel si la comprensión medida lo sugiere), coincidencia de temas elegidos o de temas de lo ya terminado, novedad, lo
que la organización terminó en los últimos 7 días y haber empezado. Cada recomendación devuelve por qué (`LEVEL`,
`COMPREHENSION`, `TOPIC`, `HISTORY`, `NEW`, `TRENDING`). Es una heurística, y se presenta como tal.
