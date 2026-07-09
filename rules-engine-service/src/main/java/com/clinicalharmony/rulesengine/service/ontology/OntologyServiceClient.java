package com.clinicalharmony.rulesengine.service.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls ontology-service's /api/ontology/validate. Response is returned as a raw JsonNode
 * rather than a typed mirror of OntologyValidationResponse — the two services are separate
 * Maven modules with no shared code, and the caller (RulesOrchestrationService) only needs
 * `allValid` plus whatever detail it passes through untouched in the aggregated report.
 */
@Component
public class OntologyServiceClient {

    private final RestClient restClient;

    public OntologyServiceClient(RestClient.Builder builder, @Value("${ontology-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public JsonNode validate(OntologyPatientRecordRequest request) {
        return restClient.post()
                .uri("/api/ontology/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }
}
