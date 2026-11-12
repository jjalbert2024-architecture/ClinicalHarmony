package com.clinicalharmony.fhircompliance.repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Postgres BIGINT[] helpers for JdbcTemplate: bind as a text literal + {@code ?::bigint[]}. */
public final class PgArrays {

    private PgArrays() {
    }

    /** {@code [1, 2, 3]} → {@code "{1,2,3}"}. Longs only, so the literal can't carry anything else. */
    public static String bigintArray(Collection<Long> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    public static List<Long> toLongs(Array array) throws SQLException {
        List<Long> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (Object o : (Object[]) array.getArray()) {
            out.add(((Number) o).longValue());
        }
        return out;
    }
}
