package com.clinicalharmony.silver.store;

import com.clinicalharmony.silver.config.Topics;
import com.clinicalharmony.silver.hl7.Hl7SilverMapper;
import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import com.clinicalharmony.silver.listener.BronzeIngestedEvent;
import com.clinicalharmony.silver.listener.InvalidEventException;
import com.clinicalharmony.silver.outbox.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One transaction per {@code clinicalharmony.bronze.ingested} record: the Bronze row is locked, mapped,
 * written to {@code silver.*}, marked PROCESSED, and the {@code clinicalharmony.silver.patient.changed}
 * outbox event is inserted — all or nothing. A message that can't be mapped makes the Bronze row FAILED
 * (with the reason) and writes nothing to Silver.
 *
 * <p>Idempotent: only a PENDING Bronze row is transformed, under {@code FOR UPDATE}; a redelivered event
 * finds it PROCESSED / FAILED and is a SKIPPED no-op (no second event). Inside one message:
 * <ul>
 *   <li>the patient is upserted on (source_system, source_patient_id); ADT overwrites demographics,
 *       results and orders only create a missing patient;</li>
 *   <li>conditions (DG1) and allergies (AL1) are patient-level lists every ADT repeats, so they are
 *       matched on (patient, code system, code) — a repeat refreshes the row, it doesn't add one;</li>
 *   <li>observations (OBX) and medications (RXE/RXO) are events owned by their message
 *       ({@code raw_message_id}): replaced as a set.</li>
 * </ul>
 */
@Service
public class SilverWriter {

    public enum Outcome { TRANSFORMED, FAILED, SKIPPED }

    public enum Change { CREATED, UPDATED }

    public record Counts(int conditions, int observations, int medications, int allergies) {
    }

    public record Result(Outcome outcome, long rawMessageId, Long silverPatientId, Change change, String messageType,
                         Counts counts, String error, String bronzeStatus, UUID outboxEventId) {
    }

    private final JdbcTemplate jdbc;
    private final Hl7SilverMapper mapper;
    private final OutboxRepository outbox;

