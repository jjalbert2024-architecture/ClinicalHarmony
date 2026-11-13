package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.clinicalharmony.gateway.read.OntologyQueries;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ontology Coverage view. Coverage is a read model; single-code lookups and cross-terminology
 * mapping are ontology-service's own API, proxied (it owns those semantics, incl. system aliases).
 */
@RestController
@RequestMapping(value = "/api/v1/ontology", produces = MediaType.APPLICATION_JSON_VALUE)
public class OntologyController {

    private final OntologyQueries ontology;
    private final DownstreamClient downstream;

    public OntologyController(OntologyQueries ontology, DownstreamClient downstream) {
        this.ontology = ontology;
        this.downstream = downstream;
    }

    @GetMapping("/coverage")
    public OntologyQueries.Coverage coverage() {
        return ontology.coverage();
    }

    /** ontology-service exposes single-code lookups for ICD-10 and LOINC. */
    @GetMapping("/codes/{system}/{code}")
    public ResponseEntity<JsonNode> code(@PathVariable @Pattern(regexp = "icd10|loinc",
                                                 message = "supported systems: icd10, loinc") String system,
                                         @PathVariable @Size(max = 20) String code) {
        return Proxy.relay(downstream.get(Downstream.ONTOLOGY, "/api/ontology/codes/{system}/{code}", system, code));
    }

    @GetMapping("/map")
    public ResponseEntity<JsonNode> map(@RequestParam @NotBlank @Size(max = 20) String source,
                                        @RequestParam @NotBlank @Size(max = 20) String code,
                                        @RequestParam @NotBlank @Size(max = 20) String target) {
        return Proxy.relay(downstream.get(Downstream.ONTOLOGY, "/api/ontology/map?source={s}&code={c}&target={t}",
                source, code, target));
    }
}
