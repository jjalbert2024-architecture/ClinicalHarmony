package com.clinicalharmony.patientindex.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stamps a correlation id into every log line of a request (MDC {@code requestId}), honoring a
 * caller-supplied {@code X-Correlation-Id} and echoing it back. GoldWriterClient forwards the same
 * id to fhir-compliance-service, so one id follows a decision across both services' logs. Runs
 * before Spring Security so 401/403 responses carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_KEY = "requestId";

    /** Caller-supplied ids end up in log lines, so only accept a conservative charset/length. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CORRELATION_HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().substring(0, 8);
        MDC.put(REQUEST_ID_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_KEY);
        }
    }
}
