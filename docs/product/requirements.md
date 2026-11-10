# Requisitos de producto

Lesen es una plataforma para aprender idiomas **leyendo**: la lectura es el producto; el diccionario, el vocabulario,
el audio, la comprensión y las estadísticas existen para que leer valga más. No es un LMS ni un curso con lecciones.

## Personas

| Persona | Quién es                                                        | Qué necesita                                                     |
| ------- | --------------------------------------------------------------- | ---------------------------------------------------------------- |
| Lena    | Hispanohablante, A2 de alemán, lee en el móvil en el transporte | Textos cortos de su nivel, entender palabras sin salir del texto |
| Tomás   | Hispanohablante, B1 de alemán, lee en el ordenador por la noche | Historias interesantes, guardar vocabulario, ver que avanza      |
| Edith   | Editora de contenido                                            | Publicar lecturas con traducciones, audio y preguntas (fase 6)   |

## El ciclo de aprendizaje

1. Descubrir una lectura interesante. 2. Abrirla en el lector inmersivo. 3. Leer en el idioma de estudio.
2. Escuchar la narración sincronizada (fase 3). 5. Explorar palabras desconocidas. 6. Guardar vocabulario útil.
3. Responder preguntas de comprensión (fase 4). 8. Recibir una devolución útil (fase 4). 9. Repasar el vocabulario
   (fase 4). 10. Ver el progreso y descubrir otra lectura (fase 5).

## Requisitos funcionales

La columna «Entrega» dice en qué fase se construye. Lo de la primera entrega (fases 0–2) está implementado y probado.

### Perfil y onboarding

| Id        | Requisito                                                                                   | Entrega |
| --------- | ------------------------------------------------------------------------------------------- | ------- |
| RF-PER-01 | Idioma de interfaz e idioma de estudio son conceptos separados; nunca el mismo              | 1       |
| RF-PER-02 | Nivel del MCER (A1–C2); «no estoy seguro» guarda A2 marcado como estimación, no como medida | 1       |
| RF-PER-03 | Objetivos, temas preferidos, minutos diarios y dificultad preferida                         | 1       |
| RF-PER-04 | Sin perfil, la app lleva al onboarding; el perfil se edita en cualquier momento             | 1       |
| RF-PER-05 | Varios idiomas de estudio a la vez                                                          | 5       |

### Descubrir y catálogo

| Id        | Requisito                                                                                         | Entrega |
| --------- | ------------------------------------------------------------------------------------------------- | ------- |
| RF-CAT-01 | Portada editorial: destacada, seguir leyendo, para ti (con el porqué), temas, colecciones, nuevas | 1       |
| RF-CAT-02 | Catálogo con filtros por idioma, nivel, tema y subtema, duración, búsqueda y orden; en la URL     | 1       |
| RF-CAT-03 | Ficha de lectura: portada, resumen, nivel, minutos, palabras, temas, relacionadas, marcador       | 1       |
| RF-CAT-04 | Temas anidados                                                                                    | 1       |
| RF-CAT-05 | Tendencias por lecturas recientes y colecciones personales                                        | 5       |

### Lector inmersivo

| Id        | Requisito                                                                                                            | Entrega |
| --------- | -------------------------------------------------------------------------------------------------------------------- | ------- |
| RF-LEC-01 | Tipografía de lectura, ancho de línea cómodo, tamaño e interlineado ajustables                                       | 1       |
| RF-LEC-02 | Temas de lectura claro, sepia y oscuro; pantalla completa                                                            | 1       |
| RF-LEC-03 | Cada palabra se puede tocar: vistazo al pasar el cursor, hoja completa al hacer clic o tocar                         | 1       |
| RF-LEC-04 | Teclado: un solo tabstop para el texto, flechas por palabras y oraciones, Intro abre                                 | 1       |
| RF-LEC-05 | Diccionario en contexto: lema, forma flexionada, traducción, clase, IPA, gramática, ejemplos                         | 1       |
| RF-LEC-06 | Traducción de oración y de párrafo a petición, escrita por una persona; nunca palabra a palabra                      | 1       |
| RF-LEC-07 | Ayudas: mostrar traducciones de párrafos (apagado por defecto), resaltar palabras guardadas                          | 1       |
| RF-LEC-08 | Resaltar palabras desconocidas y aprendidas                                                                          | 4       |
| RF-LEC-09 | Avance, tiempo restante, posición guardada y restaurada al volver                                                    | 1       |
| RF-LEC-10 | Marcar para después, guardar una oración con nota, terminar la lectura                                               | 1       |
| RF-LEC-11 | Libro ilustrado: página a página (doble página en escritorio), maqueta por página, ilustración con texto alternativo | 7       |

