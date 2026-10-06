package com.stockflow.identity.application;

/** El usuario que actúa ya no es un ADMIN activo en el momento de aplicar el cambio (se revalida bajo bloqueo). */
public class AdminActorNotAuthorizedException extends RuntimeException {

    public AdminActorNotAuthorizedException() {
        super("Actor is no longer an active ADMIN");
    }
}
