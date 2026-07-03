package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.FhirMessageProcessor;
import com.clinicalharmony.ingestion.service.FhirProcessingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR JSON intake ("FHIRNormalizer" in the system architecture). Accepts a single FHIR
 * resource or a Bundle; the optional X-Source-System header identifies the sender.
 */
@RestController
@RequestMapping("/api/ingestion/fhir")
public class FhirIngestionController {

    private final FhirMessageProcessor processor;

    public FhirIngestionController(FhirMessageProcessor processor) {
        this.processor = processor;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FhirProcessingResult> ingest(
            @RequestBody String rawFhirJson,
            @RequestHeader(name = "X-Source-System", required = false) String sourceSystem) {
        FhirProcessingResult result = processor.process(rawFhirJson, sourceSystem);
        HttpStatus status = result.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }
}
