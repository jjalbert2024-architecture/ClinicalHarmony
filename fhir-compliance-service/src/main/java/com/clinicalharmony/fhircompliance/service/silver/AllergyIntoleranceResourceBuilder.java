package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverAllergyRow;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceCategory;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceCriticality;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceSeverity;
import org.hl7.fhir.r4.model.AllergyIntolerance.AllergyIntoleranceType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.util.Locale;

import static com.clinicalharmony.fhircompliance.service.silver.Narratives.isBlank;

/**
 * silver.allergies (HL7 AL1, Phase 9b) → US Core AllergyIntolerance (Phase 9c).
 *
 * <p>Mapping, never inventing:
 * <ul>
 *   <li>{@code code} — the allergen as coded by the sender (RxNorm / SNOMED CT; LOCAL codes keep their text only).</li>
 *   <li>{@code clinicalStatus} = active: an AL1 segment lists an allergy the patient currently has (HL7 v2
 *       sends a removal as a separate action, which the generator and the Silver mapper don't produce).
 *       Required by invariant ait-1 whenever verificationStatus isn't entered-in-error.</li>
 *   <li>{@code verificationStatus} is left out: AL1 doesn't say whether the allergy was confirmed.</li>
 *   <li>AL1-2 type (table 0127) → {@code type} + {@code category}: DA → allergy / medication, FA → allergy /
 *       food, EA, AA, PA, LA → allergy / environment; MA / MC (miscellaneous) → no type or category.</li>
 *   <li>AL1-4 severity (table 0128) → {@code reaction.severity} (SV severe, MO moderate, MI mild) and
 *       {@code criticality} (SV → high, MO / MI → low, U → unable-to-assess). FHIR keeps the two apart
 *       (how bad the last reaction was vs. the risk of the next one); HL7 v2 has only the one field, so
 *       both are derived from it and the narrative says so.</li>
 *   <li>AL1-5 reaction → {@code reaction.manifestation} as text only (it's free text in the feed).</li>
 * </ul>
 */
@Component
public class AllergyIntoleranceResourceBuilder {

    static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    static final String SNOMED = "http://snomed.info/sct";
    static final String CLINICAL_STATUS_SYSTEM = "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical";

    public AllergyIntolerance build(SilverAllergyRow row, String patientFhirId) {
        AllergyIntolerance allergy = new AllergyIntolerance();
        allergy.setId(FhirIds.allergyIntolerance(row.id()));
        allergy.getMeta().addProfile(UsCoreProfiles.ALLERGY_INTOLERANCE);

        allergy.setClinicalStatus(new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(CLINICAL_STATUS_SYSTEM, "active", "Active")));

        String type = row.allergyType() == null ? "" : row.allergyType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "DA" -> {
                allergy.setType(AllergyIntoleranceType.ALLERGY);
                allergy.addCategory(AllergyIntoleranceCategory.MEDICATION);
            }
            case "FA" -> {
                allergy.setType(AllergyIntoleranceType.ALLERGY);
                allergy.addCategory(AllergyIntoleranceCategory.FOOD);
            }
            case "EA", "AA", "PA", "LA" -> {
                allergy.setType(AllergyIntoleranceType.ALLERGY);
                allergy.addCategory(AllergyIntoleranceCategory.ENVIRONMENT);
            }
            default -> {
                // MA / MC / absent: nothing in AL1-2 says which FHIR type or category applies.
            }
        }

        String severity = row.severity() == null ? "" : row.severity().trim().toUpperCase(Locale.ROOT);
        switch (severity) {
            case "SV" -> allergy.setCriticality(AllergyIntoleranceCriticality.HIGH);
            case "MO", "MI" -> allergy.setCriticality(AllergyIntoleranceCriticality.LOW);
            case "U" -> allergy.setCriticality(AllergyIntoleranceCriticality.UNABLETOASSESS);
            default -> {
            }
        }

        CodeableConcept code = new CodeableConcept();
        String system = systemUri(row.codeSystem());
        if (system != null && !isBlank(row.code())) {
            code.addCoding().setSystem(system).setCode(row.code().trim())
                    .setDisplay(isBlank(row.display()) ? null : row.display());
        }
        if (!isBlank(row.display())) {
            code.setText(row.display());
        } else if (system == null && !isBlank(row.code())) {
            code.setText(row.code().trim()); // LOCAL code: keep what the sender said, as text
        }
        if (code.hasCoding() || code.hasText()) {
            allergy.setCode(code);
        }

        allergy.setPatient(new Reference("Patient/" + patientFhirId));

        if (!isBlank(row.reaction())) {
            AllergyIntolerance.AllergyIntoleranceReactionComponent reaction = allergy.addReaction();
            reaction.addManifestation(new CodeableConcept().setText(row.reaction().trim()));
            switch (severity) {
                case "SV" -> reaction.setSeverity(AllergyIntoleranceSeverity.SEVERE);
                case "MO" -> reaction.setSeverity(AllergyIntoleranceSeverity.MODERATE);
                case "MI" -> reaction.setSeverity(AllergyIntoleranceSeverity.MILD);
                default -> {
                }
            }
        }

        allergy.setText(Narratives.generated("Allergy: " + Narratives.orUnknown(row.display())
                + " (" + Narratives.orUnknown(row.codeSystem()) + " " + Narratives.orUnknown(row.code()) + ")"
                + (isBlank(row.reaction()) ? "" : ", reaction " + row.reaction().trim())
                + (severity.isEmpty() ? "" : ", HL7 v2 severity " + severity)));
        return allergy;
    }

    static String systemUri(String codeSystem) {
        if (codeSystem == null) {
            return null;
        }
        return switch (codeSystem.trim().toUpperCase(Locale.ROOT)) {
            case "RXNORM" -> RXNORM;
            case "SNOMED-CT", "SNOMED" -> SNOMED;
            default -> null;
        };
    }
}
