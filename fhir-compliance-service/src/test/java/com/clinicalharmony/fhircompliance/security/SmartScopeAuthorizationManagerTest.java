package com.clinicalharmony.fhircompliance.security;

import com.clinicalharmony.fhircompliance.security.SmartScopeAuthorizationManager.Target;
import com.clinicalharmony.fhircompliance.security.SmartScopes.Interaction;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class SmartScopeAuthorizationManagerTest {

    private final SmartScopeAuthorizationManager manager = new SmartScopeAuthorizationManager();

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private static Authentication token(String... scopes) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("svc")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        String[] authorities = java.util.Arrays.stream(scopes).map(s -> "SCOPE_" + s).toArray(String[]::new);
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authorities));
    }

    private boolean granted(Authentication auth, String uri) {
        return manager.check(() -> auth, new RequestAuthorizationContext(request(uri))).isGranted();
    }

    @Test
    void parsesSearchAndReadTargets() {
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4/Coverage")))
                .contains(new Target("Coverage", Interaction.SEARCH));
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4/Coverage/")))
                .contains(new Target("Coverage", Interaction.SEARCH));
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4/Device/cms-device-1")))
                .contains(new Target("Device", Interaction.READ));
    }

    @Test
    void noScopeTargetForUnsupportedTypesOrOtherPaths() {
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4/Claim/1"))).isEqualTo(Optional.empty());
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4/Coverage/1/_history/2"))).isEmpty();
        assertThat(SmartScopeAuthorizationManager.target(request("/fhir/r4"))).isEmpty();
        assertThat(SmartScopeAuthorizationManager.target(request("/api/fhir/validate"))).isEmpty();
    }

    @Test
    void honorsContextPath() {
        MockHttpServletRequest request = request("/svc/fhir/r4/Coverage/x");
        request.setContextPath("/svc");
        assertThat(SmartScopeAuthorizationManager.target(request)).contains(new Target("Coverage", Interaction.READ));
    }

    @Test
    void grantsOnlyWithTheMatchingScope() {
        assertThat(granted(token("system/Coverage.r"), "/fhir/r4/Coverage/1")).isTrue();
        assertThat(granted(token("system/Coverage.r"), "/fhir/r4/Coverage")).isFalse();
        assertThat(granted(token("system/Device.rs"), "/fhir/r4/Coverage/1")).isFalse();
        assertThat(granted(token("system/*.rs"), "/fhir/r4/MeasureReport")).isTrue();
    }

    @Test
    void unsupportedTypeNeedsOnlyAValidTokenSoTheControllerCan404() {
        assertThat(granted(token(), "/fhir/r4/Claim/1")).isTrue();
    }

    @Test
    void anonymousIsAlwaysDenied() {
        Authentication anonymous = new AnonymousAuthenticationToken("k", "anon",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(granted(anonymous, "/fhir/r4/Claim/1")).isFalse();
        assertThat(granted(null, "/fhir/r4/Coverage")).isFalse();
    }
}
