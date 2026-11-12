package com.clinicalharmony.fhircompliance.integration;

import com.clinicalharmony.fhircompliance.security.SmartScopes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMART on FHIR backend-services auth over real HTTP with real signed JWTs (TestJwts): every token
 * check the production decoder makes (signature, expiry, issuer, audience), the SMART scope rules on
 * /fhir/r4, the pipeline.write scope, the public endpoints, and the 401/403 response shapes with
 * correlation ids. Same context and database as GoldWriterIntegrationTest (AbstractIntegrationTest).
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String COVERAGE = "/fhir/r4/Coverage/cms-coverage-seed-0001";

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertOperationOutcome(ResponseEntity<String> response, HttpStatus status, String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/fhir+json");
        JsonNode body = json(response);
        assertThat(body.get("resourceType").asText()).isEqualTo("OperationOutcome");
        assertThat(body.get("issue").get(0).get("code").asText()).isEqualTo(code);
    }

    @Test
    void noTokenOnFhirIs401OperationOutcomeWithChallengeAndCorrelationId() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "it-sec-401");
        ResponseEntity<String> response = anonymous().exchange(COVERAGE, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertOperationOutcome(response, HttpStatus.UNAUTHORIZED, "login");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer realm=\"clinicalharmony\"");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-sec-401");
    }

    @Test
    void everyKindOfBadTokenIs401InvalidToken() throws Exception {
        String scope = "system/Coverage.rs";
        for (String token : new String[]{
                "not-a-jwt",
                TestJwts.mintWithUntrustedKey(scope),
                TestJwts.mintExpired(scope),
                TestJwts.mintForAudience("some-other-api", scope),
                TestJwts.mintForIssuer("http://evil.example/realms/clinicalharmony", scope)}) {
            ResponseEntity<String> response = withToken(token).getForEntity(COVERAGE, String.class);
            assertOperationOutcome(response, HttpStatus.UNAUTHORIZED, "login");
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
        }
    }

    @Test
    void readAndSearchEachNeedTheirOwnScope() throws Exception {
        assertThat(withScopes("system/Coverage.r").getForEntity(COVERAGE, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> search = withScopes("system/Coverage.r").getForEntity("/fhir/r4/Coverage?status=active", String.class);
        assertOperationOutcome(search, HttpStatus.FORBIDDEN, "forbidden");
        assertThat(search.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"clinicalharmony\", error=\"insufficient_scope\", scope=\"system/Coverage.s\"");

        assertThat(withScopes("system/Coverage.rs").getForEntity("/fhir/r4/Coverage?status=active", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void scopeForOneTypeDoesNotGrantAnother() throws Exception {
        TestRestTemplate coverageOnly = withScopes("system/Coverage.rs");
        assertOperationOutcome(coverageOnly.getForEntity("/fhir/r4/Device/cms-device-seed-0002", String.class),
                HttpStatus.FORBIDDEN, "forbidden");
        assertThat(withScopes("system/*.rs").getForEntity("/fhir/r4/Device/cms-device-seed-0002", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withScopes("system/Device.read").getForEntity("/fhir/r4/Device?status=active", String.class)
                .getStatusCode()).as("SMART v1 .read").isEqualTo(HttpStatus.OK);
    }

    @Test
    void unsupportedTypeWithAValidTokenStill404s() throws Exception {
        assertOperationOutcome(withScopes().getForEntity("/fhir/r4/Claim/1", String.class), HttpStatus.NOT_FOUND, "not-supported");
    }

    @Test
    void pipelineEndpointsNeedPipelineWriteScopeAndAnswerInPlainJson() throws Exception {
        ResponseEntity<String> anonymousBuild = anonymous().postForEntity("/api/fhir/build", null, String.class);
        assertThat(anonymousBuild.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(anonymousBuild).get("error").asText()).isEqualTo("Unauthorized");

        ResponseEntity<String> readerRevalidate = withScopes("system/*.rs").postForEntity("/api/fhir/gold/revalidate", null, String.class);
        assertThat(readerRevalidate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(readerRevalidate).get("message").asText()).contains(SmartScopes.PIPELINE_WRITE);

        ResponseEntity<String> readerToggle = withScopes("system/*.rs").exchange(
                "/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle", HttpMethod.PUT, null, String.class);
        assertThat(readerToggle.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        TestRestTemplate anon = anonymous();
        assertThat(anon.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anon.getForEntity("/api/fhir-compliance/rules", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> validate = anon.postForEntity("/api/fhir/validate",
                new HttpEntity<>("{\"resourceType\":\"Patient\",\"id\":\"p\"}", json), String.class);
        assertThat(validate.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> ccda = anon.postForEntity("/api/fhir/convert/ccda",
                new HttpEntity<>("<ClinicalDocument/>", xml()), String.class);
        assertThat(ccda.getStatusCode()).as("C-CDA conversion needs a token").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void smartConfigurationIsPublicAndPointsAtTheRealm() throws Exception {
        for (String path : new String[]{"/.well-known/smart-configuration", "/fhir/r4/.well-known/smart-configuration"}) {
            ResponseEntity<String> response = anonymous().getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode body = json(response);
            assertThat(body.get("issuer").asText()).isEqualTo(TestJwts.ISSUER);
            assertThat(body.get("token_endpoint").asText()).isEqualTo(TestJwts.ISSUER + "/protocol/openid-connect/token");
            assertThat(body.get("jwks_uri").asText()).isEqualTo(TestJwts.ISSUER + "/protocol/openid-connect/certs");
            assertThat(body.get("grant_types_supported").toString()).isEqualTo("[\"client_credentials\"]");
            assertThat(body.get("capabilities").toString()).contains("permission-v2", "client-confidential-symmetric");
            assertThat(body.get("scopes_supported").toString()).contains("system/Coverage.rs", "system/*.rs");
        }
    }

    private static HttpHeaders xml() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return headers;
    }
}
