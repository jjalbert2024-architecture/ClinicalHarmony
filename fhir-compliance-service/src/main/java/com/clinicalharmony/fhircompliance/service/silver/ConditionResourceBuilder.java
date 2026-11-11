package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverConditionRow;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.clinicalharmony.fhircompliance.service.silver.Narratives.isBlank;

/** silver.conditions → US Core Condition (Problems and Health Concerns profile). */
@Component
public class ConditionResourceBuilder {

    static final String CLINICAL_STATUS_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-clinical";
    static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-category";

    static final Map<String, String> CODE_SYSTEM_URIS = Map.of(
            "ICD-10", "http://hl7.org/fhir/sid/icd-10-cm",
            "SNOMED-CT", "http://snomed.info/sct"
    );

    public Condition build(SilverConditionRow row, String patientFhirId) {
        Condition condition = new Condition();
        condition.setId(FhirIds.condition(row.id()));
        condition.getMeta().addProfile(UsCoreProfiles.CONDITION_PROBLEMS);

        if (!isBlank(row.clinicalStatus())) {
            CodeableConcept clinicalStatus = new CodeableConcept();
            clinicalStatus.addCoding().setSystem(CLINICAL_STATUS_SYSTEM).setCode(row.clinicalStatus());
            condition.setClinicalStatus(clinicalStatus);
        }
        condition.addCategory().addCoding()
                .setSystem(CATEGORY_SYSTEM).setCode("problem-list-item").setDisplay("Problem List Item");

        // Blank code → no coding at all (rather than a coding with an empty code), so the gap
        // surfaces as US Core's "Condition.code minimum required" rather than a parse quirk.
        if (!isBlank(row.code())) {
            CodeableConcept code = new CodeableConcept();
            code.addCoding()
                    .setSystem(CODE_SYSTEM_URIS.getOrDefault(row.codeSystem(), row.codeSystem()))
                    .setCode(row.code().trim())
                    .setDisplay(isBlank(row.display()) ? null : row.display());
            if (!isBlank(row.display())) {
                code.setText(row.display());
            }
            condition.setCode(code);
        } else if (!isBlank(row.display())) {
            condition.setCode(new CodeableConcept().setText(row.display()));
        }

        condition.setSubject(new Reference("Patient/" + patientFhirId));
        if (row.onsetDate() != null) {
            condition.setOnset(new DateTimeType(row.onsetDate().toString()));
        }
        if (row.recordedDate() != null) {
            condition.setRecordedDateElement(new DateTimeType(row.recordedDate().toString()));
        }

        condition.setText(Narratives.generated("Condition: " + Narratives.orUnknown(row.display())
                + " (" + row.codeSystem() + " " + Narratives.orUnknown(row.code()) + "), "
                + Narratives.orUnknown(row.clinicalStatus())));
        return condition;
    }
}
