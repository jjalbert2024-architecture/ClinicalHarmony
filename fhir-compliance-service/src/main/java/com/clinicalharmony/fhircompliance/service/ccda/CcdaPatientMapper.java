package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.service.silver.Narratives;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * {@code recordTarget/patientRole} → US Core Patient: identifiers, names, gender, birth date,
 * addresses, telecoms, OMB race/ethnicity extensions, and preferred language.
 *
 * <p>Like the Silver builders, nothing is invented: an element missing from the C-CDA is missing
 * from the Patient, and the validator reports it. An explicit C-CDA {@code nullFlavor} is mapped
 * to FHIR's own way of saying "known to be unknown" (gender {@code unknown}, or a
 * data-absent-reason on the name) because that is information, not a gap.
 */
@Component
public class CcdaPatientMapper {

    static final String SECTION = "Patient (recordTarget)";
    static final String US_CORE_RACE = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race";
    static final String US_CORE_ETHNICITY = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity";

    /** OMB minimum categories — the only codes allowed in the {@code ombCategory} sub-extension. */
    private static final Set<String> OMB_RACE = Set.of("1002-5", "2028-9", "2054-5", "2076-8", "2106-3");
    private static final Set<String> OMB_ETHNICITY = Set.of("2135-2", "2186-5");

    private static final Map<String, HumanName.NameUse> NAME_USE = Map.of(
            "L", HumanName.NameUse.OFFICIAL, "P", HumanName.NameUse.NICKNAME,
            "ASGN", HumanName.NameUse.USUAL, "C", HumanName.NameUse.OLD);
    private static final Map<String, Address.AddressUse> ADDRESS_USE = Map.of(
            "H", Address.AddressUse.HOME, "HP", Address.AddressUse.HOME, "HV", Address.AddressUse.HOME,
            "WP", Address.AddressUse.WORK, "TMP", Address.AddressUse.TEMP, "OLD", Address.AddressUse.OLD);
    private static final Map<String, ContactPoint.ContactPointUse> TELECOM_USE = Map.of(
            "H", ContactPoint.ContactPointUse.HOME, "HP", ContactPoint.ContactPointUse.HOME,
            "HV", ContactPoint.ContactPointUse.HOME, "WP", ContactPoint.ContactPointUse.WORK,
            "MC", ContactPoint.ContactPointUse.MOBILE, "TMP", ContactPoint.ContactPointUse.TEMP);

    public Patient map(Element patientRole, CcdaMappingContext ctx) {
        CcdaXml xml = ctx.xml();
        Patient patient = new Patient();
        String id = ctx.resourceId("Patient", patientRole, "patient");
        patient.setId(id);
        ctx.setPatientId(id);
        patient.getMeta().addProfile(UsCoreProfiles.PATIENT);

        for (Element idElement : xml.elements(patientRole, "hl7:id[not(@nullFlavor)]")) {
            Identifier identifier = identifier(idElement);
            if (identifier != null) {
                patient.addIdentifier(identifier);
            }
        }

        Element person = xml.element(patientRole, "hl7:patient");
        if (person == null) {
            ctx.note(SECTION, "patientRole has no <patient> element — no name, gender, or birth date to map");
        }
        for (Element nameElement : xml.elements(person, "hl7:name")) {
            HumanName name = name(nameElement, xml);
            if (name != null) {
                patient.addName(name);
            }
        }

        Element gender = xml.element(person, "hl7:administrativeGenderCode");
        if (gender != null) {
            AdministrativeGender mapped = gender(attr(gender, "code"), attr(gender, "nullFlavor"));
            if (mapped != null) {
                patient.setGender(mapped);
            } else {
                ctx.note(SECTION, "administrativeGenderCode '" + attr(gender, "code") + "' has no FHIR equivalent; gender omitted");
            }
        }

        String birthDate = Hl7Dates.toFhirDate(xml.string(person, "hl7:birthTime/@value"));
        if (birthDate != null) {
            patient.setBirthDateElement(new DateType(birthDate));
        }

        for (Element addr : xml.elements(patientRole, "hl7:addr[not(@nullFlavor)]")) {
            patient.addAddress(address(addr, xml));
        }
        for (Element telecom : xml.elements(patientRole, "hl7:telecom[@value]")) {
            ContactPoint contactPoint = telecom(telecom);
            if (contactPoint != null) {
                patient.addTelecom(contactPoint);
            }
        }

        Extension race = omb(US_CORE_RACE, xml.element(person, "hl7:raceCode[@code]"),
                xml.elements(person, "sdtc:raceCode[@code]"), OMB_RACE);
        if (race != null) {
            patient.addExtension(race);
            noteDetailedCodes(race, "race", ctx);
        }
        Extension ethnicity = omb(US_CORE_ETHNICITY, xml.element(person, "hl7:ethnicGroupCode[@code]"),
                xml.elements(person, "sdtc:ethnicGroupCode[@code]"), OMB_ETHNICITY);
        if (ethnicity != null) {
            patient.addExtension(ethnicity);
            noteDetailedCodes(ethnicity, "ethnicity", ctx);
        }

        for (Element language : xml.elements(person, "hl7:languageCommunication/hl7:languageCode[@code]")) {
            Patient.PatientCommunicationComponent communication = patient.addCommunication();
            communication.getLanguage().addCoding().setSystem(CodeSystems.BCP47).setCode(attr(language, "code"));
            String preferred = xml.string(language, "../hl7:preferenceInd/@value");
            if (preferred != null) {
                communication.setPreferred(Boolean.parseBoolean(preferred));
            }
        }

        patient.setText(Narratives.generated("Patient " + displayName(patient)
                + ", " + (patient.hasGender() ? patient.getGender().toCode() : "(gender unknown)")
                + ", born " + (patient.hasBirthDate() ? patient.getBirthDateElement().getValueAsString() : "(unknown)")
                + " — converted from C-CDA"));
        return patient;
    }

