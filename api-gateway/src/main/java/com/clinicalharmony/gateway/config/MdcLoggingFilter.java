package com.clinicalharmony.gateway.config;

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
 * Correlation id for every request: honors a caller-supplied {@code X-Correlation-Id} (the UI may
 * mint one per user action), else generates one; puts it in the MDC ({@code requestId}) for log
 * lines, echoes it on the response, and {@code DownstreamClient} forwards it on every downstream
 * call. Runs before Spring Security and CORS so 401/403 and preflight responses carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_KEY = "requestId";

    /** Caller-supplied ids end up in log lines and downstream headers: conservative charset/length only. */
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

    /** The current request's correlation id, or null outside a request. */
    public static String current() {
        return MDC.get(REQUEST_ID_KEY);
    }
}
