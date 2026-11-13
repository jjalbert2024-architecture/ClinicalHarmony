package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.clinicalharmony.gateway.read.FhirQueries;
import com.clinicalharmony.gateway.read.Page;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FHIR Viewer. Gold resources are a read model; the HL7 validator debugger and the C-CDA converter
 * are fhir-compliance-service's, proxied with the request body passed through VERBATIM (the
 * validator checks the raw JSON — re-serializing would hide e.g. empty strings).
 */
@RestController
@RequestMapping(value = "/api/v1/fhir", produces = MediaType.APPLICATION_JSON_VALUE)
public class FhirController {

    static final String FHIR_JSON = "application/fhir+json";
    private static final String TYPE = "[A-Z][A-Za-z]{1,49}";

    private final FhirQueries fhir;
    private final DownstreamClient downstream;

    public FhirController(FhirQueries fhir, DownstreamClient downstream) {
        this.fhir = fhir;
        this.downstream = downstream;
    }

    @GetMapping("/resource-types")
    public List<FhirQueries.TypeCount> types() {
        return fhir.types();
    }

    @GetMapping("/resources")
    public Page<FhirQueries.ResourceSummary> resources(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) Long masterPatientId,
            @RequestParam(required = false) Boolean compliant,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size) {
        return fhir.search(type, masterPatientId, compliant, q, page, size);
    }

    @GetMapping("/resources/{type}/{fhirId}")
    public FhirQueries.ResourceDetail resource(@PathVariable @Pattern(regexp = TYPE) String type,
                                               @PathVariable @Size(max = 100) String fhirId) {
        return fhir.detail(type, fhirId);
    }

    @PostMapping(value = "/validate", consumes = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON})
    public ResponseEntity<JsonNode> validate(@RequestBody String resource) {
        return Proxy.relay(downstream.post(Downstream.FHIR_COMPLIANCE, "/api/fhir/validate",
                MediaType.APPLICATION_JSON, resource));
    }

    @PostMapping(value = "/convert/ccda", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<JsonNode> convertCcda(@RequestBody String ccda) {
        return Proxy.relay(downstream.post(Downstream.FHIR_COMPLIANCE, "/api/fhir/convert/ccda",
                MediaType.APPLICATION_XML, ccda));
    }
}
