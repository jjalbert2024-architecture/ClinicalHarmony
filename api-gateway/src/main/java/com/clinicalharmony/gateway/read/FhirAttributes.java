package com.clinicalharmony.gateway.read;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The FHIR Viewer's per-type attribute filters: for each resource type, the main elements of that resource
 * (Patient gender / birthDate / address, Observation status / category / interpretation / effective…) that our
 * Gold resources actually carry. A fixed catalog: the SQL of each attribute is written here, the user only ever
 * picks a key and supplies a bound value — so a filter can't reach any other part of the JSON.
 * <p>
 * Query parameters are {@code attr.<key>} (CHOICE exact, TEXT contains, FLAG yes|no) and
 * {@code attr.<key>From} / {@code attr.<key>To} (DATE, inclusive, {@code YYYY}, {@code YYYY-MM} or
 * {@code YYYY-MM-DD}, compared on that prefix of the ISO date).
 */
public final class FhirAttributes {

    public enum Kind { CHOICE, TEXT, DATE, FLAG }

    /** One filterable element. {@code sql} is a text (or, for FLAG, boolean) expression over {@code f.resource_json}. */
    public record Attribute(String key, String label, String path, Kind kind, String sql) {
    }

    private static final String R = "f.resource_json";
    private static final Pattern DATE_PREFIX = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?");
    private static final int MAX_VALUE = 100;

    private static Attribute choice(String key, String label, String path, String sql) {
        return new Attribute(key, label, path, Kind.CHOICE, sql);
    }

    private static Attribute text(String key, String label, String path, String sql) {
        return new Attribute(key, label, path, Kind.TEXT, sql);
    }

    private static Attribute date(String key, String label, String path, String sql) {
        return new Attribute(key, label, path, Kind.DATE, sql);
    }

    private static Attribute flag(String key, String label, String path, String sql) {
        return new Attribute(key, label, path, Kind.FLAG, sql);
    }

    private static Attribute status(String path) {
        return choice("status", "Status", path, R + " ->> 'status'");
    }

    private static Attribute active(String type) {
        return choice("active", "Active", type + ".active", R + " ->> 'active'");
    }

