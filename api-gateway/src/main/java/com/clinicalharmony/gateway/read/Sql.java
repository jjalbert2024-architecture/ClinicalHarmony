package com.clinicalharmony.gateway.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Small helpers for the read models: optional filters appended as named-parameter predicates (never
 * string-concatenated values), ILIKE escaping, and null-safe column readers.
 */
final class Sql {

    private Sql() {
    }

    /** A WHERE clause built from optional filters. */
    static final class Where {
        private final List<String> predicates = new ArrayList<>();
        final MapSqlParameterSource params = new MapSqlParameterSource();

        Where and(boolean present, String predicate, String name, Object value) {
            if (present) {
                predicates.add(predicate);
                if (name != null) {
                    params.addValue(name, value);
                }
            }
            return this;
        }

        Where and(String predicate) {
            predicates.add(predicate);
            return this;
        }

        String sql() {
            return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        }
    }

    /** {@code %text%} for ILIKE with the user's own {@code %}, {@code _} and {@code \} escaped. */
    static String contains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static OffsetDateTime ts(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<String> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(String.valueOf(v));
        }
        return out;
    }

    static JsonNode json(ObjectMapper mapper, ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new SQLException("Column " + column + " is not valid JSON", e);
        }
    }
}
