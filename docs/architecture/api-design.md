# API de Lesen

Todo bajo `/api/lesen`. Lo anónimo, bajo `/api/lesen/public`. Autenticación con el JWT de Wasichai
(`POST /api/auth/login`). Errores en `application/problem+json` con `errors[{field, message}]`. Páginas como
`{content, page, size, totalElements, totalPages}` (`page` desde 0, `size` 1–50). Lo ajeno o inexistente es 404; sin
permiso, 403; sin token, 401. El contrato completo y actualizado está en `/api/lesen/public/openapi`.

Permisos (`Access`): **lectura** = READ sobre `lectura`; **cuaderno** = READ sobre `cuaderno`; **escribir** = UPDATE
sobre `cuaderno`.

## Público

| Método | Ruta                       | Respuesta                                                                                             |
| ------ | -------------------------- | ----------------------------------------------------------------------------------------------------- |
| GET    | `public/languages`         | `{languages[{code, nativeName, interfaceLanguage, study}], levels[{code, position, wordsPerMinute}]}` |
| GET    | `public/topics`            | Árbol `[{slug, labels{es,en}, children[]}]`                                                           |
| GET    | `public/covers/{file}.svg` | La portada, con caché pública de 7 días y CSP restrictiva                                             |
| GET    | `public/openapi`           | OpenAPI de `/api/lesen/**`                                                                            |

## Perfil

| Método | Ruta              | Permiso  | Notas                                                                                                                                  |
| ------ | ----------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `learner/profile` | cuaderno | 404 hasta completar el onboarding                                                                                                      |
| PUT    | `learner/profile` | escribir | `{interfaceLanguage, targetLanguage, level, levelEstimated, goals[], topics[], dailyMinutes, difficulty}`; 400 con cada campo inválido |

## Catálogo y lectura

| Método | Ruta                                        | Permiso | Notas                                                                                                                                                                                                                                                                                                   |
| ------ | ------------------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `discover`                                  | lectura | `{continueReading, featured, recommended[{reading, reasons}], thisWeek[{reading, readers}], fresh, collections, topics}`; 404 sin perfil                                                                                                                                                                |
| GET    | `readings`                                  | lectura | Filtros `language`, `level` (repetible), `topic` (incluye subtemas), `minMinutes`, `maxMinutes`, `q`, `status` (`BOOKMARKED`, `IN_PROGRESS`, `COMPLETED`, `NOT_STARTED`), `featured`, `format` (`TEXT`, `PICTURE_BOOK`); `sort` (`RECENT`, `POPULAR`, `LEVEL`, `DURATION`, `TITLE`); página de tarjetas |
| GET    | `readings/{slug}`                           | lectura | `{reading, paragraphs, related[], collections[]}`                                                                                                                                                                                                                                                       |
| GET    | `readings/{slug}/versions/{n}/content`      | lectura | Párrafos → oraciones → tokens; un libro, además `format`, `layout` y `pages[{id, span, layout, paragraphs[], illustration{src, alt}}]` (ADR-0014). `Cache-Control: private, max-age=31536000, immutable` y ETag                                                                                         |
| GET    | `readings/{slug}/versions/{n}/translations` | lectura | `unit` (`SENTENCE`, `PARAGRAPH`), `ref` opcional, `language` (por defecto el de la interfaz). Con `ref` y sin traducción: 404                                                                                                                                                                           |

La tarjeta (`ReadingCard`) lleva slug, idioma, nivel, temas, destacada, versión vigente, título, subtítulo, resumen,
portada, acento, palabras, minutos, fecha de publicación, si está marcada, el progreso de quien pregunta y cuántas
personas la abrieron.

## Audio

| Método | Ruta                                 | Permiso | Notas                                                                                                                                                               |
| ------ | ------------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `readings/{slug}/versions/{n}/audio` | lectura | `{assets[{id, voice, url, format, durationMs, origin, timings[[tokenId, inicio, fin]], sentences[{id, startMs, endMs}], paragraphs[…]}]}`; inmutable; 404 sin audio |
| GET    | `public/audio/{archivo}`             | público | El archivo, con rangos (`Range` → 206) para saltar sin descargarlo entero                                                                                           |

