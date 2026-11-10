# ADR-0018 — Imágenes entre párrafos: bloques anclados a un párrafo

Estado: aceptada (2026-11-10)

## Contexto

Hasta ahora un texto solo tenía portada, y las ilustraciones existían únicamente en los libros ilustrados (ADR-0014),
repartidas en sus páginas. La app móvil (`wasichai/lesen-app`, que descarga una lectura para leerla sin conexión) y la
web necesitan mostrar ilustraciones **dentro de un texto normal**, entre sus párrafos (issue #7). El contrato que pide la
app es un bloque `{type: "image", id, url, width, height, alt, caption?}` entre párrafos, inmutable por versión, con
`alt` obligatorio, `caption` opcional en el idioma de la lectura, y `width` y `height` para reservar el espacio antes de
cargar la imagen.

## Decisión

- **Un bloque aparte, anclado a un párrafo.** El borrador lleva `images[{after, file, width, height, alt, caption?}]`,
  donde `after` es cuántos párrafos la preceden (0, antes del primero). La versión publicada guarda
  `images[{id: img-1…, after: p2 | null, file, width, height, alt, caption}]` en el orden de lectura. Los párrafos y sus
  ids (ADR-0004) no cambian, así que traducciones, audio, preguntas, notas y progreso siguen igual; no se generaliza
  `Illustration` de ADR-0014, que sigue siendo cosa de las páginas de un libro.
- **Solo en un texto.** Un libro ilustrado muestra sus ilustraciones en sus páginas: no lleva imágenes entre párrafos.
- **El orden de lectura en la respuesta.** `GET readings/{slug}/versions/{n}/content` añade `blocks`: `{type:
"paragraph", id}` remite al párrafo, que sigue entero en `paragraphs`, y `{type: "image", id, url, width, height, alt,
caption}` es la imagen. Sin imágenes, `blocks` es null: los clientes de hoy no cambian.
- **`alt` y `caption` en el idioma de la lectura**, una sola cadena cada uno, como pide el contrato. Es distinto de las
  ilustraciones de un libro, cuyo texto alternativo va por idioma de interfaz (ADR-0014): allí la imagen narra la escena
  para quien todavía no lee el idioma; aquí acompaña un texto que quien lee ya está leyendo, y la app la descarga con él.
- **Los archivos son ilustraciones** (`media.kind = ILLUSTRATION`, `public/illustrations/{archivo}`, caché inmutable):
  no hace falta migración. Al subirla, el estudio recibe su ancho y alto, leídos de la cabecera (PNG, JPEG, WebP); los
  SVG de la demostración los declaran en su `viewBox`.
- **Nada inventado (ADR-0005).** Una imagen sin archivo o sin texto alternativo no se publica (`IMAGE_MISSING`,
  `IMAGE_ALT_MISSING` en la comprobación del estudio). Si un cliente no puede cargarla, lo dice.
- **La huella de un texto sin imágenes no cambia**: `images` vacía no entra en ella.
- **Exportación (ADR-0017)**: cada imagen sale como `<figure>` con `<img alt width height>` y `<figcaption>` tras su
  párrafo, y el ePub la empaqueta.
- **El `bundle` de la versión**, cuando exista, llevará `blocks` y los archivos de las imágenes.

## Consecuencias

- Publicar una imagen nueva en un texto publica una versión nueva (ADR-0003), como cualquier otro cambio del contenido.
- La demostración lleva una imagen en «Der alte Leuchtturm», tras el párrafo de la tormenta; su huella cambia y, en una
  instalación que ya la tenía, se publica como versión nueva.
