package com.clinicalharmony.patientindex.web;

import com.clinicalharmony.patientindex.client.GoldWriterRejectedException;
import com.clinicalharmony.patientindex.client.GoldWriterUnavailableException;
import com.clinicalharmony.patientindex.service.ConflictException;
import com.clinicalharmony.patientindex.service.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error is {@code {"error", "message"}} (plus {@code fields} for body validation), the same
 * shape as the other services. GoldWriter's refusals keep their status (404/409) with GoldWriter's
 * message; GoldWriter being unreachable is a 502.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException ex) {
        log.info("409: {}", ex.getMessage());
        return error(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(GoldWriterRejectedException.class)
    public ResponseEntity<Map<String, Object>> goldWriterRejected(GoldWriterRejectedException ex) {
        HttpStatus status = ex.status() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return error(status, status == HttpStatus.NOT_FOUND ? "Not found" : "Conflict",
                "GoldWriter refused the change: " + ex.getMessage());
    }

    @ExceptionHandler(GoldWriterUnavailableException.class)
    public ResponseEntity<Map<String, Object>> goldWriterUnavailable(GoldWriterUnavailableException ex) {
        log.warn("GoldWriter unavailable: {}", ex.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "GoldWriter unavailable", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError f : ex.getBindingResult().getFieldErrors()) {
            fields.put(f.getField(), f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "fields", fields));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> invalidParameter(Exception ex) {
        String message = ex instanceof MethodArgumentTypeMismatchException m
                ? "Parameter '" + m.getName() + "' has an invalid value: " + m.getValue()
                : ex instanceof HandlerMethodValidationException h
                ? h.getAllErrors().stream().map(e -> String.valueOf(e.getDefaultMessage())).reduce((a, b) -> a + "; " + b).orElse("invalid")
                : ex.getMessage();
        return error(HttpStatus.BAD_REQUEST, "Invalid request", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "Malformed request body", String.valueOf(ex.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", String.valueOf(ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "Not found", "No endpoint " + ex.getHttpMethod() + " /" + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("Unhandled exception in patient-index-service", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", String.valueOf(ex.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
