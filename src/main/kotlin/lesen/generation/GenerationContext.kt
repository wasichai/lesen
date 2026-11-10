package lesen.generation

import lesen.assessment.QuestionType
import lesen.reading.ReadingFormat

// el tipo de texto que se pide
enum class TextKind { STORY, ARTICLE, DIALOGUE, LETTER }

// una palabra que la persona está aprendiendo y que la lectura debe usar: su entrada del diccionario, el lema y las
// formas que el diccionario reconoce (una forma fuera de la lista no resuelve a la entrada y no cuenta)
data class TargetWord(
    val entry: String,
    val lemma: String,
    val forms: List<String> = emptyList(),
)

// lo que pide la persona: los controles (con los valores de su perfil ya puestos) y, si quiere, un prompt libre
data class GenerationRequest(
    val language: String,
    val level: String,
    val minutes: Int,
    val kind: TextKind,
    val topics: List<String> = emptyList(),
    val prompt: String? = null,
    // un texto o un libro ilustrado, con sus páginas, maquetas y la biblia visual para ilustrarlo (ADR-0014, ADR-0015)
    val format: ReadingFormat = ReadingFormat.TEXT,
)

// el «perfil de escritura» (ADR-0013): todo lo que el modelo sabe de la persona, compacto y calculado por el código. Ni
// nombre, ni correo, ni nada que la identifique. El modelo no consulta la base
data class GenerationContext(
    val request: GenerationRequest,
    // palabras por minuto del nivel (lesen.cefr_level), para el largo
    val wordsPerMinute: Int,
    // los idiomas de interfaz a los que se traduce cada oración (todos menos el de la lectura)
    val translateInto: List<String>,
    // los idiomas en que se escriben las preguntas (todos los de interfaz)
    val questionLanguages: List<String>,
    // los temas de la instalación: la lectura se clasifica en uno o más
    val topics: List<String>,
    val targetWords: List<TargetWord> = emptyList(),
    // lemas con entrada en el diccionario curado del idioma: con ellos el lector tiene diccionario de verdad
    val dictionaryLemmas: List<String> = emptyList(),
    // lemas que la persona ya vio, los más frecuentes primero: la base del texto
    val knownLemmas: List<String> = emptyList(),
    // las entradas que la persona ya vio: para medir cuánto es nuevo para ella
    val knownEntries: Set<String> = emptySet(),
    // títulos de lo que leyó hace poco: no repetir argumentos
    val recentTitles: List<String> = emptyList(),
    // los tipos de pregunta en que le va peor: incluir alguno
    val weakQuestionTypes: List<QuestionType> = emptyList(),
) {
    // el largo pedido en palabras
    val targetWordCount get() = request.minutes * wordsPerMinute

    val book get() = request.format == ReadingFormat.PICTURE_BOOK

    companion object {
        const val MAX_PROMPT = 500
        const val MAX_TARGET_WORDS = 8
        const val MAX_KNOWN_LEMMAS = 400
        const val MAX_DICTIONARY_LEMMAS = 2000
        const val MAX_RECENT_TITLES = 10
        val MINUTES = setOf(2, 5, 8)
    }

    init {
        require(request.minutes in MINUTES) { "minutes must be one of $MINUTES" }
        require((request.prompt?.length ?: 0) <= MAX_PROMPT) { "prompt longer than $MAX_PROMPT" }
        require(targetWords.size <= MAX_TARGET_WORDS) { "at most $MAX_TARGET_WORDS target words" }
        require(dictionaryLemmas.size <= MAX_DICTIONARY_LEMMAS) { "at most $MAX_DICTIONARY_LEMMAS dictionary lemmas" }
        require(knownLemmas.size <= MAX_KNOWN_LEMMAS) { "at most $MAX_KNOWN_LEMMAS known lemmas" }
        require(topics.isNotEmpty()) { "the installation has no topics" }
        require(request.language !in translateInto) { "a reading is not translated into its own language" }
    }
}
