package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.gold.GoldMasterConflictException;
import com.clinicalharmony.fhircompliance.gold.GoldMasterNotFoundException;
import com.clinicalharmony.fhircompliance.service.ccda.CcdaDocumentTooLargeException;
import com.clinicalharmony.fhircompliance.service.ccda.InvalidCcdaDocumentException;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientNotFoundException;
import com.clinicalharmony.fhircompliance.validation.InvalidFhirResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(),
                    fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage());
        }
        Map<String, Object> body = Map.of("error", "Validation failed", "fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidFhirResourceException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFhir(InvalidFhirResourceException ex) {
        log.info("Rejected unparseable FHIR payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid FHIR resource", "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidCcdaDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCcda(InvalidCcdaDocumentException ex) {
        log.info("Rejected C-CDA payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid C-CDA document", "message", ex.getMessage()));
    }

    @ExceptionHandler(CcdaDocumentTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleCcdaTooLarge(CcdaDocumentTooLargeException ex) {
        log.info("Rejected oversized C-CDA payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Document too large", "message", ex.getMessage()));
    }

    @ExceptionHandler(SilverPatientNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSilverPatientNotFound(SilverPatientNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "message", ex.getMessage()));
    }

    @ExceptionHandler(GoldMasterNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleMasterNotFound(GoldMasterNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "message", ex.getMessage()));
    }

    @ExceptionHandler(GoldMasterConflictException.class)
    public ResponseEntity<Map<String, Object>> handleMasterConflict(GoldMasterConflictException ex) {
        log.info("Refused master-patient operation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Conflict", "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Malformed request body", "message", String.valueOf(ex.getMostSpecificCause().getMessage())));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("error", "Unsupported media type", "message", String.valueOf(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception in fhir-compliance-service", ex);
        Map<String, Object> body = Map.of("error", "Internal error", "message", String.valueOf(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
