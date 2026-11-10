package lesen.commission

import lesen.LesenApiTest
import lesen.assessment.QuestionType
import lesen.common.Jsonb
import lesen.generation.Finding
import lesen.generation.GeneratedArt
import lesen.generation.GeneratedBook
import lesen.generation.GeneratedCharacter
import lesen.generation.GeneratedOption
import lesen.generation.GeneratedPage
import lesen.generation.GeneratedParagraph
import lesen.generation.GeneratedQuestion
import lesen.generation.GeneratedReading
import lesen.generation.GeneratedSentence
import lesen.generation.GenerationContext
import lesen.generation.ReadingWriter
import lesen.generation.TokenUsage
import lesen.generation.WritingSession
import lesen.generation.Written
import lesen.reading.PageLayout
import lesen.reading.ReadingDraft
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

// el estudio encarga un libro ilustrado (ADR-0015) y lo recibe como borrador, con sus páginas e ilustraciones. El
// escritor es un guion con el texto de «Ein Sonntag bei Oma» (su vocabulario está en el diccionario de la demostración);
// las imágenes, del proveedor de prueba. Sin clave de Anthropic, Embabel queda fuera y el arte lo dirige la biblia que
// escribió la generación
// su trabajador atiende la cola de la base de prueba compartida: el contexto se cierra al acabar para que no le quite
// los encargos a otra clase
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = ["lesen.illustration.provider=fake", "lesen.generation.poll-millis=200"])
@Import(CommissionApiTest.ScriptedWriter::class)
class CommissionApiTest : LesenApiTest() {
    @TestConfiguration
    class ScriptedWriter {
        @Bean
        @Primary
        fun scriptedReadingWriter(): ReadingWriter =
            object : ReadingWriter {
                override val available = true

                override fun start(context: GenerationContext): WritingSession =
                    object : WritingSession {
                        override suspend fun write(): Written =
                            if (context.request.prompt ==
                                "refuse"
                            ) {
                                Written.Refused(TokenUsage(10, 1))
                            } else {
                                Written.Reading(book(context), TokenUsage(1000, 2000))
                            }

                        override suspend fun correct(findings: List<Finding>): Written = error("the script fits the request: $findings")
                    }
            }
    }

    private fun job(
        token: String,
        id: String,
    ): JsonNode {
        val deadline = Instant.now().plus(Duration.ofSeconds(60))
        while (true) {
            val view = get("/api/lesen/studio/generations/$id", token)
            if (view["status"].asString() in setOf("READY", "FAILED") || Instant.now().isAfter(deadline)) return view
            Thread.sleep(250)
        }
    }

    @Test
    fun `an editor commissions a picture book and gets a draft with its pages and illustrations`() {
        val editor = editor()
        assertThat(get("/api/lesen/studio/generations/status", editor)["illustrations"].asBoolean()).isTrue()
        // quien solo lee no encarga; lo que no es válido, no se encola
        send("POST", "/api/lesen/studio/generations", tomas(), mapOf("language" to "de", "level" to "A1"), 403)
        send("POST", "/api/lesen/studio/generations", editor, mapOf("language" to "xx", "level" to "Z9", "minutes" to 3), 400)

        val queued =
            send(
                "POST",
                "/api/lesen/studio/generations",
                editor,
                mapOf(
                    "language" to "de",
                    "level" to "A1",
                    "minutes" to 2,
                    "kind" to "STORY",
                    "format" to "PICTURE_BOOK",
                    "topics" to listOf("everyday-life/home"),
                ),
                202,
            )!!
        assertThat(queued["status"].asString()).isEqualTo("QUEUED")
        val done = job(editor, queued["id"].asString())
        assertThat(done["status"].asString()).isEqualTo("READY")
        assertThat(done["directedBy"].asString()).isEqualTo("generation")
        assertThat(done["illustrations"].asInt()).isEqualTo(4)

        // el borrador: un libro con su maqueta, cada página ilustrada con su archivo, su texto alternativo y su escena
        val draft = get("/api/lesen/studio/drafts/${done["draftId"].asString()}", editor)
        assertThat(draft["status"].asString()).isEqualTo("DRAFT")
        assertThat(draft["events"].values().map { it["action"].asString() }).contains("GENERATED")
        val document = draft["document"]
        assertThat(document["format"].asString()).isEqualTo("PICTURE_BOOK")
        assertThat(document["slug"].asString()).startsWith("ein-sonntag-im-bilderbuch-")
        val pages = document["pages"].values().toList()
        assertThat(pages).hasSize(5)
        assertThat(
            pages
                .filter {
                    !it["illustration"]["file"].isNull
                }.map { it["illustration"]["file"].asString() },
        ).allMatch { it.matches(Regex("illustration-[0-9a-f]{24}\\.png")) }
        assertThat(pages[1]["illustration"]["file"].isNull).isTrue() // TEXT_ONLY: no lleva ilustración
        assertThat(pages[0]["illustration"]["alt"]["en"].asString()).isEqualTo("Lena gets off the bus")
        assertThat(pages[0]["illustration"]["scene"].asString()).isEqualTo("Lena steigt aus dem Bus")
        // listo para revisar: nada falta en las páginas
        val check = get("/api/lesen/studio/drafts/${done["draftId"].asString()}/check", editor)
        assertThat(check["errors"].values().map { it["code"].asString() }).doesNotContain("ILLUSTRATION_MISSING", "ALT_MISSING")

        // volver a dibujar una página: otro archivo, el mismo texto alternativo
        val before = pages[2]["illustration"]["file"].asString()
        val redrawn = send("POST", "/api/lesen/studio/drafts/${done["draftId"].asString()}/pages/3/illustration", editor, null, 200)!!
        val after = redrawn["document"]["pages"][2]["illustration"]
        assertThat(after["file"].asString()).isNotEqualTo(before)
        assertThat(after["alt"]["es"].asString()).isEqualTo("La caja con la foto")
        send("POST", "/api/lesen/studio/drafts/${done["draftId"].asString()}/pages/2/illustration", editor, null, 400)
        send("POST", "/api/lesen/studio/drafts/${done["draftId"].asString()}/pages/3/illustration", tomas(), null, 403)
        assertThat(get("/api/lesen/studio/generations", editor).values().map { it["id"].asString() }).contains(queued["id"].asString())
    }

