package lesen.illustration

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

// Embabel no arranca sin un modelo, y Lesen arranca entera sin claves (ADR-0005, ADR-0015). Sin clave de Anthropic, sin
// proveedor de imágenes o con la dirección de arte apagada, sus autoconfiguraciones quedan fuera del contexto y los
// libros se ilustran con la biblia que escribió la generación del texto. La misma idea que EmbabelGate de Wasichai,
// sin depender de esa clase
class IllustrationGate : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        environment.propertySources.addLast(MapPropertySource(DEFAULTS_SOURCE, DEFAULTS))
        if (agentWanted(environment)) return
        // se suman a las que excluya la aplicación: un Binder lee tanto la lista de YAML como la cadena con comas
        val existing = Binder.get(environment).bind(EXCLUDE, Bindable.listOf(String::class.java)).orElse(emptyList())
        val excluded = (existing.orEmpty() + EXCLUDED).distinct()
        environment.propertySources.addFirst(MapPropertySource(SOURCE_NAME, mapOf(EXCLUDE to excluded.joinToString(","))))
    }

    companion object {
        const val SOURCE_NAME = "lesen-illustration-gate"
        const val DEFAULTS_SOURCE = "lesen-illustration-defaults"
        private const val EXCLUDE = "spring.autoconfigure.exclude"

        val DEFAULTS: Map<String, Any> =
            mapOf(
                // sin él, Embabel busca un modelo de OpenAI por defecto y el contexto no arranca
                "embabel.models.default-llm" to "\${LESEN_AI_MODEL:claude-opus-5-5}",
                "embabel.agent.platform.models.anthropic.api-key" to "\${ANTHROPIC_API_KEY:}",
            )

        // todas: excluir solo la del modelo mueve el fallo, porque la plataforma exige que haya uno
        val EXCLUDED =
            listOf(
                "com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.AgentCacheProviderAutoConfiguration",
                "com.embabel.agent.autoconfigure.platform.cache.CacheSnapshotStoreAutoConfiguration",
            )

        fun agentWanted(environment: Environment): Boolean {
            val provider = environment.getProperty("lesen.illustration.provider", "none")
            val agent = environment.getProperty("lesen.illustration.agent", Boolean::class.java, true)
            return provider != "none" && agent && anthropicKey(environment).isNotBlank()
        }

        fun anthropicKey(environment: Environment): String =
            sequenceOf("lesen.ai.api-key", "ANTHROPIC_API_KEY", "embabel.agent.platform.models.anthropic.api-key")
                .mapNotNull { environment.getProperty(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
    }
}
