package com.clinicalharmony.patientindex.integration;

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
 * Self-signed stand-in for Keycloak (same approach as fhir-compliance-service's TestJwts): tokens
 * shaped like the realm's, and a JwtDecoder with production's validators (signature, expiry,
 * issuer, audience). Everything after decoding is the real security chain.
 */
@TestConfiguration
public class TestJwts {

    public static final String ISSUER = "http://localhost:8180/realms/clinicalharmony";
    public static final String AUDIENCE = "clinicalharmony-fhir";
    private static final KeyPair KEYS = generate();

    public static String mint(String... scopes) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test-key").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("service-account-clinicalharmony-app")
                .claim("azp", "clinicalharmony-app")
                .claim("scope", String.join(" ", scopes))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(),
                claims)).getTokenValue();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE))));
        return decoder;
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
