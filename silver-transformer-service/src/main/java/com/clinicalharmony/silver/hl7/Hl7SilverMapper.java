package com.clinicalharmony.silver.hl7;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Group;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 v2 → {@link SilverRecord}. Same HAPI parser settings as bronze-listener-service (2.3, structures
 * v2.5, no conformance validation); segments are visited in message order, whatever group HAPI puts
 * them in, so an out-of-place segment is still seen.
 *
 * <table>
 *   <caption>What is mapped</caption>
 *   <tr><th>Message</th><th>Segments → Silver</th></tr>
 *   <tr><td>ADT^*</td><td>PID → patients (demographics updated), DG1 → conditions, AL1 → allergies</td></tr>
 *   <tr><td>ORU^R01</td><td>PID → patients (created if new), OBX → observations (time: OBX-14, else OBR-7, else MSH-7)</td></tr>
 *   <tr><td>RDE^O11, OMP^O09</td><td>PID → patients (created if new), ORC + RXE / RXO → medications</td></tr>
 * </table>
 *
 * <p>Anything that can't be mapped faithfully throws {@link MappingException} (→ Bronze FAILED with that
 * reason): a missing required field, an unsupported message type, an unknown code system, a value that
 * doesn't fit its Silver column. Free-text descriptions are truncated to their column instead.
 */
@Component
public class Hl7SilverMapper {

    private static final Set<String> ICD10 = Set.of("I10", "ICD10", "ICD-10", "I10C", "ICD10CM", "ICD-10-CM");
    private static final Set<String> SNOMED = Set.of("SCT", "SNM", "SNOMED", "SNOMEDCT", "SNOMED-CT");
    private static final Set<String> LOINC = Set.of("LN", "LOINC");
    private static final Set<String> RXNORM = Set.of("RXNORM", "RXN", "RXNORM-CT");
    private static final Set<String> ALLERGY_TYPES = Set.of("DA", "FA", "MA", "MC", "EA", "AA", "PA", "LA");
    private static final Set<String> SEVERITIES = Set.of("SV", "MO", "MI", "U");
    private static final Map<String, String> GENDERS = Map.of("F", "female", "M", "male", "O", "other", "A", "other",
            "U", "unknown", "N", "unknown");
    private static final Map<String, String> OBX_STATUS = Map.of("F", "final", "C", "corrected", "P", "preliminary",
            "R", "preliminary", "I", "registered", "X", "cancelled", "D", "cancelled", "W", "cancelled");
    private static final Pattern RANGE = Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*-\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Pattern TS = Pattern.compile("^(\\d{8})(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d{1,4})?([+-]\\d{4})?$");

    // PipeParser isn't documented as thread-safe and the listener runs 3 consumer threads.
    private final ThreadLocal<PipeParser> parser = ThreadLocal.withInitial(() -> {
        PipeParser p = new PipeParser();
        p.setValidationContext(ValidationContextFactory.noValidation());
        return p;
    });