## Biblioteca

| Método | Ruta                         | Permiso  | Notas                                                                                                            |
| ------ | ---------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------- |
| GET    | `library`                    | cuaderno | `{inProgress, bookmarked, completed}`, con `q` opcional                                                          |
| GET    | `readings/{slug}/progress`   | cuaderno | 404 sin progreso                                                                                                 |
| PUT    | `readings/{slug}/progress`   | escribir | `{version, position, percent, secondsRead}`; idempotente (`ProgressPolicy`); posición validada contra la versión |
| POST   | `readings/{slug}/completion` | escribir | `{version, secondsRead}`; idempotente                                                                            |
| PUT    | `readings/{slug}/bookmark`   | escribir | 204                                                                                                              |
| DELETE | `readings/{slug}/bookmark`   | escribir | 204                                                                                                              |
| GET    | `readings/{slug}/notes`      | cuaderno | Notas propias                                                                                                    |
| POST   | `readings/{slug}/notes`      | escribir | `{version, sentenceRef, note?}`; la cita sale del texto publicado; 201                                           |
| DELETE | `notes/{id}`                 | escribir | 204; ajena o inexistente, 404                                                                                    |

## Listas propias

| Método | Ruta                         | Permiso  | Notas                                                                                    |
| ------ | ---------------------------- | -------- | ---------------------------------------------------------------------------------------- |
| GET    | `lists`                      | cuaderno | `[{id, name, count, contains, updatedAt}]`; con `?reading=slug`, `contains` dice si está |
| POST   | `lists`                      | escribir | `{name}` (1–80, único por persona sin distinguir mayúsculas: 409); hasta 50 listas; 201  |
| GET    | `lists/{id}`                 | cuaderno | `{id, name, updatedAt, readings[ReadingCard]}`, de la última añadida a la primera        |
| PATCH  | `lists/{id}`                 | escribir | `{name}`                                                                                 |
| DELETE | `lists/{id}`                 | escribir | 204                                                                                      |
| PUT    | `lists/{id}/readings/{slug}` | escribir | 204; idempotente; hasta 200 lecturas por lista                                           |
| DELETE | `lists/{id}/readings/{slug}` | escribir | 204                                                                                      |

Una lista de otra persona o inexistente es 404.

## Diccionario

| Método | Ruta                         | Permiso                              | Notas                                                                                                       |
| ------ | ---------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| GET    | `dictionary/lookup`          | lectura                              | `reading`, `version`, `token` → `{token, sentence, entry, inflected, alternatives}`; `entry` null si no hay |
| GET    | `dictionary/search`          | lectura (escribir con `record=true`) | `q`, `study`, `language`, `limit` ≤ 30; coincide por lema, forma o traducción                               |
| GET    | `dictionary/entries/{key}`   | lectura                              | La entrada completa con `favorite`, `saved` y `readings` donde aparece                                      |
| GET    | `dictionary/history`         | cuaderno                             | Búsquedas recientes distintas                                                                               |
| DELETE | `dictionary/history`         | escribir                             | 204                                                                                                         |
| GET    | `dictionary/favorites`       | cuaderno                             | Resúmenes                                                                                                   |
| PUT    | `dictionary/favorites/{key}` | escribir                             | 204                                                                                                         |
| DELETE | `dictionary/favorites/{key}` | escribir                             | 204                                                                                                         |

## Vocabulario

