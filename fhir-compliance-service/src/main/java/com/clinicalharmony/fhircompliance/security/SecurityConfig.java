package com.clinicalharmony.fhircompliance.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 resource server: Bearer JWTs issued by the Keycloak {@code clinicalharmony} realm
 * (issuer, signature via JWKS, and {@code aud=clinicalharmony-fhir} checked — see
 * {@code spring.security.oauth2.resourceserver.jwt} in application.yml).
 *
 * <table>
 *   <tr><td>public</td><td>SMART discovery, {@code /actuator/health}, the stateless validators
 *       ({@code POST /api/fhir/validate}, {@code POST /api/fhir-compliance/validate}), the rule
 *       catalog reads ({@code GET /api/fhir-compliance/rules[/{code}]})</td></tr>
 *   <tr><td>SMART scope</td><td>{@code /fhir/r4/**}: {@code system/<Type>.r} to read,
 *       {@code .s} to search ({@link SmartScopeAuthorizationManager})</td></tr>
 *   <tr><td>{@code clinicalharmony/pipeline.write}</td><td>everything that writes Gold or rule
 *       config: build, gold/revalidate, gold/masters/** (PMI decisions), fhir-compliance generate + rule toggle</td></tr>
 *   <tr><td>any valid token</td><td>{@code POST /api/fhir/convert/ccda} (whole patient
 *       documents, not persisted) and anything not listed</td></tr>
 * </table>
 */
@Configuration
public class SecurityConfig {

    static final String PIPELINE_WRITE_AUTHORITY = "SCOPE_" + SmartScopes.PIPELINE_WRITE;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorResponses errors) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless Bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, SmartConfigurationController.ROOT_PATH,
                                SmartConfigurationController.FHIR_BASE_PATH).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/fhir/validate", "/api/fhir-compliance/validate").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/fhir-compliance/rules", "/api/fhir-compliance/rules/*").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/fhir/**").access(new SmartScopeAuthorizationManager())
                        .requestMatchers(HttpMethod.POST, "/api/fhir/build", "/api/fhir/build/**",
                                "/api/fhir/gold/revalidate", "/api/fhir/gold/masters/**",
                                "/api/fhir-compliance/generate").hasAuthority(PIPELINE_WRITE_AUTHORITY)
                        .requestMatchers(HttpMethod.PUT, "/api/fhir-compliance/rules/*/toggle").hasAuthority(PIPELINE_WRITE_AUTHORITY)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }
}
