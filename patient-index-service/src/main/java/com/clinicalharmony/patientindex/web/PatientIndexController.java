package com.clinicalharmony.patientindex.web;

import com.clinicalharmony.patientindex.matching.MatchScore;
import com.clinicalharmony.patientindex.repository.MasterRow;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.service.CandidateView;
import com.clinicalharmony.patientindex.service.MasterView;
import com.clinicalharmony.patientindex.service.MatchingRunService;
import com.clinicalharmony.patientindex.service.PatientIndexQueryService;
import com.clinicalharmony.patientindex.service.ReviewService;
import com.clinicalharmony.patientindex.service.RunResult;
import com.clinicalharmony.patientindex.web.dto.ManualMergeRequest;
import com.clinicalharmony.patientindex.web.dto.ReviewRequest;
import com.clinicalharmony.patientindex.web.dto.UnmergeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Patient Master Index REST API (port 8085). GETs need any valid Bearer token; POSTs need
 * {@code clinicalharmony/pipeline.write}.
 */
@RestController
@Validated
@RequestMapping(value = "/api/pmi", produces = MediaType.APPLICATION_JSON_VALUE)
public class PatientIndexController {

    private static final String MASTER_STATUS = "PROVISIONAL|MATCHED|MERGED";
    private static final String CANDIDATE_STATUS = "PENDING|ACCEPTED|REJECTED|AUTO_MERGED|SUPERSEDED";

    private final MatchingRunService runService;
    private final ReviewService reviewService;
    private final PatientIndexQueryService queries;

    public PatientIndexController(MatchingRunService runService, ReviewService reviewService,
                                  PatientIndexQueryService queries) {
        this.runService = runService;
        this.reviewService = reviewService;
        this.queries = queries;
    }

    // ---- matching runs ------------------------------------------------------------------------

    @PostMapping("/match/run")
    public ResponseEntity<RunResult> run(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(runService.run("api:" + client(jwt)));
    }

    @GetMapping("/runs")
    public List<MatchRun> runs(@RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return queries.runs(limit);
    }

    @GetMapping("/runs/{runId}")
    public MatchRun run(@PathVariable long runId) {
        return queries.run(runId);
    }

    @GetMapping("/score")
    public MatchScore score(@RequestParam("a") long silverPatientA, @RequestParam("b") long silverPatientB) {
        return queries.score(silverPatientA, silverPatientB);
    }

    // ---- masters ------------------------------------------------------------------------------

    @GetMapping("/masters")
    public List<MasterRow> masters(@RequestParam(required = false) @Pattern(regexp = MASTER_STATUS) String status,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return queries.masters(status, limit);
    }

    @GetMapping("/masters/{masterId}")
    public MasterView master(@PathVariable long masterId) {
        return queries.master(masterId);
    }

    @PostMapping(value = "/merge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> merge(@Valid @RequestBody ManualMergeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.manualMerge(request.survivorMasterId(),
                request.absorbedMasterId(), request.reviewer(), request.note()));
    }

    @PostMapping(value = "/merges/{mergeId}/unmerge", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewService.UnmergeOutcome unmerge(@PathVariable long mergeId, @Valid @RequestBody UnmergeRequest request) {
        return reviewService.unmerge(mergeId, request.reviewer(), request.reason());
    }

    // ---- review queue -------------------------------------------------------------------------

    @GetMapping("/candidates")
    public List<MatchCandidate> candidates(
            @RequestParam(defaultValue = "PENDING") @Pattern(regexp = CANDIDATE_STATUS + "|ALL") String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return queries.candidates("ALL".equals(status) ? null : status, limit);
    }

    @GetMapping("/candidates/{candidateId}")
    public CandidateView candidate(@PathVariable long candidateId) {
        return queries.candidate(candidateId);
    }

    @PostMapping(value = "/candidates/{candidateId}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewService.Decision accept(@PathVariable long candidateId, @Valid @RequestBody ReviewRequest request) {
        return reviewService.accept(candidateId, request.reviewer(), request.note());
    }

    @PostMapping(value = "/candidates/{candidateId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewService.Decision reject(@PathVariable long candidateId, @Valid @RequestBody ReviewRequest request) {
        return reviewService.reject(candidateId, request.reviewer(), request.note());
    }

    private static String client(Jwt jwt) {
        if (jwt == null) {
            return "unknown";
        }
        String azp = jwt.getClaimAsString("azp");
        return azp != null ? azp : String.valueOf(jwt.getSubject());
    }
}
