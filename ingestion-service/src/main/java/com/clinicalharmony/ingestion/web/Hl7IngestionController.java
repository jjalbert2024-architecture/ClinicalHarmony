package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.Hl7MessageProcessor;
import com.clinicalharmony.ingestion.service.Hl7ProcessingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-MLLP entry point for HL7 v2 payloads — same parse/land path as {@link
 * com.clinicalharmony.ingestion.route.Hl7MllpRoute}, exposed over plain HTTP so the
 * pipeline can be exercised with curl/Postman without standing up an MLLP client.
 */
@RestController
@RequestMapping("/api/ingestion/hl7")
public class Hl7IngestionController {

    private final Hl7MessageProcessor processor;

    public Hl7IngestionController(Hl7MessageProcessor processor) {
        this.processor = processor;
    }

    @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Hl7IngestionResponse> ingest(@RequestBody String rawHl7Message) {
        Hl7ProcessingResult result = processor.process(rawHl7Message);
        HttpStatus status = result.accepted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Hl7IngestionResponse.from(result));
    }
}
