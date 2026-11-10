package lesen.common

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import wasichai.core.platform.ModuleMigration
import java.time.Clock

// lesen.* — el nombre del producto es configuración, nunca dominio
@ConfigurationProperties("lesen")
data class LesenProperties(
    val demo: Demo = Demo(),
    val ai: Ai = Ai(),
    val generation: Generation = Generation(),
    val illustration: Illustration = Illustration(),
) {
    // publica el contenido de src/main/resources/demo al arrancar (docs/content/demo-content.md)
    data class Demo(
        val enabled: Boolean = false,
    )

    // la corrección asistida de respuestas abiertas (ADR-0009). Sin clave no hay asistente: la respuesta queda para
    // autoevaluar con la respuesta modelo a la vista. La clave nunca va a un archivo: ANTHROPIC_API_KEY
    data class Ai(
        val apiKey: String = "",
        val model: String = "claude-opus-5-5",
        // solo para pruebas o un proxy; vacío es la API de Anthropic
        val baseUrl: String = "",
        // correcciones asistidas por persona y hora; las siguientes quedan para autoevaluar
        val hourlyLimit: Int = 30,
        val pollMillis: Long = 2000,
        val timeoutSeconds: Long = 60,
    ) {
        val enabled get() = apiKey.isNotBlank()
    }

    // las lecturas a medida (ADR-0013), con la misma clave y el mismo modelo que la corrección asistida
    data class Generation(
        // low, medium, high, xhigh o max; se ajusta con el juego de evaluación
        val effort: String = "medium",
        // el razonamiento y la salida: una lectura de 8 minutos con sus traducciones y preguntas cabe con holgura
        val maxTokens: Long = 32000,
        // las veces que la lectura vuelve al modelo con sus fallos antes de darla por fallida
        val maxCorrections: Int = 2,
        val timeoutSeconds: Long = 300,
        // los trabajos de generación que corren a la vez (lesen.generation_job) y cada cuánto se mira la cola
        val workers: Int = 1,
        val pollMillis: Long = 2000,
        // un trabajo sin noticias en este tiempo se da por caído y vuelve a la cola
        val staleMinutes: Long = 30,
    )

    // las ilustraciones de los libros (ADR-0015). provider: none (sin ilustraciones: el libro queda con ellas pendientes),
    // fake (imágenes de prueba, para desarrollo y pruebas), openai o gemini. Las claves nunca van a un archivo
    data class Illustration(
        val provider: String = "none",
        // el modelo de imagen del proveedor; vacío, el suyo por defecto (gpt-image-1, gemini-2.5-flash-image)
        val model: String = "",
        val openaiApiKey: String = "",
        val openaiBaseUrl: String = "https://api.openai.com",
        val geminiApiKey: String = "",
        val geminiBaseUrl: String = "https://generativelanguage.googleapis.com",
        // la dirección de arte con Embabel y Claude (biblia visual, planos y revisión con visión); sin clave de
        // Anthropic, el libro se ilustra con la biblia que escribió la generación del texto
        val agent: Boolean = true,
        // las veces que una página que la revisión rechaza se vuelve a dibujar
        val maxRetries: Int = 1,
        val timeoutSeconds: Long = 180,
    ) {
        val enabled get() = provider != "none"
    }
}

@Configuration
@EnableConfigurationProperties(LesenProperties::class)
class LesenConfiguration {
    // las tablas propias de Lesen (esquema lesen), con su propio historial de Flyway; después de los módulos de Wasichai
    @Bean
    fun lesenMigration(): ModuleMigration = ModuleMigration(MIGRATION_NAME, "classpath:db/lesen", ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()

    companion object {
        const val MIGRATION_NAME = "lesen"
        const val ORDER = 200
    }
}
