package com.clinicalharmony.patientindex.repository;

import java.time.OffsetDateTime;

/** A pmi.match_runs row. */
public record MatchRun(long id, String status, String triggeredBy, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                       int provisionalMasters, int pairsCompared, int autoMerges, int queuedForReview,
                       int confirmedSingletons, String errorMessage) {
}
