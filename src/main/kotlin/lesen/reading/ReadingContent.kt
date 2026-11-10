package lesen.reading

// el contenido congelado de una versión publicada (ADR-0004). Los ids son estables dentro de la versión: p3 (párrafo),
// p3s2 (oración), p3s2t5 (token). Lo que guarda la persona (posición, notas, vocabulario) apunta a ellos junto con el
// número de versión, y la sincronización de audio (fase 3) les asignará tiempos sin tocar el texto
// format, layout y pages: los de un libro ilustrado (ADR-0014); un texto no los lleva
data class ReadingContent(
    val paragraphs: List<Paragraph>,
    val format: ReadingFormat = ReadingFormat.TEXT,
    val layout: PageLayout? = null,
    val pages: List<Page>? = null,
    // imágenes entre párrafos de un texto (ADR-0018); null si no tiene
    val images: List<InlineImage>? = null,
) {
    val sentences: List<Sentence> get() = paragraphs.flatMap { it.sentences }
    val words: List<Token> get() = sentences.flatMap { s -> s.tokens.filter { it.kind == TokenKind.WORD } }

    fun token(id: String): Pair<Sentence, Token>? {
        for (sentence in sentences) {
            sentence.tokens.firstOrNull { it.id == id }?.let { return sentence to it }
        }
        return null
    }

    fun sentence(id: String): Sentence? = sentences.firstOrNull { it.id == id }
}

data class Paragraph(
    val id: String,
    val sentences: List<Sentence>,
)

data class Sentence(
    val id: String,
    val text: String,
    val tokens: List<Token>,
)

enum class TokenKind { WORD, PUNCT }

// start/end: posición en el texto de la oración (UTF-16, como String). space: hay espacio después, para reconstruir
// el texto exacto. entry: la entrada del diccionario que la publicación resolvió para esta forma, o null si no hay
data class Token(
    val id: String,
    val text: String,
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val space: Boolean,
    val entry: String? = null,
)
