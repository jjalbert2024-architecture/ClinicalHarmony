package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.MasterMergeCommand;
import com.clinicalharmony.fhircompliance.gold.MasterMergeResult;
import com.clinicalharmony.fhircompliance.gold.MasterUnmergeResult;
import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;
import com.clinicalharmony.fhircompliance.web.dto.MasterDecisionRequest;
import com.clinicalharmony.fhircompliance.web.dto.MergeMastersRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master-patient decisions from patient-index-service (Phase 6), applied by GoldWriter — which
 * stays the only writer of gold.*. Every endpoint needs {@code clinicalharmony/pipeline.write}.
 *
 * <ul>
 *   <li>{@code POST /api/fhir/gold/masters/merge} — absorb one master into another (201)</li>
 *   <li>{@code POST /api/fhir/gold/masters/{id}/confirm} — PROVISIONAL → MATCHED (200, idempotent)</li>
 *   <li>{@code POST /api/fhir/gold/masters/merges/{mergeId}/unmerge} — undo a merge (200)</li>
 * </ul>
 * 404 for an unknown master/merge, 409 when Gold's state doesn't allow it (nothing written).
 * {@code decided_by} records the caller's {@code decidedBy} plus the token's client, so the audit
 * trail shows both the reviewer and the service that relayed the decision.
 */
@RestController
@RequestMapping(value = "/api/fhir/gold/masters", produces = MediaType.APPLICATION_JSON_VALUE)
public class GoldMasterController {

    private final GoldWriter goldWriter;

    public GoldMasterController(GoldWriter goldWriter) {
        this.goldWriter = goldWriter;
    }

    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MasterMergeResult> merge(@Valid @RequestBody MergeMastersRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        JsonNode evidence = request.evidence();
        MasterMergeResult result = goldWriter.mergeMasters(new MasterMergeCommand(
                request.survivorMasterId(), request.absorbedMasterId(), request.matchConfidenceScore(),
                request.matchMethod(), evidence == null || evidence.isNull() ? null : evidence.toString(),
                auditName(request.decidedBy(), jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping(value = "/{masterId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MasterPatientSnapshot> confirm(@PathVariable long masterId,
                                                         @Valid @RequestBody MasterDecisionRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(goldWriter.confirmMaster(masterId, auditName(request.decidedBy(), jwt)));
    }

    @PostMapping(value = "/merges/{mergeId}/unmerge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MasterUnmergeResult> unmerge(@PathVariable long mergeId,
                                                       @Valid @RequestBody MasterDecisionRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(goldWriter.unmergeMasters(mergeId, auditName(request.decidedBy(), jwt), request.reason()));
    }

    /** "reviewer:dana via patient-index-service" — fits decided_by's VARCHAR(100). */
    static String auditName(String decidedBy, Jwt jwt) {
        String client = jwt == null ? null : firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id"),
                jwt.getSubject());
        String name = client == null ? decidedBy : decidedBy + " via " + client;
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
