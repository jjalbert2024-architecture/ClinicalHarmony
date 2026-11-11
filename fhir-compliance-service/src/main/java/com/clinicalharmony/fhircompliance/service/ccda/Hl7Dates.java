package com.clinicalharmony.fhircompliance.service.ccda;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 v3 TS ({@code YYYY[MM[DD[HH[MM[SS[.S+]]]]]][+/-ZZZZ]}) → FHIR date / dateTime strings.
 *
 * <p>FHIR requires a timezone whenever a dateTime carries a time. A C-CDA timestamp with a time
 * but no offset is therefore truncated to its date rather than given an invented timezone —
 * losing precision is safer than asserting a wrong instant.
 */
public final class Hl7Dates {

    private static final Pattern TS = Pattern.compile(
            "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d+)?([+-]\\d{4})?$");

    private Hl7Dates() {
    }

    /** FHIR dateTime string, or null if absent/unparseable. */
    public static String toFhirDateTime(String ts) {
        Matcher m = match(ts);
        if (m == null) {
            return null;
        }
        String date = datePart(m);
        if (m.group(4) == null || m.group(3) == null || m.group(7) == null) {
            return date;
        }
        String minutes = m.group(5) == null ? "00" : m.group(5);
        String seconds = m.group(6) == null ? "00" : m.group(6);
        String tz = m.group(7);
        return date + "T" + m.group(4) + ":" + minutes + ":" + seconds + tz.substring(0, 3) + ":" + tz.substring(3);
    }

    /** FHIR date string (YYYY, YYYY-MM or YYYY-MM-DD), or null if absent/unparseable. */
    public static String toFhirDate(String ts) {
        Matcher m = match(ts);
        return m == null ? null : datePart(m);
    }

    private static Matcher match(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        Matcher m = TS.matcher(ts.trim());
        return m.matches() ? m : null;
    }

    private static String datePart(Matcher m) {
        StringBuilder date = new StringBuilder(m.group(1));
        if (m.group(2) != null) {
            date.append('-').append(m.group(2));
            if (m.group(3) != null) {
                date.append('-').append(m.group(3));
            }
        }
        return date.toString();
    }
}