| Método | Ruta                      | Permiso  | Notas                                                                                                                                        |
| ------ | ------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `vocabulary`              | cuaderno | Filtros `reading`, `status`, `q`; página de 24 por defecto                                                                                   |
| GET    | `vocabulary/keys`         | cuaderno | Claves guardadas (para resaltar en el texto), con `language` opcional                                                                        |
| POST   | `vocabulary`              | escribir | `{entryKey, reading?, version?, tokenRef?}`. 201 la primera vez, 200 si ya estaba. El token debe ser de esa entrada                          |
| PATCH  | `vocabulary/{id}`         | escribir | `{note}`                                                                                                                                     |
| DELETE | `vocabulary/{id}`         | escribir | 204; ajena, 404                                                                                                                              |
| GET    | `vocabulary/known`        | cuaderno | `?language=`: lo guardado con su estado y lo encontrado al terminar lecturas, para resaltar en el texto                                      |
| GET    | `vocabulary/reviews/next` | cuaderno | `?language=&reading=&limit=` (≤ 50): palabras que tocan, con la fecha que daría cada respuesta (`preview`), cuántas tocan y la próxima fecha |
| POST   | `vocabulary/reviews`      | escribir | `{vocabularyId, rating: AGAIN\|HARD\|GOOD\|EASY, clientId}`. FSRS-5 (ADR-0010); repetir el mismo `clientId` no repasa dos veces              |
| GET    | `vocabulary/stats`        | cuaderno | Total, por estado, cuántas tocan, repasadas hoy y encontradas                                                                                |

## Comprensión

| Método | Ruta                                                 | Permiso  | Notas                                                                                                                                        |
| ------ | ---------------------------------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `readings/{slug}/versions/{n}/assessment`            | lectura  | Preguntas en el idioma de interfaz (`?language=`), sin respuestas; `lastAttempt` si quien pregunta tiene cuaderno. 404 sin preguntas         |
| POST   | `assessments/{id}/attempts`                          | escribir | `{answers: [{questionId, options?, value?, blanks?, pairs?, order?, text?}]}`. 201 con lo cerrado corregido; lo no respondido es `INCORRECT` |
| GET    | `attempts/{id}`                                      | cuaderno | El intento con cada respuesta, la solución, la explicación y sus oraciones. Con respuestas `PENDING`, la interfaz vuelve a pedirlo           |
| POST   | `attempts/{id}/answers/{questionId}/self-assessment` | escribir | `{result: CORRECT\|PARTIAL\|INCORRECT}` sobre una respuesta `UNGRADED`; si no, 409                                                           |

Resultados: `CORRECT`, `PARTIAL`, `INCORRECT`, `PENDING` (la corrige el asistente) y `UNGRADED` (abierta, para
autoevaluar con la respuesta modelo). Evaluador: `RULE`, `AI` (con justificación y oraciones citadas) o `SELF` (ADR-0009).

## Progreso

| Método | Ruta                    | Permiso  | Notas                                                                                                                                                                          |
| ------ | ----------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| GET    | `progress/dashboard`    | cuaderno | `?language=` (por defecto el de estudio). `{language, timeZone, today, goal, streak, days[28], reading, vocabulary, comprehension, level, recentAchievements}`; 404 sin perfil |
| GET    | `progress/weekly`       | cuaderno | `?week=AAAA-MM-DD` (la semana, de lunes a domingo, que contiene ese día; por defecto la en curso): días, totales, días activos, meta semanal, racha y logros de esa semana     |
| GET    | `progress/achievements` | cuaderno | Todos los logros con su regla (`kind`, `threshold`), cuánto se lleva (`value`, hasta el umbral) y `earnedAt` o null                                                            |
| GET    | `progress/goals`        | cuaderno | `{dailyMinutes, weeklyDays, timeZone, weeklySummary, configured}`                                                                                                              |
| PUT    | `progress/goals`        | escribir | Lo que se mande de `{dailyMinutes (al perfil), weeklyDays 1–7, timeZone IANA, weeklySummary}`; cambiar de zona reconstruye la proyección                                       |

Cada cifra es `{value, kind}` con `kind` `MEASURED` (contado) o `ESTIMATED` (inferido: palabras leídas, aprendiendo,
dominadas, nivel). La racha (`{current, best, weeksMet, target, activeThisWeek, metThisWeek}`) es semanal y nunca dice
que se perdió nada. La actividad la registran los endpoints de siempre (progreso, terminar, guardar, repasar, enviar un
intento); no hay un endpoint para inventarla (ADR-0011).

## Estudio editorial

