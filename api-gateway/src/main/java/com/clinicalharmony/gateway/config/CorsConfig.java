package com.clinicalharmony.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * CORS for the dashboard (default origin {@code http://localhost:3000}, env
 * {@code CORS_ALLOWED_ORIGINS}, comma-separated). Bearer tokens only — no cookies — so
 * credentials stay off. {@code X-Correlation-Id} is exposed so the UI can show it next to an
 * error. Applied by Spring Security's {@code cors()} so preflights never hit the auth checks.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins().stream().map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", MdcLoggingFilter.CORRELATION_HEADER));
        cors.setExposedHeaders(List.of(MdcLoggingFilter.CORRELATION_HEADER, "WWW-Authenticate"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
