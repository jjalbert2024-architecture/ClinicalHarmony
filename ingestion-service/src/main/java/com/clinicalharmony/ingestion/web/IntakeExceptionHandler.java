package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.intake.RestIntakeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Not-stored outcomes (400 / 413 / 503 + Retry-After). Body {@code {"error","message","correlationId"}} — the
 * shape the api-gateway's DownstreamErrors already reads.
 */
@RestControllerAdvice(assignableTypes = RestIntakeController.class)
public class IntakeExceptionHandler {

    @ExceptionHandler(RestIntakeService.IntakeRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(RestIntakeService.IntakeRejectedException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("message", e.getMessage());
        body.put("correlationId", e.correlationId());
        ResponseEntity.BodyBuilder r = ResponseEntity.status(e.status())
                .header(RestIntakeController.CORRELATION, e.correlationId());
        if (e.retryAfterSeconds() != null) {
            r.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return r.body(body);
    }
}
