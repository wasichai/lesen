# ADR-0010 — Repaso espaciado con FSRS-5 y estados del cuaderno movidos solo por el repaso

Estado: aceptada (2026-11-09)

## Contexto

El cuaderno guarda palabras desde la fase 2 en estado `SAVED`. La fase 4 pide repaso espaciado que cambie la próxima
fecha de cada palabra, y estados (`LEARNING`, `FAMILIAR`, `MASTERED`) que no se puedan marcar con un botón.

## Decisión

- **FSRS-5** con sus 19 parámetros por defecto publicados, retención deseada 0,9, pasos de aprendizaje de 1 y 10
  minutos y de reaprendizaje de 10 minutos, sin aleatoriedad: la misma máquina que el planificador de referencia
  (py-fsrs 4.x), escrita en Kotlin (`Fsrs`), pura y probada contra los valores de sus fórmulas. Sin optimizar
  parámetros por persona hasta que haya historial que lo justifique.
- **La tarjeta vive en la palabra** (`vocabulary_entry`: estado, paso, estabilidad, dificultad, próxima fecha, último
  repaso, repasos, olvidos) y cada respuesta queda en `vocabulary_review`, idempotente por el id que pone el cliente.
- **El estado sale de la tarjeta** (`ReviewStatus`): sin repasar, `SAVED`; en aprendizaje o con menos de 7 días de
  estabilidad, `LEARNING`; desde 7 días, `FAMILIAR`; desde 60, `MASTERED`. Olvidar una palabra la devuelve a `LEARNING`.
- **Encontrar no es recordar.** Al terminar una lectura se anota cada entrada del diccionario vista
  (`vocabulary_encounter`), sin tocar el cuaderno. `library` avisa por `ReadingCompletionListener`, que vive en
  `reading`: así `library` no depende de `vocabulary`.

## Consecuencias

- Las palabras guardadas antes de la fase 4 quedan para repasar desde su fecha de guardado.
- La interfaz puede enseñar en cada botón la fecha que daría esa respuesta (`preview`), porque el cálculo es puro.
- «Hoy» en las estadísticas es el día UTC hasta que la fase 5 traiga la zona horaria de cada persona.
