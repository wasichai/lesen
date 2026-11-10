package lesen.illustration

import lesen.reading.PageLayout

// lo que se ilustra (ADR-0015): un libro con su biblia visual y la escena de cada página, en orden de lectura. Lo arma
// quien encarga el libro (la generación del texto o el estudio) y no lleva nada de la persona que lo pidió
data class IllustrationBrief(
    val title: String,
    val language: String,
    val level: String,
    val art: Art,
    val pages: List<BriefPage>,
    // los idiomas de interfaz: el texto alternativo de cada ilustración va en todos
    val altLanguages: List<String>,
)

data class Art(
    val style: String,
    val palette: String,
    val setting: String,
    val characters: List<CharacterSheet>,
)

// un personaje con su aspecto fijo: el mismo en todas las páginas
data class CharacterSheet(
    val name: String,
    val look: String,
)

// una página que lleva ilustración: su índice en el libro, su maqueta y ancho, lo que pasa, quién sale, el texto que
// acompaña (en el idioma de la lectura) y el texto alternativo que trae, si lo trae
data class BriefPage(
    val index: Int,
    val layout: PageLayout,
    val span: Int,
    val scene: String,
    val characters: List<String>,
    val text: String,
    val alt: Map<String, String>,
)

// la biblia visual que siguen todas las ilustraciones: estilo, paleta, luz y una hoja por personaje
data class ArtBible(
    val style: String,
    val palette: String,
    val lighting: String,
    val setting: String,
    val characters: List<CharacterSheet>,
)

// la proporción de una ilustración, según su maqueta y su ancho (docs/design-system/lesen del frontend, tokens book)
enum class Aspect(
    val width: Int,
    val height: Int,
) {
    PORTRAIT(3, 4),
    LANDSCAPE(4, 3),
    WIDE(3, 2),
    PANORAMA(12, 5),
    SQUARE(1, 1),
    ;

    val ratio get() = "$width:$height"
}

// el plano de una página: qué se dibuja, con quién, en qué encuadre y el texto alternativo que tendrá
data class Shot(
    val page: Int,
    val description: String,
    val characters: List<String>,
    val framing: String,
    val aspect: Aspect,
    val alt: Map<String, String>,
)

data class ShotList(
    val shots: List<Shot>,
)

// una imagen y su tipo
class Picture(
    val contentType: String,
    val bytes: ByteArray,
)

// las hojas de referencia: una por personaje, que acompañan cada página donde sale, y un fotograma de estilo
data class ReferenceSet(
    val characters: Map<String, Picture>,
    val style: Picture?,
)

// el resultado de una página: su imagen, o por qué no la hay (nunca una inventada)
data class PageIllustration(
    val page: Int,
    val picture: Picture?,
    val alt: Map<String, String>,
    val problem: String? = null,
    val attempts: Int = 1,
)

data class IllustratedBook(
    val bible: ArtBible,
    val pages: List<PageIllustration>,
    // quién dirigió el arte: el agente con Claude o la biblia que escribió la generación del texto
    val directedBy: String,
)

// lo que la revisión con visión dice de una página frente a las referencias
data class Verdict(
    val consistent: Boolean,
    val problems: List<String> = emptyList(),
)
