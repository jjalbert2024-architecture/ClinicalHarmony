package com.clinicalharmony.patientindex.matching;

/**
 * Jaro-Winkler string similarity in [0, 1] (Winkler 1990): Jaro similarity, boosted for a shared
 * prefix of up to 4 characters (scaling factor 0.1) when Jaro similarity exceeds 0.7. The standard
 * record-linkage comparator for short strings like names.
 */
public final class JaroWinkler {

    private static final double PREFIX_SCALE = 0.1;
    private static final int MAX_PREFIX = 4;
    private static final double BOOST_THRESHOLD = 0.7;

    private JaroWinkler() {
    }

    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        double jaro = jaro(s1, s2);
        if (jaro <= BOOST_THRESHOLD) {
            return jaro;
        }
        int prefix = 0;
        int limit = Math.min(MAX_PREFIX, Math.min(s1.length(), s2.length()));
        while (prefix < limit && s1.charAt(prefix) == s2.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * PREFIX_SCALE * (1.0 - jaro);
    }

    static double jaro(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int window = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(len2 - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!matched2[j] && s1.charAt(i) == s2.charAt(j)) {
                    matched1[i] = true;
                    matched2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!matched1[i]) {
                continue;
            }
            while (!matched2[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double m = matches;
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
    }
}
