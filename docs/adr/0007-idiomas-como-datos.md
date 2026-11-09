# ADR-0007 — Idiomas de interfaz es/en; idiomas de estudio como datos

Estado: aceptada (2026-11-09)

## Contexto

Idioma de interfaz e idioma de estudio son cosas distintas (una persona hispanohablante que aprende alemán ve la app
en español y el diccionario alemán → español). Wasichai trae la interfaz en español e inglés.

## Decisión

- Idiomas de interfaz: **es** y **en** (los de Wasichai). Sus textos viven en el i18n de cada módulo, con prueba de que
  los dos tienen las mismas claves.
- Idiomas de estudio: **datos** (`lesen.language.study`). Hoy alemán e inglés. Añadir uno es una fila, su léxico y sus
  lecturas, sin código, salvo que necesite otro tokenizador (ADR-0004).
- Un par de idiomas lo forma la entrada (idioma de estudio) y la clave de la traducción (idioma de interfaz); el
  perfil nunca permite estudiar el idioma de la interfaz.
- El contenido en el idioma de estudio nunca pasa por el i18n de la interfaz y se marca con `lang` en el HTML.

## Consecuencias

- Un tercer idioma de interfaz exige traducir los módulos y que Wasichai lo admita.
