package com.stockflow.identity.adapter.out.security;

import com.stockflow.support.MutableClock;
import com.stockflow.shared.config.JwtProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.identity.application.AccessTokenVerifier.VerifiedToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtAccessTokenServiceTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_SECRET = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private final MutableClock clock = new MutableClock(START);
    private final JwtAccessTokenService service = new JwtAccessTokenService(new JwtProperties(SECRET), clock);
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();

    private String token() {
        return service.issue(userId, sessionId, START, START.plus(TTL));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> part(String token, int index) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(token.split("\\.")[index]);
        return json.readValue(bytes, Map.class);
    }

    @Test
    void issuedTokenVerifiesAndCarriesOnlyTheMinimalClaims() throws Exception {
        String token = token();

        assertThat(service.verify(token)).contains(new VerifiedToken(userId, sessionId));

        Map<String, Object> claims = part(token, 1);
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "jti", "iat", "exp");
        assertThat(claims.get("sub")).isEqualTo(userId.toString());
        assertThat(claims.get("jti")).isEqualTo(sessionId.toString());
        long iat = ((Number) claims.get("iat")).longValue();
        long exp = ((Number) claims.get("exp")).longValue();
        assertThat(iat).isEqualTo(START.getEpochSecond());
        assertThat(exp - iat).isEqualTo(900);
        assertThat(part(token, 0).get("alg")).isEqualTo("HS256");
    }

    @Test
    void expiresExactlyAtTheExpClaimWithoutClockSkew() {
        String token = token();

        clock.advance(TTL.minusSeconds(1));
        assertThat(service.verify(token)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void rejectsTamperedSignaturePayloadAndForeignSecrets() throws Exception {
        String token = token();
        String[] parts = token.split("\\.");

        // Primer carácter de la firma (6 bits útiles); el último solo aporta 4 y podía no cambiar los bytes.
        String flipped = (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThat(service.verify(parts[0] + "." + parts[1] + "." + flipped)).isEmpty();

        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"jti\":\"" + sessionId + "\",\"iat\":1,\"exp\":4102444800}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(service.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();

        var other = new JwtAccessTokenService(new JwtProperties(OTHER_SECRET), clock);
        assertThat(other.verify(token)).isEmpty();
        assertThat(service.verify(other.issue(userId, sessionId, START, START.plus(TTL)))).isEmpty();
    }

    @Test
    void rejectsUnsignedAndMalformedTokens() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\",\"jti\":\"" + sessionId + "\",\"iat\":1,\"exp\":4102444800}")
                        .getBytes(StandardCharsets.UTF_8));

        for (String bad : new String[]{header + "." + payload + ".", "", "abc", "a.b.c", "....", "Bearer x", token() + "x.y"}) {
            assertThat(service.verify(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void rejectsValidlySignedTokensWithMissingOrInvalidClaims() {
        // Firmados con el secreto correcto pero con sub/jti que no son UUID.
        assertThat(service.verify(signedWith("not-a-uuid", sessionId.toString()))).isEmpty();
        assertThat(service.verify(signedWith(userId.toString(), "not-a-uuid"))).isEmpty();
    }

    private String signedWith(String sub, String jti) {
        // JwtClaimsSet no valida el formato de sub/jti, así que sirve para fabricar el token inválido.
        var encoderService = new com.nimbusds.jose.jwk.source.ImmutableSecret<com.nimbusds.jose.proc.SecurityContext>(
                new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256"));
        var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(encoderService);
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .subject(sub).id(jti).issuedAt(START).expiresAt(START.plus(TTL)).build();
        var header = org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256;
        return encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(header).build(), claims)).getTokenValue();
    }

    @Test
    void anInvalidSecretFailsFastWithoutLeakingIt() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new JwtAccessTokenService(new JwtProperties(shortSecret), clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes").hasMessageNotContaining(shortSecret);
        for (String bad : new String[]{null, "", "  ", "%%%not-base64%%%"}) {
            assertThatThrownBy(() -> new JwtAccessTokenService(new JwtProperties(bad), clock))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(new JwtProperties(SECRET).toString()).doesNotContain(SECRET);
    }

    @Test
    void verifyNeverThrows() {
        assertThat(service.verify(null)).isEqualTo(Optional.empty());
        assertThat(Set.of(service.verify("x"))).containsExactly(Optional.empty());
    }
}
