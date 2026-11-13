package com.clinicalharmony.gateway.security;

import com.clinicalharmony.gateway.web.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401/403 as RFC 7807 problems (same shape as every other gateway error) with an RFC 6750
 * {@code WWW-Authenticate} challenge.
 */
@Component
public class SecurityProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblemResponses.class);
    static final String REALM = "clinicalharmony";

    private final ObjectMapper objectMapper;

    public SecurityProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        String detail;
        String challenge;
        if (ex instanceof OAuth2AuthenticationException oauth) {
            String description = oauth.getError().getDescription();
            detail = "Bearer token rejected" + (description == null ? "" : ": " + description);
            challenge = "Bearer realm=\"" + REALM + "\", error=\"invalid_token\", error_description=\""
                    + (description == null ? "invalid token" : description.replace("\\", "").replace("\"", "'")) + "\"";
        } else {
            detail = "Authentication required: sign in to the dashboard (Keycloak realm " + REALM
                    + ", client clinicalharmony-dashboard) and send the access token as a Bearer token";
            challenge = "Bearer realm=\"" + REALM + "\"";
        }
        log.info("401 {} {}: {}", request.getMethod(), request.getRequestURI(), detail);
        write(response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", detail,
                request.getRequestURI()), challenge);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        boolean action = !"GET".equals(request.getMethod()) && !request.getRequestURI().startsWith("/api/v1/fhir/");
        String required = action ? Roles.STEWARD : Roles.VIEWER + " or " + Roles.STEWARD;
        String detail = "Your account lacks the role required for " + request.getMethod() + " "
                + request.getRequestURI() + ": " + required;
        log.info("403 {}", detail);
        ProblemDetail problem = Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", detail, request.getRequestURI());
        problem.setProperty("requiredRole", required);
        write(response, problem, "Bearer realm=\"" + REALM + "\", error=\"insufficient_scope\"");
    }

    private void write(HttpServletResponse response, ProblemDetail problem, String challenge) throws IOException {
        response.setStatus(problem.getStatus());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
