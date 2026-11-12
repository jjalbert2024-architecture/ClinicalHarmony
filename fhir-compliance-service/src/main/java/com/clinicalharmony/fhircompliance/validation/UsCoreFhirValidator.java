package com.clinicalharmony.fhircompliance.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates FHIR R4 resources against US Core 6.1.0 (and, for MedicationKnowledge/MeasureReport, the
 * bundled Da Vinci profiles) using the official HL7 validator.
 *
 * <p>Validation runs on the raw JSON text, not on a HAPI object parsed from it: HAPI's parser
 * silently drops things like empty strings and unknown elements, which are exactly the defects a
 * debugger endpoint needs to report.
 *
 * <p>If the resource declares a {@code meta.profile}, it is validated against that. Otherwise the
 * default US Core profile for its resourceType is added via {@link ValidationOptions} (without
 * mutating the resource) — so a bare {@code {"resourceType":"Patient"}} is checked against
 * us-core-patient rather than just base FHIR, which would silently pass most US Core gaps.
 */
@Service
public class UsCoreFhirValidator {

    private static final Logger log = LoggerFactory.getLogger(UsCoreFhirValidator.class);

    /** "Patient.name: minimum required = 1, but only found 0 (...)" → element path "Patient.name". */
    private static final Pattern CARDINALITY_PATH = Pattern.compile("^([A-Z][A-Za-z]*(?:\\.[A-Za-z\\[\\]0-9]+)+):\\s");

    private static final Comparator<FhirViolation> BY_SEVERITY = Comparator
            .comparingInt((FhirViolation v) -> severityRank(v.severity()))
            .thenComparing(v -> v.location() == null ? "" : v.location());

    private final FhirContext fhirContext;
    private final FhirValidator validator;
    private final SuggestedFixAdvisor fixAdvisor;
    private final ObjectMapper objectMapper;

    public UsCoreFhirValidator(FhirContext fhirContext, FhirValidator validator, SuggestedFixAdvisor fixAdvisor,
                               ObjectMapper objectMapper) {
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.fixAdvisor = fixAdvisor;
        this.objectMapper = objectMapper;
    }

    /** Validates a HAPI-built resource (the Silver → FHIR builders' path). */
    public FhirValidationOutcome validate(IBaseResource resource) {
        return validateJson(fhirContext.newJsonParser().encodeResourceToString(resource));
    }

    /**
     * Validates a raw JSON payload.
     *
     * @throws InvalidFhirResourceException if the payload isn't a JSON object with a known R4 resourceType
     */
    public FhirValidationOutcome validateJson(String json) {
        JsonNode root = readEnvelope(json);
        String resourceType = root.get("resourceType").asText();
        String resourceId = root.path("id").isTextual() ? root.get("id").asText() : null;

        ValidationOptions options = new ValidationOptions();
        String profile = declaredProfile(root);
        if (profile == null) {
            profile = defaultProfileFor(resourceType, root);
            if (profile != null) {
                options.addProfile(profile);
            }
        }

        long start = System.currentTimeMillis();
        ValidationResult result = validator.validateWithResult(json, options);
        List<FhirViolation> violations = result.getMessages().stream()
                .map(this::toViolation)
                .sorted(BY_SEVERITY)
                .toList();
        int errors = (int) violations.stream().filter(v -> "ERROR".equals(v.severity())).count();
        int warnings = (int) violations.stream().filter(v -> "WARNING".equals(v.severity())).count();

        log.info("Validated {}/{} against {} in {} ms: {} error(s), {} warning(s)",
                resourceType, resourceId, profile == null ? "base FHIR R4" : profile,
                System.currentTimeMillis() - start, errors, warnings);
        return new FhirValidationOutcome(resourceType, resourceId, profile, errors == 0, errors, warnings, violations);
    }

