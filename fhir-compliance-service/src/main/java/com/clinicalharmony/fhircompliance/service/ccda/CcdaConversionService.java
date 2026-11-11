package com.clinicalharmony.fhircompliance.service.ccda;

import ca.uhn.fhir.context.FhirContext;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * C-CDA document → FHIR R4 Bundle, with every resource validated against US Core 6.1.0.
 *
 * <p>Why a hand-written XPath transformer rather than a library: {@code hapi-fhir-converter} only
 * converts between FHIR versions (DSTU2 ↔ R4 ↔ R5), and the {@code CCDAConverter} buried in
 * {@code org.hl7.fhir.convertors} targets DSTU3, needs a DSTU3 worker context, and doesn't handle
 * the Problems, Medications, or Results sections at all.
 *
 * <p>Flow: parse (XXE-safe) → Patient from {@code recordTarget/patientRole} → each known body
 * section located by LOINC code (templateId fallback) and mapped → Bundle of type
 * {@code collection} with {@code urn:uuid:} fullUrls → each resource validated by
 * {@link UsCoreFhirValidator}. Nothing is persisted: Gold writes are Slice 3's GoldWriter.
 *
 * <p>Like {@code /api/fhir/validate}, a document that parses converts and returns 200 whether or
 * not the result is US Core compliant — non-compliance is the finding, reported per resource.
 */
@Service
public class CcdaConversionService {

    private static final Logger log = LoggerFactory.getLogger(CcdaConversionService.class);
    static final String DOCUMENT = "Document";

    private final CcdaPatientMapper patientMapper;
    private final List<CcdaSectionMapper> sectionMappers;
    private final UsCoreFhirValidator validator;
    private final FhirContext fhirContext;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long maxDocumentBytes;

    @Autowired
    public CcdaConversionService(CcdaPatientMapper patientMapper,
                                 CcdaProblemMapper problemMapper,
                                 CcdaMedicationMapper medicationMapper,
                                 CcdaResultMapper resultMapper,
                                 UsCoreFhirValidator validator,
                                 FhirContext fhirContext,
                                 ObjectMapper objectMapper,
                                 @Value("${ccda.max-document-bytes:5242880}") long maxDocumentBytes) {
        this(patientMapper, List.of(problemMapper, medicationMapper, resultMapper), validator, fhirContext,
                objectMapper, Clock.systemUTC(), maxDocumentBytes);
    }

