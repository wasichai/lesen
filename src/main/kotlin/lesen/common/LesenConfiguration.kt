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
) {
    // publica el contenido de src/main/resources/demo al arrancar (docs/content/demo-content.md)
    data class Demo(
        val enabled: Boolean = false,
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
