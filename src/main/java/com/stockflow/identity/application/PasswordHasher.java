package com.stockflow.identity.application;

public interface PasswordHasher {

    String hash(String rawPassword);
}
