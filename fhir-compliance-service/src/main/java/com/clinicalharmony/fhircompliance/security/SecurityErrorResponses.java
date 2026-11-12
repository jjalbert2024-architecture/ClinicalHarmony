package com.clinicalharmony.fhircompliance.security;

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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 401/403 bodies. Under {@code /fhir/**} they're FHIR OperationOutcomes (issue code {@code login}
 * / {@code forbidden}), consistent with FhirR4ExceptionHandler; elsewhere the service's usual
 * {@code {"error","message"}} shape. Both carry an RFC 6750 {@code WWW-Authenticate} header, and
 * the 403 names the scope the token was missing. The correlation id header is already set by
 * MdcLoggingFilter, which runs ahead of the security filter chain.
 */
@Component
public class SecurityErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponses.class);
    static final String REALM = "clinicalharmony";
    private static final MediaType FHIR_JSON = new MediaType("application", "fhir+json", StandardCharsets.UTF_8);

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
                    + quoteSafe(description == null ? "invalid token" : description) + "\"";
        } else {
            message = "Authentication required: send a Bearer access token from the " + REALM
                    + " realm (see /.well-known/smart-configuration)";
            challenge = "Bearer realm=\"" + REALM + "\"";
        }
        log.info("401 {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        write(request, response, 401, "login", message, challenge);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        String scope = requiredScope(request);
        String message = "Access token lacks the scope required for " + request.getMethod() + " "
                + request.getRequestURI() + ": " + scope;
        String challenge = "Bearer realm=\"" + REALM + "\", error=\"insufficient_scope\", scope=\"" + scope + "\"";
        log.info("403 {}", message);
        write(request, response, 403, "forbidden", message, challenge);
    }

    static String requiredScope(HttpServletRequest request) {
        Optional<SmartScopeAuthorizationManager.Target> target = SmartScopeAuthorizationManager.target(request);
        return target.map(SmartScopeAuthorizationManager.Target::requiredScope).orElse(SmartScopes.PIPELINE_WRITE);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String issueCode,
                       String message, String challenge) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        Map<String, Object> body = new LinkedHashMap<>();
        if (isFhirPath(request)) {
            response.setContentType(FHIR_JSON.toString());
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("severity", "error");
            issue.put("code", issueCode);
            issue.put("diagnostics", message);
            body.put("resourceType", "OperationOutcome");
            body.put("issue", List.of(issue));
        } else {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            body.put("error", status == 401 ? "Unauthorized" : "Forbidden");
            body.put("message", message);
        }
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    static boolean isFhirPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/fhir") || path.startsWith("/fhir/");
    }

    private static String quoteSafe(String value) {
        return value.replace("\\", "").replace("\"", "'");
    }
}
