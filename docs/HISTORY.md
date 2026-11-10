# Historial

Lo entregado, lo más nuevo arriba.

## 2026-11-10 — Fase 7, etapa 2 (backend): libros ilustrados generados desde el estudio

- ADR-0015: la generación (ADR-0013) escribe también un libro, con su maqueta, sus páginas, la escena y el texto
  alternativo de cada una y la biblia visual (`book`), y comprueba páginas, palabras por página, escenas, personajes y
  textos alternativos (`PictureBooks`).
- Capacidad `illustration`: `ImageProvider` con los proveedores `fake`, `openai` (gpt-image) y `gemini` (con imágenes de
  referencia), los pasos de la dirección de arte (`IllustrationSteps`), el recorrido que dibuja en orden con las hojas
  de los personajes y la página anterior y vuelve a dibujar lo que la revisión rechaza (`IllustrationPipeline`), y el
  agente de Embabel `IllustrationAgent` (biblia → planos → ilustraciones con revisión con visión), fuera del contexto sin
  clave (`IllustrationGate`).
- Capacidad `commission` y esquema V9: la cola de encargos con trabajadores reactivos, encargos desde el estudio
  (`studio/generations`) que terminan en un borrador con el evento `GENERATED`, y volver a dibujar una página.

## 2026-11-10 — Fase 7, etapa 1 (backend): libros ilustrados

- ADR-0014: formato `PICTURE_BOOK` además del texto, páginas y dobles páginas que reparten los párrafos sin cambiar sus
  ids, una maqueta por defecto y otra por página del catálogo cerrado `PageLayout` (`PageRules`).
- Esquema V8: `reading_version.format` (también inmutable) y `media.kind = ILLUSTRATION`. La huella de un texto no
  cambia; la escena de una página no cuenta.
- Contenido de una versión con `format`, `layout` y `pages` (ilustración con `src` y `alt`); ilustraciones públicas en
  `public/illustrations/{archivo}`; tarjeta con `format` y filtro `format` en el catálogo.
- Estudio: subida de ilustraciones (`studio/illustrations`), borradores de libros desde la versión vigente y
  comprobación con `ILLUSTRATION_MISSING` y `ALT_MISSING`; un libro no se publica con una ilustración pendiente.
- Demostración: «Ein Sonntag bei Oma» es un libro ilustrado de cinco páginas con ilustraciones SVG coherentes;
  `tools/check_demo.py` valida páginas, maquetas, ilustraciones y textos alternativos.

## 2026-11-10 — Lecturas a medida, fase 1 (backend): prototipo y evaluación

- ADR-0013 y capacidad `generation`: perfil de escritura (`GenerationContext`) sin datos que identifiquen a la persona,
  prompt de sistema fijo y esquema cerrado de salida estructurada, escritor con Claude por streaming con caché del
  prefijo (`ClaudeReadingWriter`) y flujo escribir → comprobar → corregir con hasta dos correcciones (`Generator`).
- Comprobaciones puras (`GenerationCheck`, `LevelFit`): largo, nivel, vocabulario del diccionario curado, palabras
  objetivo, traducciones y preguntas (con `ReadingAssembler` y `QuestionCheck`).
- Juego de evaluación de 30 perfiles × 3 peticiones sobre el contenido de demostración y `./gradlew generationEval`
  contra la API real (a mano, nunca en CI), con informe, coste medido y hojas de revisión humana.

## 2026-11-09 — Fase 6 (backend): estudio editorial y operación

- Esquema V7 y capacidad `editorial` (ADR-0012): borradores con revisión optimista, un borrador abierto por lectura,
  comprobación antes de publicar (`DraftCheck`, `QuestionCheck`), flujo borrador → revisión → publicada con notas,
  historial solo de inserciones y publicación de la versión con sus preguntas y su audio, idempotente paso a paso.
- Ancla `estudio` con la acción propia `PUBLICAR` y roles `REDACTOR` y `EDITOR`; persona de demostración «Rita»
  (redactora).
- Audio subido con tiempos por palabra en WebVTT, SRT o el JSON de `generate_audio.py`, casados con el texto por la
  subsecuencia común más larga (`Transcripts`); audio y portadas en el almacén de `wasichai-files` (`common.Media`),
  servidos públicos con rangos y `nosniff`.
- Entradas nuevas del diccionario curado desde el estudio y borradores de lecturas publicadas con sus traducciones,
  preguntas y audio.
