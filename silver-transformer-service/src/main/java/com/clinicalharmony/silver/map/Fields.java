package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Field rules shared by the FHIR / claims / C-CDA mappers — the same rules as Hl7SilverMapper's. */
final class Fields {

    private Fields() {
    }

    /** Identity / code fields must fit their column whole. */
    static String fit(String v, int max, String where) {
        v = blankToNull(v);
        if (v != null && v.length() > max) {
            throw new MappingException(where + " is longer than " + max + " characters");
        }
        return v;
    }

    /** Descriptive text may be shortened to its column. */
    static String clip(String v, int max) {
        v = blankToNull(v);
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String lower(String v) {
        v = blankToNull(v);
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    static String required(String v, String what) {
        v = blankToNull(v);
        if (v == null) {
            throw new MappingException(what + " is missing");
        }
        return v;
    }

    /** FHIR date / dateTime: {@code 2026-11-01}, {@code 2026-11-01T10:00:00Z}, with offset, or without (= UTC). */
    static Instant fhirInstant(String v, String where) {
        v = blankToNull(v);
        if (v == null) {
            return null;
        }
        try {
            if (v.length() == 4) {
                return LocalDate.of(Integer.parseInt(v), 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (v.length() == 7) {
                return LocalDate.parse(v + "-01").atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (v.length() == 10) {
                return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new MappingException(where + " '" + v + "' is not a FHIR date/dateTime");
        }
    }

    static LocalDate fhirDate(String v, String where) {
        Instant t = fhirInstant(v, where);
        return t == null ? null : utcDate(t);
    }

    static LocalDate utcDate(Instant t) {
        return t.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** NUMERIC(18, 4): null when it doesn't fit (kept as text by the caller) or isn't a number. */
    static BigDecimal number(String v) {
        v = blankToNull(v);
        if (v == null) {
            return null;
        }
        try {
            BigDecimal n = new BigDecimal(v);
            return n.scale() > 4 || n.precision() - n.scale() > 14 ? null : n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
