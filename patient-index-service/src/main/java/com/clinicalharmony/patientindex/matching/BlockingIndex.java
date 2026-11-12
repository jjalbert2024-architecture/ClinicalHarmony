package com.clinicalharmony.patientindex.matching;

import org.apache.commons.codec.language.DoubleMetaphone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blocking: only records sharing at least one key are ever compared, instead of all n² pairs.
 * Keys: exact birth date; Double Metaphone of the last name + birth year (catches spelling
 * variants like Wilson/Willson); phone. A pair is emitted once however many keys it shares.
 *
 * <p>Built in memory per run — fine at this project's scale. At production scale the same keys
 * would be persisted and indexed, and oversized blocks (a very common DOB) are skipped with a
 * warning here rather than exploding into millions of comparisons.
 */
public final class BlockingIndex {

    private static final Logger log = LoggerFactory.getLogger(BlockingIndex.class);
    static final int MAX_BLOCK_SIZE = 500;
    private static final DoubleMetaphone METAPHONE = new DoubleMetaphone();

    private BlockingIndex() {
    }

    public record Pair(PatientRecord a, PatientRecord b) {
    }

    public static List<String> keys(PatientRecord r) {
        List<String> keys = new ArrayList<>(3);
        if (r.dateOfBirth() != null) {
            keys.add("DOB:" + r.dateOfBirth());
        }
        String last = Normalizer.name(r.lastName());
        if (last != null && r.dateOfBirth() != null) {
            keys.add("LNAME:" + METAPHONE.doubleMetaphone(last) + ":" + r.dateOfBirth().getYear());
        }
        String phone = Normalizer.phone(r.phone());
        if (phone != null) {
            keys.add("PHONE:" + phone);
        }
        return keys;
    }

    /** Candidate pairs (lower Silver id first) from records sharing a blocking key. */
    public static List<Pair> candidatePairs(List<PatientRecord> records) {
        Map<String, List<PatientRecord>> blocks = new HashMap<>();
        for (PatientRecord r : records) {
            for (String key : keys(r)) {
                blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        Set<Long> seen = new HashSet<>();
        List<Pair> pairs = new ArrayList<>();
        blocks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(block -> {
            List<PatientRecord> members = block.getValue();
            if (members.size() > MAX_BLOCK_SIZE) {
                log.warn("Skipping oversized blocking key {} ({} records)", block.getKey(), members.size());
                return;
            }
            for (int i = 0; i < members.size(); i++) {
                for (int j = i + 1; j < members.size(); j++) {
                    PatientRecord x = members.get(i);
                    PatientRecord y = members.get(j);
                    PatientRecord a = x.silverPatientId() < y.silverPatientId() ? x : y;
                    PatientRecord b = a == x ? y : x;
                    if (a.silverPatientId() == b.silverPatientId()) {
                        continue;
                    }
                    if (seen.add(pairKey(a.silverPatientId(), b.silverPatientId()))) {
                        pairs.add(new Pair(a, b));
                    }
                }
            }
        });
        return pairs;
    }

    /** Packs an ordered id pair into one long (both ids fit in 32 bits). */
    static long pairKey(long lower, long higher) {
        return (lower << 32) | (higher & 0xffffffffL);
    }
}