    /** {@code <id root extension>} → Identifier; a bare root is itself the identifier (an OID or UUID). */
    static Identifier identifier(Element id) {
        String root = attr(id, "root");
        String extension = attr(id, "extension");
        if (root == null) {
            return null;
        }
        if (extension != null) {
            return new Identifier().setSystem("urn:oid:" + root).setValue(extension);
        }
        boolean uuid = root.matches("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");
        return new Identifier().setSystem("urn:ietf:rfc:3986")
                .setValue((uuid ? "urn:uuid:" : "urn:oid:") + root.toLowerCase());
    }

    private static HumanName name(Element nameElement, CcdaXml xml) {
        String nullFlavor = attr(nameElement, "nullFlavor");
        if (nullFlavor != null) {
            // Explicitly unknown: US Core (us-core-6) accepts a name carrying only a data-absent-reason.
            return CcdaMappingContext.withDataAbsentReason(new HumanName(), nullFlavor);
        }
        HumanName name = new HumanName();
        String use = attr(nameElement, "use");
        if (use != null && NAME_USE.containsKey(use)) {
            name.setUse(NAME_USE.get(use));
        }
        for (Element given : xml.elements(nameElement, "hl7:given")) {
            String text = CcdaXml.text(given);
            if (text != null) {
                name.addGiven(text);
            }
        }
        String family = CcdaXml.text(xml.element(nameElement, "hl7:family"));
        if (family != null) {
            name.setFamily(family);
        }
        for (Element prefix : xml.elements(nameElement, "hl7:prefix")) {
            String text = CcdaXml.text(prefix);
            if (text != null) name.addPrefix(text);
        }
        for (Element suffix : xml.elements(nameElement, "hl7:suffix")) {
            String text = CcdaXml.text(suffix);
            if (text != null) name.addSuffix(text);
        }
        if (!name.hasFamily() && !name.hasGiven()) {
            // Unstructured name ("<name>Jane Doe</name>") — keep it as text rather than drop it.
            String text = CcdaXml.text(nameElement);
            if (text == null) {
                return null;
            }
            name.setText(text);
        }
        return name;
    }

    /** M/F/UN → male/female/other; any nullFlavor → unknown; anything else → null (omitted, noted). */
    static AdministrativeGender gender(String code, String nullFlavor) {
        if (code == null) {
            return nullFlavor != null ? AdministrativeGender.UNKNOWN : null;
        }
        return switch (code) {
            case "M" -> AdministrativeGender.MALE;
            case "F" -> AdministrativeGender.FEMALE;
            case "UN" -> AdministrativeGender.OTHER;
            default -> null;
        };
    }

