# ADR-0009 — Comprensión: corrección por reglas y asistida con cita, con autoevaluación de respaldo

Estado: aceptada (2026-11-09)

## Contexto

La fase 4 pide diez tipos de pregunta, explicaciones con referencia al texto y evaluación asistida de las respuestas
abiertas, sin que la IA invente (ADR-0005) y sin que la aplicación dependa de un proveedor para funcionar.

## Decisión

- **Las preguntas son de una versión** (`lesen.assessment`, una por versión). Sus referencias son ids de oración y de
  palabra de esa versión inmutable (ADR-0003). Se guardan como un documento `jsonb` (el formato de
  `docs/content/demo-content.md` §Preguntas) en lugar de tablas `question` y `question_option`: nadie consulta una
  opción suelta, y el estudio editorial (fase 6) las editará como documento. Se pueden reemplazar solo mientras nadie
  ha respondido; después, el cambio pide versión nueva.
- **Diez tipos, una forma por familia.** Opción (`MULTIPLE_CHOICE`, `MAIN_IDEA`, `INFERENCE`, `DETAIL`,
  `VOCABULARY_IN_CONTEXT`), verdadero o falso, completar (huecos que son palabras de una oración del texto), emparejar,
  ordenar y respuesta corta. El tipo dice qué habilidad se mide; la forma, cómo se responde. No hay campo «habilidad»
  aparte: repetiría el tipo.
- **Todo lo cerrado lo corrige `Grader`**, una función pura: la misma respuesta da siempre el mismo resultado. Hay
  parcial en lo que tiene partes (varias opciones correctas, varios huecos, parejas, posiciones). Completar compara sin
  mayúsculas, espacios ni signos alrededor y en NFC, con las variantes que la autora acepte.
- **Respuesta corta: asistente si hay clave, autoevaluación si no.** Con `ANTHROPIC_API_KEY`, la respuesta queda
  `PENDING` y una cola en la base (`lesen.evaluation_job`, `FOR UPDATE SKIP LOCKED`, como `lms.trabajo` de elearning)
  la manda a Claude por el SDK oficial con salida estructurada: resultado cerrado, justificación y las oraciones citadas.
  Al modelo solo le llegan la pregunta, la rúbrica, la respuesta modelo, las oraciones de referencia y la respuesta; ni
  el nombre ni nada más de la persona. Una evaluación sin cita, o que cita una oración que no se le dio, no vale.
  Tres fallos seguidos (o una negativa del modelo) dejan la respuesta `UNGRADED`.
- **`UNGRADED` no es un fracaso**: la persona ve la respuesta modelo y dice cómo le fue (`SELF`). Es también lo que
  pasa sin clave y al pasar el límite de 30 correcciones asistidas por persona y hora: el envío nunca falla por el
  asistente, en lugar del 429 que preveía `api-design.md`.
- Modelo por defecto `claude-opus-5-5` con esfuerzo bajo (corregir una respuesta corta contra una rúbrica es sencillo),
  configurable con `LESEN_AI_MODEL`. Las pruebas usan un doble local de la API; la API real no corre en CI.

## Consecuencias

- La aplicación funciona entera sin proveedor; con él, las respuestas abiertas se corrigen en segundos.
- La autoevaluación cuenta en el resumen como cualquier otra corrección, marcada `SELF`: el panel de la fase 5 puede
  separarla.
- El SDK de Anthropic trae OkHttp y Jackson 2 al classpath; se usan solo desde `ClaudeAnswerEvaluator`.
