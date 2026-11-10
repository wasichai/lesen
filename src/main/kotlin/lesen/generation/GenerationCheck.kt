package lesen.generation

import lesen.assessment.Question
import lesen.assessment.QuestionCheck
import lesen.reading.AssembledVersion
import lesen.reading.ReadingTime
import lesen.reading.TokenKind
import lesen.reading.Tokenizer
import wasichai.core.common.FieldViolation
import kotlin.math.roundToInt

// un fallo de la comprobación, en palabras que entienden tanto la persona que revisa como el modelo que corrige
data class Finding(
    val code: String,
    val ref: String? = null,
    val detail: String? = null,
)

// lo que se midió de una lectura generada: la evaluación (fase 1) lo resume y la petición lo guarda (fase 2)
data class Measures(
    val words: Int,
    val minutes: Int,
    val sentences: Int,
    val averageSentence: Double,
    val longestSentence: Int,
    // palabras (tokens) con entrada en el diccionario o nombres propios declarados
    val covered: Double,
    // formas distintas sin entrada, sin contar los nombres propios
    val unresolvedForms: Double,
    // palabras con entrada que la persona no ha visto nunca, sin contar las objetivo
    val newToLearner: Double,
    val targetUses: Map<String, Int>,
    val questions: Int,
    val questionTypes: Int,
)

data class CheckResult(
    val findings: List<Finding>,
    val measures: Measures?,
) {
    val passed get() = findings.isEmpty()
}

// el ritmo de frases de cada nivel: media y máximo de palabras por oración. Valores iniciales, que la evaluación ajusta
object LevelFit {
    data class Range(
        val average: Double,
        val longest: Int,
    )

    val RANGES =
        mapOf(
            "A1" to Range(10.0, 16),
            "A2" to Range(12.0, 20),
            "B1" to Range(16.0, 28),
            "B2" to Range(20.0, 35),
            "C1" to Range(25.0, 45),
            "C2" to Range(30.0, 60),
        )

    fun range(level: String): Range = RANGES.getValue(level)
}

// las comprobaciones antes de entregar una lectura generada (ADR-0013). Pura: las mismas reglas que el estudio
// (ReadingAssembler, QuestionCheck, traducciones) más el largo, el nivel, el vocabulario y las palabras objetivo
object GenerationCheck {
    data class Thresholds(
        val lengthTolerance: Double = 0.25,
        val minCovered: Double = 0.90,
        val maxUnresolvedForms: Double = 0.05,
        val minTargetUses: Int = 2,
        val minQuestions: Int = 4,
        val maxQuestions: Int = 7,
        val minQuestionTypes: Int = 3,
    )

