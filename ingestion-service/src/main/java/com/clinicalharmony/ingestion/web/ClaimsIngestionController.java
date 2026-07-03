package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.ClaimsMessageProcessor;
import com.clinicalharmony.ingestion.service.ClaimsProcessingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Claims CSV intake ("ClaimsTransformer" in the system architecture). Accepts a whole CSV file
 * as text/plain or text/csv; the optional X-Source-System header identifies the sender.
 */
@RestController
@RequestMapping("/api/ingestion/claims")
public class ClaimsIngestionController {

    private final ClaimsMessageProcessor processor;

    public ClaimsIngestionController(ClaimsMessageProcessor processor) {
        this.processor = processor;
    }

    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClaimsProcessingResult> ingest(
            @RequestBody String rawCsv,
            @RequestHeader(name = "X-Source-System", required = false) String sourceSystem) {
        ClaimsProcessingResult result = processor.process(rawCsv, sourceSystem);
        HttpStatus status = result.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }
}
