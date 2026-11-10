package lesen.editorial

import lesen.assessment.Question
import lesen.assessment.QuestionCheck
import lesen.assessment.QuestionType
import lesen.audio.WordTiming
import lesen.reading.DraftParagraph
import lesen.reading.DraftSentence
import lesen.reading.FormResolver
import lesen.reading.ReadingAssembler
import lesen.reading.ReadingDraft
import lesen.reading.UnicodeTokenizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.common.FieldViolation

// lo que impide publicar un borrador y lo que solo conviene mirar
class DraftCheckTest {
    private val document =
        ReadingDraft(
            slug = "der-test",
            language = "de",
            level = "A1",
            topics = listOf("nature"),
            title = "Test",
            summary = "Kurz.",
            paragraphs =
                listOf(
                    DraftParagraph(
                        listOf(
                            DraftSentence("Mia wohnt am Meer.", mapOf("es" to "Mia vive junto al mar.", "en" to "Mia lives by the sea.")),
                            DraftSentence("Das Haus ist rot.", mapOf("es" to "La casa es roja.")),
                        ),
                        mapOf("es" to "Mia vive junto al mar. La casa es roja.", "en" to "Mia lives by the sea. The house is red."),
                    ),
                ),
        )
    private val known = setOf("mia", "wohnt", "am", "meer", "das", "haus", "ist")
    private val assembled = ReadingAssembler.assemble(document, UnicodeTokenizer(), FormResolver { if (it in known) "de:$it" else null })

    private fun tf(
        id: String = "q1",
        refs: List<String> = listOf("p1s1"),
    ) = Question(
        id,
        QuestionType.TRUE_FALSE,
        1,
        mapOf("es" to "¿Vive junto al mar?", "en" to "By the sea?"),
        mapOf("es" to "Sí.", "en" to "Yes."),
        refs,
        mapOf("correct" to true),
    )

    private fun input(
        questions: List<Question> = listOf(tf()),
        audio: DraftAudio? = null,
        unchanged: Boolean = false,
        currentQuestions: List<Question>? = null,
        answered: Boolean = false,
        problems: List<FieldViolation> = emptyList(),
    ) = CheckInput("de", listOf("es", "en"), problems, if (problems.isEmpty()) assembled else null, questions, audio, unchanged, currentQuestions, answered)

    @Test
    fun `a draft with warnings only is ready, and each warning says where`() {
        val report = DraftCheck.report(input())
        assertThat(report.ready).isTrue()
        assertThat(report.errors).isEmpty()
        assertThat(report.warnings).contains(Issue("UNRESOLVED", "rot"), Issue("TRANSLATION_MISSING", "p1s2", "en"), Issue("NO_AUDIO"))
        assertThat(report.missingTranslations).isEqualTo(1)
        assertThat(report.words).isEqualTo(8)
        assertThat(report.sentences).isEqualTo(2)
    }

    @Test
    fun `invalid fields stop everything else`() {
        val report = DraftCheck.report(input(problems = listOf(FieldViolation("summary", "must not be blank"))))
        assertThat(report.ready).isFalse()
        assertThat(report.errors).containsExactly(Issue("FIELD", "summary", "must not be blank"))
    }

    @Test
    fun `questions must cite what exists in this text`() {
        val choice =
            Question(
                "q2",
                QuestionType.VOCABULARY_IN_CONTEXT,
                2,
                mapOf("es" to "¿Qué es «Haus»?"),
                emptyMap(),
                listOf("p1s2"),
                mapOf(
                    "token" to "p1s2t5",
                    "options" to listOf(mapOf("id" to "a", "text" to "casa"), mapOf("id" to "b", "text" to "árbol")),
                    "correct" to listOf("c"),
                ),
            )
        val report = DraftCheck.report(input(questions = listOf(tf(refs = listOf("p9s9")), choice)))
        assertThat(report.ready).isFalse()
        assertThat(report.errors.map { it.ref to it.detail }).contains(
            "q1" to "p9s9 is not a sentence of this text",
            "q2" to "the correct option c does not exist",
            "q2" to "choose the word the question is about",
        )
        assertThat(report.warnings).contains(Issue("PROMPT_MISSING", "q2", "en"), Issue("EXPLANATION_MISSING", "q2", "es"))
    }

