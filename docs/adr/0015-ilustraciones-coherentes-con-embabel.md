# ADR-0015 — Ilustraciones coherentes con Embabel y proveedores de imagen detrás de una interfaz

Estado: aceptada (2026-11-10)

## Contexto

Un libro ilustrado (ADR-0014) se genera con su texto (ADR-0013) y necesita ilustraciones. Lo que producto pide es
coherencia: los mismos personajes con el mismo aspecto, un estilo y una paleta, y continuidad de una página a la
siguiente. No sirve pedir una imagen al azar a partir del texto de cada página. Claude no genera imágenes; hay que usar
un proveedor de imágenes (OpenAI gpt-image o el modelo de imagen de Gemini), elegible por configuración. Wasichai ya
ejecuta su asistente sobre Embabel (ADR-015 de Wasichai, `wasichai-agent`), y su propia ADR anota que el marco compensa
cuando hay acciones que encadenan llamadas a un modelo. Es el caso.

## Decisión

- **Dos encargos, un formato.** Los libros (y las lecturas) se encargan desde el estudio y terminan en un borrador
  `DRAFT` que sigue la revisión y la publicación de siempre (ADR-0012), con el evento `GENERATED`. Las lecturas a medida
  para quien aprende son ADR-0016.
- **La cola de encargos** (`lesen.generation_job`, V9) es reactiva, con `SELECT … FOR UPDATE SKIP LOCKED`:
  - estados `QUEUED → WRITING → ILLUSTRATING → READY | FAILED`;
  - el motivo de fallo (`REFUSED`, `UNREADABLE`, `CHECKS`, `ERROR`) y lo que encontró la comprobación;
  - lo que costó;
  - la biblia visual con que se ilustró.
  - Un encargo colgado vuelve a la cola y, al tercer intento, falla. Los trabajadores son
    `lesen.generation.workers`.
- **El texto lo sigue escribiendo el `Generator` de ADR-0013.** Para un libro, el esquema pide además:
  - `book.layout` y `book.pages`, cada página con `span`, maqueta, párrafos, `scene`, personajes y `alt` por idioma de
    interfaz;
  - `book.art`, la biblia visual: estilo, paleta, lugar y un aspecto fijo por personaje.
- **Comprobaciones del libro:**
  - de 4 a 16 páginas;
  - palabras por página según el nivel (A1 40, A2 60, B1 90, B2 120, C1 y C2 160);
  - escena en cada página;
  - personajes declarados en la biblia;
  - texto alternativo en cada idioma.
  - El ensamblado ya comprueba que las páginas cuadren con los párrafos y que cada maqueta quepa en su ancho.
- **Proveedor de imágenes detrás de `ImageProvider` (ADR-0005)**, por `lesen.illustration.provider`:
  - `none` (por defecto): no se dibuja nada y las ilustraciones quedan pendientes. El libro no se publica sin ellas, y el
    estudio permite subirlas o volver a dibujarlas.
  - `fake`: imágenes de prueba deterministas, para desarrollo y pruebas.
  - `openai` (gpt-image, `OPENAI_API_KEY`): con referencias usa la edición, que las toma como imágenes de partida.
  - `gemini` (`gemini-2.5-flash-image`, `GEMINI_API_KEY`): las referencias van en el mismo mensaje.
  - Imagen (`imagen-*:predict`) no se usa porque no admite referencias, y sin ellas no hay coherencia.
  - Un fallo o una negativa del proveedor es un fallo de esa página: se dice, nunca se rellena.
- **La coherencia sale del recorrido, no del azar** (`IllustrationSteps` e `IllustrationPipeline`):
  1. una biblia visual;
  2. una hoja de referencia por personaje y un fotograma de estilo;
  3. un plano por página que nombra lo que pasó antes y lo que viene;
  4. cada página dibujada en orden con las hojas de quien sale, la ilustración anterior aceptada y el fotograma de
     estilo como referencias, y la proporción que fija su maqueta;
  5. una revisión por página; la que no casa se vuelve a dibujar con sus problemas, hasta
     `lesen.illustration.max-retries` veces.
- **Embabel dirige el arte cuando hay modelo** (`IllustrationAgent`, Embabel 1.5.4, la versión de Wasichai 1.10.0). Son
  tres acciones tipadas que el planificador encadena: `IllustrationBrief → ArtBible → ShotList → IllustratedBook`.
  - Claude afina la biblia y escribe los planos con la continuidad y el texto alternativo.
  - En la revisión, Claude mira con visión cada página frente a sus referencias.
  - Las imágenes las dibuja el proveedor.
  - El modelo es el de `LESEN_AI_MODEL`.
  - Sin clave de Anthropic, con `lesen.illustration.agent=false` o si el agente falla, el libro se ilustra con la biblia
    y las escenas que escribió la generación del texto, sin revisión. El encargo dice quién dirigió el arte
    (`directedBy`).
- **Embabel sin romper el arranque:**
  - se usa el artefacto `embabel-agent-starter` (con su proveedor de Anthropic), no
    `wasichai-spring-boot-starter-agent`: ese starter añade el asistente de Wasichai con sus rutas, y si se apaga
    desactiva también Embabel;
  - `IllustrationGate`, un `EnvironmentPostProcessor` con la misma idea que `EmbabelGate` pero sin depender de esa
    clase, saca del contexto las autoconfiguraciones de Embabel si no hay clave, proveedor o dirección de arte. Así
    Lesen arranca entera sin claves.
  - Embabel no despliega agentes con un perfil llamado exactamente `test`; Lesen no lo usa.
- **Volver a dibujar una página** desde el estudio usa la biblia del encargo, las hojas de sus personajes y la
  ilustración anterior, y conserva el texto alternativo.
- **Capacidades nuevas:**
  - `illustration` (depende de `common` y `reading`): proveedores, pasos y agente;
  - `commission` (depende de `common`, `language`, `reading`, `assessment`, `generation`, `illustration` y
    `editorial`): la cola, el trabajador y los endpoints;
  - `generation` sigue sin depender de nadie.

## Consecuencias

- Con `provider=none` la aplicación funciona igual. Los libros generados llegan al estudio con sus escenas y sus textos
  alternativos y con las ilustraciones pendientes.
- El coste de un libro es el texto (ADR-0013) más una imagen por personaje, una de estilo y una o más por página, y,
  con el agente, tres llamadas a Claude más una revisión por página. El encargo guarda los tokens y las ilustraciones
  dibujadas; la evaluación (`generationEval`) mide el resto.
- Embabel añade su árbol de dependencias (MCP, Spring AI). `ArchitectureTest` y `noServletStack` siguen en verde: no trae
  pila de servlets ni JDBC bloqueante.
- Las traducciones de un borrador generado entran como las de una autora: una persona las revisa antes de publicar
  (ADR-0012). Las de una lectura a medida, sin revisión, son de proveedor (ADR-0016).
