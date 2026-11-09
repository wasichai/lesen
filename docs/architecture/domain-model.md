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
idiomas de estudio a la vez (fase 5) separarán `learner_language` del perfil.

## Lecturas

| Entidad               | Estado | Notas                                                                                                      |
| --------------------- | ------ | ---------------------------------------------------------------------------------------------------------- |
| `topic`               | Hecho  | Árbol: el slug es la ruta (`travel/coasts`), etiquetas por idioma de interfaz                              |
| `reading`             | Hecho  | Slug, idioma, nivel, destacada, versión vigente. Su idioma no cambia                                       |
| `reading_topic`       | Hecho  | Temas en orden                                                                                             |
| `reading_version`     | Hecho  | Inmutable (disparador). Título, subtítulo, resumen, portada, acento, palabras, minutos, contenido y huella |
| `reading_translation` | Hecho  | Por unidad (`SENTENCE`, `PARAGRAPH`), referencia e idioma; origen `AUTHORED` o `PROVIDER`                  |
| `reading_entry`       | Hecho  | Entradas del diccionario de cada versión y cuántas veces aparecen                                          |
| `collection`          | Hecho  | Etiquetas y descripciones por idioma, lecturas en orden                                                    |

### El contenido de una versión

```text
content
└── paragraphs[]  id: p3
    └── sentences[]  id: p3s2, text
        └── tokens[]  id: p3s2t5, text, kind (WORD | PUNCT), start, end, space, entry
```

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
trabajos) llega en la fase 6.

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

**Estados.** `SAVED` (hecho) → `LEARNING` → `FAMILIAR` → `MASTERED`, movidos solo por el repaso. «Encontrada» no es un
estado del cuaderno: es un `vocabulary_encounter` (fase 4, diseñado: persona, entrada, versión, token, fecha) que se
anota al terminar una lectura, y permite resaltar palabras nuevas sin confundir ver con recordar.

**Repaso (fase 4, diseñado).** FSRS-5 (Free Spaced Repetition Scheduler), con sus parámetros por defecto publicados.
`vocabulary_review` guarda cada respuesta (de 1 a 4) y el estado de la tarjeta (estabilidad, dificultad, próxima
fecha). Las fórmulas viven en una clase pura con pruebas contra los valores de referencia de FSRS.

## Comprensión (fase 4, diseñado)

| Entidad              | Campos                                                                                                                                                                                                                                                           |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `assessment`         | Versión de lectura (las preguntas son de un texto concreto), estado                                                                                                                                                                                              |
| `question`           | Tipo (opción múltiple, verdadero/falso, completar, emparejar, ordenar, respuesta corta, vocabulario en contexto, idea principal, inferencia, detalle), enunciado por idioma, criterio de corrección, explicación, oraciones de referencia, dificultad, habilidad |
| `question_option`    | Texto, si es correcta, orden                                                                                                                                                                                                                                     |
| `assessment_attempt` | Persona, evaluación, inicio, fin, puntuación                                                                                                                                                                                                                     |
| `answer`             | Intento, pregunta, respuesta, resultado (`CORRECT`, `PARTIAL`, `INCORRECT`), evaluador (`RULE`, `AI`), justificación con referencia al texto                                                                                                                     |

La evaluación determinista corrige todo lo cerrado. La asistida por IA solo corrige respuestas abiertas, siempre con
las oraciones de referencia en la entrada y la cita en la salida; sin proveedor, la respuesta abierta queda como
autoevaluación con la respuesta modelo a la vista.

## Progreso (hecho en parte)

`reading_progress` (hecho): versión, posición (oración), porcentaje máximo de esa versión, segundos leídos (total que
cuenta el lector), inicio, actualización y fin. Repetir un informe deja el mismo estado. `reading_bookmark` y
`reading_note` (hechos). Fase 5, diseñado: `learning_activity` (eventos por día), `daily_progress` (proyección),
`achievement` y `learner_achievement`; la racha es una proyección que nunca se muestra como fracaso.

## Recomendación

Recomendación v0 (hecha, `Recommender`): solo lecturas del idioma que se estudia y aún no terminadas. Suma cercanía a la
banda de nivel (el nivel propio y, según la dificultad preferida, uno por debajo o por encima), coincidencia de temas,
novedad y haber empezado. Cada recomendación devuelve por qué (`LEVEL`, `TOPIC`, `NEW`). Es una heurística, y se
presenta como tal.
