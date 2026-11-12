package com.clinicalharmony.fhircompliance.config;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.ValueSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Offline stand-ins for the three Da Vinci US Drug Formulary 2.1.0 value sets that
 * {@code usdf-FormularyDrug} binds MedicationKnowledge.code's slices to:
 * <ul>
 *   <li>{@code NonPackSemanticDrugVS} — RxNorm TTY = SCD | SBD</li>
 *   <li>{@code SemanticDrugVS} — NonPackSemanticDrugVS + TTY = GPCK | BPCK</li>
 *   <li>{@code SemanticDrugFormGroupVS} — RxNorm TTY = SCDG | SBDG</li>
 * </ul>
 * The IG defines them as RxNorm {@code TTY} property filters, which need an RxNorm terminology
 * server to evaluate. Without one, every coding "matches" both the semantic-drug and the
 * form-group slice, and the validator reports {@code Validation_VAL_Profile_MatchMultiple} on
 * every formulary drug, compliant or not.
 *
 * <p>Same posture as {@link OmbRaceEthnicityValueSets}: the value sets keep their real canonical
 * URLs and TTY semantics, but membership is evaluated against the RxNorm concepts whose TTY is
 * known locally ({@code terminology/rxnorm-formulary-tty.csv}, looked up from NLM RxNav). An RxNorm
 * code that isn't in that file is NOT in these value sets offline and fails the required binding —
 * a known limitation, stated in the validation response, rather than a silent pass.
 *
 * <p>Must sit <em>before</em> the NpmPackageValidationSupport in the chain: the Formulary package
 * ships the filter-based definitions under the same URLs, and the chain returns the first match.
 */
final class RxNormFormularyValueSets {

    static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    private static final String VS = "http://hl7.org/fhir/us/davinci-drug-formulary/ValueSet/";
    static final String NON_PACK_SEMANTIC_DRUG = VS + "NonPackSemanticDrugVS";
    static final String SEMANTIC_DRUG = VS + "SemanticDrugVS";
    static final String SEMANTIC_DRUG_FORM_GROUP = VS + "SemanticDrugFormGroupVS";

    private RxNormFormularyValueSets() {
    }

    record RxNormConcept(String rxcui, String tty, String name) {
    }

    static PrePopulatedValidationSupport create(FhirContext fhirContext) {
        List<RxNormConcept> concepts = loadConcepts("/terminology/rxnorm-formulary-tty.csv");
        PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(fhirContext);
        support.addValueSet(valueSet(NON_PACK_SEMANTIC_DRUG, "Non-Pack Semantic Drug (offline RxNorm subset)",
                concepts, Set.of("SCD", "SBD")));
        support.addValueSet(valueSet(SEMANTIC_DRUG, "Semantic Drug (offline RxNorm subset)",
                concepts, Set.of("SCD", "SBD", "GPCK", "BPCK")));
        support.addValueSet(valueSet(SEMANTIC_DRUG_FORM_GROUP, "Semantic Drug Form Group (offline RxNorm subset)",
                concepts, Set.of("SCDG", "SBDG")));
        return support;
    }

    private static ValueSet valueSet(String url, String title, List<RxNormConcept> concepts, Set<String> ttys) {
        ValueSet valueSet = new ValueSet();
        valueSet.setId(url.substring(url.lastIndexOf('/') + 1));
        valueSet.setUrl(url);
        valueSet.setTitle(title);
        valueSet.setStatus(PublicationStatus.ACTIVE);
        ValueSet.ConceptSetComponent include = valueSet.getCompose().addInclude().setSystem(RXNORM);
        concepts.stream()
                .filter(c -> ttys.contains(c.tty()))
                .forEach(c -> include.addConcept().setCode(c.rxcui()).setDisplay(c.name()));
        return valueSet;
    }

    static List<RxNormConcept> loadConcepts(String classpathLocation) {
        try (InputStream in = RxNormFormularyValueSets.class.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + classpathLocation);
            }
            List<RxNormConcept> concepts = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(",", 3);
                concepts.add(new RxNormConcept(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
            return concepts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
