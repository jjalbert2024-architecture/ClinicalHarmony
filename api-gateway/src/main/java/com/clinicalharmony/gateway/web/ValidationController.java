package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.clinicalharmony.gateway.downstream.RuleOwnership;
import com.clinicalharmony.gateway.read.Page;
import com.clinicalharmony.gateway.read.ValidationQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Validation Report view: findings + one rule catalog across all three validating services.
 * Toggling a rule is routed to the service that evaluates it ({@link RuleOwnership}); the response
 * is the gateway's own rule view re-read from the catalog, so its shape doesn't depend on which
 * service did the toggle.
 */
@RestController
@RequestMapping(value = "/api/v1/validation", produces = MediaType.APPLICATION_JSON_VALUE)
public class ValidationController {

    private static final Logger log = LoggerFactory.getLogger(ValidationController.class);

    private final ValidationQueries validation;
    private final DownstreamClient downstream;

    public ValidationController(ValidationQueries validation, DownstreamClient downstream) {
        this.validation = validation;
        this.downstream = downstream;
    }

    @GetMapping("/summary")
    public ValidationQueries.Summary summary() {
        return validation.summary();
    }

    @GetMapping("/findings")
    public Page<ValidationQueries.Finding> findings(
            @RequestParam(required = false) @Size(max = 50) String ruleCode,
            @RequestParam(required = false) @Pattern(regexp = "ERROR|WARNING|INFO") String severity,
            @RequestParam(required = false) @Size(max = 30) String entityType,
            @RequestParam(required = false)
            @Pattern(regexp = "CODE_VALIDATION|DATA_QUALITY|CLINICAL_LOGIC|IDENTITY") String category,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return validation.findings(ruleCode, severity, entityType, category, patientId, resolved, page, size);
    }

    @GetMapping("/rules")
    public List<ValidationQueries.Rule> rules() {
        return validation.rules();
    }

    @GetMapping("/rules/{code}")
    public ValidationQueries.Rule rule(@PathVariable @Size(max = 50) String code) {
        return validation.rule(code);
    }

    @PutMapping("/rules/{code}/toggle")
    public ValidationQueries.Rule toggle(@PathVariable @Size(max = 50) String code, @AuthenticationPrincipal Jwt jwt) {
        ValidationQueries.Rule rule = validation.rule(code); // 404 for an unknown code
        if (!rule.toggleable()) {
            throw new ApiException(HttpStatus.CONFLICT, "rule-not-toggleable", "Rule " + code + " can't be toggled "
                    + "through the gateway: " + (rule.evaluatedBy() == null ? "no service evaluates it yet"
                    : rule.evaluatedBy() + " has no toggle endpoint for it"));
        }
        log.info("{} toggles rule {} (active={}) via {}", Reviewer.of(jwt), code, rule.active(), rule.evaluatedBy());
        downstream.put(RuleOwnership.evaluatedBy(code), RuleOwnership.toggleUri(code), code);
        return validation.rule(code);
    }
}
