package com.stockflow.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token de un solo uso: el valor crudo solo viaja por correo; en base de datos solo se guarda su hash. */
public record ActivationToken(String raw, String hash) {

    private static final int TOKEN_BYTES = 32;

    public static ActivationToken generate(SecureRandom random) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ActivationToken(raw, hash(raw));
    }

    /** SHA-256 en hexadecimal; suficiente porque el token tiene 256 bits de entropía. */
    public static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "ActivationToken[redacted]";
    }
}
