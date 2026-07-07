package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.repository.ConceptMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConceptMappingServiceTest {

    @Test
    void normalizesHyphenlessSystemAliasesBeforeQuerying() {
        ConceptMappingRepository repository = mock(ConceptMappingRepository.class);
        when(repository.findMappings(eq("SNOMED"), eq("44054006"), eq("ICD-10")))
                .thenReturn(List.of(new ConceptMapping("SNOMED", "44054006", "ICD-10", "E11.9", "EQUIVALENT", null)));
        ConceptMappingService service = new ConceptMappingService(repository);

        // caller passes "ICD10" (no hyphen), as the endpoint's documented query param does
        List<ConceptMapping> results = service.map("SNOMED", "44054006", "ICD10");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).targetCode()).isEqualTo("E11.9");
        verify(repository).findMappings("SNOMED", "44054006", "ICD-10");
    }

    @Test
    void returnsEmptyListWhenNoMappingExists() {
        ConceptMappingRepository repository = mock(ConceptMappingRepository.class);
        when(repository.findMappings(eq("SNOMED"), eq("000000"), eq("ICD-10"))).thenReturn(List.of());
        ConceptMappingService service = new ConceptMappingService(repository);

        List<ConceptMapping> results = service.map("SNOMED", "000000", "ICD-10");

        assertThat(results).isEmpty();
    }
}