    CcdaConversionService(CcdaPatientMapper patientMapper, List<CcdaSectionMapper> sectionMappers,
                          UsCoreFhirValidator validator, FhirContext fhirContext, ObjectMapper objectMapper,
                          Clock clock, long maxDocumentBytes) {
        this.patientMapper = patientMapper;
        this.sectionMappers = sectionMappers;
        this.validator = validator;
        this.fhirContext = fhirContext;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    /**
     * @throws CcdaDocumentTooLargeException if the payload exceeds {@code ccda.max-document-bytes}
     * @throws InvalidCcdaDocumentException  if it isn't a parseable C-CDA ClinicalDocument with a patient
     */
    public CcdaConversionResult convert(byte[] documentBytes) {
        long start = System.currentTimeMillis();
        if (documentBytes != null && documentBytes.length > maxDocumentBytes) {
            throw new CcdaDocumentTooLargeException(documentBytes.length, maxDocumentBytes);
        }
        CcdaXml xml = CcdaXml.parse(documentBytes);
        Element root = xml.root();

        Element documentId = xml.element(root, "hl7:id[@root]");
        String documentIdText = documentId == null ? null
                : attr(documentId, "root") + (attr(documentId, "extension") == null ? "" : "^" + attr(documentId, "extension"));
        String title = xml.string(root, "hl7:title");

        Element patientRole = xml.element(root, "hl7:recordTarget/hl7:patientRole");
        if (patientRole == null) {
            throw new InvalidCcdaDocumentException("ClinicalDocument has no recordTarget/patientRole — no patient to convert", null);
        }

        // Without a document id, fall back to a content hash: same bytes → same resource ids.
        String documentSeed = documentIdText != null ? documentIdText
                : UUID.nameUUIDFromBytes(documentBytes).toString();
        CcdaMappingContext ctx = new CcdaMappingContext(xml, documentSeed);
        if (documentIdText == null) {
            ctx.note(DOCUMENT, "ClinicalDocument has no id; resource ids derived from document content instead");
        }

        List<Resource> resources = new ArrayList<>();
        List<String> sourceSections = new ArrayList<>();
        Patient patient = patientMapper.map(patientRole, ctx);
        resources.add(patient);
        sourceSections.add(CcdaPatientMapper.SECTION);

        List<String> sectionSummaries = new ArrayList<>();
        for (CcdaSectionMapper mapper : sectionMappers) {
            Element section = findSection(xml, mapper);
            if (section == null) {
                ctx.note(mapper.label(), "Section not present in document");
                sectionSummaries.add(mapper.label() + ": not present");
                continue;
            }
            if ("NI".equals(attr(section, "nullFlavor"))) {
                sectionSummaries.add(mapper.label() + ": present, no information (nullFlavor=NI)");
                continue;
            }
            List<? extends Resource> mapped = mapper.map(section, ctx);
            for (Resource resource : mapped) {
                resources.add(resource);
                sourceSections.add(mapper.label());
            }
            sectionSummaries.add(mapper.label() + ": " + mapped.size() + " resource(s)");
        }

        Bundle bundle = bundle(resources, documentId, documentSeed);
        List<ConvertedResource> validated = new ArrayList<>(resources.size());
        int compliant = 0;
        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            FhirValidationOutcome outcome = validator.validate(resource);
            if (outcome.compliant()) {
                compliant++;
            }
            validated.add(new ConvertedResource(CcdaMappingContext.fullUrl(resource.getIdPart()),
                    resource.fhirType(), resource.getIdPart(), sourceSections.get(i), outcome));
        }

        log.info("Converted C-CDA {} ({}) in {} ms: {} resource(s), {} US Core compliant, {} conversion note(s)",
                documentIdText, title, System.currentTimeMillis() - start, resources.size(), compliant, ctx.notes().size());
        return new CcdaConversionResult(documentIdText, title, resources.size(), compliant,
                compliant == resources.size(), sectionSummaries, List.copyOf(ctx.notes()), validated, toJson(bundle));
    }

    /** Section by LOINC code first, then by any of its templateIds. */
    private static Element findSection(CcdaXml xml, CcdaSectionMapper mapper) {
        String sections = "hl7:component/hl7:structuredBody/hl7:component/hl7:section";
        Element section = xml.element(xml.root(), sections + "[hl7:code/@code='" + mapper.loincCode() + "']");
        if (section != null) {
            return section;
        }
        for (String templateId : mapper.templateIds()) {
            section = xml.element(xml.root(), sections + "[hl7:templateId/@root='" + templateId + "']");
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    private Bundle bundle(List<Resource> resources, Element documentId, String documentSeed) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.nameUUIDFromBytes(("ccda-bundle|" + documentSeed).getBytes(StandardCharsets.UTF_8)).toString());
        bundle.setType(Bundle.BundleType.COLLECTION);
        bundle.setTimestamp(Date.from(clock.instant()));
        if (documentId != null) {
            Identifier identifier = CcdaPatientMapper.identifier(documentId);
            if (identifier != null) {
                bundle.setIdentifier(identifier);
            }
        }
        for (Resource resource : resources) {
            bundle.addEntry().setFullUrl(CcdaMappingContext.fullUrl(resource.getIdPart())).setResource(resource);
        }
        return bundle;
    }

    private JsonNode toJson(Bundle bundle) {
        try {
            return objectMapper.readTree(fhirContext.newJsonParser().encodeResourceToString(bundle));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("HAPI produced unparseable JSON for the Bundle", e);
        }
    }
}
