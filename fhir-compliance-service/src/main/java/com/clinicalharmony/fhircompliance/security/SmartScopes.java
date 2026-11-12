package com.clinicalharmony.fhircompliance.security;

import com.clinicalharmony.fhircompliance.cms0057.Cms0057SearchParameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SMART App Launch v2 scope rules for the {@code /fhir/r4} read API (backend services, so only
 * the {@code system/} context counts — {@code patient/} and {@code user/} scopes need a launch
 * context this server doesn't have).
 *
 * <ul>
 *   <li>v2 form {@code system/<Type|*>.<cruds>}: read-by-id needs {@code r}, search needs
 *       {@code s}; so {@code system/Coverage.rs} grants both, {@code system/Coverage.r} only reads.
 *       The permission letters must be a non-empty, in-order subset of {@code cruds} (spec rule).</li>
 *   <li>v1 form {@code system/<Type|*>.read|write|*}: accepted for backward compatibility, as SMART v2
 *       asks servers to; {@code read} and {@code *} grant both r and s, {@code write} grants neither.</li>
 * </ul>
 * Non-SMART operational endpoints use {@link #PIPELINE_WRITE} instead.
 */
public final class SmartScopes {

    /** Builds/revalidates Gold, generates FHIR, toggles rules — not a FHIR data-access scope. */
    public static final String PIPELINE_WRITE = "clinicalharmony/pipeline.write";

    public enum Interaction {
        READ('r'), SEARCH('s');

        private final char letter;

        Interaction(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }
    }

    private static final Pattern SCOPE = Pattern.compile("^system/(\\*|[A-Z][A-Za-z]+)\\.([a-z*]+)$");
    private static final Pattern V2_PERMISSIONS = Pattern.compile("^c?r?u?d?s?$");

    private SmartScopes() {
    }

    /** True if any of {@code granted} allows {@code interaction} on {@code resourceType}. */
    public static boolean permits(Collection<String> granted, String resourceType, Interaction interaction) {
        for (String scope : granted) {
            if (scope != null && permitsOne(scope.trim(), resourceType, interaction)) {
                return true;
            }
        }
        return false;
    }

    static boolean permitsOne(String scope, String resourceType, Interaction interaction) {
        Matcher m = SCOPE.matcher(scope);
        if (!m.matches()) {
            return false;
        }
        String type = m.group(1);
        if (!type.equals("*") && !type.equals(resourceType)) {
            return false;
        }
        String permissions = m.group(2);
        switch (permissions) {
            case "read", "*" -> {
                return true; // SMART v1: read covers read + search
            }
            case "write" -> {
                return false;
            }
            default -> {
                return V2_PERMISSIONS.matcher(permissions).matches()
                        && permissions.indexOf(interaction.letter()) >= 0;
            }
        }
    }

    /** The narrowest v2 scope that would allow the interaction, e.g. {@code system/Coverage.s}. */
    public static String required(String resourceType, Interaction interaction) {
        return "system/" + resourceType + "." + interaction.letter();
    }

    /** Every scope this server understands, for {@code scopes_supported} in SMART discovery. */
    public static List<String> supported() {
        List<String> scopes = new ArrayList<>();
        for (String type : Cms0057SearchParameters.SUPPORTED_TYPES) {
            scopes.add("system/" + type + ".rs");
        }
        scopes.add("system/*.rs");
        scopes.add(PIPELINE_WRITE);
        return scopes;
    }
}
