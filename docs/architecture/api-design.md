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

| Método | Ruta                                        | Permiso | Notas                                                                                                                                                                                                                                                                |
| ------ | ------------------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GET    | `discover`                                  | lectura | `{continueReading, featured, recommended[{reading, reasons}], fresh, collections, topics}`; 404 sin perfil                                                                                                                                                           |
| GET    | `readings`                                  | lectura | Filtros `language`, `level` (repetible), `topic` (incluye subtemas), `minMinutes`, `maxMinutes`, `q`, `status` (`BOOKMARKED`, `IN_PROGRESS`, `COMPLETED`, `NOT_STARTED`), `featured`; `sort` (`RECENT`, `POPULAR`, `LEVEL`, `DURATION`, `TITLE`); página de tarjetas |
| GET    | `readings/{slug}`                           | lectura | `{reading, paragraphs, related[], collections[]}`                                                                                                                                                                                                                    |
| GET    | `readings/{slug}/versions/{n}/content`      | lectura | Párrafos → oraciones → tokens. `Cache-Control: private, max-age=31536000, immutable` y ETag                                                                                                                                                                          |
| GET    | `readings/{slug}/versions/{n}/translations` | lectura | `unit` (`SENTENCE`, `PARAGRAPH`), `ref` opcional, `language` (por defecto el de la interfaz). Con `ref` y sin traducción: 404                                                                                                                                        |

La tarjeta (`ReadingCard`) lleva slug, idioma, nivel, temas, destacada, versión vigente, título, subtítulo, resumen,
portada, acento, palabras, minutos, fecha de publicación, si está marcada, el progreso de quien pregunta y cuántas
personas la abrieron.

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

| Método | Ruta              | Permiso  | Notas                                                                                                               |
| ------ | ----------------- | -------- | ------------------------------------------------------------------------------------------------------------------- |
| GET    | `vocabulary`      | cuaderno | Filtros `reading`, `status`, `q`; página de 24 por defecto                                                          |
| GET    | `vocabulary/keys` | cuaderno | Claves guardadas (para resaltar en el texto), con `language` opcional                                               |
| POST   | `vocabulary`      | escribir | `{entryKey, reading?, version?, tokenRef?}`. 201 la primera vez, 200 si ya estaba. El token debe ser de esa entrada |
| PATCH  | `vocabulary/{id}` | escribir | `{note}`                                                                                                            |
| DELETE | `vocabulary/{id}` | escribir | 204; ajena, 404                                                                                                     |

## Próximas fases (diseñado)

- `readings/{slug}/versions/{n}/audio` → activos, voces y tiempos por palabra (fase 3).
- `readings/{slug}/versions/{n}/assessment` y `assessments/{id}/attempts` (fase 4).
- `vocabulary/reviews` (siguientes tarjetas y respuestas, fase 4).
- `progress/dashboard` y `progress/activity` (fase 5).
- `studio/readings/**` (borradores, revisión y publicación, fase 6).

Las operaciones caras (generar audio, evaluar con IA) irán a la cola de trabajos con un límite por persona y
devolverán 429 con `Retry-After` al pasarlo, como en elearning-backend.
