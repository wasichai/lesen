# ADR-0003 — Las versiones publicadas de una lectura son inmutables

Estado: aceptada (2026-11-09)

## Contexto

Lo que una persona guarda (posición, notas, palabras con su contexto, en el futuro intentos de comprensión y tiempos
de audio) apunta a oraciones y palabras concretas. Si el texto cambiara debajo, esas referencias mentirían.

## Decisión

- Publicar crea `reading_version` con número creciente; una versión publicada no cambia nunca. Un disparador de la base
  rechaza cualquier cambio de su texto, título, resumen, número o huella; solo puede pasar a `RETIRED`.
- `reading.current_version` señala la vigente. Las anteriores siguen legibles para quien las empezó: el lector abre la
  versión del progreso guardado y avisa de que hay otra más reciente.
- Publicar dos veces lo mismo (misma huella SHA-256 del borrador) no crea otra versión.
- El contenido de una versión se sirve con caché inmutable.

## Consecuencias

- Las referencias guardadas siempre resuelven. El coste es guardar cada versión entera (pocos KB por lectura).
