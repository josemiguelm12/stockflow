package com.stockflow.permission.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/** El motivo se entrega literal (sin trim) al agregado, que lo persiste tal cual. */
record RejectPermissionRequestHttpRequest(@NotBlank String reason) {

    /** Spring puede registrar el cuerpo leído en DEBUG: el motivo escrito por el ADMIN no se muestra. */
    @Override
    public String toString() {
        return "RejectPermissionRequestHttpRequest[redacted]";
    }
}
