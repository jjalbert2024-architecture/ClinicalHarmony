package com.clinicalharmony.silver.validation;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates one Silver patient through rules-engine-service, using the patient's WHOLE Silver record
 * (every condition, observation, medication and allergy from every source message), so rules that
 * compare entities — medication vs allergy, pregnancy vs gender — see the full picture.
 *
 * <p>Runs outside any database transaction (no row locks held over HTTP). Outcomes:
 * <ul>
 *   <li>rules-engine answered and ontology-service was reachable → VALIDATED, and this patient's older
 *       open findings are marked resolved: the latest evaluation is the patient's current state (a
 *       problem that's still there was just reported again by this run);</li>
 *   <li>rules-engine down, or it answered without ontology-service → stays PENDING_VALIDATION with the
 *       reason; the {@link RevalidationSweeper} tries again.</li>
 * </ul>
 */
@Component
public class PatientValidator {

    public enum Outcome { VALIDATED, PENDING }

    public record Result(Outcome outcome, int findings, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(PatientValidator.class);

    private final JdbcTemplate jdbc;
    private final RulesEngineClient rulesEngine;
    private final MeterRegistry meters;

    public PatientValidator(JdbcTemplate jdbc, RulesEngineClient rulesEngine, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.rulesEngine = rulesEngine;
        this.meters = meters;
    }

    public Result validate(long patientId) {
        long start = System.nanoTime();
        Map<String, Object> request = load(patientId);
        if (request == null) {
            return new Result(Outcome.PENDING, 0, "patient not found");
        }
        Instant asOf = jdbc.queryForObject("SELECT now()", Timestamp.class).toInstant();
        JsonNode report;
        try {
            report = rulesEngine.evaluate(request);
        } catch (Exception e) {
            return pending(patientId, "rules-engine-service unavailable: " + e.getMessage(), start);
        }
        List<Long> reportIds = new ArrayList<>();
        report.path("validationReportIds").forEach(n -> reportIds.add(n.asLong()));
        if (!report.path("ontologyValidationAvailable").asBoolean(false)) {
            return pending(patientId, "ontology-service unavailable (rules-engine ran rules only)", start);
        }

        int superseded = jdbc.update("""
                UPDATE silver.validation_reports SET is_resolved = TRUE, resolved_at = now()
                WHERE patient_id = ? AND NOT is_resolved AND validated_at < ?
                """, patientId, Timestamp.from(asOf));
        jdbc.update("""
                UPDATE silver.patients SET validation_status = 'VALIDATED', validated_at = now(),
                    validation_attempts = validation_attempts + 1, validation_error = NULL
                WHERE id = ?
                """, patientId);
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        if (!reportIds.isEmpty()) {
            jdbc.query("SELECT severity, count(*) AS n FROM silver.validation_reports WHERE id = ANY (?) GROUP BY severity",
                    rs -> {
                        bySeverity.put(rs.getString("severity"), rs.getInt("n"));
                    }, (Object) reportIds.toArray(new Long[0]));
        }
        log.atInfo().addKeyValue("event", "silver.validated")
                .addKeyValue("silverPatientId", patientId)
                .addKeyValue("allValid", report.path("allValid").asBoolean())
                .addKeyValue("findings", reportIds.size())
                .addKeyValue("findingsBySeverity", bySeverity)
                .addKeyValue("supersededFindings", superseded)
                .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                .log("Patient validated by rules-engine-service + ontology-service");
        meters.counter("clinicalharmony.silver.validations", "outcome", "validated").increment();
        bySeverity.forEach((severity, n) ->
                meters.counter("clinicalharmony.silver.findings", "severity", severity).increment(n));
        return new Result(Outcome.VALIDATED, reportIds.size(), null);
    }

    private Result pending(long patientId, String reason, long start) {
        jdbc.update("""
                UPDATE silver.patients SET validation_status = 'PENDING_VALIDATION', validation_requested_at = now(),
                    validation_attempts = validation_attempts + 1, validation_error = left(?, 1000)
                WHERE id = ?
                """, reason, patientId);
        log.atWarn().addKeyValue("event", "silver.validation_deferred")
                .addKeyValue("silverPatientId", patientId)
                .addKeyValue("reason", reason)
                .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                .log("Patient left PENDING_VALIDATION; the sweeper will retry");
        meters.counter("clinicalharmony.silver.validations", "outcome", "deferred").increment();
        return new Result(Outcome.PENDING, 0, reason);
    }

    /** rules-engine's PatientRecordRequest, built from Silver; null when the patient doesn't exist. */
    Map<String, Object> load(long patientId) {
        List<Map<String, Object>> patients = jdbc.queryForList(
                "SELECT id, gender, date_of_birth FROM silver.patients WHERE id = ?", patientId);
        if (patients.isEmpty()) {
            return null;
        }
        Map<String, Object> p = patients.get(0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("patientId", patientId);
        request.put("gender", p.get("gender"));
        request.put("dateOfBirth", p.get("date_of_birth") == null ? null : p.get("date_of_birth").toString());
        request.put("conditions", jdbc.query("""
                SELECT id, code_system, code, onset_date FROM silver.conditions WHERE patient_id = ? ORDER BY id
                """, (rs, i) -> item("id", rs.getLong("id"), "codeSystem", rs.getString("code_system"),
                "code", rs.getString("code"), "onsetDate", str(rs.getDate("onset_date"))), patientId));
        request.put("observations", jdbc.query("""
                SELECT id, code, value_numeric, effective_date, reference_low, reference_high
                FROM silver.observations WHERE patient_id = ? ORDER BY id
                """, (rs, i) -> item("id", rs.getLong("id"), "code", rs.getString("code"),
                "valueNumeric", rs.getBigDecimal("value_numeric"),
                "effectiveDate", rs.getTimestamp("effective_date") == null ? null
                        : rs.getTimestamp("effective_date").toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime().toString(),
                "referenceLow", rs.getBigDecimal("reference_low"), "referenceHigh", rs.getBigDecimal("reference_high")),
                patientId));
        request.put("medications", jdbc.query("""
                SELECT id, rxnorm_code, display, start_date FROM silver.medications WHERE patient_id = ? ORDER BY id
                """, (rs, i) -> item("id", rs.getLong("id"), "rxnormCode", rs.getString("rxnorm_code"),
                "display", rs.getString("display"), "startDate", str(rs.getDate("start_date"))), patientId));
        request.put("allergies", jdbc.query("""
                SELECT code_system, code, display FROM silver.allergies WHERE patient_id = ? ORDER BY id
                """, (rs, i) -> item("rxnormCode", "RXNORM".equals(rs.getString("code_system")) ? rs.getString("code") : null,
                "substance", rs.getString("display")), patientId));
        return request;
    }

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String str(java.sql.Date d) {
        return d == null ? null : d.toLocalDate().toString();
    }
}
