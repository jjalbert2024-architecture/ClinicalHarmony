package com.clinicalharmony.fhircompliance.cms0057;

import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository.SqlCriterion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The search parameters each CMS-0057-F resource type supports, and their translation into SQL
 * predicates over gold.fhir_resources.resource_json.
 *
 * <p>Deliberately "basic search": a small whitelist per type (standard FHIR R4 SearchParameter
 * names), no modifiers, no chaining/_include, no sort. Anything else is a 400 OperationOutcome —
 * strict handling (FHIR's {@code Prefer: handling=strict}) rather than silently ignoring a filter
 * and returning more data than the client asked for, which for a payer API is the wrong failure.
 *
 * <p>Matching semantics follow FHIR: repeating a parameter ANDs, commas within one value OR. Most
 * parameters compile to JSONB containment ({@code resource_json @> ?::jsonb}), which the
 * idx_fhir_resources_json_gin index serves. Parameter <em>names</em> and JSON paths come only from
 * this whitelist; request values are only ever bind parameters.
 */
@Component
public class Cms0057SearchParameters {

    public static final List<String> SUPPORTED_TYPES =
            List.of("Coverage", "PractitionerRole", "MedicationKnowledge", "MeasureReport", "Device");

    static final int DEFAULT_COUNT = 50;
    static final int MAX_COUNT = 100;
    private static final Set<String> FORMATS = Set.of("json", "application/json", "application/fhir+json");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");
    private static final Pattern CODE = Pattern.compile("[^\\s|]+");

    enum Kind { ID, REFERENCE, REFERENCE_ARRAY, TOKEN, TOKEN_ARRAY, CODE, BOOLEAN, CANONICAL }

    /** @param targetType the reference target type a bare id implies ("123" → "Patient/123"); null = must be explicit */
    record Param(String name, Kind kind, String path, String targetType) {
    }

    private static final Map<String, Map<String, Param>> PARAMS = new LinkedHashMap<>();

    static {
        register("Coverage",
                new Param("patient", Kind.REFERENCE, "beneficiary", "Patient"),
                new Param("beneficiary", Kind.REFERENCE, "beneficiary", "Patient"),
                new Param("payor", Kind.REFERENCE_ARRAY, "payor", null),
                new Param("status", Kind.CODE, "status", null),
                new Param("type", Kind.TOKEN, "type", null));
        register("PractitionerRole",
                new Param("practitioner", Kind.REFERENCE, "practitioner", "Practitioner"),
                new Param("organization", Kind.REFERENCE, "organization", "Organization"),
                new Param("active", Kind.BOOLEAN, "active", null),
                new Param("role", Kind.TOKEN_ARRAY, "code", null),
                new Param("specialty", Kind.TOKEN_ARRAY, "specialty", null));
        register("MedicationKnowledge",
                new Param("code", Kind.TOKEN, "code", null),
                new Param("doseform", Kind.TOKEN, "doseForm", null),
                new Param("status", Kind.CODE, "status", null));
        register("MeasureReport",
                new Param("patient", Kind.REFERENCE, "subject", "Patient"),
                new Param("subject", Kind.REFERENCE, "subject", null),
                new Param("reporter", Kind.REFERENCE, "reporter", null),
                new Param("measure", Kind.CANONICAL, "measure", null),
                new Param("status", Kind.CODE, "status", null));
        register("Device",
                new Param("patient", Kind.REFERENCE, "patient", "Patient"),
                new Param("type", Kind.TOKEN, "type", null),
                new Param("status", Kind.CODE, "status", null));
    }

    private static void register(String type, Param... params) {
        Map<String, Param> byName = new LinkedHashMap<>();
        byName.put("_id", new Param("_id", Kind.ID, null, null));
        for (Param p : params) {
            byName.put(p.name(), p);
        }
        PARAMS.put(type, byName);
    }

    private final ObjectMapper objectMapper;

    public Cms0057SearchParameters(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean supports(String resourceType) {
        return PARAMS.containsKey(resourceType);
    }

    /** Parameter names a type accepts, for error messages and docs. */
    public Set<String> parameterNames(String resourceType) {
        return PARAMS.getOrDefault(resourceType, Map.of()).keySet();
    }

    /** A parsed search: SQL criteria plus the page size. */
    public record ParsedSearch(List<SqlCriterion> criteria, int count) {
    }

    public ParsedSearch parse(String resourceType, MultiValueMap<String, String> query) {
        Map<String, Param> allowed = PARAMS.get(resourceType);
        if (allowed == null) {
            throw FhirApiException.unsupportedType(resourceType);
        }
        List<SqlCriterion> criteria = new ArrayList<>();
        int count = DEFAULT_COUNT;
        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
            String name = entry.getKey();
            for (String value : entry.getValue()) {
                if ("_count".equals(name)) {
                    count = parseCount(value);
                } else if ("_format".equals(name)) {
                    if (value == null || !FORMATS.contains(value)) {
                        throw FhirApiException.invalidSearch("Unsupported _format '" + value + "'; only JSON is supported");
                    }
                } else {
                    criteria.add(criterion(resourceType, allowed, name, value));
                }
            }
        }
        return new ParsedSearch(criteria, count);
    }

    private SqlCriterion criterion(String resourceType, Map<String, Param> allowed, String name, String value) {
        if (name.contains(":") || name.contains(".")) {
            throw FhirApiException.invalidSearch("Search modifiers and chaining are not supported ('" + name + "')");
        }
        Param param = allowed.get(name);
        if (param == null) {
            throw FhirApiException.invalidSearch("Unknown search parameter '" + name + "' for " + resourceType
                    + ". Supported: " + String.join(", ", allowed.keySet()) + ", _count, _format");
        }
        if (value == null || value.isBlank()) {
            throw FhirApiException.invalidSearch("Search parameter '" + name + "' has no value");
        }
        List<SqlCriterion> alternatives = new ArrayList<>();
        for (String alternative : value.split(",", -1)) {
            if (alternative.isBlank()) {
                throw FhirApiException.invalidSearch("Search parameter '" + name + "' has an empty value in '" + value + "'");
            }
            alternatives.add(single(param, alternative.trim()));
        }
        if (alternatives.size() == 1) {
            return alternatives.get(0);
        }
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (SqlCriterion c : alternatives) {
            sql.append(sql.isEmpty() ? "" : " OR ").append('(').append(c.sql()).append(')');
            args.addAll(c.args());
        }
        return new SqlCriterion(sql.toString(), args);
    }

    private SqlCriterion single(Param param, String value) {
        return switch (param.kind()) {
            case ID -> {
                requireMatch(ID, value, param, "a FHIR id");
                yield new SqlCriterion("fhir_id = ?", List.of(value));
            }
            case REFERENCE -> contains(objectMapper.createObjectNode()
                    .set(param.path(), referenceNode(param, value)));
            case REFERENCE_ARRAY -> contains(objectMapper.createObjectNode()
                    .set(param.path(), objectMapper.createArrayNode().add(referenceNode(param, value))));
            case TOKEN -> contains(objectMapper.createObjectNode().set(param.path(), codeableConcept(param, value)));
            case TOKEN_ARRAY -> contains(objectMapper.createObjectNode()
                    .set(param.path(), objectMapper.createArrayNode().add(codeableConcept(param, value))));
            case CODE -> {
                requireMatch(CODE, value, param, "a code");
                yield contains(objectMapper.createObjectNode().put(param.path(), value));
            }
            case BOOLEAN -> {
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw FhirApiException.invalidSearch("Search parameter '" + param.name() + "' must be true or false, got '" + value + "'");
                }
                yield contains(objectMapper.createObjectNode().put(param.path(), Boolean.parseBoolean(value)));
            }
            case CANONICAL -> value.contains("|")
                    ? new SqlCriterion("resource_json->>'" + param.path() + "' = ?", List.of(value))
                    // An unversioned canonical matches any version of it (FHIR canonical search semantics).
                    : new SqlCriterion("resource_json->>'" + param.path() + "' = ? OR resource_json->>'"
                    + param.path() + "' LIKE ? ESCAPE '\\'", List.of(value, escapeLike(value) + "|%"));
        };
    }

    private ObjectNode referenceNode(Param param, String value) {
        String reference;
        if (value.contains("/")) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2 || parts[0].isBlank() || !ID.matcher(parts[1]).matches()) {
                throw FhirApiException.invalidSearch("Search parameter '" + param.name()
                        + "' must be a relative reference like 'Type/id', got '" + value + "'");
            }
            if (param.targetType() != null && !param.targetType().equals(parts[0])) {
                throw FhirApiException.invalidSearch("Search parameter '" + param.name() + "' references "
                        + param.targetType() + ", got '" + value + "'");
            }
            reference = value;
        } else {
            if (param.targetType() == null) {
                throw FhirApiException.invalidSearch("Search parameter '" + param.name()
                        + "' needs a typed reference like 'Organization/id', got '" + value + "'");
            }
            requireMatch(ID, value, param, "a FHIR id");
            reference = param.targetType() + "/" + value;
        }
        return objectMapper.createObjectNode().put("reference", reference);
    }

    /** token: "system|code", "code" (any system), or "system|" (any code in that system). */
    private ObjectNode codeableConcept(Param param, String value) {
        String system = null;
        String code = value;
        int bar = value.indexOf('|');
        if (bar >= 0) {
            system = value.substring(0, bar);
            code = value.substring(bar + 1);
            if (system.isEmpty() && code.isEmpty()) {
                throw FhirApiException.invalidSearch("Search parameter '" + param.name() + "' token is empty");
            }
        }
        ObjectNode coding = objectMapper.createObjectNode();
        if (system != null && !system.isEmpty()) {
            coding.put("system", system);
        }
        if (!code.isEmpty()) {
            requireMatch(CODE, code, param, "a token code");
            coding.put("code", code);
        }
        ArrayNode codings = objectMapper.createArrayNode().add(coding);
        return objectMapper.createObjectNode().set("coding", codings);
    }

    private static SqlCriterion contains(ObjectNode fragment) {
        return new SqlCriterion("resource_json @> ?::jsonb", List.of(fragment.toString()));
    }

    private static int parseCount(String value) {
        try {
            int count = Integer.parseInt(value);
            if (count < 0 || count > MAX_COUNT) {
                throw FhirApiException.invalidSearch("_count must be between 0 and " + MAX_COUNT + ", got " + count);
            }
            return count;
        } catch (NumberFormatException e) {
            throw FhirApiException.invalidSearch("_count must be an integer, got '" + value + "'");
        }
    }

    private static void requireMatch(Pattern pattern, String value, Param param, String what) {
        if (!pattern.matcher(value).matches()) {
            throw FhirApiException.invalidSearch("Search parameter '" + param.name() + "' must be " + what + ", got '" + value + "'");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
