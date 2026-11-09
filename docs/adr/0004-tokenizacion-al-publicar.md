# ADR-0004 — Se tokeniza al publicar, con ids estables

Estado: aceptada (2026-11-09)

## Contexto

El lector necesita cada palabra como elemento propio; el diccionario, saber qué entrada corresponde a cada forma; el
audio (fase 3), tiempos por palabra. Tokenizar en el navegador daría resultados distintos según el motor.

## Decisión

- Se tokeniza **al publicar**, en el servidor, y se guarda el resultado en la versión: párrafos (`p3`), oraciones
  (`p3s2`, fijadas por la autora) y tokens (`p3s2t5`) con texto, tipo (`WORD`, `PUNCT`), posiciones, si sigue un
  espacio y la entrada de diccionario resuelta.
- Regla de palabra Unicode, determinista y la misma que `tools/check_demo.py`:
  `[\p{L}\p{M}\p{N}]+(?:['’-][\p{L}\p{M}\p{N}]+)*`. Contracciones (`don't`), elisiones y compuestos con guion quedan
  enteros; cada signo es un token. Se prefirió a `BreakIterator` porque su resultado depende de la versión del JDK.
- La forma se normaliza (NFC, apóstrofo recto, minúsculas sin región) para buscar en el diccionario.
- Un idioma que necesite otra regla registra su `Tokenizer` en `Tokenizers`.

## Consecuencias

- El cliente pinta sin calcular nada y todos los dispositivos ven los mismos ids.
- Cambiar la regla exige republicar (una versión nueva), nunca reescribir las existentes.
