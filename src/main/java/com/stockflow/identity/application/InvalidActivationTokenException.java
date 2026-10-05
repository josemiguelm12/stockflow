package com.stockflow.identity.application;

public class InvalidActivationTokenException extends RuntimeException {

    public InvalidActivationTokenException() {
        super("Activation token is invalid, expired or already used");
    }
}
