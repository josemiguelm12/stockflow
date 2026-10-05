package com.stockflow.identity.application;

public class InvalidCurrentPasswordException extends RuntimeException {

    public InvalidCurrentPasswordException() {
        super("Current password rejected");
    }
}
