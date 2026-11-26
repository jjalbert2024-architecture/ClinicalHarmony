package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverObservationRow;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

import static com.clinicalharmony.fhircompliance.service.silver.Narratives.isBlank;

/**
 * silver.observations → US Core Laboratory Result Observation. Every Silver observation is
 * LOINC-coded (table CHECK) and the Silver layer models lab results, so all are categorized
 * {@code laboratory}.
 */
@Component
public class ObservationResourceBuilder {

    static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";
    static final String LOINC = "http://loinc.org";
    static final String UCUM = "http://unitsofmeasure.org";
    static final String INTERPRETATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";

    /**
     * HL7 v2 table 0078 (OBX-8) flags that are the same code in FHIR's v3 ObservationInterpretation. Any other flag
     * goes to {@code interpretation.text} only, never as a made-up coding.
     */
    static final Map<String, String> INTERPRETATIONS = Map.of(
            "N", "Normal", "H", "High", "L", "Low", "HH", "Critical high", "LL", "Critical low",
            "A", "Abnormal", "AA", "Critical abnormal");

    public Observation build(SilverObservationRow row, String patientFhirId) {
        Observation observation = new Observation();
        observation.setId(FhirIds.observation(row.id()));
        observation.getMeta().addProfile(UsCoreProfiles.OBSERVATION_LAB);

        if (!isBlank(row.status())) {
            observation.setStatus(Observation.ObservationStatus.fromCode(row.status()));
        }
        observation.addCategory().addCoding()
                .setSystem(CATEGORY_SYSTEM).setCode("laboratory").setDisplay("Laboratory");

        if (!isBlank(row.code())) {
            CodeableConcept code = new CodeableConcept();
            code.addCoding().setSystem(LOINC).setCode(row.code().trim())
                    .setDisplay(isBlank(row.display()) ? null : row.display());
            if (!isBlank(row.display())) {
                code.setText(row.display());
            }
            observation.setCode(code);
        }

        observation.setSubject(new Reference("Patient/" + patientFhirId));
        if (row.effectiveDate() != null) {
            observation.setEffective(new DateTimeType(Date.from(row.effectiveDate().toInstant())));
        }

        if (row.valueNumeric() != null) {
            Quantity quantity = new Quantity().setValue(row.valueNumeric().stripTrailingZeros());
            if (!isBlank(row.unit())) {
                // Silver units are expected to be UCUM; the validator checks them against UCUM.
                quantity.setUnit(row.unit()).setSystem(UCUM).setCode(row.unit());
            }
            observation.setValue(quantity);
        } else if (!isBlank(row.valueText())) {
            observation.setValue(new StringType(row.valueText()));
        }

        if (!isBlank(row.abnormalFlag())) {
            String flag = row.abnormalFlag().trim().toUpperCase();
            CodeableConcept interpretation = observation.addInterpretation();
            String display = INTERPRETATIONS.get(flag);
            if (display != null) {
                interpretation.addCoding().setSystem(INTERPRETATION_SYSTEM).setCode(flag).setDisplay(display);
            } else {
                interpretation.setText(flag);
            }
        }
        if (row.referenceLow() != null || row.referenceHigh() != null) {
            Observation.ObservationReferenceRangeComponent range = observation.addReferenceRange();
            if (row.referenceLow() != null) {
                range.setLow(rangeQuantity(row.referenceLow(), row.unit()));
            }
            if (row.referenceHigh() != null) {
                range.setHigh(rangeQuantity(row.referenceHigh(), row.unit()));
            }
        }

        String value = row.valueNumeric() != null
                ? row.valueNumeric().stripTrailingZeros().toPlainString() + (isBlank(row.unit()) ? "" : " " + row.unit())
                : Narratives.orUnknown(row.valueText());
        observation.setText(Narratives.generated("Lab: " + Narratives.orUnknown(row.display())
                + " (LOINC " + Narratives.orUnknown(row.code()) + ") = " + value
                + (isBlank(row.abnormalFlag()) ? "" : " [" + row.abnormalFlag().trim() + "]")));
        return observation;
    }

    /** A SimpleQuantity in the result's own unit (the OBX-7 range is in the OBX-6 unit). */
    private static org.hl7.fhir.r4.model.SimpleQuantity rangeQuantity(BigDecimal value, String unit) {
        org.hl7.fhir.r4.model.SimpleQuantity quantity = new org.hl7.fhir.r4.model.SimpleQuantity();
        quantity.setValue(value.stripTrailingZeros());
        if (!isBlank(unit)) {
            quantity.setUnit(unit).setSystem(UCUM).setCode(unit);
        }
        return quantity;
    }
}
