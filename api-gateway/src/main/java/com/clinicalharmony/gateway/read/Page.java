package com.clinicalharmony.gateway.read;

import java.util.List;

/**
 * One page of a list endpoint. {@code page} is 0-based. {@code minId} / {@code maxId}: the lowest and highest record
 * number in the WHOLE filtered result (not just this page), for lists whose records have a number — so the UI can say
 * "173 messages, #5 – #1088" and the user knows what to type in the range boxes; {@code null} for lists without one.
 */
public record Page<T>(List<T> items, int page, int size, long total, int totalPages, Long minId, Long maxId) {

    public static <T> Page<T> of(List<T> items, int page, int size, long total) {
        return of(items, page, size, total, null, null);
    }

    public static <T> Page<T> of(List<T> items, int page, int size, long total, Long minId, Long maxId) {
        return new Page<>(items, page, size, total, (int) ((total + size - 1) / size), minId, maxId);
    }

    public static <T> Page<T> of(List<T> items, int page, int size, Sql.Counted counted) {
        return of(items, page, size, counted.total(), counted.minId(), counted.maxId());
    }
}
