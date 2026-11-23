package com.clinicalharmony.ingestion.intake;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One syntax check per intake format. bronze-listener-service applies the same rules when it decides
 * PENDING vs QUARANTINED (the two services share no code, so each keeps its own copy — keep them in step).
 * Nothing here throws on bad input.
 */
@Component
public class FormatChecks {

    public static final String CCDA_NAMESPACE = "urn:hl7-org:v3";

    private final ObjectMapper objectMapper;

    // PipeParser isn't documented as thread-safe; Tomcat serves requests on many threads.
    private final ThreadLocal<PipeParser> hl7Parser = ThreadLocal.withInitial(() -> {
        PipeParser p = new PipeParser();
        p.setValidationContext(ValidationContextFactory.noValidation());
        return p;
    });

    public FormatChecks(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** HAPI 2.3 / v2.5 structures, no conformance validation; MSH-9 and MSH-10 must be present. */
    public SyntaxCheck hl7(String raw) {
        if (raw == null || raw.isBlank()) {
            return SyntaxCheck.bad("Empty payload");
        }
        // REST clients often send \n or \r\n; tolerate them for parsing (Bronze keeps the bytes as sent).
        String normalized = raw.replace("\r\n", "\r").replace("\n", "\r");
        try {
            Message message = hl7Parser.get().parse(normalized);
            Terser terser = new Terser(message);
            String code = terser.get("MSH-9-1");
            String trigger = terser.get("MSH-9-2");
            String controlId = terser.get("MSH-10");
            if (code == null || code.isBlank()) {
                return SyntaxCheck.bad("MSH-9 (message type) is empty");
            }
            if (controlId == null || controlId.isBlank()) {
                return SyntaxCheck.bad("MSH-10 (message control id) is empty");
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("hl7MessageType", trigger == null || trigger.isBlank() ? code : code + "^" + trigger);
            details.put("controlId", controlId);
            return SyntaxCheck.ok(null, details);
        } catch (Exception e) {
            return SyntaxCheck.bad(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Well-formed JSON object with a non-blank {@code resourceType}. US Core conformance is not checked here. */
    public SyntaxCheck fhir(String raw) {
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return SyntaxCheck.bad("Not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return SyntaxCheck.bad("JSON is not an object — not a FHIR resource");
        }
        JsonNode type = root.path("resourceType");
        if (!type.isTextual() || type.asText().isBlank()) {
            return SyntaxCheck.bad("JSON has no resourceType field — not a FHIR resource");
        }
        String resourceType = type.asText();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resourceType", resourceType);
        details.put("resourceCount", "Bundle".equals(resourceType) ? root.path("entry").size() : 1);
        return SyntaxCheck.ok(fhirPatientId(root), details);
    }

    /** First Patient's first identifier value (else its id), for a Patient or a Bundle holding one. */
    static String fhirPatientId(JsonNode root) {
        JsonNode patient = null;
        if ("Patient".equals(root.path("resourceType").asText())) {
            patient = root;
        } else if ("Bundle".equals(root.path("resourceType").asText())) {
            for (JsonNode entry : root.path("entry")) {
                if ("Patient".equals(entry.path("resource").path("resourceType").asText())) {
                    patient = entry.path("resource");
                    break;
                }
            }
        }
        if (patient == null) {
            return null;
        }
        String identifier = patient.path("identifier").path(0).path("value").asText(null);
        if (identifier != null && !identifier.isBlank()) {
            return identifier;
        }
        String id = patient.path("id").asText(null);
        return id == null || id.isBlank() ? null : id;
    }

    /** Header + at least one row, every row as wide as the header (all-or-nothing per file). */
    public SyntaxCheck claims(String raw) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build()
                .parse(new StringReader(raw))) {
            int width = parser.getHeaderNames().size();
            int lines = 0;
            for (CSVRecord row : parser) {
                if (row.size() != width) {
                    return SyntaxCheck.bad("Row " + row.getRecordNumber() + " has " + row.size()
                            + " columns, header has " + width);
                }
                lines++;
            }
            if (lines == 0) {
                return SyntaxCheck.bad("CSV contains a header but no claim rows");
            }
            return SyntaxCheck.ok(null, Map.of("claimLines", lines));
        } catch (Exception e) {
            return SyntaxCheck.bad(e.getMessage());
        }
    }

    /**
     * Well-formed XML (no DOCTYPE, no external entities) whose root is HL7 v3 {@code ClinicalDocument} with a
     * {@code recordTarget/patientRole}. Section content is not checked here (the Silver mapping's job).
     */
    public SyntaxCheck ccda(String raw) {
        if (raw == null || raw.isBlank()) {
            return SyntaxCheck.bad("Empty payload");
        }
        Document doc;
        try {
            doc = secureBuilder().parse(new InputSource(new StringReader(raw)));
        } catch (Exception e) {
            return SyntaxCheck.bad("Not well-formed XML: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (!"ClinicalDocument".equals(root.getLocalName()) || !CCDA_NAMESPACE.equals(root.getNamespaceURI())) {
            return SyntaxCheck.bad("Root element is {" + root.getNamespaceURI() + "}" + root.getLocalName()
                    + ", expected {" + CCDA_NAMESPACE + "}ClinicalDocument");
        }
        Element patientRole = child(child(root, "recordTarget"), "patientRole");
        if (patientRole == null) {
            return SyntaxCheck.bad("ClinicalDocument has no recordTarget/patientRole");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        Element docId = child(root, "id");
        if (docId != null) {
            String ext = docId.getAttribute("extension");
            details.put("documentId", docId.getAttribute("root") + (ext.isEmpty() ? "" : "^" + ext));
        }
        Element title = child(root, "title");
        if (title != null && !title.getTextContent().isBlank()) {
            details.put("title", title.getTextContent().strip());
        }
        Element patientId = child(patientRole, "id");
        String mrn = patientId == null ? null : patientId.getAttribute("extension");
        return SyntaxCheck.ok(mrn == null || mrn.isBlank() ? null : mrn, details);
    }

    static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());   // no "[Fatal Error]" on stderr; the exception carries the reason
        return b;
    }

    private static Element child(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName()) && CCDA_NAMESPACE.equals(e.getNamespaceURI())) {
                return e;
            }
        }
        return null;
    }
}
