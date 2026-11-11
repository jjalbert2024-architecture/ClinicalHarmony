package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverMedicationRow;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import static com.clinicalharmony.fhircompliance.service.silver.Narratives.isBlank;

/**
 * silver.medications → US Core MedicationRequest. Silver medication status values are a subset
 * of MedicationRequest.status (table CHECK), so they map 1:1. Intent is always {@code order}:
 * Silver medications are orders/active meds from source systems, not proposals or plans.
 *
 * <p>US Core invariant us-core-21 requires a requester on any {@code order}, but Silver doesn't
 * capture the prescriber. Rather than inventing one, the requester carries only the standard
 * data-absent-reason extension ({@code unknown}) — US Core's sanctioned way to say "required, but
 * the source didn't give us this".
 */
@Component
public class MedicationRequestResourceBuilder {

    static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    static final String DATA_ABSENT_REASON = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";

    public MedicationRequest build(SilverMedicationRow row, String patientFhirId) {
        MedicationRequest request = new MedicationRequest();
        request.setId(FhirIds.medicationRequest(row.id()));
        request.getMeta().addProfile(UsCoreProfiles.MEDICATION_REQUEST);

        if (!isBlank(row.status())) {
            request.setStatus(MedicationRequest.MedicationRequestStatus.fromCode(row.status()));
        }
        request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);

        if (!isBlank(row.rxnormCode())) {
            CodeableConcept medication = new CodeableConcept();
            medication.addCoding().setSystem(RXNORM).setCode(row.rxnormCode().trim())
                    .setDisplay(isBlank(row.display()) ? null : row.display());
            if (!isBlank(row.display())) {
                medication.setText(row.display());
            }
            request.setMedication(medication);
        }

        request.setSubject(new Reference("Patient/" + patientFhirId));
        Reference requester = new Reference();
        requester.addExtension(DATA_ABSENT_REASON, new CodeType("unknown"));
        request.setRequester(requester);
        if (row.startDate() != null) {
            request.setAuthoredOnElement(new DateTimeType(row.startDate().toString()));
        }
        if (!isBlank(row.dosageText())) {
            request.addDosageInstruction().setText(row.dosageText());
        }
        if (row.startDate() != null || row.endDate() != null) {
            Period validity = new Period();
            if (row.startDate() != null) validity.setStartElement(new DateTimeType(row.startDate().toString()));
            if (row.endDate() != null) validity.setEndElement(new DateTimeType(row.endDate().toString()));
            request.getDispenseRequest().setValidityPeriod(validity);
        }

        request.setText(Narratives.generated("Medication: " + Narratives.orUnknown(row.display())
                + " (RxNorm " + Narratives.orUnknown(row.rxnormCode()) + ")"
                + (isBlank(row.dosageText()) ? "" : ", " + row.dosageText())
                + ", " + Narratives.orUnknown(row.status())));
        return request;
    }
}
