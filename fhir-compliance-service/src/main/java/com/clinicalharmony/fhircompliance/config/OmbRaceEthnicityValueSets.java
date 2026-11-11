package com.clinicalharmony.fhircompliance.config;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.ValueSet;

/**
 * The three small VSAC value sets that US Core 6.1.0's {@code omb-race-category} and
 * {@code omb-ethnicity-category} are composed from. US Core binds the race/ethnicity extensions'
 * {@code ombCategory} to those value sets with <em>required</em> strength, but defines them only as
 * includes of VSAC value sets — which this service deliberately doesn't bundle (no network, no
 * VSAC licence key). Without these, every Patient carrying a race or ethnicity extension fails
 * validation with "code not found in value set", even for a correct OMB code.
 *
 * <p>The content is the fixed OMB 1997 minimum categories (CDC Race &amp; Ethnicity code system,
 * OID 2.16.840.1.113883.6.238) plus the two null flavors US Core allows. It hasn't changed since
 * 1997 and is small enough to state here rather than ship as a package. The ~900-code
 * {@code detailed-race}/{@code detailed-ethnicity} value sets are NOT provided, so a
 * {@code detailed} sub-extension still can't be checked offline.
 */
final class OmbRaceEthnicityValueSets {

    static final String CDC_RACE_ETHNICITY = "urn:oid:2.16.840.1.113883.6.238";
    static final String NULL_FLAVOR = "http://terminology.hl7.org/CodeSystem/v3-NullFlavor";
    private static final String VSAC = "http://cts.nlm.nih.gov/fhir/ValueSet/";

    private OmbRaceEthnicityValueSets() {
    }

    static PrePopulatedValidationSupport create(FhirContext fhirContext) {
        PrePopulatedValidationSupport support = new PrePopulatedValidationSupport(fhirContext);

        ValueSet race = valueSet("2.16.840.1.114222.4.11.836", "Race (OMB)");
        race.getCompose().addInclude().setSystem(CDC_RACE_ETHNICITY)
                .addConcept(c("1002-5", "American Indian or Alaska Native"))
                .addConcept(c("2028-9", "Asian"))
                .addConcept(c("2054-5", "Black or African American"))
                .addConcept(c("2076-8", "Native Hawaiian or Other Pacific Islander"))
                .addConcept(c("2106-3", "White"));
        support.addValueSet(race);

        ValueSet ethnicity = valueSet("2.16.840.1.114222.4.11.837", "Ethnicity (OMB)");
        ethnicity.getCompose().addInclude().setSystem(CDC_RACE_ETHNICITY)
                .addConcept(c("2135-2", "Hispanic or Latino"))
                .addConcept(c("2186-5", "Not Hispanic or Latino"));
        support.addValueSet(ethnicity);

        ValueSet nullFlavors = valueSet("2.16.840.1.113762.1.4.1021.102", "Race and Ethnicity Null Flavors");
        nullFlavors.getCompose().addInclude().setSystem(NULL_FLAVOR)
                .addConcept(c("UNK", "unknown"))
                .addConcept(c("ASKU", "asked but unknown"));
        support.addValueSet(nullFlavors);

        return support;
    }

    private static ValueSet valueSet(String oid, String title) {
        ValueSet valueSet = new ValueSet();
        valueSet.setId(oid);
        valueSet.setUrl(VSAC + oid);
        valueSet.setTitle(title);
        valueSet.setStatus(PublicationStatus.ACTIVE);
        return valueSet;
    }

    private static ValueSet.ConceptReferenceComponent c(String code, String display) {
        return new ValueSet.ConceptReferenceComponent().setCode(code).setDisplay(display);
    }
}
