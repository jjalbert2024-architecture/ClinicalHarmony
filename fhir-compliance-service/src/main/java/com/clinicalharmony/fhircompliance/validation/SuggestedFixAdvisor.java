package com.clinicalharmony.fhircompliance.validation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an HL7 validator finding into a plain-English "how to fix it" hint for the debugger
 * endpoint. Keyed on the validator's stable message id / constraint key first, then on message
 * shape for the families of messages whose ids vary. Returns null when there's nothing more useful
 * to say than the validator's own message.
 */
@Component
public class SuggestedFixAdvisor {

    private static final Pattern MIN_CARDINALITY =
            Pattern.compile("^([A-Za-z.\\[\\]0-9]+): minimum required = (\\d+), but only found (\\d+)");
    private static final Pattern MAX_CARDINALITY =
            Pattern.compile("^([A-Za-z.\\[\\]0-9]+): max allowed = (\\d+), but found (\\d+)");

    private static final Map<String, String> BY_RULE = Map.ofEntries(
            Map.entry("dom-6",
                    "Add a human-readable narrative: a 'text' element with status 'generated' and an XHTML div summarizing the resource."),
            Map.entry("ele-1",
                    "Remove the empty element, or give it a value — FHIR forbids empty strings and empty objects."),
            Map.entry("Type_Specific_Checks_DT_Primitive_NotEmpty",
                    "Remove the empty string, or supply a real value (empty strings are not allowed in FHIR JSON)."),
            Map.entry("us-core-6",
                    "Give each name a 'family' and/or 'given', or mark it with a data-absent-reason extension."),
            Map.entry("us-core-21",
                    "Add 'requester' (the prescriber). If the source doesn't record one, give requester a "
                            + "data-absent-reason extension (valueCode 'unknown') instead of omitting it."),
            Map.entry("All_observations_should_have_a_performer",
                    "Add 'performer' referencing the lab Organization or Practitioner (best practice, not required)."),
            Map.entry("Terminology_PassThrough_TX_Message",
                    "This validator has no terminology server, so the code's existence isn't checked here — "
                            + "verify it via ontology-service (GET /api/ontology/codes/...)."),
            Map.entry("Terminology_TX_ValueSet_NotFound",
                    "The bound ValueSet lives in a package not bundled with this validator (e.g. VSAC); "
                            + "the binding can't be checked locally. Make sure the code comes from that value set."),
            Map.entry("Validation_VAL_Unknown_Profile",
                    "meta.profile names a profile this validator doesn't know. Use a US Core 6.1.0 profile URL "
                            + "or remove meta.profile to get the default for the resource type."),
            Map.entry("Reference_REF_CantResolve",
                    "The reference can't be resolved inside this request — expected for a standalone resource; "
                            + "make sure it points at a real resource in your system."),
            Map.entry("Reference_REF_NoDisplay",
                    "Add a 'display' to the reference so a reader can see what it points at (best practice)."),
            Map.entry("Unrecognized_property",
                    "Remove or correct the property name — it isn't defined for this element in FHIR R4."),
            Map.entry("Terminology_TX_NoValid_1_CC",
                    "None of the codings matched the required value set — use a code from the bound value set."),
            Map.entry("Terminology_TX_NoValid_2_CC",
                    "None of the codings matched the extensible value set — prefer a code from it where one fits."),
            Map.entry("Terminology_TX_Code_ValueSet_Ext",
                    "Use a code from the bound value set for this element."),
            Map.entry("Terminology_TX_Code_ValueSet",
                    "Use a code from the bound value set for this element.")
    );

    public String suggest(String rule, String message, String location) {
        if (message != null) {
            Matcher min = MIN_CARDINALITY.matcher(message);
            if (min.find()) {
                int required = Integer.parseInt(min.group(2));
                return "Add " + (required == 1 ? "a" : "at least " + required) + " '" + lastSegment(min.group(1))
                        + "' element (" + min.group(1) + ") — US Core requires it.";
            }
            Matcher max = MAX_CARDINALITY.matcher(message);
            if (max.find()) {
                return "Reduce '" + lastSegment(max.group(1)) + "' to at most " + max.group(2) + " occurrence(s).";
            }
        }
        if (rule == null) {
            return null;
        }
        String fix = BY_RULE.get(rule);
        if (fix != null) {
            return fix;
        }
        if (rule.startsWith("us-core-")) {
            return "US Core invariant " + rule + " failed — see the constraint text in 'message' for the exact condition.";
        }
        if (rule.startsWith("Terminology_")) {
            return "Check the code and system against the value set bound to " + (location == null ? "this element" : location) + ".";
        }
        return null;
    }

    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
