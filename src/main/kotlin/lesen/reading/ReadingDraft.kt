package lesen.reading

import lesen.common.requireLength
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.security.MessageDigest

// lo que se publica: hoy lo escribe el contenido de demostración (docs/content/demo-content.md); en la fase 6, el
// estudio editorial. Las oraciones las fija la autora; las traducciones son por unidad y por idioma de interfaz
data class ReadingDraft(
    val slug: String,
    val language: String,
    val level: String,
    val topics: List<String>,
    val featured: Boolean = false,
    val cover: String? = null,
    val accent: String? = null,
    val title: String,
    val subtitle: String? = null,
    val summary: String,
    val paragraphs: List<DraftParagraph>,
)

data class DraftParagraph(
    val sentences: List<DraftSentence>,
    val translations: Map<String, String> = emptyMap(),
)

// senses: forma de esta oración → entrada del diccionario, para homógrafos (Bank → de:bank#seat)
data class DraftSentence(
    val text: String,
    val translations: Map<String, String> = emptyMap(),
    val senses: Map<String, String> = emptyMap(),
)

// la forma → la entrada por defecto del diccionario del idioma de la lectura; null si no hay ninguna
fun interface FormResolver {
    fun resolve(normalizedForm: String): String?
}

data class TranslationUnit(
    val unit: String,
    val ref: String,
    val language: String,
    val text: String,
)

data class AssembledVersion(
    val content: ReadingContent,
    val translations: List<TranslationUnit>,
    val wordCount: Int,
    val entryOccurrences: Map<String, Int>,
    // formas sin entrada en el diccionario: la versión se publica igual y el lector dice con honestidad «sin entrada»
    val unresolved: Set<String>,
    val hash: String,
)

// la parte pura de publicar: valida el borrador, tokeniza y resuelve cada palabra contra el diccionario
object ReadingAssembler {
    private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val LEVELS = setOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val ACCENT = Regex("^#[0-9A-Fa-f]{6}$")

    fun validate(draft: ReadingDraft) {
        val problems = mutableListOf<FieldViolation>()
        if (!SLUG.matches(draft.slug)) problems += FieldViolation("slug", "must be lowercase words joined by hyphens")
        if (draft.level !in LEVELS) problems += FieldViolation("level", "must be a CEFR level A1–C2")
        if (draft.title.isBlank()) problems += FieldViolation("title", "must not be blank")
        if (draft.summary.isBlank()) problems += FieldViolation("summary", "must not be blank")
        if (draft.topics.isEmpty()) problems += FieldViolation("topics", "must name at least one topic")
        if (draft.paragraphs.isEmpty()) problems += FieldViolation("paragraphs", "must have at least one paragraph")
        if (draft.accent != null && !ACCENT.matches(draft.accent)) problems += FieldViolation("accent", "must be a #RRGGBB color")
        draft.paragraphs.forEachIndexed { p, paragraph ->
            if (paragraph.sentences.isEmpty()) problems += FieldViolation("paragraphs[$p]", "must have at least one sentence")
            paragraph.sentences.forEachIndexed { s, sentence ->
                if (sentence.text.isBlank()) problems += FieldViolation("paragraphs[$p].sentences[$s]", "must not be blank")
            }
        }
        if (problems.isNotEmpty()) throw ValidationException("Invalid reading", problems)
        requireLength(draft.title, "title", 200)
        requireLength(draft.subtitle, "subtitle", 300)
        requireLength(draft.summary, "summary", 1000)
    }

    fun assemble(
        draft: ReadingDraft,
        tokenizer: Tokenizer,
        resolver: FormResolver,
    ): AssembledVersion {
        validate(draft)
        val translations = mutableListOf<TranslationUnit>()
        val occurrences = mutableMapOf<String, Int>()
        val unresolved = sortedSetOf<String>()
        val paragraphs =
            draft.paragraphs.mapIndexed { p, paragraph ->
                val paragraphId = "p${p + 1}"
                paragraph.translations.forEach { (language, text) -> translations += TranslationUnit("PARAGRAPH", paragraphId, language, text) }
                val sentences =
                    paragraph.sentences.mapIndexed { s, draftSentence ->
                        val sentenceId = "${paragraphId}s${s + 1}"
                        val text = draftSentence.text.trim()
                        draftSentence.translations.forEach { (language, value) -> translations += TranslationUnit("SENTENCE", sentenceId, language, value) }
                        val overrides = draftSentence.senses.mapKeys { Tokenizer.normalize(it.key) }
                        val tokens =
                            tokenizer.tokenize(sentenceId, text).map { token ->
                                if (token.kind != TokenKind.WORD) return@map token
                                val form = Tokenizer.normalize(token.text)
                                val entry = overrides[form] ?: resolver.resolve(form)
                                if (entry == null) unresolved += token.text else occurrences.merge(entry, 1, Int::plus)
                                token.copy(entry = entry)
                            }
                        Sentence(sentenceId, text, tokens)
                    }
                Paragraph(paragraphId, sentences)
            }
        val content = ReadingContent(paragraphs)
        return AssembledVersion(content, translations, content.words.size, occurrences, unresolved, hash(draft))
    }

    // la huella de lo que la persona lee: publicar dos veces lo mismo no crea otra versión
    fun hash(draft: ReadingDraft): String {
        val canonical =
            lesen.common.Jsonb.MAPPER
                .writeValueAsString(draft.copy(featured = false, topics = draft.topics.sorted()))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
