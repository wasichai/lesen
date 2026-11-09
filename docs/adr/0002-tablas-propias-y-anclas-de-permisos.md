# ADR-0002 — Tablas propias en el esquema lesen; Wasichai para identidad y permisos

Estado: aceptada (2026-11-09)

## Contexto

Wasichai ofrece Custom Objects (metadatos y CRUD genérico) y elearning modela así casi todo. El contenido de Lesen es
un documento anidado (párrafos, oraciones y tokens) que se lee entero; el progreso, el vocabulario y el historial son
de alto volumen y siempre por persona. Además, los permisos de Wasichai son (rol, objeto, acción) con acciones
predefinidas o declaradas por un objeto: sin un objeto no hay a qué atar un permiso.

## Decisión

- El dominio vive en **tablas propias del esquema `lesen`** con Flyway (`ModuleMigration("lesen", …, 200)`) y SQL con
  `DatabaseClient`. jsonb solo donde el dato es un documento (contenido, acepciones, gramática, etiquetas).
- Wasichai sigue siendo dueño de usuarios, roles, permisos, organizaciones y auditoría.
- Dos **objetos ancla** `apiOnly` en `model/model.json`, sin registros: `lectura` (leer catálogo y diccionario) y
  `cuaderno` (lo personal). Roles `LECTOR` (lectura READ; cuaderno READ y UPDATE) y `EDITOR`. `model/apply.py` los
  aplica de forma idempotente por la API de Wasichai.
- El contenido editorial es de toda la instalación; lo personal lleva siempre organización y usuario.

## Consecuencias

- Consultas a la medida (tarjetas con progreso en una sola consulta) y un disparador para la inmutabilidad.
- Las pantallas genéricas de Wasichai no muestran el dominio de Lesen; el estudio editorial (fase 6) será propio.
