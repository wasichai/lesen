# Historial

Lo entregado, lo más nuevo arriba.

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
