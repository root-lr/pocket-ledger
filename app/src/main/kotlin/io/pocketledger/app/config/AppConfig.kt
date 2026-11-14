package io.pocketledger.app.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import java.time.Clock

@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("PocketLedger API")
                    .version("v1")
                    .description(
                        "Backend de finances personnelles mobile-first : CQRS, event sourcing, Kafka. " +
                            "Toute écriture exige l'en-tête Idempotency-Key ; les erreurs suivent la RFC 9457.",
                    ).license(License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")),
            ).components(
                Components().addSecuritySchemes(
                    "bearer",
                    SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList("bearer"))
}

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, jwtDecoder: JwtDecoder): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/api/v1/auth/**", "/.well-known/**", "/api/v1/currencies", "/api/v1/exports/**",
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                    ).permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { rs ->
                rs.jwt { it.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                rs.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }.build()

    /** Rôles lus dans la claim "roles" du JWT → ROLE_USER, ROLE_ADMIN. */
    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authorities =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("roles")
                setAuthorityPrefix("ROLE_")
            }
        return JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
    }
}
