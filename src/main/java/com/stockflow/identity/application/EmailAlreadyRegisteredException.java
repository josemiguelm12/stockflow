package com.stockflow.identity.application;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email already registered");
    }
}
