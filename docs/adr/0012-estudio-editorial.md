# ADR-0012 — Estudio editorial: borradores, revisión y publicación con audio y preguntas

Estado: aceptada (2026-11-09)

## Contexto

Hasta la fase 5 todo el contenido entra como JSON del classpath (`docs/content/demo-content.md`). La fase 6 pide que
una editora publique una lectura nueva con audio y preguntas sin tocar JSON, con versiones e historial. El texto
publicado es inmutable (ADR-0003), las preguntas y los tiempos del audio se anclan a los ids de una versión (ADR-0004,
ADR-0008, ADR-0009) y nada se inventa (ADR-0005).

## Decisión

- **Un borrador es el mismo documento que publica `ReadingPublisher`**: metadatos, párrafos y oraciones fijados por la
  autora, traducciones por oración y por párrafo, elecciones de entrada para homógrafos (`senses`), más sus preguntas y
  su audio. Vive en `lesen.studio_draft` (jsonb), con una revisión que crece en cada guardado: dos personas no se pisan
  (409). Hay un solo borrador abierto por lectura. Editar una lectura publicada crea el borrador desde su versión
  vigente, con sus traducciones, sus preguntas y su audio.
- **Los ids son los de la versión que saldrá**: el borrador se ensambla igual que al publicar (`ReadingPublisher.preview`),
  así que el estudio ofrece los ids reales de oraciones y palabras para citar en las preguntas y para casar el audio.
- **Comprobación antes de publicar** (`DraftCheck`, pura): son errores los campos inválidos, los temas o el idioma que no
  existen, las preguntas que citan oraciones o palabras que no están o cuya forma no es la de su tipo
  (`QuestionCheck`), el audio cuyos tiempos no casan con este texto y las preguntas ya respondidas que cambiarían sin
  texto nuevo. Son avisos las palabras sin entrada en el diccionario, las traducciones y los textos de pregunta que
  faltan en algún idioma de interfaz, la falta de audio o de preguntas y que el texto sea el de la versión vigente (no
  habrá versión nueva). Un borrador con errores no se envía a revisión ni se publica.
- **Flujo**: `DRAFT` → `IN_REVIEW` (en revisión no se edita) → `PUBLISHED`, o vuelve a `DRAFT` con una nota que dice
  qué corregir; `DISCARDED` en cualquier momento antes de publicar. Cada paso queda en `lesen.studio_event` (solo
  inserciones): quién, qué, cuándo, con qué nota y qué versión. Ese historial, con las versiones de `reading_version`, es
  el historial de cada lectura.
- **Permisos**: un ancla nueva, `estudio`. Ver y escribir borradores y enviarlos a revisión son READ y UPDATE; devolver y
  publicar es una acción propia del ancla, `PUBLICAR` (`model/model.json`, declarada por `model/apply.py`). Dos roles:
  `REDACTOR` (escribe y envía) y `EDITOR` (además devuelve y publica). El código nunca mira un rol.
- **Publicar** es, en este orden, `ReadingPublisher.publish` (idempotente por la huella), las preguntas contra la versión
  resultante (idempotente por su huella) y el audio (idempotente por versión y voz). Si un paso falla, publicar otra vez
  termina el trabajo sin duplicar nada.
- **Audio subido, no generado en el servidor**: el estudio recibe el archivo (mp3, ogg o m4a, comprobado por su firma) y
  sus tiempos por palabra como WebVTT o SRT con una palabra por tramo, la salida habitual de los alineadores forzados y
  de la transcripción con marcas por palabra, o el JSON de `tools/generate_audio.py`. Lo dicho se casa con lo escrito
  por la subsecuencia común más larga de las formas normalizadas (`Transcripts`); lo que no casa queda sin tiempo y lo
  juzga `Timings.check` (más de un 5 % rechaza el audio). Un tramo con varias palabras no reparte su tiempo: sería
  inventarlo. Generar la voz en el servidor exigiría eSpeak NG y ffmpeg en producción y Python fuera de las herramientas;
  queda para cuando haya un proveedor detrás de una interfaz con una implementación real. La voz y su licencia las
  declara quien sube el audio, y se guardan con él (`audio_voice.license`).
- **Archivos en el almacén de Wasichai** (`wasichai-files`): solo su interfaz pública `FileStore` (disco local o S3), sin
  sus campos de archivo de objetos, porque el contenido de Lesen no vive en Custom Objects (ADR-0002). Lo que es cada
  archivo va en `lesen.media`; el nombre público sale de la huella SHA-256. Portadas PNG, JPEG o WebP, nunca SVG subido
  (podría llevar código); se sirven con `nosniff` y CSP restrictiva, el audio con rangos.
- **Diccionario desde el estudio**: una entrada nueva (lema, clase, formas, acepciones con traducciones) se añade al
  diccionario curado con el mismo `DictionaryService.import` que la demostración; resuelve las palabras al publicar la
  siguiente versión. Nunca se reescribe una entrada existente desde aquí (409).

## Consecuencias

- `editorial` depende de `common`, `language`, `reading`, `audio`, `assessment` y `dictionary`; nadie depende de él.
- La demostración sigue entrando por el classpath; sus lecturas se pueden editar en el estudio como cualquier otra.
- Los archivos subidos se respaldan con la base (`docs/operations.md`): una fila de `lesen.media` sin sus bytes da 404.
- El contenido editorial sigue siendo de toda la instalación: los borradores no llevan organización.