    private static Address address(Element addr, CcdaXml xml) {
        Address address = new Address();
        String use = attr(addr, "use");
        if (use != null && ADDRESS_USE.containsKey(use)) {
            address.setUse(ADDRESS_USE.get(use));
        }
        for (Element line : xml.elements(addr, "hl7:streetAddressLine")) {
            String text = CcdaXml.text(line);
            if (text != null) address.addLine(text);
        }
        String city = CcdaXml.text(xml.element(addr, "hl7:city"));
        String state = CcdaXml.text(xml.element(addr, "hl7:state"));
        String postalCode = CcdaXml.text(xml.element(addr, "hl7:postalCode"));
        String country = CcdaXml.text(xml.element(addr, "hl7:country"));
        if (city != null) address.setCity(city);
        if (state != null) address.setState(state);
        if (postalCode != null) address.setPostalCode(postalCode);
        if (country != null) address.setCountry(country);
        return address;
    }

    /** {@code tel:+1(555)555-2003} / {@code mailto:x@y} / {@code fax:...} → ContactPoint. */
    static ContactPoint telecom(Element telecom) {
        String value = attr(telecom, "value");
        if (value == null) {
            return null;
        }
        ContactPoint contactPoint = new ContactPoint();
        int colon = value.indexOf(':');
        String scheme = colon > 0 ? value.substring(0, colon).toLowerCase() : "";
        String rest = colon > 0 ? value.substring(colon + 1) : value;
        switch (scheme) {
            case "tel" -> contactPoint.setSystem(ContactPoint.ContactPointSystem.PHONE);
            case "mailto" -> contactPoint.setSystem(ContactPoint.ContactPointSystem.EMAIL);
            case "fax" -> contactPoint.setSystem(ContactPoint.ContactPointSystem.FAX);
            case "http", "https" -> {
                contactPoint.setSystem(ContactPoint.ContactPointSystem.URL);
                rest = value;
            }
            default -> contactPoint.setSystem(ContactPoint.ContactPointSystem.OTHER);
        }
        contactPoint.setValue(rest.trim());
        String use = attr(telecom, "use");
        if (use != null && TELECOM_USE.containsKey(use)) {
            contactPoint.setUse(TELECOM_USE.get(use));
        }
        return contactPoint;
    }

    /**
     * US Core race/ethnicity complex extension: OMB codes go in {@code ombCategory}, finer-grained
     * CDC codes (including {@code sdtc:raceCode} repeats) in {@code detailed}, and {@code text}
     * (required) from the display names.
     */
    private static Extension omb(String url, Element primary, List<Element> extra, Set<String> ombCodes) {
        List<Element> codes = new ArrayList<>();
        if (primary != null) codes.add(primary);
        codes.addAll(extra);
        if (codes.isEmpty()) {
            return null;
        }
        Extension extension = new Extension(url);
        List<String> displays = new ArrayList<>();
        for (Element code : codes) {
            String value = attr(code, "code");
            String display = attr(code, "displayName");
            Coding coding = new Coding(CodeSystems.CDC_RACE_ETHNICITY, value, display);
            extension.addExtension(ombCodes.contains(value) ? "ombCategory" : "detailed", coding);
            displays.add(display != null ? display : value);
        }
        extension.addExtension("text", new StringType(String.join(", ", displays)));
        return extension;
    }

    /**
     * Detailed (non-OMB) codes are mapped faithfully, but the validator can't check them offline:
     * US Core's detailed-race/-ethnicity value sets are ~900-code VSAC sets that aren't bundled
     * (only the small OMB ones are, see OmbRaceEthnicityValueSets). Say so up front, so the
     * resulting terminology ERROR isn't mistaken for bad source data.
     */
    private static void noteDetailedCodes(Extension extension, String kind, CcdaMappingContext ctx) {
        for (Extension detailed : extension.getExtensionsByUrl("detailed")) {
            ctx.note(SECTION, "Detailed " + kind + " code " + ((Coding) detailed.getValue()).getCode()
                    + " mapped to the 'detailed' sub-extension. The offline validator doesn't bundle the VSAC"
                    + " detailed-" + kind + " value set, so expect a terminology ERROR on it even though the code is"
                    + " valid CDC Race & Ethnicity");
        }
    }

    private static String displayName(Patient patient) {
        if (!patient.hasName()) {
            return "(name unknown)";
        }
        HumanName name = patient.getNameFirstRep();
        if (name.hasText()) {
            return name.getText();
        }
        String text = (name.getGivenAsSingleString() + " " + (name.hasFamily() ? name.getFamily() : "")).trim();
        return text.isBlank() ? "(name unknown)" : text;
    }
}
