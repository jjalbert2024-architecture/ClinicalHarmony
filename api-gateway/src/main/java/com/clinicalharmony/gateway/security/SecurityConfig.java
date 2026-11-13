package com.clinicalharmony.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;

/**
 * Access matrix (USER tokens from the {@code clinicalharmony-dashboard} PKCE client — audience
 * {@code clinicalharmony-gateway}, realm roles in claim {@code roles}):
 * <table>
 *   <tr><th>Who</th><th>What</th></tr>
 *   <tr><td>anyone</td><td>{@code /actuator/health}, {@code GET /api/v1/config} (what the SPA needs to log in)</td></tr>
 *   <tr><td>{@code dashboard-viewer} or {@code data-steward}</td><td>every other {@code /api/v1/**} read, plus
 *       the side-effect-free POSTs {@code /api/v1/fhir/validate} and {@code /api/v1/fhir/convert/ccda}</td></tr>
 *   <tr><td>{@code data-steward}</td><td>actions: {@code POST /api/v1/pmi/**} (match runs, review decisions,
 *       unmerge) and {@code PUT /api/v1/validation/rules/{code}/toggle}</td></tr>
 * </table>
 * Downstream calls never carry the user's token: the gateway uses its own client
 * ({@code api-gateway}, client_credentials) — the user's role is what authorizes the action here.
 */
@Configuration
public class SecurityConfig {

    static final String VIEWER = "ROLE_" + Roles.VIEWER;
    static final String STEWARD = "ROLE_" + Roles.STEWARD;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemResponses problems) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless Bearer-token API, no cookies
                .cors(Customizer.withDefaults())        // CorsConfig's CorsConfigurationSource
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/config").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pmi/**").hasAuthority(STEWARD)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/validation/rules/*/toggle").hasAuthority(STEWARD)
                        .requestMatchers("/api/v1/**").hasAnyAuthority(VIEWER, STEWARD)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems));
        return http.build();
    }

    /** Realm roles (claim {@code roles}) → {@code ROLE_*}; principal name = {@code preferred_username}. */
    static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