    private static final Map<String, List<Attribute>> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("Patient", List.of(
                choice("gender", "Gender", "Patient.gender", R + " ->> 'gender'"),
                date("birthDate", "Birth date", "Patient.birthDate", R + " ->> 'birthDate'"),
                choice("state", "State", "Patient.address.state", R + " #>> '{address,0,state}'"),
                text("city", "City", "Patient.address.city", R + " #>> '{address,0,city}'"),
                text("postalCode", "Postal code", "Patient.address.postalCode", R + " #>> '{address,0,postalCode}'"),
                flag("phone", "Has a phone", "Patient.telecom (system phone)",
                        "EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(" + R + " -> 'telecom') = 'array'"
                                + " THEN " + R + " -> 'telecom' ELSE '[]'::jsonb END) t WHERE t ->> 'system' = 'phone')")));
        CATALOG.put("Observation", List.of(
                status("Observation.status"),
                choice("category", "Category", "Observation.category", R + " #>> '{category,0,coding,0,code}'"),
                choice("interpretation", "Interpretation", "Observation.interpretation",
                        "coalesce(" + R + " #>> '{interpretation,0,coding,0,code}', " + R + " #>> '{interpretation,0,text}')"),
                flag("abnormal", "Abnormal (H/L/A…)", "Observation.interpretation not N",
                        R + " #>> '{interpretation,0,coding,0,code}' IN ('H','L','HH','LL','A','AA')"),
                flag("referenceRange", "Has a reference range", "Observation.referenceRange",
                        "jsonb_typeof(" + R + " -> 'referenceRange') = 'array'"),
                date("effective", "Effective date", "Observation.effective[x]",
                        "coalesce(" + R + " ->> 'effectiveDateTime', " + R + " #>> '{effectivePeriod,start}', " + R + " ->> 'effectiveInstant')")));
        CATALOG.put("Condition", List.of(
                choice("clinicalStatus", "Clinical status", "Condition.clinicalStatus", R + " #>> '{clinicalStatus,coding,0,code}'"),
                choice("verificationStatus", "Verification status", "Condition.verificationStatus",
                        R + " #>> '{verificationStatus,coding,0,code}'"),
                choice("category", "Category", "Condition.category", R + " #>> '{category,0,coding,0,code}'"),
                choice("codeSystem", "Code system", "Condition.code.coding.system", R + " #>> '{code,coding,0,system}'"),
                date("onset", "Onset date", "Condition.onset[x]",
                        "coalesce(" + R + " ->> 'onsetDateTime', " + R + " #>> '{onsetPeriod,start}')")));
        CATALOG.put("MedicationRequest", List.of(
                status("MedicationRequest.status"),
                choice("intent", "Intent", "MedicationRequest.intent", R + " ->> 'intent'"),
                date("authoredOn", "Authored on", "MedicationRequest.authoredOn", R + " ->> 'authoredOn'")));
        CATALOG.put("AllergyIntolerance", List.of(
                choice("clinicalStatus", "Clinical status", "AllergyIntolerance.clinicalStatus",
                        R + " #>> '{clinicalStatus,coding,0,code}'"),
                choice("type", "Type", "AllergyIntolerance.type", R + " ->> 'type'"),
                choice("category", "Category", "AllergyIntolerance.category", R + " #>> '{category,0}'"),
                choice("criticality", "Criticality", "AllergyIntolerance.criticality", R + " ->> 'criticality'")));
        for (String type : List.of("Coverage", "Encounter", "DiagnosticReport", "MedicationKnowledge", "MeasureReport", "Device")) {
            CATALOG.put(type, List.of(status(type + ".status")));
        }
        for (String type : List.of("Organization", "Practitioner", "PractitionerRole")) {
            CATALOG.put(type, List.of(active(type)));
        }
    }

    private FhirAttributes() {
    }

    /** The attributes of a type, empty for a type without any. */
    public static List<Attribute> of(String type) {
        return type == null ? List.of() : CATALOG.getOrDefault(type, List.of());
    }

    /** One parsed {@code attr.*} parameter, ready to bind. */
    record Condition(String predicate, String param, Object value) {
    }

    /**
     * Turns the request's {@code attr.*} parameters into predicates. Unknown keys, a key of another type, an empty
     * or over-long value, a bad date or a FLAG other than yes/no → {@link InvalidFilterException} (400) (never silently ignored: a typo would
     * otherwise show unfiltered results as if they matched).
     */
    static List<Condition> parse(String type, Map<String, String> attributes) {
        List<Condition> conditions = new ArrayList<>();
        if (attributes == null || attributes.isEmpty()) {
            return conditions;
        }
        if (type == null || type.isBlank()) {
            throw new InvalidFilterException("Attribute filters (attr.*) need a resource type");
        }
        Map<String, Attribute> byKey = new LinkedHashMap<>();
        of(type).forEach(a -> byKey.put(a.key(), a));
        int n = 0;
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            String name = e.getKey();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) {
                continue;   // an emptied form field = no filter
            }
            if (value.length() > MAX_VALUE) {
                throw new InvalidFilterException("attr." + name + " is longer than " + MAX_VALUE + " characters");
            }
            String key = name;
            String bound = null;
            if (name.endsWith("From") || name.endsWith("To")) {
                String base = name.substring(0, name.length() - (name.endsWith("From") ? 4 : 2));
                if (byKey.containsKey(base) && byKey.get(base).kind() == Kind.DATE) {
                    key = base;
                    bound = name.endsWith("From") ? ">=" : "<=";
                }
            }
            Attribute attribute = byKey.get(key);
            if (attribute == null) {
                throw new InvalidFilterException("Unknown attribute filter attr." + name + " for " + type
                        + (byKey.isEmpty() ? " (it has none)" : " (allowed: " + String.join(", ", keysOf(byKey)) + ")"));
            }
            String param = "attr" + n++;
            switch (attribute.kind()) {
                case CHOICE -> conditions.add(new Condition(attribute.sql() + " = :" + param, param, value));
                case TEXT -> conditions.add(new Condition(attribute.sql() + " ILIKE :" + param, param, Sql.contains(value)));
                case FLAG -> {
                    boolean yes = switch (value.toLowerCase()) {
                        case "yes", "true" -> true;
                        case "no", "false" -> false;
                        default -> throw new InvalidFilterException("attr." + name + " must be yes or no");
                    };
                    conditions.add(new Condition((yes ? "" : "NOT ") + "coalesce(" + attribute.sql() + ", false)", null, null));
                }
                case DATE -> {
                    if (bound == null) {
                        throw new InvalidFilterException("Date attribute " + key + " takes attr." + key + "From / attr." + key + "To");
                    }
                    if (!DATE_PREFIX.matcher(value).matches()) {
                        throw new InvalidFilterException("attr." + name + " must be YYYY, YYYY-MM or YYYY-MM-DD");
                    }
                    conditions.add(new Condition("left(" + attribute.sql() + ", " + value.length() + ") " + bound + " :" + param,
                            param, value));
                }
            }
        }
        return conditions;
    }

    private static List<String> keysOf(Map<String, Attribute> byKey) {
        List<String> keys = new ArrayList<>();
        byKey.values().forEach(a -> {
            if (a.kind() == Kind.DATE) {
                keys.add(a.key() + "From");
                keys.add(a.key() + "To");
            } else {
                keys.add(a.key());
            }
        });
        return keys;
    }
}
