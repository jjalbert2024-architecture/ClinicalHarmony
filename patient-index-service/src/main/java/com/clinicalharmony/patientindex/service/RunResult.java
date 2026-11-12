package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.repository.MatchRun;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a matching run did.
 *
 * @param silverPatientsWithoutMaster Silver patients not built into Gold yet — invisible to the
 *                                    PMI until POST /api/fhir/build creates their provisional master
 * @param skipped                     decisions GoldWriter refused (state changed underneath the run)
 */
public record RunResult(MatchRun run, List<AutoMerge> autoMerges, List<Long> queuedCandidateIds,
                        List<Long> confirmedMasterIds, List<String> skipped, int silverPatientsWithoutMaster) {

    public record AutoMerge(long mergeId, long survivorMasterId, long absorbedMasterId, BigDecimal score,
                            String method, String deterministicRule, long silverPatientAId, long silverPatientBId) {
    }
}
