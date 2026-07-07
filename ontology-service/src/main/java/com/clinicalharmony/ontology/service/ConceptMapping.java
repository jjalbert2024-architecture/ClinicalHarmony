package com.clinicalharmony.ontology.service;

/** Mirrors a row of ontology.concept_mappings. */
public record ConceptMapping(
        String sourceSystem,
        String sourceCode,
        String targetSystem,
        String targetCode,
        String mappingType,
        String notes
) {
}
