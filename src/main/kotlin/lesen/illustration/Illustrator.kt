package lesen.illustration

import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lesen.common.LesenProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

// ilustrar un libro (ADR-0015). Con Embabel en el contexto (clave de Anthropic, proveedor y dirección de arte
// encendida), el agente dirige el arte; si no, o si el agente falla, el libro se dibuja con la biblia y las escenas que
// escribió la generación del texto. Sin proveedor no se dibuja nada: las ilustraciones quedan pendientes
@Service
class Illustrator(
    private val provider: ImageProvider,
    private val properties: LesenProperties,
    private val platform: ObjectProvider<AgentPlatform>,
) {
    private val log = LoggerFactory.getLogger(Illustrator::class.java)

    val available get() = provider.available

    suspend fun illustrate(brief: IllustrationBrief): IllustratedBook {
        if (!provider.available || brief.pages.isEmpty()) {
            val bible = IllustrationSteps.bibleFrom(brief.art)
            return IllustratedBook(
                bible,
                brief.pages.map { PageIllustration(it.index, null, it.alt, "no image provider", 0) },
                SCRIPTED,
            )
        }
        platform.ifAvailable?.let { agentPlatform ->
            try {
                val book =
                    withContext(Dispatchers.IO) {
                        AgentInvocation
                            .builder(agentPlatform)
                            .options(ProcessOptions(budget = Budget(actions = 12)))
                            .build(IllustratedBook::class.java)
                            .invoke(brief)
                    }
                return book
            } catch (error: Exception) {
                log.warn("dirección de arte con el agente: {}; se ilustra con la biblia de la generación", error.toString())
            }
        }
        val pipeline = IllustrationPipeline(provider, properties.illustration.maxRetries)
        val bible = IllustrationSteps.bibleFrom(brief.art)
        return pipeline.render(brief, bible, IllustrationSteps.shotsFrom(brief), pipeline.references(bible), null, SCRIPTED)
    }

    companion object {
        const val SCRIPTED = "generation"
    }
}
