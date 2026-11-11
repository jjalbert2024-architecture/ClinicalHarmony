package com.clinicalharmony.fhircompliance.service.ccda;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CcdaXmlTest {

    @Test
    void parsesClinicalDocumentAndEvaluatesNamespacedXPath() {
        CcdaXml xml = CcdaXml.parse(CcdaFixtures.load("ccd_compliant.xml"));

        assertThat(xml.string(xml.root(), "hl7:id/@extension")).isEqualTo("CCD-CH-0001");
        assertThat(xml.elements(xml.root(), "hl7:component/hl7:structuredBody/hl7:component/hl7:section")).hasSize(3);
        assertThat(xml.element(xml.root(), "hl7:nope")).isNull();
        assertThat(xml.string(xml.root(), "hl7:nope/@value")).isNull();
    }

    @Test
    void rejectsMalformedXmlWithLineAndColumn() {
        byte[] bad = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><title>unclosed</ClinicalDocument>"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CcdaXml.parse(bad))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessageStartingWith("Not well-formed XML (line 1, column");
    }

    @Test
    void rejectsDoctypeSoXxeCannotReadLocalFiles() {
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE ClinicalDocument [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <ClinicalDocument xmlns="urn:hl7-org:v3"><title>&xxe;</title></ClinicalDocument>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CcdaXml.parse(xxe))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessageContaining("DOCTYPE declarations are not allowed");
    }

    @Test
    void rejectsWellFormedXmlThatIsNotAClinicalDocument() {
        assertThatThrownBy(() -> CcdaXml.parse("<Patient xmlns=\"http://hl7.org/fhir\"/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessage("Root element must be {urn:hl7-org:v3}ClinicalDocument, found {http://hl7.org/fhir}Patient");
    }

    @Test
    void rejectsClinicalDocumentOutsideTheHl7Namespace() {
        assertThatThrownBy(() -> CcdaXml.parse("<ClinicalDocument/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessageContaining("found ClinicalDocument");
    }

    @Test
    void rejectsEmptyBody() {
        assertThatThrownBy(() -> CcdaXml.parse(new byte[0]))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void honorsTheEncodingDeclaredInTheProlog() {
        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><ClinicalDocument xmlns=\"urn:hl7-org:v3\"><title>Muñoz</title></ClinicalDocument>"
                .getBytes(StandardCharsets.ISO_8859_1);

        CcdaXml xml = CcdaXml.parse(latin1);

        assertThat(xml.string(xml.root(), "hl7:title")).isEqualTo("Muñoz");
    }

    @Test
    void xsiTypeIgnoresNamespacePrefix() {
        CcdaXml xml = CcdaXml.parse("""
                <ClinicalDocument xmlns="urn:hl7-org:v3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:v3="urn:hl7-org:v3">
                  <value xsi:type="v3:PQ" value="1"/><value xsi:type="CD"/><value/>
                </ClinicalDocument>""".getBytes(StandardCharsets.UTF_8));

        var values = xml.elements(xml.root(), "hl7:value");
        assertThat(CcdaXml.xsiType(values.get(0))).isEqualTo("PQ");
        assertThat(CcdaXml.xsiType(values.get(1))).isEqualTo("CD");
        assertThat(CcdaXml.xsiType(values.get(2))).isNull();
    }

    @Test
    void textCollapsesWhitespace() {
        CcdaXml xml = CcdaXml.parse("<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><title>  a\n   b  </title></ClinicalDocument>"
                .getBytes(StandardCharsets.UTF_8));
        Element title = xml.element(xml.root(), "hl7:title");

        assertThat(CcdaXml.text(title)).isEqualTo("a b");
    }
}
