package com.clinicalharmony.fhircompliance.cms0057;

import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serves the CMS-0057-F payer resources straight from gold.fhir_resources: read by id and basic
 * search. What's returned is exactly what Gold holds — the resource JSON as validated and stored
 * by GoldWriter — and each read carries that stored compliance verdict (see {@link ReadResult}).
 */
@Service
public class GoldFhirReadService {

    private final GoldFhirResourceRepository repository;
    private final Cms0057SearchParameters searchParameters;
    private final ObjectMapper objectMapper;

    public GoldFhirReadService(GoldFhirResourceRepository repository, Cms0057SearchParameters searchParameters,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.searchParameters = searchParameters;
        this.objectMapper = objectMapper;
    }

    /**
     * @param profile    the profile the stored resource was validated against (null if pending)
     * @param compliance "true"/"false", or "pending" if GoldWriter hasn't validated it yet
     */
    public record ReadResult(GoldFhirResourceRow row, String profile, String compliance) {
    }

    public ReadResult read(String resourceType, String id) {
        requireSupported(resourceType);
        GoldFhirResourceRow row = repository.findByTypeAndFhirId(resourceType, id)
                .orElseThrow(() -> FhirApiException.notFound(resourceType, id));
        JsonNode notes = parse(row.complianceNotes());
        boolean pending = notes == null || "PENDING_VALIDATION".equals(notes.path("status").asText());
        String profile = notes == null || notes.path("profile").isMissingNode() ? null : notes.path("profile").asText(null);
        return new ReadResult(row, profile, pending ? "pending" : String.valueOf(row.usCoreCompliant()));
    }

    /** Returns a FHIR searchset Bundle as JSON. */
    public String search(String resourceType, MultiValueMap<String, String> query, String selfUrl, String baseUrl) {
        requireSupported(resourceType);
        Cms0057SearchParameters.ParsedSearch parsed = searchParameters.parse(resourceType, query);
        long total = repository.count(resourceType, parsed.criteria());
        List<GoldFhirResourceRow> rows = parsed.count() == 0 ? List.of()
                : repository.search(resourceType, parsed.criteria(), parsed.count());

        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("id", UUID.randomUUID().toString());
        bundle.putObject("meta").put("lastUpdated", Instant.now().toString());
        bundle.put("type", "searchset");
        bundle.put("total", total);
        bundle.putArray("link").addObject().put("relation", "self").put("url", selfUrl);
        ArrayNode entries = bundle.putArray("entry");
        for (GoldFhirResourceRow row : rows) {
            ObjectNode entry = entries.addObject();
            entry.put("fullUrl", baseUrl + resourceType + "/" + row.fhirId());
            entry.set("resource", parse(row.resourceJson()));
            entry.putObject("search").put("mode", "match");
        }
        if (entries.isEmpty()) {
            bundle.remove("entry");
        }
        return bundle.toString();
    }

    private void requireSupported(String resourceType) {
        if (!searchParameters.supports(resourceType)) {
            throw FhirApiException.unsupportedType(resourceType);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored Gold JSON is unreadable", e);
        }
    }
}