    public SilverRecord map(String raw, String fallbackSourceSystem) {
        if (raw == null || raw.isBlank()) {
            throw new MappingException("Empty payload");
        }
        Message message;
        try {
            message = parser.get().parse(raw.replace("\r\n", "\r").replace("\n", "\r"));
        } catch (HL7Exception e) {
            throw new MappingException("Not parseable as HL7 v2: " + e.getMessage());
        }
        List<Segment> segments = new ArrayList<>();
        try {
            collect(message, segments);
        } catch (HL7Exception e) {
            throw new MappingException("Can't read message structure: " + e.getMessage());
        }
        Segment msh = first(segments, "MSH");
        if (msh == null) {
            throw new MappingException("No MSH segment");
        }
        String code = upper(get(msh, 9, 1));
        String trigger = upper(get(msh, 9, 2));
        String messageType = trigger == null ? code : code + "^" + trigger;
        Instant messageTime = timestamp(get(msh, 7, 1), "MSH-7");
        String sendingFacility = blankToNull(get(msh, 4, 1));
        String controlId = get(msh, 10, 1);

        boolean adt = "ADT".equals(code);
        boolean oru = "ORU".equals(code) && "R01".equals(trigger);
        boolean pharmacy = ("RDE".equals(code) && "O11".equals(trigger)) || ("OMP".equals(code) && "O09".equals(trigger));
        if (!adt && !oru && !pharmacy) {
            throw new MappingException("Message type " + messageType + " is not mapped to Silver "
                    + "(supported: ADT^*, ORU^R01, RDE^O11, OMP^O09)");
        }

        Segment pid = first(segments, "PID");
        if (pid == null) {
            throw new MappingException(messageType + " has no PID segment");
        }
        SilverRecord.Patient patient = patient(pid, sendingFacility == null ? fallbackSourceSystem : sendingFacility);

        List<SilverRecord.Condition> conditions = new ArrayList<>();
        List<SilverRecord.Observation> observations = new ArrayList<>();
        List<SilverRecord.Medication> medications = new ArrayList<>();
        List<SilverRecord.Allergy> allergies = new ArrayList<>();
        Instant obrTime = null;
        String orderControl = null;
        Instant orderTime = null;
        for (Segment s : segments) {
            switch (s.getName()) {
                case "DG1" -> {
                    if (adt) {
                        conditions.add(condition(s, messageTime));
                    }
                }
                case "AL1" -> {
                    if (adt) {
                        allergies.add(allergy(s));
                    }
                }
                case "OBR" -> obrTime = timestamp(get(s, 7, 1), "OBR-7");
                case "OBX" -> {
                    if (oru) {
                        SilverRecord.Observation o = observation(s, obrTime, messageTime);
                        if (o != null) {
                            observations.add(o);
                        }
                    }
                }
                case "ORC" -> {
                    orderControl = upper(get(s, 1, 1));
                    orderTime = timestamp(get(s, 9, 1), "ORC-9");
                }
                case "RXE" -> {
                    if (pharmacy) {
                        medications.add(medication(s, "RXE", 2, 3, 5, orderControl,
                                orderTime != null ? orderTime : timestamp(get(s, 1, 4), "RXE-1.4"), messageTime));
                    }
                }
                case "RXO" -> {
                    if (pharmacy && "OMP".equals(code)) {
                        medications.add(medication(s, "RXO", 1, 2, 4, orderControl, orderTime, messageTime));
                    }
                }
                default -> {
                    // EVN, PV1, NTE, Z-segments…: not part of the Silver model
                }
            }
        }
        if (pharmacy && medications.isEmpty()) {
            throw new MappingException(messageType + " has no RXE/RXO segment");
        }
        return new SilverRecord(messageType, controlId, messageTime, adt, patient, conditions, observations,
                medications, dedupeAllergies(allergies));
    }

    // ---- segments ---------------------------------------------------------------------------------

    private SilverRecord.Patient patient(Segment pid, String fallbackSource) {
        String id = blankToNull(get(pid, 3, 1));
        if (id == null) {
            throw new MappingException("PID-3 (patient identifier) is empty");
        }
        String authority = blankToNull(get(pid, 3, 4));
        String source = authority != null ? authority : fallbackSource;
        if (source == null) {
            throw new MappingException("No source system: PID-3.4 and MSH-4 are both empty");
        }
        String last = blankToNull(get(pid, 5, 1));
        String first = blankToNull(get(pid, 5, 2));
        if (last == null || first == null) {
            throw new MappingException("PID-5 (patient name) needs family and given name");
        }
        LocalDate dob = date(get(pid, 7, 1), "PID-7");
        if (dob == null) {
            throw new MappingException("PID-7 (date of birth) is empty");
        }
        String sex = upper(get(pid, 8, 1));
        String gender = sex == null ? "unknown" : GENDERS.get(sex);
        if (gender == null) {
            throw new MappingException("PID-8 sex '" + sex + "' is not an HL7 administrative sex code");
        }
        String state = blankToNull(get(pid, 11, 4));
        if (state != null && state.length() != 2) {
            throw new MappingException("PID-11.4 state '" + state + "' is not a 2-letter code");
        }
        return new SilverRecord.Patient(
                fit(source, 100, "PID-3.4 / MSH-4 source system"), fit(id, 100, "PID-3"), fit(id, 50, "PID-3 (MRN)"),
                fit(first, 100, "PID-5.2"), fit(last, 100, "PID-5.1"), dob, gender,
                fit(blankToNull(get(pid, 11, 1)), 200, "PID-11.1"), fit(blankToNull(get(pid, 11, 2)), 200, "PID-11.2"),
                fit(blankToNull(get(pid, 11, 3)), 100, "PID-11.3"), state,
                fit(blankToNull(get(pid, 11, 5)), 10, "PID-11.5"), fit(blankToNull(get(pid, 13, 1)), 20, "PID-13"));
    }

