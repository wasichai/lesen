package lesen.illustration

import kotlinx.coroutines.runBlocking
import lesen.reading.PageLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// la dirección de arte sin modelo y el recorrido de dibujo (ADR-0015): la coherencia sale de pedir cada página con la
// misma biblia, las hojas de quien sale y la página anterior
class IllustrationTest {
    private val art = Art("acuarela suave", "ocres y verdes", "una cocina con ventana", listOf(CharacterSheet("Lena", "coleta castaña, abrigo verde azulado")))

    private fun page(
        index: Int,
        layout: PageLayout = PageLayout.IMAGE_TOP,
        span: Int = 1,
        characters: List<String> = listOf("Lena"),
    ) = BriefPage(index, layout, span, "escena $index", characters, "Text $index", mapOf("es" to "Imagen $index", "en" to "Picture $index"))

    private val brief =
        IllustrationBrief(
            "Ein Buch",
            "de",
            "A1",
            art,
            listOf(page(0), page(2, PageLayout.PANORAMA, 2), page(3, PageLayout.IMAGE_ONLY, characters = emptyList())),
            listOf("es", "en"),
        )

    @Test
    fun `each layout gets its aspect and framing, and each shot carries continuity`() {
        assertThat(IllustrationSteps.aspect(PageLayout.IMAGE_TOP, 1)).isEqualTo(Aspect.LANDSCAPE)
        assertThat(IllustrationSteps.aspect(PageLayout.PANORAMA, 2)).isEqualTo(Aspect.PANORAMA)
        assertThat(IllustrationSteps.aspect(PageLayout.IMAGE_ONLY, 2)).isEqualTo(Aspect.WIDE)
        assertThat(IllustrationSteps.framing(PageLayout.TEXT_OVER_IMAGE)).contains("lower third")
        val shots = IllustrationSteps.shotsFrom(brief).shots
        assertThat(shots.map { it.page }).containsExactly(0, 2, 3)
        assertThat(shots[1].description).contains("escena 2", "Just before: escena 0", "Right after: escena 3")
        val prompt = IllustrationSteps.imagePrompt(IllustrationSteps.bibleFrom(art), shots[0], withPrevious = true)
        assertThat(prompt).contains("acuarela suave", "ocres y verdes", "Lena: coleta castaña", "continuity", "No text")
        // quien no sale no entra en el prompt
        assertThat(IllustrationSteps.imagePrompt(IllustrationSteps.bibleFrom(art), shots[2], false)).doesNotContain("Lena:")
    }

    @Test
    fun `pages are drawn in order with the sheets of who appears and the previous page`() =
        runBlocking<Unit> {
            val provider = FakeImageProvider()
            val pipeline = IllustrationPipeline(provider, maxRetries = 1)
            val bible = IllustrationSteps.bibleFrom(art)
            val references = pipeline.references(bible)
            assertThat(references.characters.keys).containsExactly("Lena")
            assertThat(references.style).isNotNull()
            provider.calls.clear()
            val book = pipeline.render(brief, bible, IllustrationSteps.shotsFrom(brief), references, null, "generation")
            assertThat(book.pages.map { it.page }).containsExactly(0, 2, 3)
            assertThat(book.pages.all { it.picture != null && it.problem == null }).isTrue()
            assertThat(book.pages[1].alt).containsEntry("en", "Picture 2")
            // la primera, con la hoja de Lena y el estilo; las siguientes, también con la página anterior
            assertThat(provider.calls.map { it.second }).containsExactly(2, 3, 2)
            assertThat(provider.calls.map { it.third }).containsExactly(Aspect.LANDSCAPE, Aspect.PANORAMA, Aspect.PORTRAIT)
        }

    @Test
    fun `a page the review rejects is drawn again with its problems, up to the retries`() =
        runBlocking<Unit> {
            val provider = FakeImageProvider()
            val pipeline = IllustrationPipeline(provider, maxRetries = 1)
            var reviews = 0
            val reviewer =
                Reviewer { _, _, _, _ ->
                    reviews++
                    if (reviews == 1) Verdict(false, listOf("Lena lleva otro abrigo")) else Verdict(true)
                }
            val one = brief.copy(pages = brief.pages.take(1))
            val bible = IllustrationSteps.bibleFrom(art)
            val book = pipeline.render(one, bible, IllustrationSteps.shotsFrom(one), ReferenceSet(emptyMap(), null), reviewer, "agent")
            assertThat(book.pages.single().attempts).isEqualTo(2)
            assertThat(book.pages.single().problem).isNull()
            assertThat(provider.calls.last().first).contains("Fix these differences", "Lena lleva otro abrigo")
            // si sigue sin casar, se entrega con su problema a la vista
            val stubborn =
                pipeline.render(
                    one,
                    bible,
                    IllustrationSteps.shotsFrom(one),
                    ReferenceSet(emptyMap(), null),
                    { _, _, _, _ -> Verdict(false, listOf("no")) },
                    "agent",
                )
            assertThat(stubborn.pages.single().problem).startsWith("inconsistent")
        }

    @Test
    fun `without a provider nothing is drawn and every illustration stays pending`() =
        runBlocking<Unit> {
            val failing =
                object : ImageProvider {
                    override val name = "down"
                    override val available = true

                    override suspend fun render(
                        prompt: String,
                        references: List<Picture>,
                        aspect: Aspect,
                    ) = Rendered.Refused("not this one")
                }
            val book =
                IllustrationPipeline(
                    failing,
                    1,
                ).render(brief, IllustrationSteps.bibleFrom(art), IllustrationSteps.shotsFrom(brief), ReferenceSet(emptyMap(), null), null, "generation")
            assertThat(book.pages.map { it.picture }).containsOnlyNulls()
            assertThat(book.pages.first().problem).isEqualTo("refused: not this one")
            assertThat(NoImageProvider.available).isFalse()
        }
}
