package com.clinicalharmony.bronze.inspect;

import com.clinicalharmony.bronze.hl7.Hl7Inspector;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides PENDING vs QUARANTINED for every intake format — the same rules as ingestion-service's REST syntax
 * check (the two services share no code, keep them in step) — and, for claims, splits the file into one
 * self-contained payload per claim line (header + that line). Bad data never throws out of here: it's a
 * business outcome, kept in Bronze for a data steward.
 */
@Component
public class PayloadInspector {

    public static final String CCDA_NAMESPACE = "urn:hl7-org:v3";

    /**
     * @param parsed      passes the format's check → PENDING
     * @param error       why not → QUARANTINED with this error_message
     * @param details     format facts carried on the bronze.ingested event (message type, resource type…)
     * @param rowPayloads what to store: the payload itself, or one header + line payload per claim line
     */
    public record Inspection(boolean parsed, String error, Map<String, Object> details, List<String> rowPayloads) {

        static Inspection ok(Map<String, Object> details, List<String> rows) {
            return new Inspection(true, null, details, rows);
        }

        static Inspection bad(String error) {
            String e = error == null || error.isBlank() ? "Unparseable payload" : error;
            return new Inspection(false, e.length() > 500 ? e.substring(0, 500) : e, Map.of(), List.of());
        }
    }

    private final Hl7Inspector hl7;
    private final ObjectMapper mapper;

    public PayloadInspector(Hl7Inspector hl7, ObjectMapper mapper) {
        this.hl7 = hl7;
        this.mapper = mapper;
    }

    public Inspection inspect(BronzeFormat format, String raw) {
        return switch (format) {
            case HL7V2 -> hl7(raw);
            case FHIR_JSON -> fhir(raw);
            case CLAIMS_CSV -> claims(raw);
            case CCDA_XML -> ccda(raw);
        };
    }

    Inspection hl7(String raw) {
        Hl7Inspector.Result r = hl7.inspect(raw);
        if (!r.parsed()) {
            return Inspection.bad(r.error());
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hl7MessageType", r.messageType());
        d.put("controlId", r.controlId());
        return Inspection.ok(d, List.of(raw));
    }

    /** Well-formed JSON object with a non-blank resourceType. */
    Inspection fhir(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return Inspection.bad("Not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return Inspection.bad("JSON is not an object — not a FHIR resource");
        }
        JsonNode type = root.path("resourceType");
        if (!type.isTextual() || type.asText().isBlank()) {
            return Inspection.bad("JSON has no resourceType field — not a FHIR resource");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("fhirResourceType", type.asText());
        d.put("fhirResourceCount", "Bundle".equals(type.asText()) ? root.path("entry").size() : 1);
        return Inspection.ok(d, List.of(raw));
    }

    /**
     * Header + at least one row, every row as wide as the header; then one payload per line. Rows are
     * re-encoded with CSV quoting, so a value holding a comma or quote still reads back as one value.
     */
    Inspection claims(String raw) {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build();
        try (CSVParser parser = format.parse(new StringReader(raw))) {
            List<String> header = parser.getHeaderNames();
            String headerLine = CSVFormat.DEFAULT.format(header.toArray());
            List<String> rows = new ArrayList<>();
            for (CSVRecord row : parser) {
                if (row.size() != header.size()) {
                    return Inspection.bad("Row " + row.getRecordNumber() + " has " + row.size()
                            + " columns, header has " + header.size());
                }
                rows.add(headerLine + "\n" + CSVFormat.DEFAULT.format(row.toList().toArray()));
            }
            if (rows.isEmpty()) {
                return Inspection.bad("CSV contains a header but no claim rows");
            }
            return Inspection.ok(Map.of("claimLines", rows.size()), rows);
        } catch (Exception e) {
            return Inspection.bad(e.getMessage());
        }
    }

    /** Well-formed XML (no DOCTYPE / external entities), root HL7 v3 ClinicalDocument with recordTarget/patientRole. */
    Inspection ccda(String raw) {
        if (raw == null || raw.isBlank()) {
            return Inspection.bad("Empty payload");
        }
        Document doc;
        try {
            doc = secureBuilder().parse(new InputSource(new StringReader(raw)));
        } catch (Exception e) {
            return Inspection.bad("Not well-formed XML: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (!"ClinicalDocument".equals(root.getLocalName()) || !CCDA_NAMESPACE.equals(root.getNamespaceURI())) {
            return Inspection.bad("Root element is {" + root.getNamespaceURI() + "}" + root.getLocalName()
                    + ", expected {" + CCDA_NAMESPACE + "}ClinicalDocument");
        }
        if (child(child(root, "recordTarget"), "patientRole") == null) {
            return Inspection.bad("ClinicalDocument has no recordTarget/patientRole");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        Element id = child(root, "id");
        if (id != null) {
            String ext = id.getAttribute("extension");
            d.put("ccdaDocumentId", id.getAttribute("root") + (ext.isEmpty() ? "" : "^" + ext));
        }
        return Inspection.ok(d, List.of(raw));
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
        b.setErrorHandler(new DefaultHandler());   // no "[Fatal Error]" on stderr; the exception carries the reason
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
