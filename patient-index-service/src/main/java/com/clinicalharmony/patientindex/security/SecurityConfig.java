package com.clinicalharmony.patientindex.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Access matrix (Keycloak realm {@code clinicalharmony}, same issuer/audience as
 * fhir-compliance-service):
 * <table>
 *   <tr><th>Who</th><th>What</th></tr>
 *   <tr><td>anyone</td><td>{@code /actuator/health}</td></tr>
 *   <tr><td>any valid token</td><td>every {@code GET /api/pmi/**} — masters, candidates, runs,
 *       ad-hoc scores. They show patient demographics, so they're never public.</td></tr>
 *   <tr><td>{@code clinicalharmony/pipeline.write}</td><td>every {@code POST /api/pmi/**}: matching
 *       runs and review decisions, which change Gold (through GoldWriter)</td></tr>
 * </table>
 * The service then calls GoldWriter with its OWN client's token, never the caller's: the caller's
 * pipeline.write is what authorizes the decision here; GoldWriter separately authorizes the
 * service.
 */
@Configuration
public class SecurityConfig {

    public static final String PIPELINE_WRITE = "clinicalharmony/pipeline.write";
    static final String PIPELINE_WRITE_AUTHORITY = "SCOPE_" + PIPELINE_WRITE;

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
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/pmi/**").hasAuthority(PIPELINE_WRITE_AUTHORITY)
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
