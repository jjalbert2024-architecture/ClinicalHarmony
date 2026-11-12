package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.cms0057.FhirApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns errors on the {@code /fhir/r4} endpoints into FHIR OperationOutcomes. Scoped to
 * FhirR4Controller and ordered ahead of GlobalExceptionHandler, so the rest of the service keeps
 * its existing {"error", "message"} shape.
 */
@RestControllerAdvice(assignableTypes = FhirR4Controller.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FhirR4ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(FhirR4ExceptionHandler.class);

    @ExceptionHandler(FhirApiException.class)
    public ResponseEntity<Map<String, Object>> handleFhirApi(FhirApiException ex) {
        log.info("FHIR API {} ({}): {}", ex.httpStatus(), ex.issueCode(), ex.getMessage());
        return outcome(ex.httpStatus(), ex.issueCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception on /fhir/r4", ex);
        return outcome(500, "exception", "Internal error");
    }

    static ResponseEntity<Map<String, Object>> outcome(int status, String code, String diagnostics) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("severity", status >= 500 ? "fatal" : "error");
        issue.put("code", code);
        issue.put("diagnostics", diagnostics);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceType", "OperationOutcome");
        body.put("issue", List.of(issue));
        return ResponseEntity.status(status).contentType(FhirR4Controller.FHIR_JSON).body(body);
    }
}
