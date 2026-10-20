package com.stockflow.permission.application;

/**
 * El actor no existe, no está activo o, según la operación, no es ADMIN. Se comprueba contra el rol y el estado
 * persistidos en el momento de la operación; el mensaje no incluye identificadores.
 */
public class PermissionActorNotAuthorizedException extends RuntimeException {

    public PermissionActorNotAuthorizedException() {
        super("Actor is not authorized for this permission operation");
    }
}