Permisos (`Access`): **estudio** = READ sobre `estudio`; **escribir estudio** = UPDATE; **publicar** = la acción propia
`PUBLICAR` sobre `estudio` (ADR-0012).

| Método | Ruta                             | Permiso          | Notas                                                                                                                                                                           |
| ------ | -------------------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `studio/drafts`                  | estudio          | `?status=`; resúmenes, lo más reciente primero                                                                                                                                  |
| POST   | `studio/drafts`                  | escribir estudio | `{slug, language, level, title}` (nueva) o `{from: slug}` (desde la versión vigente, con traducciones, preguntas y audio). 201; ya existe la lectura o su borrador abierto: 409 |
| GET    | `studio/drafts/{id}`             | estudio          | Documento, preguntas, audio, los ids de oraciones y palabras que tendrá la versión (`sentences`), revisión e historial (`events`)                                               |
| PUT    | `studio/drafts/{id}`             | escribir estudio | `{revision, document, questions}`; revisión vieja o borrador fuera de `DRAFT`: 409                                                                                              |
| DELETE | `studio/drafts/{id}`             | escribir estudio | Descarta un borrador abierto; 204                                                                                                                                               |
| GET    | `studio/drafts/{id}/check`       | estudio          | `{ready, errors[{code, ref, detail}], warnings[…], words, sentences, unresolved, missingTranslations, questions, alignedWords}`                                                 |
| POST   | `studio/drafts/{id}/audio`       | escribir estudio | Multipart: `audio` (mp3, ogg, m4a), `timings` (WebVTT, SRT o JSON de `generate_audio.py`), `durationMs`, `voice`, `license`                                                     |
| DELETE | `studio/drafts/{id}/audio`       | escribir estudio | Quita el audio del borrador                                                                                                                                                     |
| POST   | `studio/drafts/{id}/submit`      | escribir estudio | `{note?}`: `DRAFT` → `IN_REVIEW`; con errores, 409                                                                                                                              |
| POST   | `studio/drafts/{id}/return`      | publicar         | `{note}` obligatoria: `IN_REVIEW` → `DRAFT`                                                                                                                                     |
| POST   | `studio/drafts/{id}/publish`     | publicar         | `{note?}`: versión, preguntas y audio; `IN_REVIEW` → `PUBLISHED`. Cada paso es idempotente                                                                                      |
| POST   | `studio/illustrations`           | escribir estudio | Multipart `file` (PNG, JPEG, WebP, ≤ 5 MB) → `{file, kind: ILLUSTRATION, …}`; el `file` va en `document.pages[n].illustration.file` (ADR-0014)                                  |
| POST   | `studio/covers`                  | escribir estudio | Multipart `file` (PNG, JPEG, WebP, ≤ 3 MB) → `{file, kind, contentType, sizeBytes, sha256}`; el `file` va en `document.cover`                                                   |
| GET    | `studio/readings`                | estudio          | Lecturas publicadas con su versión vigente, cuántas versiones y su borrador abierto                                                                                             |
| GET    | `studio/readings/{slug}/history` | estudio          | `{versions[{number, status, title, wordCount, publishedAt, audio, questions}], events[…]}`                                                                                      |
| POST   | `studio/dictionary/entries`      | escribir estudio | `{language, entry: {key, lemma, pos, forms, senses}}`; 201; ya existe: 409                                                                                                      |

Los archivos subidos se sirven públicos como los de la demostración: `public/audio/{archivo}` (con rangos) y
`public/covers/{archivo}` (PNG, JPEG o WebP, con `nosniff`). Las ilustraciones, en `public/illustrations/{archivo}`, con caché
inmutable. El documento de un libro lleva `format`, `layout` y `pages[{span, layout?, paragraphs, illustration{file?, alt, scene?}}]`;
la comprobación añade `ILLUSTRATION_MISSING` (página) y `ALT_MISSING` (página, idioma) como errores.

## Próximas fases (diseñado)

La generación de audio irá a una cola de trabajos con un límite por persona. La corrección asistida ya usa una cola
(ADR-0009); al pasar su límite, la respuesta queda para autoevaluar en lugar de devolver 429.