    public SilverWriter(JdbcTemplate jdbc, Hl7SilverMapper mapper, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Transactional
    public Result transform(BronzeIngestedEvent event, KafkaSource source) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, source_system, message_type, raw_payload, processing_status
                FROM bronze.raw_messages WHERE id = ? FOR UPDATE
                """, event.rawMessageId());
        if (rows.isEmpty()) {
            throw new InvalidEventException("No bronze.raw_messages row with id " + event.rawMessageId());
        }
        Map<String, Object> bronze = rows.get(0);
        String status = (String) bronze.get("processing_status");
        if (!"PENDING".equals(status)) {
            return new Result(Outcome.SKIPPED, event.rawMessageId(), null, null, event.hl7MessageType(), null, null,
                    status, null);
        }

        SilverRecord record;
        try {
            if (!"HL7V2".equals(bronze.get("message_type"))) {
                throw new MappingException("Bronze message_type " + bronze.get("message_type") + " is not HL7V2");
            }
            record = mapper.map((String) bronze.get("raw_payload"), (String) bronze.get("source_system"));
        } catch (MappingException e) {
            jdbc.update("""
                    UPDATE bronze.raw_messages
                    SET processing_status = 'FAILED', error_message = ?, processed_at = now()
                    WHERE id = ?
                    """, e.getMessage(), event.rawMessageId());
            return new Result(Outcome.FAILED, event.rawMessageId(), null, null, event.hl7MessageType(), null,
                    e.getMessage(), "FAILED", null);
        }

        long rawId = event.rawMessageId();
        PatientRow patient = upsertPatient(record, rawId);
        for (SilverRecord.Condition c : record.conditions()) {
            upsertCondition(patient.id(), c, rawId);
        }
        for (SilverRecord.Allergy a : record.allergies()) {
            upsertAllergy(patient.id(), a, rawId);
        }
        jdbc.update("DELETE FROM silver.observations WHERE raw_message_id = ?", rawId);
        for (SilverRecord.Observation o : record.observations()) {
            jdbc.update("""
                    INSERT INTO silver.observations (patient_id, code_system, code, display, value_numeric, value_text, unit,
                        status, effective_date, reference_low, reference_high, abnormal_flag, raw_message_id)
                    VALUES (?, 'LOINC', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, patient.id(), o.code(), o.display(), o.valueNumeric(), o.valueText(), o.unit(), o.status(),
                    Timestamp.from(o.effectiveAt()), o.referenceLow(), o.referenceHigh(), o.abnormalFlag(), rawId);
        }
        jdbc.update("DELETE FROM silver.medications WHERE raw_message_id = ?", rawId);
        for (SilverRecord.Medication m : record.medications()) {
            jdbc.update("""
                    INSERT INTO silver.medications (patient_id, rxnorm_code, display, dosage_text, status, start_date, raw_message_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, patient.id(), m.rxnormCode(), m.display(), m.dosageText(), m.status(), Date.valueOf(m.startDate()),
                    rawId);
        }
        jdbc.update("""
                UPDATE silver.patients
                SET validation_status = 'PENDING_VALIDATION', validation_requested_at = now(), validation_error = NULL
                WHERE id = ?
                """, patient.id());
        jdbc.update("""
                UPDATE bronze.raw_messages SET processing_status = 'PROCESSED', error_message = NULL, processed_at = now()
                WHERE id = ?
                """, rawId);

        Counts counts = new Counts(record.conditions().size(), record.observations().size(), record.medications().size(),
                record.allergies().size());
        Change change = patient.created() ? Change.CREATED : Change.UPDATED;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("silverPatientId", patient.id());
        payload.put("sourceSystem", record.patient().sourceSystem());
        payload.put("sourcePatientId", record.patient().sourcePatientId());
        payload.put("rawMessageId", rawId);
        payload.put("hl7MessageType", record.messageType());
        payload.put("controlId", record.controlId());
        payload.put("change", change.name());
        payload.put("counts", Map.of("conditions", counts.conditions(), "observations", counts.observations(),
                "medications", counts.medications(), "allergies", counts.allergies()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("event-type", "silver.patient.changed");
        headers.put("schema-version", "1");
        headers.put("producer", "silver-transformer-service");
        if (source.correlationId() != null) {
            headers.put("correlation-id", source.correlationId());
        }
        if (source.eventId() != null) {
            headers.put("causation-id", source.eventId());
        }
        // Same partition key as the Bronze topics (source|PID-3), so one patient's events stay in order.
        String key = source.key() != null ? source.key()
                : record.patient().sourceSystem() + "|" + record.patient().sourcePatientId();
        UUID eventId = outbox.insert(Topics.SILVER_PATIENT_CHANGED, "silver.patient.changed", key, payload, headers,
                patient.id());
        return new Result(Outcome.TRANSFORMED, rawId, patient.id(), change, record.messageType(), counts, null,
                "PROCESSED", eventId);
    }

    record PatientRow(long id, boolean created) {
    }

    private PatientRow upsertPatient(SilverRecord r, long rawId) {
        SilverRecord.Patient p = r.patient();
        Object[] values = {p.sourceSystem(), p.sourcePatientId(), p.mrn(), p.firstName(), p.lastName(),
                Date.valueOf(p.dateOfBirth()), p.gender(), p.addressLine1(), p.addressLine2(), p.city(), p.state(),
                p.postalCode(), p.phone(), rawId};
        String insert = """
                INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name, date_of_birth,
                    gender, address_line1, address_line2, city, state, postal_code, phone, raw_message_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        if (r.updatesDemographics()) {
            return jdbc.queryForObject(insert + """
                    ON CONFLICT (source_system, source_patient_id) DO UPDATE SET
                        mrn = EXCLUDED.mrn, first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                        date_of_birth = EXCLUDED.date_of_birth, gender = EXCLUDED.gender,
                        address_line1 = EXCLUDED.address_line1, address_line2 = EXCLUDED.address_line2,
                        city = EXCLUDED.city, state = EXCLUDED.state, postal_code = EXCLUDED.postal_code,
                        phone = EXCLUDED.phone, raw_message_id = EXCLUDED.raw_message_id
                    RETURNING id, (xmax = 0) AS created
                    """, (rs, i) -> new PatientRow(rs.getLong("id"), rs.getBoolean("created")), values);
        }
        List<Long> inserted = jdbc.queryForList(insert + " ON CONFLICT (source_system, source_patient_id) DO NOTHING RETURNING id",
                Long.class, values);
        if (!inserted.isEmpty()) {
            return new PatientRow(inserted.get(0), true);
        }
        Long existing = jdbc.queryForObject("SELECT id FROM silver.patients WHERE source_system = ? AND source_patient_id = ?",
                Long.class, p.sourceSystem(), p.sourcePatientId());
        return new PatientRow(existing, false);
    }

    private void upsertCondition(long patientId, SilverRecord.Condition c, long rawId) {
        int updated = jdbc.update("""
                UPDATE silver.conditions
                SET display = COALESCE(?, display), onset_date = COALESCE(onset_date, ?)
                WHERE id = (SELECT min(id) FROM silver.conditions WHERE patient_id = ? AND code_system = ? AND code = ?)
                """, c.display(), c.onsetDate() == null ? null : Date.valueOf(c.onsetDate()), patientId, c.codeSystem(),
                c.code());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO silver.conditions (patient_id, code_system, code, display, onset_date, recorded_date, raw_message_id)
                    VALUES (?, ?, ?, ?, ?, COALESCE(?, CURRENT_DATE), ?)
                    """, patientId, c.codeSystem(), c.code(), c.display(),
                    c.onsetDate() == null ? null : Date.valueOf(c.onsetDate()),
                    c.recordedDate() == null ? null : Date.valueOf(c.recordedDate()), rawId);
        }
    }

    private void upsertAllergy(long patientId, SilverRecord.Allergy a, long rawId) {
        jdbc.update("""
                INSERT INTO silver.allergies (patient_id, code_system, code, display, allergy_type, severity, reaction, raw_message_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (patient_id, code_system, code) DO UPDATE SET
                    display = COALESCE(EXCLUDED.display, silver.allergies.display),
                    allergy_type = COALESCE(EXCLUDED.allergy_type, silver.allergies.allergy_type),
                    severity = COALESCE(EXCLUDED.severity, silver.allergies.severity),
                    reaction = COALESCE(EXCLUDED.reaction, silver.allergies.reaction)
                """, patientId, a.codeSystem(), a.code(), a.display(), a.allergyType(), a.severity(), a.reaction(), rawId);
    }
}