    fun check(
        context: GenerationContext,
        generated: GeneratedReading,
        assembled: AssembledVersion?,
        fieldProblems: List<FieldViolation>,
        questions: List<Question>,
        wordProblems: List<Pair<String, String>>,
        thresholds: Thresholds = Thresholds(),
    ): CheckResult {
        val findings = fieldProblems.map { Finding("FIELD", it.field, it.message) }.toMutableList()
        if (generated.topics.none { it in context.topics }) findings += Finding("TOPICS", null, "choose at least one topic from the list")
        if (assembled == null) return CheckResult(findings, null)
        val content = assembled.content
        val words = content.words
        val level = LevelFit.range(context.request.level)

        // el largo, en minutos de lectura del nivel
        val target = context.targetWordCount
        val low = (target * (1 - thresholds.lengthTolerance)).roundToInt()
        val high = (target * (1 + thresholds.lengthTolerance)).roundToInt()
        if (words.size !in low..high) findings += Finding("LENGTH", null, "${words.size} words; write between $low and $high")

        // el nivel: el largo de las oraciones
        val lengths = content.sentences.map { s -> s.tokens.count { it.kind == TokenKind.WORD } }
        val average = if (lengths.isEmpty()) 0.0 else lengths.average()
        if (average > level.average) findings += Finding("SENTENCES_TOO_LONG", null, "average ${"%.1f".format(average)} words; at most ${level.average}")
        content.sentences.zip(lengths).filter { (_, n) -> n > level.longest }.forEach { (s, n) ->
            findings += Finding("SENTENCE_TOO_LONG", s.id, "$n words; at most ${level.longest}")
        }

        // el vocabulario: con entrada en el diccionario, o un nombre propio
        val names =
            generated.names
                .flatMap {
                    Tokenizer.WORD_PATTERN
                        .findAll(it)
                        .map { m -> Tokenizer.normalize(m.value) }
                        .toList()
                }.toSet()
        val covered = words.count { it.entry != null || Tokenizer.normalize(it.text) in names }
        val coveredShare = if (words.isEmpty()) 0.0 else covered.toDouble() / words.size
        val forms = words.map { Tokenizer.normalize(it.text) }.toSet()
        val unresolved =
            words
                .filter { it.entry == null }
                .map { Tokenizer.normalize(it.text) }
                .filter { it !in names }
                .toSortedSet()
        val unresolvedShare = if (forms.isEmpty()) 0.0 else unresolved.size.toDouble() / forms.size
        if (coveredShare < thresholds.minCovered || unresolvedShare > thresholds.maxUnresolvedForms) {
            findings += Finding("VOCABULARY", null, "words outside the dictionary: ${unresolved.take(40).joinToString(", ")}")
        }
        val targets = context.targetWords.map { it.entry }.toSet()
        val withEntry = words.filter { it.entry != null && it.entry !in targets }
        val newShare = if (withEntry.isEmpty()) 0.0 else withEntry.count { it.entry !in context.knownEntries }.toDouble() / withEntry.size

        // las palabras objetivo: cada una, al menos dos veces y en una forma que el diccionario reconoce
        val uses = context.targetWords.associate { t -> t.lemma to words.count { it.entry == t.entry } }
        context.targetWords.filter { uses.getValue(it.lemma) < thresholds.minTargetUses }.forEach {
            findings += Finding("TARGET_WORD", it.lemma, "used ${uses.getValue(it.lemma)} times; use it at least ${thresholds.minTargetUses} times")
        }

        // las traducciones de cada oración, en cada idioma de interfaz
        val translated =
            assembled.translations
                .filter { it.unit == "SENTENCE" && it.text.isNotBlank() }
                .map { it.ref to it.language }
                .toSet()
        for (language in context.translateInto) {
            content.sentences.filter { (it.id to language) !in translated }.forEach { findings += Finding("TRANSLATION", it.id, language) }
        }

        // las preguntas: cuántas, de cuántos tipos, en qué idiomas y que citen lo que existe
        if (questions.size !in thresholds.minQuestions..thresholds.maxQuestions) {
            findings += Finding("QUESTIONS", null, "${questions.size} questions; write ${thresholds.minQuestions}–${thresholds.maxQuestions}")
        }
        val types = questions.map { it.type }.toSet()
        if (types.size <
            thresholds.minQuestionTypes
        ) {
            findings += Finding("QUESTION_TYPES", null, "${types.size} types; use at least ${thresholds.minQuestionTypes}")
        }
        generated.questions.filter { it.type !in GeneratedDrafts.TYPES }.forEach { findings += Finding("QUESTION", it.id, "type ${it.type} is not allowed") }
        wordProblems.forEach { (id, message) -> findings += Finding("QUESTION", id, message) }
        QuestionCheck.problems(questions, content).forEach { findings += Finding("QUESTION", it.question, it.message) }
        for (q in questions) {
            for (language in context.questionLanguages) {
                if (q.prompt[language].isNullOrBlank()) findings += Finding("QUESTION", q.id, "prompt missing in $language")
                if (q.explanation[language].isNullOrBlank()) findings += Finding("QUESTION", q.id, "explanation missing in $language")
            }
        }

        findings += bookFindings(context, generated, content)

        val measures =
            Measures(
                words = words.size,
                minutes = ReadingTime.minutes(words.size, context.wordsPerMinute),
                sentences = content.sentences.size,
                averageSentence = average,
                longestSentence = lengths.maxOrNull() ?: 0,
                covered = coveredShare,
                unresolvedForms = unresolvedShare,
                newToLearner = newShare,
                targetUses = uses,
                questions = questions.size,
                questionTypes = types.size,
            )
        return CheckResult(findings.distinct(), measures)
    }

    // un libro ilustrado (ADR-0014, ADR-0015): sus páginas, las palabras de cada una y lo que la dirección de arte necesita.
    // Que las páginas cuadren con los párrafos y que cada maqueta quepa en su ancho ya lo dice el ensamblado (FIELD)
    private fun bookFindings(
        context: GenerationContext,
        generated: GeneratedReading,
        content: lesen.reading.ReadingContent,
    ): List<Finding> {
        val book = generated.book
        if (!context.book) return if (book != null) listOf(Finding("BOOK", null, "this reading is not a picture book: leave book out")) else emptyList()
        if (book == null) return listOf(Finding("BOOK", null, "a picture book needs its book: layout, art and pages"))
        val findings = mutableListOf<Finding>()
        if (book.pages.size !in PictureBooks.PAGES) {
            findings += Finding("PAGES", null, "${book.pages.size} pages; write ${PictureBooks.PAGES.first}–${PictureBooks.PAGES.last}")
        }
        if (book.art.style.isBlank() || book.art.palette.isBlank()) findings += Finding("ART", null, "describe the style and the palette")
        val cast =
            book.art.characters
                .map { it.name.trim().lowercase() }
                .toSet()
        book.art.characters
            .filter { it.look.isBlank() }
            .forEach { findings += Finding("ART", it.name, "describe how this character looks") }
        val limit = PictureBooks.wordsPerPage(context.request.level)
        val pages = content.pages.orEmpty()
        book.pages.forEachIndexed { i, page ->
            val ref = "g${i + 1}"
            val words =
                pages
                    .getOrNull(i)
                    ?.paragraphs
                    ?.sumOf { id ->
                        content.paragraphs
                            .first { it.id == id }
                            .sentences
                            .sumOf { s -> s.tokens.count { it.kind == TokenKind.WORD } }
                    }
                    ?: 0
            if (words > limit) findings += Finding("PAGE_TOO_LONG", ref, "$words words; at most $limit per page")
            if (page.scene.isBlank()) findings += Finding("SCENE", ref, "describe what the illustration shows")
            page.characters.filter { it.trim().lowercase() !in cast }.forEach { findings += Finding("CHARACTER", ref, "$it is not in book.art.characters") }
            for (language in context.questionLanguages) {
                if (page.alt[language].isNullOrBlank()) findings += Finding("ALT", ref, "alt text missing in $language")
            }
        }
        return findings
    }
}
