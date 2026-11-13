package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.read.LineageQueries;
import com.clinicalharmony.gateway.read.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Data Lineage view: Gold lineage chains and the Bronze messages (incl. quarantined ones) behind them. */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class LineageController {

    private final LineageQueries lineage;

    public LineageController(LineageQueries lineage) {
        this.lineage = lineage;
    }

    /** Exactly one of {@code fhirResourceId} (gold.fhir_resources.id) or {@code masterPatientId}. */
    @GetMapping("/lineage")
    public LineageQueries.LineageGraph lineage(@RequestParam(required = false) Long fhirResourceId,
                                               @RequestParam(required = false) Long masterPatientId) {
        if ((fhirResourceId == null) == (masterPatientId == null)) {
            throw ApiException.badRequest("Pass exactly one of fhirResourceId or masterPatientId");
        }
        return fhirResourceId != null ? lineage.forFhirResource(fhirResourceId) : lineage.forMasterPatient(masterPatientId);
    }

    @GetMapping("/lineage/summary")
    public List<LineageQueries.StepCount> summary() {
        return lineage.summary();
    }

    @GetMapping("/bronze/messages")
    public Page<LineageQueries.BronzeSummary> bronzeMessages(
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|PROCESSING|PROCESSED|FAILED|QUARANTINED") String status,
            @RequestParam(required = false) @Pattern(regexp = "HL7V2|FHIR_JSON|CLAIMS_CSV") String messageType,
            @RequestParam(required = false) @Size(max = 100) String sourceSystem,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return lineage.bronzeMessages(status, messageType, sourceSystem, page, size);
    }

    @GetMapping("/bronze/messages/{id}")
    public LineageQueries.BronzeDetail bronzeMessage(@PathVariable long id) {
        return lineage.bronzeMessage(id);
    }
}
