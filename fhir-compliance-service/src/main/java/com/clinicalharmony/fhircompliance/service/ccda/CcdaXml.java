package com.clinicalharmony.fhircompliance.service.ccda;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One parsed C-CDA document plus a namespace-aware XPath bound to it. Created per request:
 * neither {@link DocumentBuilder} nor {@link XPath} is thread-safe, and both are cheap next to
 * the validator.
 *
 * <p>XPath expressions use the prefixes {@code hl7} ({@code urn:hl7-org:v3}), {@code sdtc}
 * ({@code urn:hl7-org:sdtc}) and {@code xsi}. Every helper evaluates relative to a context node
 * and returns null / empty rather than throwing for absent content: absence is normal in C-CDA.
 *
 * <p>Parsing is locked down against XXE: DOCTYPE declarations are refused outright (C-CDA never
 * needs one), external entities and DTDs are off, and secure processing is on.
 */
public final class CcdaXml {

    public static final String HL7_NS = "urn:hl7-org:v3";
    static final String SDTC_NS = "urn:hl7-org:sdtc";
    static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    private static final Map<String, String> PREFIXES = Map.of("hl7", HL7_NS, "sdtc", SDTC_NS, "xsi", XSI_NS);

    private final Document document;
    private final XPath xpath;

    private CcdaXml(Document document) {
        this.document = document;
        this.xpath = XPathFactory.newInstance().newXPath();
        this.xpath.setNamespaceContext(new FixedNamespaceContext());
    }

    /**
     * Parses and sanity-checks a C-CDA document.
     *
     * @throws InvalidCcdaDocumentException if the bytes aren't well-formed XML, declare a DOCTYPE,
     *                                      or the root isn't an HL7 v3 ClinicalDocument
     */
    public static CcdaXml parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw new InvalidCcdaDocumentException("Request body is empty — expected a C-CDA XML document", null);
        }
        Document document;
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            builder.setErrorHandler(SilentErrorHandler.INSTANCE);
            // Bytes, not a String: lets the parser honor the encoding declared in the XML prolog.
            document = builder.parse(new ByteArrayInputStream(xml));
        } catch (SAXParseException e) {
            String reason = e.getMessage() != null && e.getMessage().contains("DOCTYPE")
                    ? "DOCTYPE declarations are not allowed"
                    : e.getMessage();
            throw new InvalidCcdaDocumentException(
                    "Not well-formed XML (line " + e.getLineNumber() + ", column " + e.getColumnNumber() + "): " + reason, e);
        } catch (SAXException | IOException e) {
            throw new InvalidCcdaDocumentException("Not well-formed XML: " + e.getMessage(), e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser doesn't support the required security features", e);
        }

        Element root = document.getDocumentElement();
        if (!HL7_NS.equals(root.getNamespaceURI()) || !"ClinicalDocument".equals(root.getLocalName())) {
            String found = (root.getNamespaceURI() == null ? "" : "{" + root.getNamespaceURI() + "}") + root.getLocalName();
            throw new InvalidCcdaDocumentException(
                    "Root element must be {" + HL7_NS + "}ClinicalDocument, found " + found, null);
        }
        return new CcdaXml(document);
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    public Element root() {
        return document.getDocumentElement();
    }

    /** First element matching {@code expr} under {@code context}, or null. */
    public Element element(Node context, String expr) {
        Object result = evaluate(context, expr, XPathConstants.NODE);
        return result instanceof Element e ? e : null;
    }

    /** All elements matching {@code expr} under {@code context}, in document order. */
    public List<Element> elements(Node context, String expr) {
        NodeList nodes = (NodeList) evaluate(context, expr, XPathConstants.NODESET);
        List<Element> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    /** String value of {@code expr} (attribute or element text), trimmed; null if absent or blank. */
    public String string(Node context, String expr) {
        String value = (String) evaluate(context, expr, XPathConstants.STRING);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** An element's attribute, trimmed; null if the element is null or the attribute absent/blank. */
    public static String attr(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name).trim();
        return value.isEmpty() ? null : value;
    }

    /** {@code xsi:type} without any namespace prefix, e.g. {@code "PQ"} for {@code xsi:type="v3:PQ"}. */
    public static String xsiType(Element element) {
        if (element == null) {
            return null;
        }
        String type = element.getAttributeNS(XSI_NS, "type");
        if (type == null || type.isBlank()) {
            return null;
        }
        int colon = type.indexOf(':');
        return colon >= 0 ? type.substring(colon + 1).trim() : type.trim();
    }

    /** Element text with whitespace runs collapsed; null if blank. */
    public static String text(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        if (text == null) {
            return null;
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private Object evaluate(Node context, String expr, javax.xml.namespace.QName returnType) {
        if (context == null) {
            return returnType == XPathConstants.NODESET ? EmptyNodeList.INSTANCE : null;
        }
        try {
            return xpath.evaluate(expr, context, returnType);
        } catch (XPathExpressionException e) {
            // Expressions are compile-time constants in this package; a failure here is a bug.
            throw new IllegalStateException("Bad XPath expression: " + expr, e);
        }
    }

    private static final class FixedNamespaceContext implements NamespaceContext {
        @Override
        public String getNamespaceURI(String prefix) {
            return PREFIXES.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
        }

        @Override
        public String getPrefix(String namespaceURI) {
            return PREFIXES.entrySet().stream().filter(e -> e.getValue().equals(namespaceURI))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceURI) {
            String prefix = getPrefix(namespaceURI);
            return prefix == null ? List.<String>of().iterator() : List.of(prefix).iterator();
        }
    }

    /** Keeps the JDK parser from printing "[Fatal Error]" lines to stderr; the exception carries the detail. */
    private enum SilentErrorHandler implements ErrorHandler {
        INSTANCE;

        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }

    private enum EmptyNodeList implements NodeList {
        INSTANCE;

        @Override
        public Node item(int index) {
            return null;
        }

        @Override
        public int getLength() {
            return 0;
        }
    }
}
