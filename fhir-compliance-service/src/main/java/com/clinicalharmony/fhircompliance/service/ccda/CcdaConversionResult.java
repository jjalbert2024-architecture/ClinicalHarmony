package com.clinicalharmony.fhircompliance.service.ccda;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Response of {@code POST /api/fhir/convert/ccda}. Summary first, then the details, then the
 * Bundle itself — field order is the JSON order, chosen so the answer to "did it work?" is at
 * the top of the response in Postman.
 *
 * @param allCompliant  true iff every generated resource has zero US Core ERRORs
 * @param sections      what was found per mapped section, e.g. {@code "Problems (11450-4): 2 resource(s)"}
 * @param notes         what the transformer couldn't carry over (distinct from validator findings)
 * @param resources     per-resource US Core validation, in Bundle entry order
 * @param bundle        the FHIR R4 Bundle (type {@code collection}) as raw FHIR JSON
 */
public record CcdaConversionResult(
        String documentId,
        String documentTitle,
        int resourceCount,
        int compliantCount,
        boolean allCompliant,
        List<String> sections,
        List<ConversionNote> notes,
        List<ConvertedResource> resources,
        JsonNode bundle
) {
}
