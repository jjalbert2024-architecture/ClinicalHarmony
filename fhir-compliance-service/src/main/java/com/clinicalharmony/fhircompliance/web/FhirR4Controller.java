package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.cms0057.GoldFhirReadService;
import com.clinicalharmony.fhircompliance.cms0057.GoldFhirReadService.ReadResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/**
 * CMS-0057-F (Interoperability and Prior Authorization final rule) payer read endpoints, served
 * from Gold: {@code GET /fhir/r4/{Coverage,PractitionerRole,MedicationKnowledge,MeasureReport,Device}}.
 *
 * <ul>
 *   <li>{@code GET /fhir/r4/{type}/{id}} — read. 200 resource; 404 OperationOutcome for an unknown
 *       id or unsupported type. Headers report the stored validation verdict:
 *       {@code X-Profile-Compliant} (true/false/pending) and {@code X-Validated-Profile}.</li>
 *   <li>{@code GET /fhir/r4/{type}?params} — basic search → searchset Bundle. Per-type parameter
 *       whitelist (see Cms0057SearchParameters); unknown parameters, modifiers or malformed values
 *       → 400 OperationOutcome.</li>
 * </ul>
 * Errors are FHIR OperationOutcomes (FhirR4ExceptionHandler), not this service's usual JSON error
 * shape, since FHIR clients expect one.
 */
@RestController
@RequestMapping("/fhir/r4")
public class FhirR4Controller {

    static final MediaType FHIR_JSON = new MediaType("application", "fhir+json", StandardCharsets.UTF_8);
    static final String COMPLIANT_HEADER = "X-Profile-Compliant";
    static final String PROFILE_HEADER = "X-Validated-Profile";

    private final GoldFhirReadService readService;

    public FhirR4Controller(GoldFhirReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/{resourceType}/{id}")
    public ResponseEntity<String> read(@PathVariable String resourceType, @PathVariable String id) {
        ReadResult result = readService.read(resourceType, id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .header(COMPLIANT_HEADER, result.compliance());
        if (result.profile() != null) {
            response.header(PROFILE_HEADER, result.profile());
        }
        if (result.row().updatedAt() != null) {
            response.lastModified(result.row().updatedAt().toInstant())
                    .header(HttpHeaders.ETAG, "W/\"" + result.row().updatedAt().toInstant().toEpochMilli() + "\"");
        }
        return response.body(result.row().resourceJson());
    }

    @GetMapping("/{resourceType}")
    public ResponseEntity<String> search(@PathVariable String resourceType,
                                         @RequestParam MultiValueMap<String, String> params) {
        String selfUrl = ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString();
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/fhir/r4/").build().toUriString();
        return ResponseEntity.ok().contentType(FHIR_JSON)
                .body(readService.search(resourceType, params, selfUrl, baseUrl));
    }
}
