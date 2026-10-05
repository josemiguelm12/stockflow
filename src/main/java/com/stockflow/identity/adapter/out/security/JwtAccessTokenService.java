package com.stockflow.identity.adapter.out.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.stockflow.identity.application.AccessTokenIssuer;
import com.stockflow.identity.application.AccessTokenVerifier;
import com.stockflow.shared.config.JwtProperties;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT HS256 con la biblioteca Nimbus de Spring Security (sin criptografía propia). Claims: sub, jti, iat, exp.
 * Un secreto ausente, no Base64 o que no mida exactamente 32 bytes lanza IllegalStateException al construirse,
 * lo que impide el arranque. La expiración se comprueba con el Clock inyectado y sin tolerancia de reloj.
 */
public class JwtAccessTokenService implements AccessTokenIssuer, AccessTokenVerifier {

    private static final int SECRET_BYTES = 32;

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;

    public JwtAccessTokenService(JwtProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(decodeSecret(properties.secret()), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // El validador por defecto usaría el reloj del sistema y 60 s de tolerancia: se reemplaza por el nuestro.
        this.decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        this.clock = clock;
    }

    private static byte[] decodeSecret(String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stockflow.jwt.secret must be valid Base64");
        }
        if (decoded.length != SECRET_BYTES) {
            throw new IllegalStateException("stockflow.jwt.secret must decode to exactly 32 bytes");
        }
        return decoded;
    }

    @Override
    public String issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .id(sessionId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Override
    public Optional<VerifiedToken> verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null || jwt.getIssuedAt() == null || !clock.instant().isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getId())));
        } catch (RuntimeException e) {
            // Firma inválida, token mal formado, claims ausentes o UUID inválido: todos se tratan igual.
            return Optional.empty();
        }
    }
}
