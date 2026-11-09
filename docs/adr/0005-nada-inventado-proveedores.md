# ADR-0005 — Proveedores de lenguaje detrás de interfaces; nada inventado

Estado: aceptada (2026-11-09)

## Contexto

Diccionario, traducción contextual, texto a voz, alineación y evaluación abierta pueden venir de personas o de
proveedores comerciales con costes, licencias, latencia y privacidad distintos. Una traducción inventada enseña algo
falso.

## Decisión

- Lo básico funciona **sin ningún proveedor**: diccionario curado (`lexicon/*.jsonl` en la demostración), traducciones
  escritas por personas (`source = AUTHORED`), y en la fase 3 audio subido con tiempos subidos.
- Cada integración entra detrás de una interfaz propia (`FormResolvers` hoy; `TranslationProvider`, `SpeechProvider`,
  `Aligner` y `AnswerEvaluator` en sus fases), elegida por configuración, con su respaldo determinista.
- **Nada inventado:** sin entrada, la palabra se publica igual y el lector dice «todavía no tenemos esta palabra»; sin
  traducción, 404 y el mismo mensaje honesto. Lo que genere un proveedor se marca (`PROVIDER`) y, como en elearning,
  la IA propone y una persona aprueba antes de publicar.

## Consecuencias

- La demostración no depende de claves ni de red. Ampliar el diccionario cuesta trabajo editorial, que la fase 6 hará
  cómodo.
