package com.clinicalharmony.ingestion.service;

import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lands inbound FHIR JSON payloads (single resources or Bundles) in bronze.raw_messages.
 * Validation here is deliberately syntactic only — well-formed JSON with a resourceType —
 * because profile/US Core conformance is the fhir-compliance-service's job (Phase 5), not
 * ingestion's. Like the HL7 path, malformed payloads are quarantined, never dropped.
 */
@Service
public class FhirMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(FhirMessageProcessor.class);

    private final RawMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final String defaultSourceSystem;

    public FhirMessageProcessor(RawMessageRepository repository,
                                ObjectMapper objectMapper,
                                @Value("${ingestion.fhir.default-source-system}") String defaultSourceSystem) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.defaultSourceSystem = defaultSourceSystem;
    }

    /**
     * @param sourceSystem originating system as declared by the caller (X-Source-System header),
     *                     or null/blank to fall back to the configured default — FHIR payloads
     *                     carry no universally reliable sending-facility field of their own.
     */
    public FhirProcessingResult process(String rawJson, String sourceSystem) {
        String checksum = Checksums.sha256(rawJson);
        String effectiveSource = (sourceSystem == null || sourceSystem.isBlank())
                ? defaultSourceSystem
                : sourceSystem;

        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode resourceTypeNode = root.path("resourceType");
            if (!resourceTypeNode.isTextual() || resourceTypeNode.asText().isBlank()) {
                throw new IllegalArgumentException("JSON has no resourceType field — not a FHIR resource");
            }
            String resourceType = resourceTypeNode.asText();
            int resourceCount = "Bundle".equals(resourceType) ? root.path("entry").size() : 1;

            long id = repository.insert(
                    RawMessageRecord.accepted(effectiveSource, "FHIR_JSON", "JSON", rawJson, checksum));
            log.info("Landed FHIR payload id={} resourceType={} resources={} source={}",
                    id, resourceType, resourceCount, effectiveSource);
            return new FhirProcessingResult(true, id, resourceType, resourceCount);
        } catch (Exception e) {
            log.warn("Failed to parse FHIR payload, quarantining: {}", e.getMessage());
            long id = repository.insert(
                    RawMessageRecord.quarantined(effectiveSource, "FHIR_JSON", "JSON", rawJson, checksum, e.getMessage()));
            return new FhirProcessingResult(false, id, null, 0);
        }
    }
}
