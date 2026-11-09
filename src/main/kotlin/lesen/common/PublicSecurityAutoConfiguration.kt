package lesen.common

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import wasichai.core.autoconfigure.WasichaiSecurityAutoConfiguration

// la cadena de lo público de Lesen (idiomas, niveles, temas, portadas, OpenAPI): anónimo y de solo lectura.
// una auto-configuración que corre después de la de Wasichai, y no un @Configuration escaneado: la cadena de Wasichai es
// @ConditionalOnMissingBean y, si la nuestra se registrara antes, retrocedería. @Order(-1): esta se mira primero, solo
// para su ruta (el mismo patrón que el catálogo público de elearning-backend)
@AutoConfiguration(after = [WasichaiSecurityAutoConfiguration::class])
class PublicSecurityAutoConfiguration {
    @Bean
    @Order(-1)
    fun lesenPublicSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .securityMatcher(pathMatchers(PUBLIC_PATH))
            .authorizeExchange {
                it.pathMatchers(HttpMethod.GET, PUBLIC_PATH).permitAll()
                it.pathMatchers(HttpMethod.HEAD, PUBLIC_PATH).permitAll()
                it.anyExchange().denyAll()
            }.cors { }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .build()

    companion object {
        const val PUBLIC_PATH = "/api/lesen/public/**"
    }
}
