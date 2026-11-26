package com.clinicalharmony.fhircompliance.validation;

import ca.uhn.fhir.context.support.IValidationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ElementDefinition;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the "why compliant" checklist for a resource: every element its profile requires, marks
 * must-support or lists as an Additional USCDI Requirement, and whether the resource has it. The validator says THAT a resource conforms; this
 * shows WHAT it was checked for, element by element.
 *
 * <p>The element list comes from the profile's snapshot in the bundled IG packages (the same
 * validation-support chain the validator uses), cached per profile. Covered: top-level elements,
 * their direct children, extension slices (matched by extension URL, e.g. US Core race / ethnicity)
 * and CodeableConcept pattern slices (e.g. Observation {@code category:us-core} = laboratory). Other
 * slices and deeper elements are left out to keep the list readable; the validator still checks them.
 *
 * <p>Presence is evaluated on the raw JSON, like validation itself, so nothing HAPI's parser would
 * drop is counted as present by mistake.
 */
@Component
public class ProfileChecklistBuilder {

    private enum Kind { PLAIN, EXTENSION_SLICE, PATTERN_SLICE }

    private static final String USCDI_REQUIREMENT =
            "http://hl7.org/fhir/us/core/StructureDefinition/uscdi-requirement";

    private record Spec(String path, String label, int depth, boolean required, boolean mustSupport, boolean uscdi,
                        Kind kind, String extensionUrl, List<Coding> pattern) {
    }

    private final IValidationSupport validationSupport;
    private final Map<String, List<Spec>> specsByProfile = new ConcurrentHashMap<>();

    public ProfileChecklistBuilder(IValidationSupport usCoreValidationSupport) {
        this.validationSupport = usCoreValidationSupport;
    }

    /** The checklist for {@code resource} against {@code profileUrl}; empty if the profile isn't known. */
    public List<ChecklistItem> build(String profileUrl, JsonNode resource) {
        if (profileUrl == null || resource == null) {
            return List.of();
        }
        List<Spec> specs = specsByProfile.computeIfAbsent(profileUrl, this::loadSpecs);
        List<ChecklistItem> items = new ArrayList<>(specs.size());
        for (Spec spec : specs) {
            items.add(new ChecklistItem(spec.path(), spec.label(), spec.depth(), spec.required(), spec.mustSupport(),
                    spec.uscdi(), isPresent(spec, resource)));
        }
        return items;
    }

    private List<Spec> loadSpecs(String profileUrl) {
        IBaseResource fetched = validationSupport.fetchStructureDefinition(profileUrl);
        if (!(fetched instanceof StructureDefinition sd) || !sd.hasSnapshot()) {
            return List.of();
        }
        List<Spec> specs = new ArrayList<>();
        Set<String> includedTopLevel = new HashSet<>();
        for (ElementDefinition e : sd.getSnapshot().getElement()) {
            String path = e.getPath();
            String id = e.hasId() ? e.getId() : path;
            String[] segments = path.split("\\.");
            int depth = segments.length - 1;
            boolean required = e.getMin() >= 1;
            boolean mustSupport = e.getMustSupport();
            boolean uscdi = isUscdiRequirement(e);
            if (depth < 1 || depth > 2 || "0".equals(e.getMax()) || !(required || mustSupport || uscdi)) {
                continue;
            }
            if (e.hasSliceName()) {
                if (depth == 1) {
                    sliceSpec(e, path, required, mustSupport, uscdi).ifPresent(specs::add);
                }
                continue;
            }
            if (id.contains(":")) {
                continue; // an element inside a slice: covered by the slice line
            }
            if (depth == 2 && !includedTopLevel.contains(segments[0] + "." + segments[1])) {
                continue; // only children of elements already on the list
            }
            if (depth == 1) {
                includedTopLevel.add(path);
            }
            specs.add(new Spec(path, segments[segments.length - 1], depth, required, mustSupport, uscdi,
                    Kind.PLAIN, null, List.of()));
        }
        return List.copyOf(specs);
    }

    private static boolean isUscdiRequirement(ElementDefinition e) {
        return e.getExtension().stream().anyMatch(x -> USCDI_REQUIREMENT.equals(x.getUrl())
                && x.getValue() instanceof org.hl7.fhir.r4.model.BooleanType b && b.booleanValue());
    }

    private static Optional<Spec> sliceSpec(ElementDefinition e, String path, boolean required,
                                            boolean mustSupport, boolean uscdi) {
        String label = e.getSliceName();
        if (path.endsWith(".extension") && e.hasType() && e.getTypeFirstRep().hasProfile()) {
            String url = e.getTypeFirstRep().getProfile().get(0).getValue();
            return Optional.of(new Spec(path + ":" + label, label + " (extension)", 1, required, mustSupport, uscdi,
                    Kind.EXTENSION_SLICE, url, List.of()));
        }
        Type pattern = e.getPattern();
        if (pattern instanceof CodeableConcept cc && cc.hasCoding()) {
            return Optional.of(new Spec(path + ":" + label, path.substring(path.indexOf('.') + 1) + ": " + label,
                    1, required, mustSupport, uscdi, Kind.PATTERN_SLICE, null, cc.getCoding()));
        }
        return Optional.empty();
    }

    private static boolean isPresent(Spec spec, JsonNode resource) {
        List<JsonNode> nodes = select(resource, spec.path().replaceAll(":.*$", ""));
        return switch (spec.kind()) {
            case PLAIN -> !nodes.isEmpty();
            case EXTENSION_SLICE -> nodes.stream().anyMatch(n -> spec.extensionUrl().equals(n.path("url").asText()));
            case PATTERN_SLICE -> nodes.stream().anyMatch(n -> matchesPattern(n, spec.pattern()));
        };
    }

    private static boolean matchesPattern(JsonNode codeableConcept, List<Coding> pattern) {
        for (Coding wanted : pattern) {
            boolean found = false;
            for (JsonNode coding : codeableConcept.path("coding")) {
                boolean systemOk = !wanted.hasSystem() || wanted.getSystem().equals(coding.path("system").asText());
                boolean codeOk = !wanted.hasCode() || wanted.getCode().equals(coding.path("code").asText());
                if (systemOk && codeOk) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every JSON value at {@code path} ("Patient.name.family"), arrays flattened. A choice element
     * ("value[x]") matches any {@code valueXxx}; a primitive's {@code _element} counts too.
     */
    static List<JsonNode> select(JsonNode resource, String path) {
        String[] segments = path.split("\\.");
        List<JsonNode> current = List.of(resource);
        for (int i = 1; i < segments.length && !current.isEmpty(); i++) {
            String segment = segments[i];
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                if (!node.isObject()) {
                    continue;
                }
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (matches(segment, field.getKey())) {
                        addFlattened(next, field.getValue());
                    }
                }
            }
            current = next;
        }
        return current;
    }

    private static boolean matches(String segment, String key) {
        String name = key.startsWith("_") ? key.substring(1) : key;
        if (segment.endsWith("[x]")) {
            String prefix = segment.substring(0, segment.length() - 3);
            return name.length() > prefix.length() && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()));
        }
        return name.equals(segment);
    }

    private static void addFlattened(List<JsonNode> out, JsonNode value) {
        if (value.isArray()) {
            value.forEach(v -> {
                if (!v.isNull()) {
                    out.add(v);
                }
            });
        } else if (!value.isNull()) {
            out.add(value);
        }
    }
}
