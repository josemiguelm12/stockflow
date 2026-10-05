package com.stockflow.notification.application;

import com.stockflow.shared.config.OutboxProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM con IV aleatorio por mensaje. El key id se autentica como AAD. Una configuración inválida
 * lanza IllegalStateException al construir el bean, así que la aplicación no arranca ni degrada a texto plano.
 */
@Component
public class OutboxPayloadCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final String keyId;

    OutboxPayloadCipher(OutboxProperties properties) {
        if (properties.encryptionKeyId() == null || properties.encryptionKeyId().isBlank()) {
            throw new IllegalStateException("stockflow.outbox.encryption-key-id must not be blank");
        }
        this.keyId = properties.encryptionKeyId();
        this.key = new SecretKeySpec(decodeKey(properties.encryptionKey()), "AES");
    }

    private static byte[] decodeKey(String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("stockflow.outbox.encryption-key must be valid Base64");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("stockflow.outbox.encryption-key must decode to exactly 32 bytes");
        }
        return decoded;
    }

    public EncryptedPayload encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPayload(cipher.doFinal(plaintext), iv, keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Outbox payload encryption failed");
        }
    }

    public byte[] decrypt(EncryptedPayload payload) {
        if (!keyId.equals(payload.keyId())) {
            throw new IllegalStateException("Outbox payload was encrypted with an unknown key id");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload.iv()));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(payload.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Outbox payload decryption failed");
        }
    }
}
