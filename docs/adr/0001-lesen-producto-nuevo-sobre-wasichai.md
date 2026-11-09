# ADR-0001 — Lesen es un producto nuevo sobre Wasichai, en un monolito modular

Estado: aceptada (2026-11-09)

## Contexto

El encargo parte de `elearning-backend` y `elearning-frontend`. Su dominio es un LMS: cursos versionados, ediciones,
matrículas, lecciones, evaluaciones y créditos. Lesen es una plataforma de lectura: el centro es un texto tokenizado
con diccionario en contexto, no un temario. Existen además dos repositorios vacíos, `wasichai/lesen` y
`wasichai/lesen-ui`.

## Decisión

- Lesen es un **producto nuevo** en esos dos repositorios. Reutiliza **la plataforma** (Wasichai, `@wasichai/*`) y las
  **convenciones** de elearning (estructura, pruebas, CI, idioma de documentación y commits, sistema de fuentes), no su
  dominio.
- Un **monolito modular**: un paquete por capacidad con dependencias permitidas explícitas (`overview.md` §3),
  comprobadas por `ArchitectureTest`. Sin microservicios.

## Consecuencias

- Nada del LMS se fuerza en el modelo de lectura; ninguna regresión posible en elearning.
- Wasichai solo se publica en GitHub Packages: el CI necesita un token con `read:packages` (`PACKAGES_TOKEN`).
