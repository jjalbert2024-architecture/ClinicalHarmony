package com.clinicalharmony.fhircompliance.service.ccda;

import org.hl7.fhir.r4.model.Resource;
import org.w3c.dom.Element;

import java.util.List;

/**
 * Maps one C-CDA body section to FHIR resources. A section is located by its LOINC section code
 * (the primary key, per the C-CDA spec every section template fixes one), with the section
 * templateIds as a fallback for documents that carry a templateId but a missing or non-LOINC
 * {@code <code>}. Adding Allergies, Encounters, etc. later means adding another implementation.
 */
public interface CcdaSectionMapper {

    /** Human-readable name used in notes and the response, e.g. {@code "Problems"}. */
    String sectionName();

    /** LOINC code of the section, e.g. {@code 11450-4} for Problems. */
    String loincCode();

    /** Section templateIds (entries-optional and entries-required variants) accepted as a fallback. */
    List<String> templateIds();

    /** Maps every entry in the section; entries that can't be mapped are skipped with a note on {@code ctx}. */
    List<? extends Resource> map(Element section, CcdaMappingContext ctx);

    /** e.g. {@code "Problems (11450-4)"} — the label notes and the response use for this section. */
    default String label() {
        return sectionName() + " (" + loincCode() + ")";
    }
}
