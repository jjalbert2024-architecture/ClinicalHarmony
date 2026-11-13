package com.clinicalharmony.gateway.read;

import java.util.List;

/** One page of a list endpoint. {@code page} is 0-based. */
public record Page<T>(List<T> items, int page, int size, long total, int totalPages) {

    public static <T> Page<T> of(List<T> items, int page, int size, long total) {
        return new Page<>(items, page, size, total, (int) ((total + size - 1) / size));
    }
}
