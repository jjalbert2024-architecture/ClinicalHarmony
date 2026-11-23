package com.clinicalharmony.ingestion.intake;

import java.util.Map;

/**
 * Result of the quick syntax check REST intake runs before publishing — the same check the pre-9d REST paths
 * ran before writing Bronze. It only decides 202 vs 422 for the caller: the payload is published either way,
 * and bronze-listener-service makes the authoritative PENDING / QUARANTINED decision with the same rules.
 *
 * @param valid     the payload passes the format's syntax check
 * @param error     why it doesn't (null when valid)
 * @param patientId the patient identifier the payload is about, when cheap to find — part of the partition key
 * @param details   format-specific facts echoed in the response and the log (message type, resource count…)
 */
public record SyntaxCheck(boolean valid, String error, String patientId, Map<String, Object> details) {

    static SyntaxCheck ok(String patientId, Map<String, Object> details) {
        return new SyntaxCheck(true, null, patientId, details);
    }

    static SyntaxCheck bad(String error) {
        String e = error == null || error.isBlank() ? "Unparseable payload" : error;
        return new SyntaxCheck(false, e.length() > 500 ? e.substring(0, 500) : e, null, Map.of());
    }
}
