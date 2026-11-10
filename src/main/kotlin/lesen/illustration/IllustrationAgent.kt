package lesen.illustration

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.AgentImage
import com.embabel.agent.api.common.MultimodalContent
import com.embabel.agent.api.common.OperationContext
import com.embabel.common.ai.model.LlmOptions
import kotlinx.coroutines.runBlocking
import lesen.common.Jsonb
import lesen.common.LesenProperties

// la dirección de arte con Embabel (ADR-0015): un agente con tres acciones tipadas que el planificador encadena,
// IllustrationBrief → ArtBible → ShotList → IllustratedBook. Claude afina la biblia, escribe el plano de cada página
// viendo la anterior y la siguiente, y revisa con visión cada ilustración frente a las hojas de los personajes. Las
// imágenes las dibuja el proveedor de lesen.illustration.provider. Solo existe con el contexto de Embabel
// (IllustrationGate); los pasos son los de IllustrationSteps, los mismos que el recorrido sin modelo
@Agent(name = "lesen-illustrator", description = "Illustrates a picture book so that every page follows one visual bible")
class IllustrationAgent(
    private val provider: ImageProvider,
    private val properties: LesenProperties,
) {
    private val llm get() = LlmOptions.withModel(properties.ai.model)

    @Action(description = "Write the visual bible of the book: one style, palette, lighting and a fixed look for every character")
    fun direct(
        brief: IllustrationBrief,
        context: OperationContext,
    ): ArtBible =
        context
            .ai()
            .withLlm(llm)
            .withSystemPrompt(SYSTEM)
            .createObject(
                """
                Refine this picture book's visual bible so an illustrator can draw every page the same way. Keep what it
                says; make each character's look concrete (face, hair, clothes and their colours, proportions) and fix one
                lighting for the book. The scenes are given so the bible covers everything they need.

                ${Jsonb.of(mapOf("title" to brief.title, "art" to brief.art, "scenes" to brief.pages.map { it.scene }))}
                """.trimIndent(),
                ArtBible::class.java,
            )

    @Action(description = "Plan one shot per illustrated page, with continuity from the page before and to the page after")
    fun plan(
        brief: IllustrationBrief,
        bible: ArtBible,
        context: OperationContext,
    ): ShotList {
        val pages =
            brief.pages.map { page ->
                mapOf(
                    "page" to page.index,
                    "scene" to page.scene,
                    "characters" to page.characters,
                    "text" to page.text,
                    "framing" to IllustrationSteps.framing(page.layout),
                    "aspect" to IllustrationSteps.aspect(page.layout, page.span).name,
                )
            }
        val planned =
            context
                .ai()
                .withLlm(llm)
                .withSystemPrompt(SYSTEM)
                .createObject(
                    """
                    Write the shot for each page of this picture book, in order. For each one: what is drawn ("description"),
                    who appears, the framing and the aspect given, and "alt", a one or two sentence description of the
                    picture for someone who cannot see it, in each of these languages: ${brief.altLanguages.joinToString(", ")}.
                    Carry continuity from page to page: the same clothes and objects, the same place unless the story moves,
                    the time of day as it passes. Never ask for written words in the picture.

                    Visual bible: ${Jsonb.of(bible)}
                    Pages: ${Jsonb.of(pages)}
                    """.trimIndent(),
                    ShotList::class.java,
                )
        // el plano conserva la maqueta: la proporción la fija la página, no el modelo
        val aspects = brief.pages.associate { it.index to IllustrationSteps.aspect(it.layout, it.span) }
        return ShotList(planned.shots.filter { it.page in aspects }.map { it.copy(aspect = aspects.getValue(it.page)) })
    }

    @Action(description = "Draw every page with the provider, following the bible and the shots, and review each one with vision")
    @AchievesGoal(description = "Every illustrated page of the picture book is drawn consistently with its visual bible")
    fun illustrate(
        brief: IllustrationBrief,
        bible: ArtBible,
        shots: ShotList,
        context: OperationContext,
    ): IllustratedBook {
        val pipeline = IllustrationPipeline(provider, properties.illustration.maxRetries)
        val reviewer =
            Reviewer { b, shot, picture, references ->
                context
                    .ai()
                    .withLlm(llm)
                    .withSystemPrompt(SYSTEM)
                    .createObject(
                        MultimodalContent(
                            """
                            The first image is a new page of a picture book; the others are its references (character sheets,
                            the previous page, the style frame). Is the new page consistent with them and with the bible:
                            same characters with the same look, same style and palette, no text in the picture? Answer
                            "consistent" and, if not, the concrete "problems" to fix.

                            Bible: ${Jsonb.of(b)}
                            Shot: ${shot.description}
                            """.trimIndent(),
                            (listOf(picture) + references).map { AgentImage.create(it.contentType, it.bytes) },
                            emptyList(),
                        ),
                        Verdict::class.java,
                    )
            }
        // la plataforma corre la acción en su propio hilo: esperar al proveedor aquí no bloquea el bucle de eventos
        return runBlocking {
            val references = pipeline.references(bible)
            pipeline.render(brief, bible, shots, references, reviewer, DIRECTED_BY)
        }
    }

    companion object {
        const val DIRECTED_BY = "agent"

        val SYSTEM =
            """
            You are the art director of children's picture books for Lesen, an app where people learn a language by reading.
            Every illustration of a book must look like the same artist drew it on the same day: one style, one palette,
            characters that never change their look. Pictures carry the story for young readers; they never contain text.
            Content suitable for any age.
            """.trimIndent()
    }
}
