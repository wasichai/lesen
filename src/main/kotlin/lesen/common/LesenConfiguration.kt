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
    )
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
