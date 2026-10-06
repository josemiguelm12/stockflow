package com.stockflow.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Eventos administrativos estructurados: solo IDs, acción y resultado. Nunca email, JWT, contraseña, token ni
 * enlace. No es una auditoría persistente (fuera de alcance de la práctica).
 */
final class AdminAudit {

    private static final Logger log = LoggerFactory.getLogger("stockflow.admin");

    private AdminAudit() {
    }

    static void record(String action, UUID actorId, UUID targetId, String result) {
        log.info("admin_event action={} actor={} target={} result={}", action, actorId, targetId, result);
    }
}
