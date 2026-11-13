package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PMI review queue, proxied to patient-index-service (which applies each decision to Gold through
 * GoldWriter). The reviewer recorded downstream is the signed-in user's {@code preferred_username};
 * the request body only carries the note / reason. POSTs need {@code data-steward}.
 */
@RestController
@RequestMapping(value = "/api/v1/pmi", produces = MediaType.APPLICATION_JSON_VALUE)
public class PmiController {

    private static final Logger log = LoggerFactory.getLogger(PmiController.class);

    public record DecisionRequest(@Size(max = 2000) String note) {
    }

    public record UnmergeRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    private final DownstreamClient downstream;

    public PmiController(DownstreamClient downstream) {
        this.downstream = downstream;
    }

    @GetMapping("/candidates")
    public ResponseEntity<JsonNode> candidates(
            @RequestParam(defaultValue = "PENDING")
            @Pattern(regexp = "PENDING|ACCEPTED|REJECTED|AUTO_MERGED|SUPERSEDED|ALL") String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return Proxy.relay(downstream.get(Downstream.PATIENT_INDEX, "/api/pmi/candidates?status={status}&limit={limit}",
                status, limit));
    }

    @GetMapping("/candidates/{candidateId}")
    public ResponseEntity<JsonNode> candidate(@PathVariable long candidateId) {
        return Proxy.relay(downstream.get(Downstream.PATIENT_INDEX, "/api/pmi/candidates/{id}", candidateId));
    }

    @GetMapping("/runs")
    public ResponseEntity<JsonNode> runs(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return Proxy.relay(downstream.get(Downstream.PATIENT_INDEX, "/api/pmi/runs?limit={limit}", limit));
    }

    @PostMapping("/match/run")
    public ResponseEntity<JsonNode> run(@AuthenticationPrincipal Jwt jwt) {
        log.info("Matching run requested by {}", Reviewer.of(jwt));
        return Proxy.relay(downstream.post(Downstream.PATIENT_INDEX, "/api/pmi/match/run", null, null));
    }

    @PostMapping("/candidates/{candidateId}/accept")
    public ResponseEntity<JsonNode> accept(@PathVariable long candidateId, @AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody(required = false) DecisionRequest body) {
        return decide(candidateId, "accept", jwt, body);
    }

    @PostMapping("/candidates/{candidateId}/reject")
    public ResponseEntity<JsonNode> reject(@PathVariable long candidateId, @AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody(required = false) DecisionRequest body) {
        return decide(candidateId, "reject", jwt, body);
    }

    @PostMapping(value = "/merges/{mergeId}/unmerge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> unmerge(@PathVariable long mergeId, @AuthenticationPrincipal Jwt jwt,
                                            @Valid @RequestBody UnmergeRequest body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reviewer", Reviewer.of(jwt));
        request.put("reason", body.reason());
        return Proxy.relay(downstream.post(Downstream.PATIENT_INDEX, "/api/pmi/merges/{id}/unmerge",
                MediaType.APPLICATION_JSON, request, mergeId));
    }

    private ResponseEntity<JsonNode> decide(long candidateId, String decision, Jwt jwt, DecisionRequest body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reviewer", Reviewer.of(jwt));
        request.put("note", body == null ? null : body.note());
        return Proxy.relay(downstream.post(Downstream.PATIENT_INDEX, "/api/pmi/candidates/{id}/" + decision,
                MediaType.APPLICATION_JSON, request, candidateId));
    }
}
