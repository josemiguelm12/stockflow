package com.stockflow.notification.application;

import com.stockflow.shared.config.OutboxProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxPayloadCipherTest {

    private static final String KEY_32 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private static OutboxPayloadCipher cipher(String key, String keyId) {
        return new OutboxPayloadCipher(new OutboxProperties(key, keyId));
    }

    @Test
    void roundTripsAndNeverStoresPlaintext() {
        OutboxPayloadCipher cipher = cipher(KEY_32, "k1");
        byte[] plaintext = "{\"token\":\"secret-token\"}".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload encrypted = cipher.encrypt(plaintext);

        assertThat(new String(encrypted.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("secret-token");
        assertThat(encrypted.iv()).hasSize(12);
        assertThat(encrypted.keyId()).isEqualTo("k1");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void usesAFreshIvPerMessage() {
        OutboxPayloadCipher cipher = cipher(KEY_32, "k1");
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload a = cipher.encrypt(plaintext);
        EncryptedPayload b = cipher.encrypt(plaintext);

        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void detectsTamperingAndUnknownKeyId() {
        OutboxPayloadCipher cipher = cipher(KEY_32, "k1");
        EncryptedPayload encrypted = cipher.encrypt("data".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = Arrays.copyOf(encrypted.ciphertext(), encrypted.ciphertext().length);
        tampered[0] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(new EncryptedPayload(tampered, encrypted.iv(), "k1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedPayload(encrypted.ciphertext(), encrypted.iv(), "other")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidConfigurationFailsFastWithoutLeakingTheKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> cipher(shortKey, "k1")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes").hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> cipher("%%%not-base64%%%", "k1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher("", "k1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(null, "k1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(KEY_32, " ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher(KEY_32, null)).isInstanceOf(IllegalStateException.class);
    }
}
