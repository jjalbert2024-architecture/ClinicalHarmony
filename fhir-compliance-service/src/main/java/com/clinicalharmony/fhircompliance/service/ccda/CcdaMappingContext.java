package com.clinicalharmony.fhircompliance.service.ccda;

import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Element;
import org.hl7.fhir.r4.model.Reference;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * Per-document state shared by the section mappers: the parsed XML, the document's identity (seed
 * for deterministic resource ids), the Patient every clinical resource points at, and the notes
 * collected along the way. One instance per conversion request; not thread-safe.
 */
public final class CcdaMappingContext {

    static final String DATA_ABSENT_REASON = "http://hl7.org/fhir/StructureDefinition/data-absent-reason";

    /** C-CDA nullFlavor → FHIR data-absent-reason code. */
    private static final Map<String, String> NULL_FLAVOR_TO_DAR = Map.of(
            "UNK", "unknown",
            "NI", "unknown",
            "ASKU", "asked-unknown",
            "NAV", "temp-unknown",
            "NASK", "not-asked",
            "MSK", "masked",
            "NA", "not-applicable",
            "OTH", "unsupported",
            "NP", "unknown"
    );

    private final CcdaXml xml;
    private final String documentSeed;
    private final List<ConversionNote> notes = new ArrayList<>();
    private String patientId;

    public CcdaMappingContext(CcdaXml xml, String documentSeed) {
        this.xml = xml;
        this.documentSeed = documentSeed;
    }

    public CcdaXml xml() {
        return xml;
    }

    public List<ConversionNote> notes() {
        return notes;
    }

    public void note(String section, String message) {
        notes.add(new ConversionNote(section, message));
    }

    void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    /** Reference to the document's Patient, in the same {@code urn:uuid:} form as its Bundle fullUrl. */
    public Reference patientReference() {
        return new Reference(fullUrl(patientId));
    }

    public static String fullUrl(String resourceId) {
        return "urn:uuid:" + resourceId;
    }

    /**
     * Deterministic resource id: the same C-CDA entry (by its {@code id} root/extension) always
     * yields the same FHIR id, so re-converting a document — or the same entry arriving in a later
     * document from the same source — produces the same resource identity. Entries without a usable
     * {@code id} fall back to document identity + position, stable for re-submissions of the same
     * document only.
     */
    public String resourceId(String resourceType, org.w3c.dom.Element entry, String positionalFallback) {
        String identity = entryIdentity(entry);
        String seed = identity != null
                ? "ccda|" + resourceType + "|" + identity
                : "ccda|" + resourceType + "|" + documentSeed + "|" + positionalFallback;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String entryIdentity(org.w3c.dom.Element entry) {
        for (org.w3c.dom.Element id : xml.elements(entry, "hl7:id[@root and not(@nullFlavor)]")) {
            String root = attr(id, "root");
            String extension = attr(id, "extension");
            return extension == null ? root : root + "|" + extension;
        }
        return null;
    }

    /**
     * C-CDA CD/CE (code, codeSystem, displayName, originalText, translation*) → CodeableConcept.
     * Returns null if there is nothing at all — no code, no translation, no text — so the caller
     * leaves the FHIR element absent and the validator reports the gap.
     *
     * @param section the enclosing {@code <section>}, used to resolve {@code originalText/reference}
     *                pointers into the section's narrative
     */
    public CodeableConcept codeableConcept(org.w3c.dom.Element cd, org.w3c.dom.Element section) {
        if (cd == null) {
            return null;
        }
        CodeableConcept concept = new CodeableConcept();
        Coding primary = coding(cd);
        if (primary != null) {
            concept.addCoding(primary);
        }
        for (org.w3c.dom.Element translation : xml.elements(cd, "hl7:translation")) {
            Coding coding = coding(translation);
            if (coding != null) {
                concept.addCoding(coding);
            }
        }
        String text = originalText(cd, section);
        if (text == null && primary != null) {
            text = primary.getDisplay();
        }
        if (text != null) {
            concept.setText(text);
        }
        return concept.hasCoding() || concept.hasText() ? concept : null;
    }

    private static Coding coding(org.w3c.dom.Element cd) {
        String code = attr(cd, "code");
        String system = CodeSystems.uriForOid(attr(cd, "codeSystem"));
        if (code == null || system == null) {
            return null;
        }
        return new Coding(system, code, attr(cd, "displayName"));
    }

    /**
     * Text of {@code originalText} under {@code element}: either inline, or — the common C-CDA
     * pattern — a {@code <reference value="#id"/>} into the section narrative.
     */
    public String originalText(org.w3c.dom.Element element, org.w3c.dom.Element section) {
        org.w3c.dom.Element originalText = xml.element(element, "hl7:originalText");
        return originalText == null ? null : textOrReference(originalText, section);
    }

    /** Inline text of a C-CDA {@code ED}-ish element, else its {@code reference/@value} resolved in the section narrative. */
    public String textOrReference(org.w3c.dom.Element element, org.w3c.dom.Element section) {
        if (element == null) {
            return null;
        }
        String reference = attr(xml.element(element, "hl7:reference"), "value");
        if (reference != null && reference.startsWith("#") && section != null) {
            String id = reference.substring(1);
            for (org.w3c.dom.Element candidate : xml.elements(section, "hl7:text//*[@ID]")) {
                if (id.equals(candidate.getAttribute("ID"))) {
                    return CcdaXml.text(candidate);
                }
            }
        }
        // No (resolvable) reference: use the element's own text, minus any <reference> child's.
        org.w3c.dom.Element copy = (org.w3c.dom.Element) element.cloneNode(true);
        for (org.w3c.dom.Element ref : xml.elements(copy, "hl7:reference")) {
            copy.removeChild(ref);
        }
        return CcdaXml.text(copy);
    }

    /** FHIR data-absent-reason code for a C-CDA nullFlavor ({@code unknown} for anything unmapped). */
    public static String dataAbsentReason(String nullFlavor) {
        return NULL_FLAVOR_TO_DAR.getOrDefault(nullFlavor, "unknown");
    }

    /** Marks a FHIR element as explicitly absent, with the reason taken from a C-CDA nullFlavor. */
    public static <T extends Element> T withDataAbsentReason(T element, String nullFlavor) {
        element.addExtension(DATA_ABSENT_REASON, new CodeType(dataAbsentReason(nullFlavor)));
        return element;
    }
}
