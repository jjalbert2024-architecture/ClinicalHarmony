package com.clinicalharmony.patientindex.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scores a pair of Silver patients (Phase 6 matching algorithm):
 * <ol>
 *   <li><b>Deterministic rules</b> — {@code SAME_SOURCE_MRN} or {@code EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP}
 *       — give score 1.0 and AUTO_MERGE, as long as nothing contradicts them outright (birth
 *       dates that disagree entirely, or male vs female).</li>
 *   <li>Otherwise <b>Fellegi-Sunter-style weighted agreement</b>: each field scores its outcome
 *       band's weight; score = total / maximum possible over the fields present on BOTH records
 *       (a missing value is neither evidence for nor against), clamped to [0, 1].</li>
 *   <li>score ≥ autoMerge → AUTO_MERGE (only if at least {@code minComparableWeight} points were
 *       comparable — sparse records go to a human however well they agree), ≥ review → REVIEW,
 *       else NO_MATCH.</li>
 * </ol>
 * Every outcome carries the per-field breakdown, so a reviewer (or an auditor reading the merge's
 * evidence in Gold) can see exactly why.
 */
@Component
public class PatientMatcher {

    public MatchScore score(PatientRecord first, PatientRecord second, MatchSettings settings) {
        PatientRecord a = first.silverPatientId() <= second.silverPatientId() ? first : second;
        PatientRecord b = a == first ? second : first;

        String firstA = Normalizer.name(a.firstName());
        String firstB = Normalizer.name(b.firstName());
        String lastA = Normalizer.name(a.lastName());
        String lastB = Normalizer.name(b.lastName());
        String phoneA = Normalizer.phone(a.phone());
        String phoneB = Normalizer.phone(b.phone());
        String zipA = Normalizer.postalCode(a.postalCode());
        String zipB = Normalizer.postalCode(b.postalCode());

        List<FieldScore> fields = new ArrayList<>();
        fields.add(lastName(lastA, lastB, settings.lastName()));
        fields.add(firstName(firstA, firstB, settings.firstName()));
        FieldScore dob = birthDate(a.dateOfBirth(), b.dateOfBirth(), settings.birthDate());
        fields.add(dob);
        FieldScore gender = gender(Normalizer.gender(a.gender()), Normalizer.gender(b.gender()), settings.gender());
        fields.add(gender);
        fields.add(exactOnly("phone", phoneA, phoneB, settings.phone()));
        fields.add(exactOnly("postalCode", zipA, zipB, settings.postalCode()));
        fields.add(address(Normalizer.address(a.addressLine1()), Normalizer.address(b.addressLine1()), settings.address()));

        double total = fields.stream().mapToDouble(FieldScore::weight).sum();
        double max = fields.stream().mapToDouble(FieldScore::maxWeight).sum();
        BigDecimal weighted = round(max <= 0 ? 0 : Math.max(0, Math.min(1, total / max)));

        String rule = null;
        boolean contradicted = FieldScore.DISAGREE.equals(dob.outcome()) || FieldScore.DISAGREE.equals(gender.outcome());
        if (!contradicted) {
            String mrnA = Normalizer.mrn(a.mrn());
            if (mrnA != null && mrnA.equals(Normalizer.mrn(b.mrn())) && Objects.equals(a.sourceSystem(), b.sourceSystem())) {
                rule = MatchScore.SAME_SOURCE_MRN;
            } else if (firstA != null && firstA.equals(firstB) && lastA != null && lastA.equals(lastB)
                    && FieldScore.EXACT.equals(dob.outcome())
                    && ((phoneA != null && phoneA.equals(phoneB)) || (zipA != null && zipA.equals(zipB)))) {
                rule = MatchScore.EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP;
            }
        }

        if (rule != null) {
            return new MatchScore(a.silverPatientId(), b.silverPatientId(), round(1.0), weighted, total, max,
                    MatchScore.DETERMINISTIC, rule, MatchScore.AUTO_MERGE, fields);
        }
        double s = weighted.doubleValue();
        boolean enoughEvidence = max >= settings.minComparableWeight();
        String decision = s >= settings.autoMergeThreshold() && enoughEvidence ? MatchScore.AUTO_MERGE
                : s >= settings.reviewThreshold() ? MatchScore.REVIEW : MatchScore.NO_MATCH;
        return new MatchScore(a.silverPatientId(), b.silverPatientId(), weighted, weighted, total, max,
                MatchScore.PROBABILISTIC, null, decision, fields);
    }