    private SilverRecord.Condition condition(Segment dg1, Instant messageTime) {
        String code = blankToNull(get(dg1, 3, 1));
        if (code == null) {
            throw new MappingException("DG1-3 (diagnosis code) is empty");
        }
        String system = upper(get(dg1, 3, 3));
        String codeSystem;
        if (system != null && ICD10.contains(system)) {
            codeSystem = "ICD-10";
        } else if (system != null && SNOMED.contains(system)) {
            codeSystem = "SNOMED-CT";
        } else {
            throw new MappingException("DG1-3.3 coding system '" + system + "' for " + code + " is not ICD-10 or SNOMED CT");
        }
        Instant diagnosed = timestamp(get(dg1, 5, 1), "DG1-5");
        return new SilverRecord.Condition(codeSystem, fit(code, 20, "DG1-3"), clip(get(dg1, 3, 2), 255),
                diagnosed == null ? null : utcDate(diagnosed), messageTime == null ? null : utcDate(messageTime));
    }

    private SilverRecord.Allergy allergy(Segment al1) {
        String code = blankToNull(get(al1, 3, 1));
        String text = blankToNull(get(al1, 3, 2));
        if (code == null && text == null) {
            throw new MappingException("AL1-3 (allergen) is empty");
        }
        String system = upper(get(al1, 3, 3));
        String codeSystem = system == null ? "LOCAL"
                : RXNORM.contains(system) ? "RXNORM" : SNOMED.contains(system) ? "SNOMED-CT" : "LOCAL";
        String type = upper(get(al1, 2, 1));
        if (type != null && !ALLERGY_TYPES.contains(type)) {
            throw new MappingException("AL1-2 allergen type '" + type + "' is not an HL7 table 0127 code");
        }
        String severity = upper(get(al1, 4, 1));
        if (severity != null && !SEVERITIES.contains(severity)) {
            throw new MappingException("AL1-4 severity '" + severity + "' is not SV / MO / MI / U");
        }
        return new SilverRecord.Allergy(codeSystem, fit(code != null ? code : text, 20, "AL1-3"), clip(text, 255),
                type, severity, clip(get(al1, 5, 1), 255));
    }

    /** @return null for a cancelled OBX with no value (nothing to record) */
    private SilverRecord.Observation observation(Segment obx, Instant obrTime, Instant messageTime) {
        String code = blankToNull(get(obx, 3, 1));
        if (code == null) {
            throw new MappingException("OBX-3 (observation identifier) is empty");
        }
        String system = upper(get(obx, 3, 3));
        if (system == null || !LOINC.contains(system)) {
            throw new MappingException("OBX-3.3 coding system '" + system + "' for " + code + " is not LOINC");
        }
        String rawStatus = upper(get(obx, 11, 1));
        String status = rawStatus == null ? "final" : OBX_STATUS.get(rawStatus);
        if (status == null) {
            throw new MappingException("OBX-11 result status '" + rawStatus + "' is not an HL7 table 0085 code");
        }
        String valueType = upper(get(obx, 2, 1));
        String value = blankToNull(get(obx, 5, 1));
        if (value == null) {
            if ("cancelled".equals(status)) {
                return null;
            }
            throw new MappingException("OBX-5 (value) is empty for " + code);
        }
        BigDecimal numeric = null;
        String text = null;
        if ("NM".equals(valueType) || "SN".equals(valueType)) {
            numeric = number(value);
        }
        if (numeric == null) {
            // ST / TX / CE / an NM the sender couldn't make numeric (e.g. ">200"): kept as text
            text = clip("CE".equals(valueType) || "CWE".equals(valueType)
                    ? firstNonBlank(get(obx, 5, 2), value) : value, 500);
        }
        Instant effective = timestamp(get(obx, 14, 1), "OBX-14");
        if (effective == null) {
            effective = obrTime != null ? obrTime : messageTime;
        }
        if (effective == null) {
            throw new MappingException("No time for OBX " + code + ": OBX-14, OBR-7 and MSH-7 are all empty");
        }
        BigDecimal low = null;
        BigDecimal high = null;
        Matcher m = RANGE.matcher(get(obx, 7, 1) == null ? "" : get(obx, 7, 1));
        if (m.matches()) {
            low = new BigDecimal(m.group(1));
            high = new BigDecimal(m.group(2));
        }
        return new SilverRecord.Observation(fit(code, 20, "OBX-3"), clip(get(obx, 3, 2), 255), numeric, text,
                fit(blankToNull(get(obx, 6, 1)), 50, "OBX-6"), status, effective, low, high,
                fit(blankToNull(get(obx, 8, 1)), 5, "OBX-8"));
    }

