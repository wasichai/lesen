# ADR-0015 — Exportar lecturas: ePub del servidor y PDF desde el navegador

Estado: aceptada (2026-11-10)

## Contexto

Quien lee quiere llevarse una lectura a otras aplicaciones: Kindle, Apple Books o un PDF para imprimir. Lesen no
exportaba nada, no tenía biblioteca de PDF ni de ePub, y el contenido no tiene campo de licencia: todo lo publicado es
original de Lesen (`docs/content/demo-content.md`). Lo decidido con producto (2026-11-10): ePub y PDF, sin
dependencias nuevas; la persona elige qué incluir además del texto y la portada (traducciones, glosario, sus notas);
se exporta cualquier lectura publicada; basta el permiso de leer.

## Decisión

- **Un solo documento, armado en el servidor** (capacidad `export`). `ExportService` reúne la versión publicada con lo
  pedido y lo escriben dos funciones puras sin E/S: `EpubWriter` (ePub 3) y `XhtmlWriter` (las páginas del ePub y una
  página suelta para imprimir). Así lo que se incluye y cómo se dice lo que falta se decide una sola vez.
- **ePub 3 con `java.util.zip` del JDK**: `mimetype` primero y sin comprimir, `container.xml`, `content.opf` con
  `dc:identifier` estable `urn:lesen:{slug}:v{n}` (la versión es inmutable, ADR-0003), `nav.xhtml`, una hoja de estilos,
  la portada como `cover-image` y una página por sección (texto; glosario y notas si se piden). Lo abren Apple Books y
  cualquier lector; Kindle lo acepta por «Send to Kindle», así que no se genera MOBI ni AZW3.
- **PDF desde el navegador**: el mismo documento en una página HTML con `@media print`, que la interfaz manda imprimir;
  «Guardar como PDF» está en todos los sistemas. Un PDF del servidor exigiría una biblioteca y fuentes incrustadas sin
  un requisito que lo pida (CLAUDE.md, regla 8).
- **Opciones**: `translations` pone la traducción de cada párrafo debajo de él, en la lengua de la interfaz; si el
  párrafo no la tiene, se forma con las de todas sus oraciones, y si falta alguna, el documento dice «Este párrafo no
  tiene traducción» (ADR-0005). `glossary` lista las entradas del diccionario curado que la publicación resolvió para
  las palabras del texto (lema, clase y hasta tres traducciones); si no hay ninguna, lo dice. `notes` añade las notas
  de quien llama, filtradas por su organización y su usuario.
- **Idiomas marcados**: el texto lleva el `lang` del idioma de estudio y las traducciones, rótulos y glosario el de la
  interfaz (es o en, ADR-0007), para que los lectores de pantalla y la separación silábica acierten.
- **Permisos**: `access.reader()` para exportar; con `notes` además `access.notebook()` (403 si no se puede leer lo
  propio). La respuesta es `Cache-Control: private, no-store` porque puede llevar notas; la página HTML va con una CSP
  sin scripts (`default-src 'none'`, estilos en línea e imágenes `data:`).
- **Fuera de esta entrega**: el audio del ePub (Media Overlays con los tiempos de ADR-0008) arrastraría la licencia de
  la voz y pesa decenas de MB; se decidirá aparte. Cuando entren textos de terceros hará falta un campo de licencia y
  atribución, y decidir si todos se pueden exportar.
- `export` depende de `common`, `reading`, `dictionary` y `library`; nadie depende de ella.

## Consecuencias

- Un endpoint nuevo, `GET readings/{slug}/versions/{n}/export?format=epub|html`, sin tablas ni migraciones.
- La hoja de estilos del documento vive en `XhtmlWriter.CSS`: sin fuentes incrustadas, cada aplicación usa las suyas.
- Si un día hace falta un PDF del servidor (envíos por correo, impresión sin navegador), se añade otro escritor sobre el
  mismo `ExportDocument` con una ADR nueva.