    static FieldScore lastName(String a, String b, FieldWeights w) {
        if (a == null || b == null) {
            return missing("lastName", a, b);
        }
        if (a.equals(b)) {
            return new FieldScore("lastName", FieldScore.EXACT, w.exact(), w.exact(), a, b, null);
        }
        double sim = round(JaroWinkler.similarity(a, b)).doubleValue();
        return sim >= w.similarMin()
                ? new FieldScore("lastName", FieldScore.SIMILAR, w.similar(), w.exact(), a, b, sim)
                : new FieldScore("lastName", FieldScore.DISAGREE, w.disagree(), w.exact(), a, b, sim);
    }

    static FieldScore firstName(String a, String b, FieldWeights w) {
        if (a == null || b == null) {
            return missing("firstName", a, b);
        }
        if (a.equals(b)) {
            return new FieldScore("firstName", FieldScore.EXACT, w.exact(), w.exact(), a, b, null);
        }
        if (Nicknames.equivalent(a, b)) {
            return new FieldScore("firstName", FieldScore.NICKNAME, w.similar(), w.exact(), a, b, null);
        }
        if ((a.length() == 1 || b.length() == 1) && a.charAt(0) == b.charAt(0)) {
            return new FieldScore("firstName", FieldScore.INITIAL, w.initial(), w.exact(), a, b, null);
        }
        double sim = round(JaroWinkler.similarity(a, b)).doubleValue();
        return sim >= w.similarMin()
                ? new FieldScore("firstName", FieldScore.SIMILAR, w.similar(), w.exact(), a, b, sim)
                : new FieldScore("firstName", FieldScore.DISAGREE, w.disagree(), w.exact(), a, b, sim);
    }

    /** PARTIAL: day and month swapped (same year), or exactly one of year / month / day differs. */
    static FieldScore birthDate(LocalDate a, LocalDate b, FieldWeights w) {
        if (a == null || b == null) {
            return missing("birthDate", a == null ? null : a.toString(), b == null ? null : b.toString());
        }
        String va = a.toString();
        String vb = b.toString();
        if (a.equals(b)) {
            return new FieldScore("birthDate", FieldScore.EXACT, w.exact(), w.exact(), va, vb, null);
        }
        boolean swapped = a.getYear() == b.getYear() && a.getMonthValue() == b.getDayOfMonth()
                && a.getDayOfMonth() == b.getMonthValue();
        int differing = (a.getYear() != b.getYear() ? 1 : 0) + (a.getMonthValue() != b.getMonthValue() ? 1 : 0)
                + (a.getDayOfMonth() != b.getDayOfMonth() ? 1 : 0);
        return swapped || differing == 1
                ? new FieldScore("birthDate", FieldScore.PARTIAL, w.partial(), w.exact(), va, vb, null)
                : new FieldScore("birthDate", FieldScore.DISAGREE, w.disagree(), w.exact(), va, vb, null);
    }

    /** Only male vs female is a disagreement; unknown/other on either side is no evidence. */
    static FieldScore gender(String a, String b, FieldWeights w) {
        if (a == null || b == null) {
            return missing("gender", a, b);
        }
        if (a.equals(b)) {
            return new FieldScore("gender", FieldScore.EXACT, w.exact(), w.exact(), a, b, null);
        }
        boolean binaryConflict = (a.equals("male") && b.equals("female")) || (a.equals("female") && b.equals("male"));
        return binaryConflict
                ? new FieldScore("gender", FieldScore.DISAGREE, w.disagree(), w.exact(), a, b, null)
                : missing("gender", a, b);
    }

    static FieldScore exactOnly(String field, String a, String b, FieldWeights w) {
        if (a == null || b == null) {
            return missing(field, a, b);
        }
        return a.equals(b)
                ? new FieldScore(field, FieldScore.EXACT, w.exact(), w.exact(), a, b, null)
                : new FieldScore(field, FieldScore.DISAGREE, w.disagree(), w.exact(), a, b, null);
    }

    static FieldScore address(String a, String b, FieldWeights w) {
        if (a == null || b == null) {
            return missing("address", a, b);
        }
        if (a.equals(b)) {
            return new FieldScore("address", FieldScore.EXACT, w.exact(), w.exact(), a, b, null);
        }
        double sim = round(JaroWinkler.similarity(a, b)).doubleValue();
        return sim >= w.similarMin()
                ? new FieldScore("address", FieldScore.SIMILAR, w.similar(), w.exact(), a, b, sim)
                : new FieldScore("address", FieldScore.DISAGREE, w.disagree(), w.exact(), a, b, sim);
    }

    private static FieldScore missing(String field, String a, String b) {
        return new FieldScore(field, FieldScore.MISSING, 0, 0, a, b, null);
    }

    static BigDecimal round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }
}
