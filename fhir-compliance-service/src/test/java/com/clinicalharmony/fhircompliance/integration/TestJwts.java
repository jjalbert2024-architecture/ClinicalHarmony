package com.clinicalharmony.fhircompliance.integration;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

/**
 * Self-signed stand-in for Keycloak in integration tests: an in-memory RSA key signs tokens shaped
 * like the realm's (iss, aud=clinicalharmony-fhir, space-separated {@code scope}), and the
 * {@link #jwtDecoder()} bean replaces Boot's JWKS-backed one with the SAME validator rules as
 * production (signature, exp/nbf, issuer, audience). Everything after decoding — scope→authority
 * mapping, SmartScopeAuthorizationManager, the 401/403 writers — is the real thing. The realm JSON
 * itself is exercised by scripts/smoke-test.sh against the compose Keycloak.
 */
@TestConfiguration
public class TestJwts {

    public static final String ISSUER = "http://localhost:8180/realms/clinicalharmony";
    public static final String AUDIENCE = "clinicalharmony-fhir";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();

    /** A valid token with these scopes. */
    public static String mint(String... scopes) {
        return sign(KEYS, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), scopes);
    }

    /** Signed by a key the server doesn't trust. */
    public static String mintWithUntrustedKey(String... scopes) {
        return sign(OTHER_KEYS, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), scopes);
    }

    public static String mintExpired(String... scopes) {
        return sign(KEYS, ISSUER, AUDIENCE, Instant.now().minusSeconds(600), scopes);
    }

    public static String mintForAudience(String audience, String... scopes) {
        return sign(KEYS, ISSUER, audience, Instant.now().plusSeconds(300), scopes);
    }

    public static String mintForIssuer(String issuer, String... scopes) {
        return sign(KEYS, issuer, AUDIENCE, Instant.now().plusSeconds(300), scopes);
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE))));
        return decoder;
    }

    private static String sign(KeyPair keys, String issuer, String audience, Instant expiresAt, String... scopes) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                .privateKey((RSAPrivateKey) keys.getPrivate()).keyID("test-key").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("service-account-clinicalharmony-app")
                .claim("azp", "clinicalharmony-app")
                .claim("scope", String.join(" ", scopes))
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