    private JsonNode readEnvelope(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidFhirResourceException("Body is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidFhirResourceException("Body must be a single FHIR resource as a JSON object", null);
        }
        JsonNode type = root.get("resourceType");
        if (type == null || !type.isTextual() || type.asText().isBlank()) {
            throw new InvalidFhirResourceException("Missing 'resourceType' — not a FHIR resource", null);
        }
        if (!fhirContext.getResourceTypes().contains(type.asText())) {
            throw new InvalidFhirResourceException("Unknown FHIR R4 resourceType '" + type.asText() + "'", null);
        }
        return root;
    }

    private static String declaredProfile(JsonNode root) {
        JsonNode profiles = root.path("meta").path("profile");
        if (profiles.isArray() && !profiles.isEmpty() && profiles.get(0).isTextual()) {
            return profiles.get(0).asText();
        }
        return null;
    }

    private static String defaultProfileFor(String resourceType, JsonNode root) {
        // Observations aren't all labs — use the lab profile only when categorized as one.
        if ("Observation".equals(resourceType)) {
            for (JsonNode category : root.path("category")) {
                for (JsonNode coding : category.path("coding")) {
                    if ("laboratory".equals(coding.path("code").asText())) {
                        return UsCoreProfiles.OBSERVATION_LAB;
                    }
                }
            }
            return UsCoreProfiles.OBSERVATION_CLINICAL_RESULT;
        }
        return UsCoreProfiles.defaultFor(resourceType).orElse(null);
    }

    private FhirViolation toViolation(SingleValidationMessage m) {
        String severity = switch (m.getSeverity()) {
            case FATAL, ERROR -> "ERROR";
            case WARNING -> "WARNING";
            case INFORMATION -> "INFORMATION";
        };
        String rule = shortRule(m.getMessageId());
        if ("ERROR".equals(severity) && isUnresolvableSliceProfile(rule, m.getMessage())) {
            severity = "WARNING";
        }
        String location = refineLocation(m.getLocationString(), m.getMessage());
        return new FhirViolation(severity, location, m.getMessage(), rule,
                fixAdvisor.suggest(rule, m.getMessage(), location));
    }

    /**
     * A slice the validator can't evaluate because the slice's profile lives in an IG package we
     * deliberately don't bundle (e.g. DEQM's MeasureReport extension slices pointing at HL7
     * Extensions 5.2.0, CRMI and R5 cross-version extensions). The core validator raises that as an
     * ERROR on every extension present, so no DEQM report could ever pass offline. Treated the same
     * as any other unresolvable canonical ({@code setErrorForUnknownProfiles(false)} in
     * FhirValidationConfig): reported, but as a WARNING. Narrow on purpose — only the
     * "profile could not be resolved" variants; a slice that fails for any other reason stays an error.
     */
    static boolean isUnresolvableSliceProfile(String rule, String message) {
        return "SLICING_CANNOT_BE_EVALUATED".equals(rule) && message != null
                && (message.contains("could not be resolved") || message.contains("Unable to resolve profile"));
    }

    /**
     * For cardinality errors the validator reports the *parent* as the location (the missing
     * element doesn't exist, so it can't be pointed at). The message names the element though —
     * lift it into the location so it's a usable FHIRPath to the gap.
     */
    static String refineLocation(String location, String message) {
        if (message != null) {
            Matcher matcher = CARDINALITY_PATH.matcher(message);
            if (matcher.find() && location != null && matcher.group(1).startsWith(stripIndexes(location))) {
                return location + matcher.group(1).substring(stripIndexes(location).length());
            }
        }
        return location;
    }

    /** "http://hl7.org/fhir/StructureDefinition/DomainResource#dom-6" → "dom-6"; message ids pass through. */
    static String shortRule(String messageId) {
        if (messageId == null) {
            return null;
        }
        int hash = messageId.lastIndexOf('#');
        return hash >= 0 && hash < messageId.length() - 1 ? messageId.substring(hash + 1) : messageId;
    }

    private static String stripIndexes(String path) {
        return path.replaceAll("\\[\\d+]", "");
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "ERROR" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        };
    }
}
