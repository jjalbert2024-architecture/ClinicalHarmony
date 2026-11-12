package com.clinicalharmony.fhircompliance.security;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * For the existing controller slice tests, which test controller behavior rather than auth: the
 * real security filter chain, with every request carrying a JWT that has every supported scope.
 * Auth itself is covered by SecurityWebTest (no default token) and SecurityIntegrationTest.
 */
@TestConfiguration
@Import({SecurityConfig.class, SecurityErrorResponses.class})
public class WebSecurityTestConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return mock(JwtDecoder.class);
    }

    @Bean
    MockMvcBuilderCustomizer allScopesByDefault() {
        SimpleGrantedAuthority[] authorities = SmartScopes.supported().stream()
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                .toArray(SimpleGrantedAuthority[]::new);
        return builder -> builder.defaultRequest(get("/").with(jwt().authorities(authorities)));
    }
}
