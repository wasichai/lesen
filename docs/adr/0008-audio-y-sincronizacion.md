# ADR-0008 — Audio con tiempos por palabra, aparte del archivo

Estado: aceptada (2026-11-09)

## Contexto

La fase 3 pide narración sincronizada palabra a palabra, sin aproximaciones por caracteres o por párrafo cuando hay
tiempos por palabra, y que el lector siga funcionando sin audio. Hay que elegir de dónde salen los tiempos, dónde viven
y con qué voz se hace la demostración.

## Decisión

- **Los tiempos viven aparte del audio** (`lesen.word_timing`) y se anclan a los ids de token de **una versión
  inmutable** (ADR-0003, ADR-0004). Una versión nueva pide audio o alineación nuevos; nunca se reescriben los de otra.
  Las oraciones y los párrafos toman su tramo de su primera y su última palabra con tiempo.
- **Validación antes de publicar** (`Timings.check`): cada tiempo es de una palabra de esa versión (nunca de un signo),
  no se solapan, van en el orden del texto y caben en el audio. Las palabras sin tiempo (lo dicho y lo escrito no casan)
  van al informe (`audio_alignment_report`); más de un 5 % rechaza el audio entero.
- **Dos orígenes**: `GENERATED` (síntesis con marcas: el motor dice cuándo llega a cada palabra) y `UPLOADED` (audio y
  tiempos subidos por una persona, fase 6). Un alineador forzado para audio grabado sin tiempos queda fuera hasta que
  haya un caso real: no se declara una interfaz sin implementación (regla 8).
- **Demostración**: eSpeak NG por `ctypes` (`tools/generate_audio.py`), una marca SSML antes de cada palabra; el inicio
  de cada palabra es el milisegundo en que el motor llega a su marca, recortado al primer tramo con voz (una marca tras
  una coma llega al empezar la pausa). Cada lectura se genera en su propio proceso: el resultado es idéntico byte a byte
  en cada ejecución. MP3 mono, que reproduce cualquier navegador.
- **Voz: las propias de eSpeak NG, no MBROLA.** La licencia de la base MBROLA DE1 prohíbe incorporarla «a cualquier
  producto que se venda» sin permiso de su dueño; para no comprometer el producto, se usan las voces de eSpeak NG, cuyo
  programa es GPL-3.0 y cuyo audio generado no tiene restricciones. Suenan sintéticas: la interfaz (`audio_voice`,
  `origin`) permite cambiarlas por grabaciones o por un proveedor comercial sin tocar el lector. La licencia de cada voz
  queda en `audio_voice.license`.
- **Archivos**: los de demostración, en el classpath, servidos por `/api/lesen/public/audio/{archivo}` con rangos (206)
  para saltar sin descargar el archivo entero. Los de la fase 6, en wasichai-files.

## Consecuencias

- El lector resalta la palabra hablada con tiempos reales, y sin audio funciona igual.
- `DemoContentTest` comprueba que los ids que escribe el generador (Python) son los del backend (Kotlin), y
  `tools/check_demo.py` valida los tiempos en CI sin regenerar el audio.
