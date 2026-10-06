package com.stockflow.identity.application;

/**
 * Cambio administrativo no permitido: auto-cambio de rol, auto-desactivación, dejar sin ADMIN activos, transición de
 * estado inválida u objetivo en estado incompatible. La respuesta HTTP es un 409 genérico que no dice cuál.
 */
public class AdminConflictException extends RuntimeException {

    private final String reason;

    public AdminConflictException(String reason) {
        super("Administrative change not allowed");
        this.reason = reason;
    }

    /** Motivo interno para el log estructurado (sin datos personales); nunca se devuelve al cliente. */
    public String reason() {
        return reason;
    }
}