    @Test
    fun `every question type is checked against its own shape`() {
        val content = assembled.content

        fun q(
            type: QuestionType,
            spec: Map<String, Any?>,
        ) = Question("x", type, 1, mapOf("es" to "?"), emptyMap(), listOf("p1s1"), spec)
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.COMPLETION, mapOf("sentence" to "p1s1", "blanks" to listOf("p1s1t4")))), content)).isEmpty()
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.COMPLETION, mapOf("sentence" to "p1s1", "blanks" to listOf("p1s2t1")))), content))
            .extracting<String> { it.message }
            .containsExactly("p1s2t1 is not a word of p1s1")
        val items = listOf(mapOf("id" to "a", "text" to "eins"), mapOf("id" to "b", "text" to "zwei"))
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.ORDERING, mapOf("items" to items, "order" to listOf("b", "a")))), content)).isEmpty()
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.ORDERING, mapOf("items" to items, "order" to listOf("a")))), content)).isNotEmpty()
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.MATCHING, mapOf("left" to items, "right" to items, "pairs" to mapOf("a" to "b")))), content))
            .extracting<String> { it.message }
            .containsExactly("every item on the left needs its pair")
        assertThat(
            QuestionCheck.problems(listOf(q(QuestionType.SHORT_ANSWER, mapOf("model" to mapOf("es" to ""), "rubric" to emptyMap<String, String>()))), content),
        ).extracting<String> { it.message }
            .containsExactly("write a model answer")
        assertThat(QuestionCheck.problems(listOf(q(QuestionType.MULTIPLE_CHOICE, mapOf("nonsense" to 1))), content))
            .extracting<String> { it.message }
            .containsExactly("its details do not match a MULTIPLE_CHOICE question")
        assertThat(QuestionCheck.problems(listOf(tf(), tf()), content)).extracting<String> { it.message }.contains("the id is repeated")
    }

    @Test
    fun `audio whose timings no longer match the text blocks publication`() {
        val audio = DraftAudio("audio-x.mp3", "mp3", 2000, "Ana", "propia", listOf(WordTiming("p1s1t1", 0, 100), WordTiming("p9s1t1", 100, 200)))
        val report = DraftCheck.report(input(audio = audio))
        assertThat(report.ready).isFalse()
        assertThat(report.errors.map { it.code }).contains("AUDIO")
        val partial = DraftAudio("audio-x.mp3", "mp3", 2000, "Ana", "propia", listOf(WordTiming("p1s1t1", 0, 100)), multiWordCues = 2)
        val partly = DraftCheck.report(input(audio = partial))
        assertThat(partly.errors.map { it.code }).contains("AUDIO")
        assertThat(partly.alignedWords).isEqualTo(1)
    }

    @Test
    fun `the same text keeps its version, and answered questions cannot change without a new text`() {
        val same = DraftCheck.report(input(unchanged = true, currentQuestions = listOf(tf()), answered = true))
        assertThat(same.ready).isTrue()
        assertThat(same.warnings).contains(Issue("UNCHANGED"))
        val locked = DraftCheck.report(input(questions = listOf(tf(id = "q9")), unchanged = true, currentQuestions = listOf(tf()), answered = true))
        assertThat(locked.errors).contains(Issue("QUESTIONS_LOCKED"))
        val free = DraftCheck.report(input(questions = listOf(tf(id = "q9")), unchanged = true, currentQuestions = listOf(tf()), answered = false))
        assertThat(free.ready).isTrue()
    }

    @Test
    fun `a picture book needs every illustration and its alt text in each interface language`() {
        val book =
            document.copy(
                format = lesen.reading.ReadingFormat.PICTURE_BOOK,
                layout = lesen.reading.PageLayout.IMAGE_TOP,
                pages =
                    listOf(
                        lesen.reading.DraftPage(paragraphs = 1, illustration = lesen.reading.DraftIllustration("bild.png", mapOf("es" to "Una imagen"))),
                    ),
            )
        val assembledBook = ReadingAssembler.assemble(book, UnicodeTokenizer(), FormResolver { if (it in known) "de:$it" else null })
        val report =
            DraftCheck.report(
                CheckInput(
                    "de",
                    listOf("es", "en"),
                    emptyList(),
                    assembledBook,
                    listOf(tf()),
                    null,
                    false,
                    null,
                    false,
                    missingIllustrations = listOf("g2"),
                ),
            )
        assertThat(report.ready).isFalse()
        assertThat(report.errors).containsExactly(Issue("ILLUSTRATION_MISSING", "g2"), Issue("ALT_MISSING", "g1", "en"))
    }

    @Test
    fun `an image between paragraphs needs its file and its alt text before publishing`() {
        val report = DraftCheck.report(input().copy(missingImages = listOf("images[0]"), imagesWithoutAlt = listOf("images[1]")))
        assertThat(report.ready).isFalse()
        assertThat(report.errors).contains(Issue("IMAGE_MISSING", "images[0]"), Issue("IMAGE_ALT_MISSING", "images[1]"))
    }
}
