package com.stockflow.permission.domain;

/** Entrada nula o combinación de estado y metadatos que viola las invariantes de una solicitud de permiso. */
public class InvalidPermissionRequestException extends RuntimeException {

    public InvalidPermissionRequestException(String message) {
        super(message);
    }
}