- `docs/operations.md`: despliegue, variables, permisos, respaldo de la base y del almacén, vigilancia.

## 2026-11-09 — Fase 5 (backend): progreso, metas amables, logros y listas propias

- Esquema V6 y capacidad `progress` (ADR-0011): registro de actividad solo de inserciones e idempotente, emitido por
  `library`, `vocabulary` y `assessment` a través de `LearningActivityListener`; proyección diaria en la zona horaria de
  cada persona que se reconstruye igual que se calcula; V6 rellena lo anterior desde las tablas de las fases 2–4.
- `progress/dashboard` (lectura, vocabulario y comprensión, cada cifra medida o estimada; nivel siempre estimado),
  `progress/weekly`, `progress/achievements` y `progress/goals`. Meta diaria amable y racha semanal con días de descanso
  que nunca la rompen; 13 logros como datos, concedidos una vez.
- Recomendación v1: la comprensión mueve la banda de nivel, cuentan los temas de lo terminado y lo que la organización
  terminó esta semana (agregado anónimo); Descubrir trae `thisWeek`.
- Listas de lecturas propias (`lists/**`) en `library`, solo para su dueña.

## 2026-11-09 — Fase 4 (backend): comprensión, encuentros y repaso espaciado

- Esquema V5 y capacidad `assessment`: preguntas por versión con los diez tipos, corrección determinista (`Grader`),
  intentos con solución y explicación, cola de corrección asistida con Claude (SDK oficial, salida estructurada, cita
  obligatoria) y autoevaluación cuando no hay asistente (ADR-0009).
- 70 preguntas de demostración para las 11 lecturas, con los diez tipos; `tools/check_demo.py` valida su forma y sus
  referencias, y `tools/lesen_text.py` imprime una lectura con sus ids.
- Repaso espaciado FSRS-5 (`Fsrs`, ADR-0010): la tarjeta vive en la palabra, cada respuesta queda registrada y es
  idempotente, y el estado del cuaderno solo lo mueve el repaso. Encuentros al terminar una lectura, sin tocar el
  cuaderno. Endpoints `vocabulary/known`, `reviews/next`, `reviews` y `stats`.
- Corrección de V4: el archivo de audio ya no es único, porque dos versiones con el mismo texto lo comparten.

## 2026-11-09 — Fase 3: audio sincronizado

- Esquema V4 (`audio_voice`, `audio_asset`, `word_timing`, `audio_alignment_report`) y capacidad `audio`: validación
  de tiempos contra el texto congelado, tramos de oración y párrafo, `GET readings/{slug}/versions/{n}/audio` inmutable y
  archivos públicos con rangos (206).
- Narración de las 11 lecturas con eSpeak NG (`tools/generate_audio.py`): tiempos por palabra desde las marcas del motor,
  recortados al tramo con voz, deterministas byte a byte. Voces de eSpeak NG y no MBROLA por la licencia de DE1 (ADR-0008).
- `tools/check_demo.py` y `DemoContentTest` validan los tiempos y que los ids del generador son los del backend.

## 2026-11-09 — Primera entrega: fases 0, 1 y 2

- **Fase 0.** Auditoría de `elearning-backend`, `elearning-frontend`, `wasichai` y `wasichai-ui`; requisitos, hoja de
  ruta, arquitectura, modelo de dominio, diseño de API y ADR 0001–0007.
- **Backend.** Andamiaje sobre Wasichai 1.10.0 con el esquema `lesen` (V1–V3); anclas de permisos y roles `LECTOR` y
  `EDITOR` aplicados por `model/apply.py`; capacidades `language`, `learner`, `reading` (tokenizador Unicode, versiones
  inmutables con disparador, publicación idempotente), `catalog` (catálogo filtrado, ficha, descubrir, recomendación
  explicable), `library` (progreso idempotente, terminar, marcadores, notas), `dictionary` (consulta en contexto,
  búsqueda en dos sentidos, historial, favoritas) y `vocabulary` (guardar desde el texto publicado, notas).
- **Contenido.** 11 lecturas originales (8 en alemán A1–C1, 3 en inglés A2–B2), 825 entradas de diccionario curadas
  con traducciones a español e inglés, traducciones de cada oración y párrafo, 12 temas con subtemas, 2 colecciones y
  11 portadas SVG originales.
- **Pruebas.** 28 unitarias y de arquitectura, 7 de integración contra PostgreSQL (incluidas las de autorización y la
  inmutabilidad de versiones) y `tools/check_demo.py`.
