package com.clinicalharmony.patientindex.matching;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small, conservative table of common English (and a few Spanish) given-name equivalents, used
 * as the "similar" band for first names ("Jim" ≈ "James"). Names are compared after
 * {@link Normalizer#name}. A name may sit in several groups ("alex", "sam").
 */
public final class Nicknames {

    private static final List<List<String>> GROUPS = List.of(
            List.of("james", "jim", "jimmy", "jamie"),
            List.of("william", "will", "bill", "billy", "liam"),
            List.of("robert", "rob", "bob", "bobby", "robbie"),
            List.of("richard", "rick", "rich", "dick", "ricky"),
            List.of("john", "jack", "johnny"),
            List.of("jonathan", "jon", "jonny"),
            List.of("michael", "mike", "mikey"),
            List.of("joseph", "joe", "joey"),
            List.of("thomas", "tom", "tommy"),
            List.of("charles", "charlie", "chuck"),
            List.of("christopher", "chris"),
            List.of("christine", "chris", "chrissy"),
            List.of("daniel", "dan", "danny"),
            List.of("david", "dave"),
            List.of("anthony", "tony"),
            List.of("edward", "ed", "eddie", "ted"),
            List.of("theodore", "ted", "teddy", "theo"),
            List.of("benjamin", "ben"),
            List.of("samuel", "sam", "sammy"),
            List.of("samantha", "sam", "sammy"),
            List.of("nicholas", "nick"),
            List.of("matthew", "matt"),
            List.of("andrew", "andy", "drew"),
            List.of("steven", "stephen", "steve"),
            List.of("timothy", "tim"),
            List.of("kenneth", "ken", "kenny"),
            List.of("ronald", "ron", "ronnie"),
            List.of("donald", "don", "donnie"),
            List.of("gregory", "greg"),
            List.of("alexander", "alex", "xander"),
            List.of("alexandra", "alex", "sasha", "lexi"),
            List.of("margaret", "maggie", "peggy", "meg"),
            List.of("elizabeth", "liz", "beth", "betty", "eliza", "lizzie"),
            List.of("katherine", "catherine", "kate", "kathy", "cathy", "katie"),
            List.of("patricia", "pat", "patty", "trish"),
            List.of("jennifer", "jen", "jenny"),
            List.of("susan", "sue", "suzy"),
            List.of("deborah", "debra", "deb", "debbie"),
            List.of("jessica", "jess", "jessie"),
            List.of("rebecca", "becky", "becca"),
            List.of("victoria", "vicky", "tori"),
            List.of("abigail", "abby"),
            List.of("francisco", "paco", "pancho", "frank"),
            List.of("jose", "pepe"),
            List.of("guadalupe", "lupe"));

    private static final Map<String, Set<Integer>> GROUPS_BY_NAME = index();

    private Nicknames() {
    }

    /** True when two (normalized) given names share a nickname group. */
    public static boolean equivalent(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        Set<Integer> groupsA = GROUPS_BY_NAME.get(a);
        Set<Integer> groupsB = GROUPS_BY_NAME.get(b);
        if (groupsA == null || groupsB == null) {
            return false;
        }
        for (Integer g : groupsA) {
            if (groupsB.contains(g)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Set<Integer>> index() {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (int i = 0; i < GROUPS.size(); i++) {
            for (String name : GROUPS.get(i)) {
                out.computeIfAbsent(name, n -> new HashSet<>()).add(i);
            }
        }
        return out;
    }
}
