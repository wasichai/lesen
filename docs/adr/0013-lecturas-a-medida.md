# ADR-0013 — Lecturas a medida: un flujo controlado por el código que escribe con Claude

Estado: aceptada (2026-11-10)

## Contexto

Cada persona quiere poder pedir una lectura escrita para ella: con controles guiados (nivel, largo, tipo de texto,
temas), con un prompt libre o con las dos cosas, y que use las palabras que le toca reforzar. Lesen ya tiene lo que hace
falta para leer, comprobar y medir una lectura (ADR-0003, ADR-0004, ADR-0009, ADR-0012), y la regla de no inventar
(ADR-0005). Lo decidido con producto (plan «Lecturas a medida», 2026-11-10): Opus 5.5 por defecto y configurable, cuota
configurable, prompt libre y guiado desde el principio, paso al catálogo público más adelante a través del estudio, y
revisión humana por revisores calificados, nativos en su mayoría.

## Decisión

- **Un flujo controlado por el código, no un agente libre.** El código reúne el perfil de escritura, pide la lectura,
  la comprueba y, si falla, se la devuelve al modelo con sus fallos (dos correcciones como máximo,
  `lesen.generation.max-corrections`). Cada paso es determinista salvo la escritura; se prueba con un escritor falso.
  Una herramienta para que el modelo consulte el diccionario queda para cuando la evaluación muestre que hace falta.
- **El perfil de escritura (`GenerationContext`) lo calcula el código**: idioma, nivel y sus palabras por minuto, largo,
  tipo de texto, temas, de 0 a 8 palabras objetivo con sus formas, los lemas que la persona ya vio (hasta 400), los
  lemas del diccionario curado (hasta 2.000), títulos de lo leído hace poco y los tipos de pregunta en que le va peor.
  Ni nombre, ni correo, ni nada que identifique a la persona. El modelo no consulta la base.
- **Claude por el SDK oficial** (`anthropic-java`, cliente asíncrono), con la clave y el modelo de la corrección
  asistida (`ANTHROPIC_API_KEY`, `LESEN_AI_MODEL`, `claude-opus-5-5` por defecto). Streaming, porque la respuesta es
  larga; esfuerzo explícito (`LESEN_GENERATION_EFFORT`, `medium` para empezar); caché automática del prefijo (el sistema,
  igual para toda la instalación, y en una corrección, el intento anterior).
- **Salida estructurada con un esquema cerrado**: título, resumen, temas (de la lista de la instalación), nombres
  propios, párrafos con sus oraciones y la traducción de cada oración, y de 4 a 7 preguntas en una forma plana. Las
  oraciones las fija el modelo; los ids los pone el ensamblado de siempre (`ReadingAssembler`). La traducción de un
  párrafo es la de sus oraciones unidas: el modelo no la escribe dos veces. Las preguntas solo pueden ser de los tipos
  que se corrigen por reglas y se citan con oraciones (opción, verdadero o falso, completar, vocabulario en contexto);
  la palabra de una pregunta se escribe como aparece en su oración y el código la convierte en su id.
- **Comprobaciones antes de entregar (`GenerationCheck`, pura)**: las del estudio (campos, traducciones, `QuestionCheck`)
  más el largo (±25 % de lo pedido), el nivel (`LevelFit`: media y máximo de palabras por oración), el vocabulario (al
  menos el 90 % de las palabras con entrada o nombres propios declarados, y como mucho el 5 % de formas sin entrada),
  cada palabra objetivo al menos dos veces en una forma que el diccionario reconoce, y de 4 a 7 preguntas de al menos 3
  tipos con sus textos en cada idioma de interfaz. Los umbrales son valores iniciales que ajusta la evaluación.
- **Nada inventado (ADR-0005)**: el texto es ficción declarada, las traducciones se guardarán como de proveedor
  (`source = PROVIDER`) y el diccionario no cambia: una palabra sin entrada se muestra como hoy, «sin entrada». Una
  negativa del modelo termina la petición con un motivo claro; una salida cortada o fuera del esquema vuelve al modelo
  como un fallo más.
- **Lo que escribe la persona es dato**: va entre etiquetas que no puede cerrar, y el sistema fija los límites de
  contenido (apto para cualquier edad, personas inventadas, nada de datos de la petición presentados como hechos).
- **Evaluar antes de abrirlo** (fase 1): un juego versionado de 30 perfiles (alemán e inglés, A1 a C1) sobre el
  contenido de demostración, cada uno con tres peticiones (guiada, libre y mixta). `./gradlew generationEval` lo corre
  contra la API real a mano, nunca en CI, y deja el informe, los resultados y cada lectura con su hoja de revisión. El
  criterio para seguir: al menos el 90 % pasa con una corrección o menos, 20 lecturas revisadas por revisores
  calificados dan al menos 4 sobre 5 en naturalidad y nivel, y el coste medio queda medido.
- La capacidad `generation` depende de `common`, `reading` y `assessment`; nadie depende de ella. Las lecturas
  privadas, la cola, la cuota y los endpoints son la fase 2.

## Consecuencias

- Sin clave no hay lecturas a medida, igual que no hay corrección asistida; la aplicación funciona entera.
- La cobertura del diccionario curado limita lo que se puede escribir: con el léxico de demostración (unas 530 entradas
  en alemán y 295 en inglés) los niveles altos y las lecturas largas fallarán más. La evaluación lo mide y decide entre
  bajar el umbral, darle al modelo una herramienta de consulta o crecer el diccionario.
- El esquema cambia con los idiomas de interfaz (sus campos de traducción son fijos), así que hay una variante por idioma
  de lectura; el sistema es el mismo para toda la instalación.
