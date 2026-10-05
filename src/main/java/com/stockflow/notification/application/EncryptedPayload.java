package com.stockflow.notification.application;

public record EncryptedPayload(byte[] ciphertext, byte[] iv, String keyId) {

    @Override
    public String toString() {
        return "EncryptedPayload[keyId=" + keyId + "]";
    }
}
