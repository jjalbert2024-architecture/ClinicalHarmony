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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final String ATTR = "attr.";

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

    /** Source systems whose patients have Gold resources — options for the source filter. */
    @GetMapping("/source-systems")
    public List<FhirQueries.SourceCount> sourceSystems() {
        return fhir.sourceSystems();
    }

    /**
     * The FHIR Viewer list. Newest first unless asked otherwise ({@code sort} defaults to {@code updated}, and
     * {@code dir} to {@code desc} for it, {@code asc} for every other column).
     *
     * @param q            resource id contains (the part after the slash, e.g. {@code silver-allergy-165})
     * @param patient      a patient's name, or exactly their MRN / source id — every resource of that person
     * @param origin       pipeline | silver-seed | api | gold-seed
     * @param sourceSystem source system of the patient the resource is about, e.g. CITY_HOSPITAL
     * @param code         an exact code (e.g. 723) or part of its display (e.g. Amoxicillin)
     * @param all          every parameter; the {@code attr.*} ones are the per-type filters of {@link #attributes}
     */
    @GetMapping("/resources")
    public Page<FhirQueries.ResourceSummary> resources(
            @RequestParam(required = false) @Pattern(regexp = TYPE) String type,
            @RequestParam(required = false) Long masterPatientId,
            @RequestParam(required = false) Boolean compliant,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 100) String patient,
            @RequestParam(required = false) @Pattern(regexp = FhirQueries.ORIGINS) String origin,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_.-]{1,50}") String sourceSystem,
            @RequestParam(required = false) @Size(max = 100) String code,
            @RequestParam(defaultValue = "updated") @Pattern(regexp = FhirQueries.SORTS) String sort,
            @RequestParam(required = false) @Pattern(regexp = "asc|desc") String dir,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) int size,
            @RequestParam Map<String, String> all) {
        boolean descending = dir == null ? "updated".equals(sort) : "desc".equals(dir);
        Map<String, String> attributes = new LinkedHashMap<>();
        all.forEach((name, value) -> {
            if (name.startsWith(ATTR)) {
                attributes.put(name.substring(ATTR.length()), value);
            }
        });
        return fhir.search(new FhirQueries.Filter(type, masterPatientId, compliant, q, patient, origin, sourceSystem, code,
                attributes), sort, descending, page, size);
    }

    /**
     * The per-type attribute filters (key, label, FHIR path, kind) and, for dropdowns, the values Gold holds with
     * their counts. Use them on {@code /resources} as {@code attr.<key>} or {@code attr.<key>From/To} (dates).
     */
    @GetMapping("/attributes")
    public List<FhirQueries.AttributeFacet> attributes(@RequestParam @Pattern(regexp = TYPE) String type) {
        return fhir.attributes(type);
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
