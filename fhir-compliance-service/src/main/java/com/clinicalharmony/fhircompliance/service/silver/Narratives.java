package com.clinicalharmony.fhircompliance.service.silver;

import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Narrative.NarrativeStatus;

/**
 * Builds the minimal generated narrative (Resource.text) every builder attaches, satisfying the
 * dom-6 best-practice check. Text is escaped — Silver data is untrusted input as far as XHTML goes.
 */
final class Narratives {

    private Narratives() {
    }

    static Narrative generated(String summary) {
        Narrative narrative = new Narrative();
        narrative.setStatus(NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">" + escape(summary) + "</div>");
        return narrative;
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String orUnknown(String s) {
        return isBlank(s) ? "(unknown)" : s;
    }
}
