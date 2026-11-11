package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.service.ccda.CcdaConversionResult;
import com.clinicalharmony.fhircompliance.service.ccda.CcdaConversionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/fhir/convert/ccda} — a C-CDA R2.1 document (application/xml or text/xml) in, a
 * FHIR R4 Bundle out (Patient + Problems → Condition, Medications → MedicationRequest, Results →
 * Observation), with each resource's US Core 6.1.0 validation outcome.
 *
 * <p>200 for any parseable C-CDA, compliant or not (compliance is reported, not enforced). 400 for
 * malformed XML, a DOCTYPE, a non-ClinicalDocument root, or no patient; 413 over the size limit;
 * 415 for a non-XML content type.
 *
 * <p>The body is taken as raw bytes so the XML parser honors the document's own encoding
 * declaration instead of Spring guessing a charset.
 */
@RestController
@RequestMapping("/api/fhir/convert")
public class CcdaConversionController {

    private final CcdaConversionService conversionService;

    public CcdaConversionController(CcdaConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @PostMapping(value = "/ccda", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CcdaConversionResult> convertCcda(@RequestBody byte[] document) {
        return ResponseEntity.ok(conversionService.convert(document));
    }
}
