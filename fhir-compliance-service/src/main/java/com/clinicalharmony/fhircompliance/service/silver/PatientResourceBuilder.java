package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

import static com.clinicalharmony.fhircompliance.service.silver.Narratives.isBlank;

/**
 * silver.patients → US Core Patient.
 *
 * <p>Maps the data as it is — it never invents values to make a resource pass. A Silver patient
 * with a blank name produces a Patient with no {@code name}, which the US Core validator then
 * flags; that finding is the point (it lands in gold.fhir_resources.compliance_notes).
 */
@Component
public class PatientResourceBuilder {

    static final String V2_IDENTIFIER_TYPE = "http://terminology.hl7.org/CodeSystem/v2-0203";

    public Patient build(SilverPatientRow row) {
        Patient patient = new Patient();
        patient.setId(FhirIds.patient(row.id()));
        patient.getMeta().addProfile(UsCoreProfiles.PATIENT);

        if (!isBlank(row.mrn())) {
            Identifier mrn = patient.addIdentifier()
                    .setSystem(systemFor(row.sourceSystem()) + ":mrn")
                    .setValue(row.mrn());
            CodeableConcept mrnType = new CodeableConcept();
            mrnType.addCoding().setSystem(V2_IDENTIFIER_TYPE).setCode("MR").setDisplay("Medical record number");
            mrn.setType(mrnType);
        }
        if (!isBlank(row.sourcePatientId())) {
            patient.addIdentifier()
                    .setSystem(systemFor(row.sourceSystem()) + ":patient-id")
                    .setValue(row.sourcePatientId());
        }

        if (!isBlank(row.firstName()) || !isBlank(row.lastName())) {
            HumanName name = patient.addName().setUse(HumanName.NameUse.OFFICIAL);
            if (!isBlank(row.lastName())) {
                name.setFamily(row.lastName().trim());
            }
            if (!isBlank(row.firstName())) {
                name.addGiven(row.firstName().trim());
            }
        }

        if (!isBlank(row.gender())) {
            patient.setGender(AdministrativeGender.fromCode(row.gender()));
        }
        if (row.dateOfBirth() != null) {
            patient.setBirthDateElement(new DateType(row.dateOfBirth().toString()));
        }

        if (!isBlank(row.addressLine1()) || !isBlank(row.city()) || !isBlank(row.state()) || !isBlank(row.postalCode())) {
            Address address = patient.addAddress().setUse(Address.AddressUse.HOME).setCountry("US");
            if (!isBlank(row.addressLine1())) address.addLine(row.addressLine1());
            if (!isBlank(row.addressLine2())) address.addLine(row.addressLine2());
            if (!isBlank(row.city())) address.setCity(row.city());
            if (!isBlank(row.state())) address.setState(row.state());
            if (!isBlank(row.postalCode())) address.setPostalCode(row.postalCode());
        }
        if (!isBlank(row.phone())) {
            patient.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.HOME).setValue(row.phone());
        }

        String displayName = (isBlank(row.firstName()) && isBlank(row.lastName()))
                ? "(name unknown)"
                : ((isBlank(row.firstName()) ? "" : row.firstName().trim() + " ") + (isBlank(row.lastName()) ? "" : row.lastName().trim())).trim();
        patient.setText(Narratives.generated("Patient " + displayName
                + ", " + Narratives.orUnknown(row.gender())
                + ", born " + (row.dateOfBirth() == null ? "(unknown)" : row.dateOfBirth())
                + " — source " + row.sourceSystem() + "/" + row.sourcePatientId()));
        return patient;
    }

    /** Identifier namespace per source system, e.g. {@code urn:clinicalharmony:source:EPIC_HL7}. */
    static String systemFor(String sourceSystem) {
        String safe = isBlank(sourceSystem) ? "UNKNOWN" : sourceSystem.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return "urn:clinicalharmony:source:" + safe;
    }
}
