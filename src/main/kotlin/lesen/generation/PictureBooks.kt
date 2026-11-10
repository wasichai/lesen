package lesen.generation

// las medidas de un libro ilustrado generado (ADR-0014, ADR-0015): cuántas páginas y cuántas palabras por página según
// el nivel. Valores iniciales que ajusta la evaluación
object PictureBooks {
    val PAGES = 4..16

    private val WORDS_PER_PAGE = mapOf("A1" to 40, "A2" to 60, "B1" to 90, "B2" to 120, "C1" to 160, "C2" to 160)

    fun wordsPerPage(level: String): Int = WORDS_PER_PAGE.getValue(level)
}
