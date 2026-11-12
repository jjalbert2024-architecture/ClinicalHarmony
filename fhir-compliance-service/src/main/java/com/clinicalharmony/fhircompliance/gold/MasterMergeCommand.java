package com.clinicalharmony.fhircompliance.gold;

import java.math.BigDecimal;

/**
 * "Absorb {@code absorbedMasterId} into {@code survivorMasterId}", as decided by patient-index-service.
 *
 * @param matchConfidenceScore the score that justified it, in [0, 1]; null for a manual merge without one
 * @param matchMethod          DETERMINISTIC, PROBABILISTIC or MANUAL
 * @param evidenceJson         JSON object explaining the decision (scored pair + breakdown, reviewer note), or null
 * @param decidedBy            who decided — a reviewer, or the PMI run — plus the calling client
 */
public record MasterMergeCommand(long survivorMasterId, long absorbedMasterId, BigDecimal matchConfidenceScore,
                                 String matchMethod, String evidenceJson, String decidedBy) {
}
