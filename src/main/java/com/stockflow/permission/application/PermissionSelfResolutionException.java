package com.stockflow.permission.application;

/** Un ADMIN no puede aprobar ni rechazar una solicitud que él mismo hizo. */
public class PermissionSelfResolutionException extends RuntimeException {

    public PermissionSelfResolutionException() {
        super("A permission request cannot be resolved by its requester");
    }
}
