package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.repository.ConceptMappingRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Cross-terminology lookups (e.g. SNOMED CT -> ICD-10) backed by ontology.concept_mappings.
 * Accepts common aliases for system names ("ICD10" as well as "ICD-10") since callers won't
 * necessarily know the exact stored spelling.
 */
@Service
public class ConceptMappingService {

    private static final Map<String, String> SYSTEM_ALIASES = Map.of(
            "ICD10", "ICD-10",
            "ICD-10", "ICD-10",
            "SNOMED", "SNOMED",
            "SNOMEDCT", "SNOMED",
            "SNOMED-CT", "SNOMED"
    );

    private final ConceptMappingRepository repository;

    public ConceptMappingService(ConceptMappingRepository repository) {
        this.repository = repository;
    }

    public List<ConceptMapping> map(String source, String code, String target) {
        return repository.findMappings(normalizeSystem(source), code, normalizeSystem(target));
    }

    private static String normalizeSystem(String system) {
        if (system == null) {
            return null;
        }
        String upper = system.toUpperCase();
        return SYSTEM_ALIASES.getOrDefault(upper, upper);
    }
}