    @Test
    fun `a refusal ends the commission with its reason and no draft`() {
        val queued =
            send("POST", "/api/lesen/studio/generations", editor(), mapOf("language" to "de", "level" to "A1", "prompt" to "refuse"), 202)!!
        val done = job(editor(), queued["id"].asString())
        assertThat(done["status"].asString()).isEqualTo("FAILED")
        assertThat(done["reason"].asString()).isEqualTo("REFUSED")
        assertThat(done["draftId"].isNull).isTrue()
    }

    companion object {
        private val oma: ReadingDraft =
            Jsonb.read(
                checkNotNull(CommissionApiTest::class.java.getResource("/demo/readings/ein-sonntag-bei-oma.json")).readText(),
            )

        private fun question(
            id: String,
            type: QuestionType,
            ref: String,
            word: String? = null,
            blanks: List<String> = emptyList(),
            answer: Boolean? = null,
        ): GeneratedQuestion {
            val choice = type != QuestionType.TRUE_FALSE && type != QuestionType.COMPLETION
            return GeneratedQuestion(
                id,
                type,
                1,
                mapOf("es" to "¿Qué pasa?", "en" to "What happens?"),
                mapOf("es" to "Lo dice el texto.", "en" to "The text says so."),
                listOf(ref),
                if (choice) {
                    listOf(
                        GeneratedOption("a", mapOf("es" to "Sí", "en" to "Yes")),
                        GeneratedOption("b", mapOf("es" to "No", "en" to "No")),
                    )
                } else {
                    emptyList()
                },
                if (choice) listOf("a") else emptyList(),
                answer,
                word,
                blanks,
            )
        }

        // el texto de la demostración, contado como si lo hubiera escrito el modelo, repartido en cinco páginas
        fun book(context: GenerationContext): GeneratedReading {
            fun page(
                paragraphs: Int,
                layout: PageLayout?,
                scene: String,
                es: String,
                en: String,
                span: Int = 1,
            ) = GeneratedPage(span, layout, paragraphs, scene, listOf("Lena"), mapOf("es" to es, "en" to en))
            return GeneratedReading(
                title = "Ein Sonntag im Bilderbuch",
                subtitle = null,
                summary = oma.summary,
                topics = listOf("everyday-life/home"),
                names = listOf("Lena", "Oma"),
                paragraphs = oma.paragraphs.map { p -> GeneratedParagraph(p.sentences.map { GeneratedSentence(it.text, it.translations) }) },
                questions =
                    listOf(
                        question("q1", QuestionType.DETAIL, "p1s2"),
                        question("q2", QuestionType.TRUE_FALSE, "p1s3", answer = true),
                        question("q3", QuestionType.VOCABULARY_IN_CONTEXT, "p2s1", word = "Kuchen"),
                        question("q4", QuestionType.COMPLETION, "p3s2", blanks = listOf("Foto")),
                    ),
                book =
                    if (context.book) {
                        GeneratedBook(
                            PageLayout.IMAGE_TOP,
                            GeneratedArt(
                                "acuarela suave",
                                "terracota y verde",
                                "una casa con jardín",
                                listOf(GeneratedCharacter("Lena", "coleta castaña, abrigo verde azulado")),
                            ),
                            listOf(
                                page(1, null, "Lena steigt aus dem Bus", "Lena baja del autobús", "Lena gets off the bus"),
                                page(1, PageLayout.TEXT_ONLY, "In der Küche", "La cocina", "The kitchen"),
                                page(1, PageLayout.TEXT_OVER_IMAGE, "Die alte Kiste mit dem Foto", "La caja con la foto", "The box with the photo"),
                                page(0, PageLayout.IMAGE_ONLY, "Das alte Foto", "La foto antigua", "The old photo"),
                                page(1, PageLayout.PANORAMA, "Lena fährt nach Hause", "Lena vuelve a casa", "Lena rides home", span = 2),
                            ),
                        )
                    } else {
                        null
                    },
            )
        }
    }
}