### Audio sincronizado (fase 3)

RF-AUD-01 reproductor completo (velocidad, saltos, párrafo anterior y siguiente); RF-AUD-02 resaltado de la palabra y
la oración habladas con tiempos por palabra; RF-AUD-03 reproducir desde una palabra o un párrafo; RF-AUD-04 el lector
funciona igual sin audio o mientras carga.

### Comprensión (fase 4)

RF-COM-01 preguntas de diez tipos con explicación, pasaje de referencia, dificultad y habilidad; RF-COM-02 evaluación
determinista separada de la asistida por IA, siempre con referencia al texto; RF-COM-03 intentos guardados contra la
versión leída.

### Diccionario

| Id        | Requisito                                                                                       | Entrega |
| --------- | ----------------------------------------------------------------------------------------------- | ------- |
| RF-DIC-01 | Búsqueda en los dos sentidos (lema o forma del idioma de estudio, o traducción) con sugerencias | 1       |
| RF-DIC-02 | Página de entrada: acepciones, traducciones, ejemplos, sinónimos, antónimos, relacionadas, uso  | 1       |
| RF-DIC-03 | Historial de búsquedas y favoritas                                                              | 1       |
| RF-DIC-04 | «Aparece en»: lecturas que contienen la entrada                                                 | 1       |
| RF-DIC-05 | Sin entrada, se dice; nunca se inventa una traducción                                           | 1       |

### Vocabulario

| Id        | Requisito                                                                               | Entrega |
| --------- | --------------------------------------------------------------------------------------- | ------- |
| RF-VOC-01 | Guardar una palabra desde la lectura con su oración, su lectura y su versión            | 1       |
| RF-VOC-02 | Cuaderno con búsqueda, nota personal y quitar                                           | 1       |
| RF-VOC-03 | Estados encontrada, guardada, aprendiendo, conocida, dominada; guardada no es recordada | 4       |
| RF-VOC-04 | Repaso espaciado documentado (FSRS) y ejercicios de reconocimiento y recuerdo           | 4       |

### Progreso, motivación y edición

RF-PRO (fase 5): panel de lectura, vocabulario y comprensión que distingue lo medido de lo estimado; metas diarias,
rachas amables que no castigan, logros. RF-EDI (fase 6): estudio editorial con borrador, revisión, publicación
versionada, traducciones, audio y alineación, preguntas y validación antes de publicar. RF-EDI (fase 7): libros
ilustrados con páginas, maquetas e ilustraciones (ADR-0014), y su generación desde el estudio.

## Requisitos no funcionales

- **Accesibilidad:** WCAG 2.2 AA (contraste comprobado por prueba, foco visible, objetivos de 44 px, movimiento reducido).
- **Responsive:** 360, 390, 768, 1024, 1440 y 1920 px sin desbordamiento horizontal.
- **Privacidad:** nadie ve la actividad ni el vocabulario de otra persona; lo ajeno es 404.
- **Rendimiento:** el texto de una versión es inmutable y se guarda en caché para siempre; el lector pinta por párrafo.
- **Proveedores:** nada básico depende de IA ni de servicios externos; todo proveedor tiene un respaldo determinista.

## Alcance de la primera entrega (fases 0–2)

Implementado: onboarding y perfil, portada editorial, catálogo con filtros, ficha, lector inmersivo completo (palabra a
palabra, diccionario en contexto, traducciones a petición, ayudas, temas, notas, marcador, posición, terminar),
biblioteca, diccionario bilingüe y cuaderno de vocabulario básico; 11 lecturas originales (8 en alemán, 3 en inglés)
con 825 entradas de diccionario curadas.

Fuera de esta entrega, con su diseño en `docs/architecture/domain-model.md`: audio (3), comprensión y repaso (4),
panel y logros (5), estudio editorial (6). El alta de cuentas la hace hoy una persona administradora con la API de
usuarios de Wasichai: Wasichai no tiene registro público (`docs/architecture/overview.md` §6).
