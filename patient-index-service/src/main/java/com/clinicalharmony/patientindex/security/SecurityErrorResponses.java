package com.clinicalharmony.patientindex.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401/403 in the service's usual {@code {"error","message"}} shape with an RFC 6750
 * {@code WWW-Authenticate} challenge — same wording as fhir-compliance-service's non-FHIR paths.
 */
@Component
public class SecurityErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponses.class);
    static final String REALM = "clinicalharmony";

    private final ObjectMapper objectMapper;

    public SecurityErrorResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        String message;
        String challenge;
        if (ex instanceof OAuth2AuthenticationException oauth) {
            String description = oauth.getError().getDescription();
            message = "Bearer token rejected" + (description == null ? "" : ": " + description);
            challenge = "Bearer realm=\"" + REALM + "\", error=\"invalid_token\", error_description=\""
                    + (description == null ? "invalid token" : description.replace("\\", "").replace("\"", "'")) + "\"";
        } else {
            message = "Authentication required: send a Bearer access token from the " + REALM + " realm";
            challenge = "Bearer realm=\"" + REALM + "\"";
        }
        log.info("401 {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        write(response, 401, message, challenge);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        String message = "Access token lacks the scope required for " + request.getMethod() + " "
                + request.getRequestURI() + ": " + SecurityConfig.PIPELINE_WRITE;
        log.info("403 {}", message);
        write(response, 403, message, "Bearer realm=\"" + REALM + "\", error=\"insufficient_scope\", scope=\""
                + SecurityConfig.PIPELINE_WRITE + "\"");
    }

    private void write(HttpServletResponse response, int status, String message, String challenge) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status == 401 ? "Unauthorized" : "Forbidden");
        body.put("message", message);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
