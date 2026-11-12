package com.clinicalharmony.patientindex.matching;

import java.text.Normalizer.Form;
import java.util.Locale;
import java.util.Map;

/**
 * Puts each field in a comparable form before scoring. Anything that normalizes to nothing is
 * "missing" (null), which scores 0 and drops out of the maximum — never a disagreement.
 */
public final class Normalizer {

    private static final Map<String, String> ADDRESS_TOKENS = Map.ofEntries(
            Map.entry("street", "st"), Map.entry("avenue", "ave"), Map.entry("av", "ave"),
            Map.entry("road", "rd"), Map.entry("drive", "dr"), Map.entry("boulevard", "blvd"),
            Map.entry("lane", "ln"), Map.entry("court", "ct"), Map.entry("place", "pl"),
            Map.entry("terrace", "ter"), Map.entry("highway", "hwy"), Map.entry("parkway", "pkwy"),
            Map.entry("circle", "cir"), Map.entry("square", "sq"),
            Map.entry("north", "n"), Map.entry("south", "s"), Map.entry("east", "e"), Map.entry("west", "w"),
            Map.entry("apartment", "apt"), Map.entry("suite", "ste"), Map.entry("unit", "apt"));

    private Normalizer() {
    }

    /** "José " → "jose", "Garcia-Lopez" → "garcialopez", "O'Brien" → "obrien"; blank → null. */
    public static String name(String value) {
        if (value == null) {
            return null;
        }
        String ascii = java.text.Normalizer.normalize(value, Form.NFD).replaceAll("\\p{M}", "");
        String letters = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return letters.isEmpty() ? null : letters;
    }

    /** US phone → its 10 digits ("(512) 555-0101", "+1 512.555.0101" → "5125550101"); anything else → null. */
    public static String phone(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        return digits.length() == 10 ? digits : null;
    }

    /** ZIP or ZIP+4 → the 5-digit ZIP; anything else → null. */
    public static String postalCode(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() >= 5 ? digits.substring(0, 5) : null;
    }

    /** "1200 Congress Avenue," → "1200 congress ave"; USPS-style suffix/direction abbreviations. */
    public static String address(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.toLowerCase(Locale.ROOT).replace("#", " apt ").replaceAll("[^a-z0-9 ]", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String token : cleaned.split("\\s+")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(ADDRESS_TOKENS.getOrDefault(token, token));
        }
        return out.toString();
    }

    /** "male"/"female"/"other" as-is (lower-cased); "unknown", blank or null → null (missing). */
    public static String gender(String value) {
        if (value == null) {
            return null;
        }
        String g = value.trim().toLowerCase(Locale.ROOT);
        return g.isEmpty() || g.equals("unknown") ? null : g;
    }

    /** MRNs compare case-insensitively, ignoring surrounding whitespace; blank → null. */
    public static String mrn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
