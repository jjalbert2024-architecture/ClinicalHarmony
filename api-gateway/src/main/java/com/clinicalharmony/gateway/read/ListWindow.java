package com.clinicalharmony.gateway.read;

/**
 * The part of every paged list that isn't a business filter: a range of the list's own record numbers
 * ({@code idFrom ≤ id ≤ idTo}, either end optional) and the order. {@code order} is {@code newest} or {@code oldest}
 * (lists of people also take {@code name}); {@code null} = the list's default order.
 */
public record ListWindow(Long idFrom, Long idTo, String order) {

    public static final ListWindow NONE = new ListWindow(null, null, null);

    /** The orders a request may ask for; each list says which of them it supports. */
    public static final String TIME_ORDERS = "newest|oldest";
    public static final String PERSON_ORDERS = "name|newest|oldest";

    public ListWindow {
        // A reversed range is a typo, not an empty question: read it the way the user meant it.
        if (idFrom != null && idTo != null && idFrom > idTo) {
            Long swap = idFrom;
            idFrom = idTo;
            idTo = swap;
        }
    }

    boolean oldestFirst() {
        return "oldest".equals(order);
    }

    boolean newestFirst() {
        return "newest".equals(order);
    }

    /** Adds the number range on {@code idColumn} (a trusted constant) as bound parameters. */
    Sql.Where restrict(Sql.Where where, String idColumn) {
        return where.and(idFrom != null, idColumn + " >= :windowFrom", "windowFrom", idFrom)
                .and(idTo != null, idColumn + " <= :windowTo", "windowTo", idTo);
    }

    /**
     * {@code ORDER BY} the list's own record number: newest (highest) first unless {@code oldest}. Deliberately not a
     * time column — rows from parallel Kafka partitions land in the same millisecond out of number order, and a list
     * whose numbers jump around (#1141, #1117, #1148 …) can't be read as a range.
     */
    String idOrder(String idColumn) {
        return " ORDER BY " + idColumn + (oldestFirst() ? " ASC" : " DESC");
    }
}
