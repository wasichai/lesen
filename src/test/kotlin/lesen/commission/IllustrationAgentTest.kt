package lesen.commission

import com.embabel.agent.test.integration.ScriptedLlmOperations
import lesen.LesenApiTest
import lesen.generation.ReadingWriter
import lesen.illustration.ArtBible
import lesen.illustration.Aspect
import lesen.illustration.CharacterSheet
import lesen.illustration.FakeImageProvider
import lesen.illustration.ImageProvider
import lesen.illustration.Shot
import lesen.illustration.ShotList
import lesen.illustration.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant

// la dirección de arte con Embabel (ADR-0015), de punta a punta con un modelo guionizado: con una clave (que nunca se
// usa) Embabel entra en el contexto, el planificador encadena biblia → planos → ilustraciones, y la revisión con visión
// rechaza la primera página, que se vuelve a dibujar con sus problemas. Nada sale a la red
// su trabajador atiende la cola de la base de prueba compartida: el contexto se cierra al acabar para que no le quite
// los encargos a otra clase
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = [
        "lesen.ai.api-key=sk-ant-not-a-real-key",
        "embabel.agent.platform.models.anthropic.api-key=sk-ant-not-a-real-key",
        "spring.ai.anthropic.api-key=sk-ant-not-a-real-key",
        "lesen.illustration.provider=fake",
        "lesen.generation.poll-millis=200", "lesen.generation.worker=true",
    ],
)
@Import(IllustrationAgentTest.Scripted::class)
class IllustrationAgentTest : LesenApiTest() {
    @TestConfiguration
    class Scripted {
        @Bean
        @Primary
        fun scriptedReadingWriter(): ReadingWriter = CommissionApiTest.ScriptedWriter().scriptedReadingWriter()

        // el modelo de la plataforma: un guion de un solo uso (la prueba es una)
        @Bean
        @Primary
        fun scriptedModel(): ScriptedLlmOperations = ScriptedLlmOperations()

        @Bean
        @Primary
        fun fakeImages(): ImageProvider = FakeImageProvider()
    }

    @Autowired
    private lateinit var model: ScriptedLlmOperations

    @Autowired
    private lateinit var images: ImageProvider

    @Test
    fun `the agent writes the bible and the shots, reviews every page and redraws the one that drifts`() {
        val bible =
            ArtBible(
                "acuarela suave, línea fina",
                "terracota, verde salvia y crema",
                "luz de media tarde",
                "una casa pequeña con jardín",
                listOf(CharacterSheet("Lena", "niña de 9 años, coleta castaña, abrigo verde azulado, botas amarillas")),
            )
        val alt = mapOf("es" to "Lena en la página", "en" to "Lena on the page")
        val shots = ShotList(listOf(0, 2, 3, 4).map { Shot(it, "plano $it con Lena", listOf("Lena"), "centrado", Aspect.SQUARE, alt) })
        model
            .returnObject(bible)
            .returnObject(shots)
            .returnObject(Verdict(false, listOf("las botas no son amarillas")))
            .returnObject(Verdict(true))
            .returnObject(Verdict(true))
            .returnObject(Verdict(true))
            .returnObject(Verdict(true))

        val editor = editor()
        val queued =
            send(
                "POST",
                "/api/lesen/studio/generations",
                editor,
                mapOf("language" to "de", "level" to "A1", "minutes" to 2, "format" to "PICTURE_BOOK", "topics" to listOf("everyday-life/home")),
                202,
            )!!
        val deadline = Instant.now().plus(Duration.ofSeconds(90))
        var done = get("/api/lesen/studio/generations/${queued["id"].asString()}", editor)
        while (done["status"].asString() !in setOf("READY", "FAILED") && Instant.now().isBefore(deadline)) {
            Thread.sleep(250)
            done = get("/api/lesen/studio/generations/${queued["id"].asString()}", editor)
        }
        assertThat(done["status"].asString()).isEqualTo("READY")
        assertThat(done["directedBy"].asString()).isEqualTo("agent")

        // la biblia afinada por el modelo pasa a cada imagen; la página rechazada se pide otra vez con lo que falló
        val calls = (images as FakeImageProvider).calls.map { it.first }
        val pages = calls.filter { it.startsWith("An illustration for one page") }
        assertThat(pages).hasSize(5)
        assertThat(pages).allMatch { it.contains("botas amarillas") && it.contains("terracota, verde salvia") }
        assertThat(pages[1]).contains("Fix these differences", "las botas no son amarillas")
        // la proporción la fija la maqueta, no el modelo: la panorámica sigue siéndolo
        assertThat((images as FakeImageProvider).calls.last().third).isEqualTo(Aspect.PANORAMA)
        assertThat(model.promptsReceived.first()).contains("visual bible", "Lena")
        val draft = get("/api/lesen/studio/drafts/${done["draftId"].asString()}", editor)
        assertThat(draft["document"]["pages"][0]["illustration"]["alt"]["en"].asString()).isEqualTo("Lena on the page")
    }
}
