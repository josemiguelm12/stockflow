package com.stockflow.identity.application;

/** Entrada rechazada por una regla de dominio; el mensaje nunca incluye el valor recibido. */
public class InvalidInputException extends RuntimeException {

    private final String field;

    public InvalidInputException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
