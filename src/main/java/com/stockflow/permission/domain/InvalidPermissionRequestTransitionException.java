package com.stockflow.permission.domain;

/**
 * Transición que la política no permite: par ausente, salida de un estado terminal o mismo estado. El mensaje solo
 * contiene los estados de origen y destino, nunca identificadores ni actores.
 */
public class InvalidPermissionRequestTransitionException extends RuntimeException {

    private final PermissionRequestStatus from;
    private final PermissionRequestStatus to;

    public InvalidPermissionRequestTransitionException(PermissionRequestStatus from, PermissionRequestStatus to) {
        super("Permission request transition not allowed: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PermissionRequestStatus from() {
        return from;
    }

    public PermissionRequestStatus to() {
        return to;
    }
}
