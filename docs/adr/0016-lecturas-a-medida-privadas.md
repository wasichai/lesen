# ADR-0016 — Lecturas a medida privadas, con cuota, para quien aprende

Estado: aceptada (2026-11-10)

## Contexto

ADR-0013 dejó para la fase 2 las lecturas privadas, la cola, la cuota y los endpoints de las lecturas a medida. ADR-0015
trajo la cola de encargos y los libros ilustrados al estudio. Falta que quien aprende pida su propia lectura (o su
libro ilustrado), escrita para su nivel y con las palabras que está aprendiendo, y la lea con el lector de siempre, sin
que nadie más la vea.

## Decisión

- **Una lectura privada es una lectura con dueña**: `reading.owner_organization` y `reading.owner_user` (V10), las dos o
  ninguna. Se publica con el `ReadingPublisher` de siempre, así que el lector, las palabras, las traducciones, el audio,
  las preguntas y el progreso funcionan igual. Un slug no cambia de dueña: una lectura a medida no puede tomar el slug de
  otra ni del catálogo, ni al revés.
- **Visibilidad**: `ReadingVersions.find` solo encuentra una lectura privada para quien la pidió (por el `CurrentUser`
  de Wasichai); para cualquier otra persona, y sin nadie que llame, no existe (404, regla 4). Por ahí pasan el contenido,
  las traducciones, el audio, las preguntas, el progreso, las notas y el vocabulario. El catálogo, las colecciones, los
  temas, las lecturas de una entrada del diccionario y el estudio muestran solo lo público; lo propio de una persona (lo
  que lleva leído, sus listas) incluye sus lecturas a medida. La tarjeta dice `custom`.
- **Sus traducciones son de proveedor** (`source = PROVIDER`, ADR-0005): nadie las revisó. Las de un borrador del
  estudio entran como de autora porque una persona las revisa antes de publicar (ADR-0012, ADR-0015).
- **El perfil de escritura** lo calcula el código con lo que Lesen ya sabe de la persona (ADR-0013): hasta cinco palabras
  que está aprendiendo (guardadas o en aprendizaje) con sus formas, los lemas que ya vio, los más vistos primero, y los
  títulos de lo último que leyó. Nada que la identifique.
- **Permisos**: un ancla propia, `lectura_a_medida` (CREATE), que el rol LECTOR tiene; cada organización puede
  quitársela. Nunca se compara un nombre de rol.
- **Cuota** (`lesen.generation.quota`): lecturas por persona y día (3) y libros ilustrados por mes (4), contados en la
  cola de encargos; un encargo que falla no gasta. Agotada, 429 con cuándo vuelve (`resetsAt`), nunca un encargo
  escondido.
- **Endpoints** (`/api/lesen/generations`): pedir (202), mis encargos, uno (404 si no es mío) y la cuota.
- El paso de una lectura a medida al catálogo público (proponerla al estudio) queda para después.

## Consecuencias

- Una lectura privada ocupa una versión publicada como cualquier otra: el disparador de inmutabilidad la cubre, y
  volver a pedirla es otra lectura.
- Con la generación apagada (sin clave), pedir responde 503 y la interfaz no lo ofrece; lo demás sigue igual.
- Cualquier consulta nueva sobre `lesen.reading` debe decidir si es del catálogo (`owner_user IS NULL`) o de quien
  pregunta; `PrivateReadingApiTest` comprueba los caminos de hoy.
