package com.stockflow.permission.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;

/**
 * Solicitud de un {@link PermissionCode} por un usuario. Su estado solo cambia a través de
 * {@link PermissionRequestTransitionPolicy}; la decisión ({@code APPROVED}/{@code REJECTED}) y la aplicación registran
 * actor y momento una sola vez, y el rechazo además su motivo escrito (RF-GP-04). Toda decisión ocurre en o después
 * de {@code requestedAt} y toda aplicación en o después de {@code decidedAt}. La autorización de esos actores no
 * pertenece a este agregado.
 */
public final class PermissionRequest {

    private final UUID id;
    private final UUID requestedBy;
    private final PermissionCode permissionCode;
    private final Instant requestedAt;
    private PermissionRequestStatus status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String rejectionReason;
    private UUID appliedBy;
    private Instant appliedAt;

    private PermissionRequest(UUID id, UUID requestedBy, PermissionCode permissionCode, Instant requestedAt,
                              PermissionRequestStatus status, UUID decidedBy, Instant decidedAt,
                              String rejectionReason, UUID appliedBy, Instant appliedAt) {
        this.id = required(id, "id");
        this.requestedBy = required(requestedBy, "requestedBy");
        this.permissionCode = required(permissionCode, "permissionCode");
        this.requestedAt = required(requestedAt, "requestedAt");
        this.status = required(status, "status");
        requireMetadataMatchesStatus(status, decidedBy, decidedAt, rejectionReason, appliedBy, appliedAt);
        if (status == REJECTED) {
            requireValidReason(rejectionReason);
        }
        if (decidedAt != null) {
            requireNotBefore(decidedAt, requestedAt, "decidedAt must not be before requestedAt");
        }
        if (appliedAt != null) {
            requireNotBefore(appliedAt, decidedAt, "appliedAt must not be before decidedAt");
        }
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectionReason = rejectionReason;
        this.appliedBy = appliedBy;
        this.appliedAt = appliedAt;
    }

    /** Nueva solicitud; siempre empieza en {@code PENDING} y sin metadatos de decisión ni aplicación. */
    public static PermissionRequest create(UUID id, UUID requestedBy, PermissionCode permissionCode,
                                           Instant requestedAt) {
        return new PermissionRequest(id, requestedBy, permissionCode, requestedAt, PENDING,
                null, null, null, null, null);
    }

    /** Reconstruye una solicitud ya registrada; rechaza metadatos incoherentes con su estado o fuera de orden. */
    public static PermissionRequest restore(UUID id, UUID requestedBy, PermissionCode permissionCode,
                                            Instant requestedAt, PermissionRequestStatus status,
                                            UUID decidedBy, Instant decidedAt, String rejectionReason,
                                            UUID appliedBy, Instant appliedAt) {
        return new PermissionRequest(id, requestedBy, permissionCode, requestedAt, status,
                decidedBy, decidedAt, rejectionReason, appliedBy, appliedAt);
    }

    public void approve(UUID approvedBy, Instant approvedAt) {
        decide(APPROVED, approvedBy, approvedAt, null);
    }

    /** El rechazo solo se completa con un motivo escrito: no nulo, no vacío y no compuesto solo por espacios. */
    public void reject(UUID rejectedBy, Instant rejectedAt, String reason) {
        requireValidReason(reason);
        decide(REJECTED, rejectedBy, rejectedAt, reason);
    }

    public void apply(UUID appliedBy, Instant appliedAt) {
        required(appliedBy, "appliedBy");
        required(appliedAt, "appliedAt");
        PermissionRequestTransitionPolicy.requireAllowed(status, APPLIED);
        requireNotBefore(appliedAt, decidedAt, "appliedAt must not be before decidedAt");
        this.status = APPLIED;
        this.appliedBy = appliedBy;
        this.appliedAt = appliedAt;
    }

    /** Valida entradas, transición y orden temporal antes de mutar: un rechazo deja la solicitud intacta. */
    private void decide(PermissionRequestStatus target, UUID decidedBy, Instant decidedAt, String rejectionReason) {
        required(decidedBy, "decidedBy");
        required(decidedAt, "decidedAt");
        PermissionRequestTransitionPolicy.requireAllowed(status, target);
        requireNotBefore(decidedAt, requestedAt, "decidedAt must not be before requestedAt");
        this.status = target;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectionReason = rejectionReason;
    }

    private static void requireMetadataMatchesStatus(PermissionRequestStatus status,
                                                     UUID decidedBy, Instant decidedAt, String rejectionReason,
                                                     UUID appliedBy, Instant appliedAt) {
        boolean decided = status != PENDING;
        boolean rejected = status == REJECTED;
        boolean applied = status == APPLIED;
        if (!presence(decidedBy, decidedAt, decided)
                || (rejectionReason != null) != rejected
                || !presence(appliedBy, appliedAt, applied)) {
            throw new InvalidPermissionRequestException("Metadata is inconsistent with status " + status);
        }
    }

    /** Ambos valores presentes si {@code expected}; ambos ausentes en caso contrario. */
    private static boolean presence(Object actor, Instant at, boolean expected) {
        return (actor != null) == expected && (at != null) == expected;
    }

    /** El mensaje nunca incluye el motivo recibido. */
    private static void requireValidReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidPermissionRequestException("rejectionReason is required");
        }
    }

    private static void requireNotBefore(Instant later, Instant earlier, String message) {
        if (later.isBefore(earlier)) {
            throw new InvalidPermissionRequestException(message);
        }
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new InvalidPermissionRequestException(name + " is required");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public PermissionCode permissionCode() {
        return permissionCode;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public PermissionRequestStatus status() {
        return status;
    }

    public Optional<UUID> decidedBy() {
        return Optional.ofNullable(decidedBy);
    }

    public Optional<Instant> decidedAt() {
        return Optional.ofNullable(decidedAt);
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<UUID> appliedBy() {
        return Optional.ofNullable(appliedBy);
    }

    public Optional<Instant> appliedAt() {
        return Optional.ofNullable(appliedAt);
    }
}
