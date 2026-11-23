package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.clinicalharmony.silver.map.Fields.blankToNull;
import static com.clinicalharmony.silver.map.Fields.fit;

/**
 * One claims CSV line (as bronze-listener-service stores it: header + that line) → Silver (9d part 2). The member is
 * a Silver patient keyed on (source system, member_id) from the line's member columns; the diagnosis becomes an
 * ICD-10 condition (onset = service date); the line itself a silver.claims row, with the claim's lifecycle (payer,
 * status, dates, amounts, reason) when the file has those optional columns (migration 28). A claim never overwrites a
 * registration's demographics: it only creates the member when it isn't in Silver yet.
 */
@Component
public class ClaimsSilverMapper {

    static final List<String> REQUIRED = List.of("claim_id", "member_id", "member_first_name", "member_last_name",
            "member_dob", "member_gender", "service_date", "icd10_code", "billed_amount");
    private static final Map<String, String> GENDERS = Map.of("M", "male", "F", "female", "MALE", "male",
            "FEMALE", "female", "O", "other", "OTHER", "other", "U", "unknown", "UNKNOWN", "unknown");
    /** Claim lifecycle (migration 28): where the claim is now — the X12 837 → 277 → 835 states, simplified. */
    static final Set<String> STATUSES = Set.of("SUBMITTED", "ACCEPTED", "PENDED", "PAID", "PARTIALLY_PAID", "DENIED",
            "REJECTED", "APPEALED", "VOIDED");
    /** A claim adjustment reason code: group (CO / PR / OA / PI / CR) + reason, e.g. CO-50, PR-1, CO-B7. */
    private static final Pattern REASON = Pattern.compile("(CO|PR|OA|PI|CR)-[A-Z0-9]{1,4}");
    private static final Set<String> OPTIONAL = Set.of("member_zip", "member_phone", "payer_id", "payer_name",
            "claim_status", "submitted_date", "status_date", "allowed_amount", "paid_amount", "patient_responsibility",
            "status_reason");

    public SilverRecord map(String raw, String sourceSystem) {
        CSVRecord row;
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build()
                .parse(new StringReader(raw))) {
            List<CSVRecord> rows = parser.getRecords();
            if (rows.size() != 1) {
                throw new MappingException("A claims Bronze row holds one claim line, found " + rows.size());
            }
            row = rows.get(0);
            List<String> missing = REQUIRED.stream().filter(c -> !parser.getHeaderNames().contains(c)).toList();
            if (!missing.isEmpty()) {
                throw new MappingException("Claims line has no member columns " + missing
                        + " (Silver needs the member's name, DOB and gender)");
            }
        } catch (MappingException e) {
            throw e;
        } catch (Exception e) {
            throw new MappingException("Claims line is not CSV: " + e.getMessage());
        }
        String memberId = fit(required(row, "member_id"), 100, "member_id");
        String gender = GENDERS.get(required(row, "member_gender").toUpperCase());
        if (gender == null) {
            throw new MappingException("member_gender '" + row.get("member_gender") + "' is not M / F / O / U");
        }
        SilverRecord.Patient patient = new SilverRecord.Patient(fit(sourceSystem, 100, "source system"), memberId, null,
                fit(required(row, "member_first_name"), 100, "member_first_name"),
                fit(required(row, "member_last_name"), 100, "member_last_name"), date(row, "member_dob"), gender,
                null, null, null, null, fit(optional(row, "member_zip"), 10, "member_zip"),
                fit(optional(row, "member_phone"), 20, "member_phone"));

        String claimId = fit(required(row, "claim_id"), 50, "claim_id");
        LocalDate serviceDate = date(row, "service_date");
        String icd10 = fit(required(row, "icd10_code"), 20, "icd10_code");
        BigDecimal billed = amount(row, "billed_amount", required(row, "billed_amount"));
        SilverRecord.Condition diagnosis = new SilverRecord.Condition("ICD-10", icd10, null, serviceDate, serviceDate);
        return new SilverRecord("CLAIM", claimId, null, false, patient, List.of(diagnosis), List.of(), List.of(),
                List.of(), List.of(new SilverRecord.Claim(claimId, serviceDate, icd10, billed, lifecycle(row))));
    }

    /** The optional lifecycle columns; strict on the ones present (status values, dates, amounts, reason codes). */
    private static SilverRecord.Lifecycle lifecycle(CSVRecord row) {
        String status = optional(row, "claim_status");
        if (status != null) {
            status = status.toUpperCase();
            if (!STATUSES.contains(status)) {
                throw new MappingException("claim_status '" + row.get("claim_status") + "' is not one of " + STATUSES);
            }
        }
        String reason = optional(row, "status_reason");
        if (reason != null) {
            reason = reason.toUpperCase();
            if (!REASON.matcher(reason).matches()) {
                throw new MappingException("status_reason '" + row.get("status_reason")
                        + "' is not a claim adjustment reason code like CO-50 or PR-1");
            }
        }
        LocalDate submitted = optionalDate(row, "submitted_date");
        LocalDate statusDate = optionalDate(row, "status_date");
        if (submitted != null && statusDate != null && statusDate.isBefore(submitted)) {
            throw new MappingException("status_date " + statusDate + " is before submitted_date " + submitted);
        }
        return new SilverRecord.Lifecycle(fit(optional(row, "payer_id"), 50, "payer_id"),
                fit(optional(row, "payer_name"), 100, "payer_name"), status, submitted, statusDate,
                optionalAmount(row, "allowed_amount"), optionalAmount(row, "paid_amount"),
                optionalAmount(row, "patient_responsibility"), reason);
    }

    private static BigDecimal optionalAmount(CSVRecord row, String column) {
        String v = optional(row, column);
        return v == null ? null : amount(row, column, v);
    }

    private static BigDecimal amount(CSVRecord row, String column, String v) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new MappingException(column + " '" + row.get(column) + "' is not a number");
        }
        if (amount.signum() < 0 || amount.scale() > 2 || amount.precision() - amount.scale() > 10) {
            throw new MappingException(column + " " + amount + " is not a non-negative amount of at most 10 digits + cents");
        }
        return amount;
    }

    private static LocalDate optionalDate(CSVRecord row, String column) {
        return optional(row, column) == null ? null : date(row, column);
    }

    private static String required(CSVRecord row, String column) {
        String v = blankToNull(row.get(column));
        if (v == null) {
            throw new MappingException(column + " is empty");
        }
        return v;
    }

    private static String optional(CSVRecord row, String column) {
        return OPTIONAL.contains(column) && row.isMapped(column) ? blankToNull(row.get(column)) : null;
    }

    private static LocalDate date(CSVRecord row, String column) {
        String v = required(row, column);
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new MappingException(column + " '" + v + "' is not a YYYY-MM-DD date");
        }
    }
}
