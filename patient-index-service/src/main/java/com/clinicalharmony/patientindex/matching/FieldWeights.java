package com.clinicalharmony.patientindex.matching;

/**
 * Weights (log-likelihood-style points) for one field's comparison outcomes. {@code exact} is also
 * the field's contribution to the maximum possible score. Bands a field doesn't use are 0.
 *
 * @param similarMin Jaro-Winkler similarity at or above which a near-miss scores {@code similar}
 */
public record FieldWeights(double exact, double similar, double similarMin, double partial, double initial,
                           double disagree) {
}
