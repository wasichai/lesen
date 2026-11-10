package lesen.illustration

// la revisión de una página frente a sus referencias. Sin modelo con visión no se revisa: lo dice, no lo da por bueno
fun interface Reviewer {
    fun review(
        bible: ArtBible,
        shot: Shot,
        picture: Picture,
        references: List<Picture>,
    ): Verdict?
}

// dibujar un libro con una biblia y un plano por página (ADR-0015): primero las hojas de los personajes y el fotograma
// de estilo; después cada página en orden, con las hojas de quien sale y la ilustración anterior aceptada; la que la
// revisión rechaza se vuelve a dibujar con sus problemas, hasta maxRetries veces. Una página que no sale queda pendiente
class IllustrationPipeline(
    private val provider: ImageProvider,
    private val maxRetries: Int,
) {
    suspend fun references(bible: ArtBible): ReferenceSet {
        val sheets =
            bible.characters
                .mapNotNull { character ->
                    (provider.render(IllustrationSteps.referencePrompt(bible, character), emptyList(), Aspect.PORTRAIT) as? Rendered.Image)
                        ?.let { character.name to it.picture }
                }.toMap()
        val style = (provider.render(IllustrationSteps.stylePrompt(bible), emptyList(), Aspect.LANDSCAPE) as? Rendered.Image)?.picture
        return ReferenceSet(sheets, style)
    }

    suspend fun render(
        brief: IllustrationBrief,
        bible: ArtBible,
        shots: ShotList,
        references: ReferenceSet,
        reviewer: Reviewer?,
        directedBy: String,
    ): IllustratedBook {
        var previous: Picture? = null
        val pages =
            brief.pages.map { page ->
                val shot = shots.shots.firstOrNull { it.page == page.index } ?: IllustrationSteps.shotsFrom(brief.copy(pages = listOf(page))).shots.first()
                val alt = IllustrationSteps.alt(shot, page, brief.altLanguages)
                val refs = IllustrationSteps.referencesFor(shot, references, previous)
                var prompt = IllustrationSteps.imagePrompt(bible, shot, previous != null)
                var attempts = 0
                var result: PageIllustration? = null
                while (result == null) {
                    attempts++
                    when (val rendered = provider.render(prompt, refs, shot.aspect)) {
                        is Rendered.Refused -> result = PageIllustration(page.index, null, alt, "refused: ${rendered.reason}", attempts)
                        is Rendered.Failed -> result = PageIllustration(page.index, null, alt, rendered.reason, attempts)
                        is Rendered.Image -> {
                            val verdict = reviewer?.review(bible, shot, rendered.picture, refs)
                            if (verdict == null || verdict.consistent || attempts > maxRetries) {
                                // una página que sigue sin casar tras los reintentos se entrega igual, con su problema a la vista
                                val problem = verdict?.takeIf { !it.consistent }?.let { "inconsistent: ${it.problems.joinToString("; ")}" }
                                result = PageIllustration(page.index, rendered.picture, alt, problem, attempts)
                            } else {
                                prompt = IllustrationSteps.imagePrompt(bible, shot, previous != null) +
                                    "\nFix these differences from the references: ${verdict.problems.joinToString("; ")}."
                            }
                        }
                    }
                }
                result.picture?.let { previous = it }
                result
            }
        return IllustratedBook(bible, pages, directedBy)
    }
}
