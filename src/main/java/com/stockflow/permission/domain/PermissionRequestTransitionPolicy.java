package com.stockflow.permission.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;

/**
 * Única fuente de las transiciones permitidas de una solicitud de permiso (RD-04). Todo par que no figure aquí se
 * rechaza, incluidos el mismo estado y cualquier salida de {@code APPLIED} o {@code REJECTED}. Quién puede decidir o
 * aplicar no se evalúa aquí.
 */
public final class PermissionRequestTransitionPolicy {

    private static final Map<PermissionRequestStatus, Set<PermissionRequestStatus>> ALLOWED = allowed();

    private PermissionRequestTransitionPolicy() {
    }

    private static Map<PermissionRequestStatus, Set<PermissionRequestStatus>> allowed() {
        Map<PermissionRequestStatus, Set<PermissionRequestStatus>> allowed =
                new EnumMap<>(PermissionRequestStatus.class);
        allowed.put(PENDING, Collections.unmodifiableSet(EnumSet.of(APPROVED, REJECTED)));
        allowed.put(APPROVED, Collections.unmodifiableSet(EnumSet.of(APPLIED)));
        allowed.put(APPLIED, Collections.unmodifiableSet(EnumSet.of(REJECTED)));
        allowed.put(REJECTED, Collections.unmodifiableSet(EnumSet.noneOf(PermissionRequestStatus.class)));
        return Collections.unmodifiableMap(allowed);
    }

    /** Lanza {@link InvalidPermissionRequestException} si algún estado es nulo. */
    public static boolean isAllowed(PermissionRequestStatus from, PermissionRequestStatus to) {
        if (from == null || to == null) {
            throw new InvalidPermissionRequestException("Transition states are required");
        }
        return ALLOWED.get(from).contains(to);
    }

    /** Lanza {@link InvalidPermissionRequestTransitionException} si {@code from -> to} no está permitida. */
    public static void requireAllowed(PermissionRequestStatus from, PermissionRequestStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidPermissionRequestTransitionException(from, to);
        }
    }
}