    private SilverRecord.Medication medication(Segment s, String name, int codeField, int amountField, int unitField,
                                               String orderControl, Instant start, Instant messageTime) {
        String code = blankToNull(get(s, codeField, 1));
        if (code == null) {
            throw new MappingException(name + "-" + codeField + " (give code) is empty");
        }
        String system = upper(get(s, codeField, 3));
        if (system == null || !RXNORM.contains(system)) {
            throw new MappingException(name + "-" + codeField + ".3 coding system '" + system + "' for " + code
                    + " is not RxNorm");
        }
        Instant when = start != null ? start : messageTime;
        if (when == null) {
            throw new MappingException("No start date for " + name + " " + code + ": ORC-9 and MSH-7 are empty");
        }
        String amount = blankToNull(get(s, amountField, 1));
        String unit = firstNonBlank(get(s, unitField, 1), get(s, unitField, 2));
        String instructions = "RXE".equals(name) ? blankToNull(get(s, 7, 2)) : null;
        StringBuilder dosage = new StringBuilder();
        if (amount != null) {
            dosage.append(amount);
            if (unit != null) {
                dosage.append(' ').append(unit);
            }
        }
        if (instructions != null) {
            dosage.append(dosage.isEmpty() ? "" : ", ").append(instructions);
        }
        return new SilverRecord.Medication(fit(code, 20, name + "-" + codeField), clip(get(s, codeField, 2), 255),
                dosage.isEmpty() ? null : clip(dosage.toString(), 255), medicationStatus(orderControl), utcDate(when));
    }

    /** ORC-1 order control (HL7 table 0119) → silver.medications.status. */
    static String medicationStatus(String orderControl) {
        if (orderControl == null) {
            return "active";
        }
        return switch (orderControl) {
            case "NW", "OK", "SC", "XO", "XX", "RE", "RO", "RP", "PA" -> "active";
            case "DC", "OD", "CA", "OC", "CR", "DR" -> "stopped";
            case "HD", "OH" -> "on-hold";
            case "RL", "OR" -> "active";
            default -> throw new MappingException("ORC-1 order control '" + orderControl + "' is not mapped to a medication status");
        };
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static List<SilverRecord.Allergy> dedupeAllergies(List<SilverRecord.Allergy> in) {
        List<SilverRecord.Allergy> out = new ArrayList<>();
        for (SilverRecord.Allergy a : in) {
            if (out.stream().noneMatch(o -> o.codeSystem().equals(a.codeSystem()) && o.code().equals(a.code()))) {
                out.add(a);
            }
        }
        return out;
    }

    private static void collect(Group group, List<Segment> out) throws HL7Exception {
        for (String name : group.getNames()) {
            for (Structure s : group.getAll(name)) {
                if (s instanceof Segment seg) {
                    if (!seg.isEmpty()) {
                        out.add(seg);
                    }
                } else if (s instanceof Group g) {
                    collect(g, out);
                }
            }
        }
    }

    private static Segment first(List<Segment> segments, String name) {
        return segments.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
    }

    /** First repetition, given component (1-based), first subcomponent; null when absent. */
    static String get(Segment s, int field, int component) {
        try {
            if (s.numFields() < field || s.getField(field).length == 0) {
                return null;
            }
            return blankToNull(Terser.get(s, field, 0, component, 1));
        } catch (HL7Exception e) {
            return null;
        }
    }

    public static Instant timestamp(String value, String where) {
        if (value == null) {
            return null;
        }
        Matcher m = TS.matcher(value.trim());
        if (!m.matches()) {
            throw new MappingException(where + " '" + value + "' is not an HL7 date/time (YYYYMMDD[HHMM[SS]][+/-ZZZZ])");
        }
        try {
            LocalDate d = LocalDate.parse(m.group(1), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            LocalDateTime t = d.atTime(m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                    m.group(4) == null ? 0 : Integer.parseInt(m.group(4)));
            // No zone in the message: read as UTC (the gateway's timeline renders UTC).
            ZoneOffset zone = m.group(5) == null ? ZoneOffset.UTC : ZoneOffset.of(m.group(5));
            return t.toInstant(zone);
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new MappingException(where + " '" + value + "' is not a valid date/time");
        }
    }

    static LocalDate date(String value, String where) {
        Instant t = timestamp(value, where);
        return t == null ? null : utcDate(t);
    }

    private static LocalDate utcDate(Instant t) {
        return t.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static BigDecimal number(String v) {
        try {
            BigDecimal n = new BigDecimal(v.trim());
            // silver.observations.value_numeric is NUMERIC(18, 4)
            return n.scale() > 4 || n.precision() - n.scale() > 14 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Identity / code fields must fit their column whole. */
    private static String fit(String v, int max, String where) {
        if (v != null && v.length() > max) {
            throw new MappingException(where + " is longer than " + max + " characters");
        }
        return v;
    }

    /** Descriptive text may be shortened to its column. */
    private static String clip(String v, int max) {
        v = blankToNull(v);
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String upper(String v) {
        v = blankToNull(v);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String a, String b) {
        String x = blankToNull(a);
        return x != null ? x : blankToNull(b);
    }
}
