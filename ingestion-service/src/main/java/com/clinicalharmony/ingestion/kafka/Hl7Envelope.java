package com.clinicalharmony.ingestion.kafka;

/**
 * The few MSH/PID fields ingestion-service reads off an MLLP message WITHOUT parsing it with HAPI —
 * just enough to route it (partition key), tag it (source system) and acknowledge it (control id).
 * Full HL7 parsing, and the PENDING/QUARANTINED decision, belong to bronze-listener-service, so a
 * message that won't parse is still published and kept in Bronze rather than rejected at the door.
 *
 * <p>Every field is null when it can't be found; nothing here throws.
 */
public record Hl7Envelope(
        String sendingApplication,   // MSH-3.1
        String sendingFacility,      // MSH-4.1 → bronze source_system
        String messageType,          // MSH-9.1^MSH-9.2, e.g. ADT^A01
        String triggerEvent,         // MSH-9.2
        String controlId,            // MSH-10
        String patientId             // PID-3.1 (first repetition)
) {

    public static Hl7Envelope peek(String raw) {
        if (raw == null) {
            return empty();
        }
        String[] segments = raw.split("[\r\n]+");
        String msh = null;
        String pid = null;
        for (String segment : segments) {
            String trimmed = segment.strip();
            if (msh == null && trimmed.startsWith("MSH") && trimmed.length() > 8) {
                msh = trimmed;
            } else if (pid == null && trimmed.startsWith("PID")) {
                pid = trimmed;
            }
        }
        if (msh == null) {
            return empty();
        }
        char fieldSep = msh.charAt(3);
        String encoding = msh.substring(4);
        char componentSep = encoding.isEmpty() ? '^' : encoding.charAt(0);
        char repetitionSep = encoding.length() > 1 ? encoding.charAt(1) : '~';

        // "MSH|^~\&|APP|FAC|..." split on '|' → [MSH, ^~\&, APP, FAC, ...]: MSH-n is index n-1.
        String[] f = msh.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
        String app = component(field(f, 2), componentSep, 0);
        String facility = component(field(f, 3), componentSep, 0);
        String msgCode = component(field(f, 8), componentSep, 0);
        String trigger = component(field(f, 8), componentSep, 1);
        String controlId = blankToNull(field(f, 9));

        String patientId = null;
        if (pid != null) {
            // "PID|1||MRN^^^..." → PID-n is index n.
            String[] p = pid.split(java.util.regex.Pattern.quote(String.valueOf(fieldSep)), -1);
            String pid3 = field(p, 3);
            if (pid3 != null) {
                String firstRepetition = pid3.split(java.util.regex.Pattern.quote(String.valueOf(repetitionSep)), -1)[0];
                patientId = component(firstRepetition, componentSep, 0);
            }
        }
        String type = msgCode == null ? null : (trigger == null ? msgCode : msgCode + "^" + trigger);
        return new Hl7Envelope(app, facility, type, trigger, controlId, patientId);
    }

    private static Hl7Envelope empty() {
        return new Hl7Envelope(null, null, null, null, null, null);
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index] : null;
    }

    private static String component(String field, char sep, int index) {
        if (field == null) {
            return null;
        }
        String[] parts = field.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        return index < parts.length ? blankToNull(parts[index]) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
