# ADR-0014 — Libros ilustrados: páginas y maquetas de un catálogo cerrado

Estado: aceptada (2026-11-10)

## Contexto

Hasta ahora una lectura es un texto que se desplaza: párrafos → oraciones → tokens, con una portada. Para quien empieza,
y en especial para el público infantil, un libro ilustrado ayuda: poco texto por página, una imagen que lo sostiene y un
ritmo de página en página. Producto pide un segundo formato de lectura, además del texto: un libro con un diseño por
cada página o doble página, como los libros infantiles. El libro tiene una maqueta por defecto y cualquier página puede
llevar otra cuando conviene. Más adelante la generación escribirá estos libros con sus ilustraciones (ADR-0013, y la
ADR que decida cómo se ilustran); esta decisión fija el formato.

## Decisión

- **Un formato por versión**: `ReadingFormat` es `TEXT` o `PICTURE_BOOK`, guardado en `reading_version.format` y en el
  contenido. Es de la versión, como el texto, y no cambia una vez publicada (el disparador de ADR-0003 lo cubre).
- **El texto no cambia de forma**: los párrafos siguen siendo la unidad de texto con sus ids (ADR-0004). Una página agrupa
  párrafos consecutivos; un libro es su texto más su reparto en páginas. Así las traducciones, el audio, las preguntas, el
  diccionario, las notas y el progreso (un id de oración) funcionan igual en un libro que en un texto.
- **La unidad de diseño es una página o una doble página** (`span` 1 o 2).
- **Un catálogo cerrado de maquetas** (`PageLayout`), cada una con los anchos que admite, si lleva ilustración y si va
  sin texto:

  | Maqueta              | Ancho | Qué es                                                             |
  | -------------------- | ----- | ------------------------------------------------------------------ |
  | `IMAGE_TOP`          | 1     | Ilustración arriba, texto debajo                                   |
  | `IMAGE_BOTTOM`       | 1     | Texto arriba, ilustración debajo                                   |
  | `TEXT_OVER_IMAGE`    | 1, 2  | Ilustración a sangre y el texto encima en una banda contrastada    |
  | `VIGNETTE`           | 1     | Una viñeta pequeña entre el texto                                  |
  | `TEXT_ONLY`          | 1     | Solo texto                                                         |
  | `IMAGE_ONLY`         | 1, 2  | Solo ilustración: una pausa sin texto                              |
  | `SPREAD_IMAGE_LEFT`  | 2     | Ilustración en la página izquierda, texto en la derecha            |
  | `SPREAD_IMAGE_RIGHT` | 2     | Texto a la izquierda, ilustración a la derecha                     |
  | `PANORAMA`           | 2     | Una ilustración que cruza las dos páginas, con una franja de texto |

  Un catálogo cerrado se puede probar, hacer accesible (contraste, foco, `alt`, móvil) y elegir por un modelo sin
  inventar composiciones; un diseño libre no.

- **Maqueta del libro y maqueta de página**: el borrador lleva `layout` (la del libro) y cada página puede llevar otra;
  la versión publicada guarda en cada página la maqueta ya resuelta y, aparte, la del libro, para volver a editarlo.
- **Cada ilustración es un archivo** con su texto alternativo en cada idioma de interfaz: SVG del classpath en la
  demostración; PNG, JPEG o WebP subidos (y más adelante generados) en el almacén de `wasichai-files`, `media.kind =
ILLUSTRATION`. Se sirven en `public/illustrations/{archivo}` con caché inmutable y `nosniff`; nunca un SVG subido.
- **Nada inventado (ADR-0005)**: un libro no se publica con una ilustración pendiente; si alguna faltara, el lector lo
  dice («Ilustración no disponible») en vez de rellenar el hueco. La escena de cada página (`scene`) es una nota para
  quien ilustra: no se publica ni cuenta para la huella.
- **La huella de un texto no cambia**: un `TEXT` se resume sin formato ni páginas, igual que antes, para que publicar la
  demostración otra vez no cree versiones nuevas.

## Consecuencias

- El lector elige la vista por el contenido: con `pages`, pasa de página en página (doble página en escritorio, una en
  el móvil); sin ellas, el texto de siempre. La posición sigue siendo un id de oración.
- El estudio edita las páginas (reparto, ancho, maqueta, ilustración y texto alternativo); la comprobación exige cada
  ilustración y su texto alternativo antes de publicar.
- El catálogo filtra por formato (`format=PICTURE_BOOK`) y la tarjeta dice el formato.
- Una maqueta nueva es un cambio de código en las dos partes (backend y lector) y en el sistema de diseño, no un dato.
