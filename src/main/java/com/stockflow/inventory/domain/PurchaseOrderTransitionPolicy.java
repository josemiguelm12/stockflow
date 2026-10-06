package com.stockflow.inventory.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.CANCELLED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.SUBMITTED;

/**
 * Única fuente de las transiciones permitidas de una orden de compra (RF-NEG-04, RD-04). Todo par que no figure aquí
 * se rechaza; un estado sin salidas es terminal (RF-NEG-05). Las condiciones de negocio (líneas, recepción, stock,
 * proveedor) son futuras y no se evalúan aquí.
 */
public final class PurchaseOrderTransitionPolicy {

    private static final Map<PurchaseOrderStatus, Set<PurchaseOrderStatus>> ALLOWED = allowed();

    private PurchaseOrderTransitionPolicy() {
    }

    private static Map<PurchaseOrderStatus, Set<PurchaseOrderStatus>> allowed() {
        Map<PurchaseOrderStatus, Set<PurchaseOrderStatus>> allowed = new EnumMap<>(PurchaseOrderStatus.class);
        allowed.put(DRAFT, EnumSet.of(SUBMITTED, CANCELLED));
        allowed.put(SUBMITTED, EnumSet.of(APPROVED, CANCELLED));
        allowed.put(APPROVED, EnumSet.of(RECEIVED, CANCELLED));
        allowed.put(RECEIVED, EnumSet.noneOf(PurchaseOrderStatus.class));
        allowed.put(CANCELLED, EnumSet.noneOf(PurchaseOrderStatus.class));
        return Collections.unmodifiableMap(allowed);
    }

    public static Set<PurchaseOrderStatus> allowedTargets(PurchaseOrderStatus from) {
        Objects.requireNonNull(from, "from");
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }

    public static boolean isAllowed(PurchaseOrderStatus from, PurchaseOrderStatus to) {
        Objects.requireNonNull(to, "to");
        return allowedTargets(from).contains(to);
    }

    public static boolean isTerminal(PurchaseOrderStatus status) {
        return allowedTargets(status).isEmpty();
    }

    /** Lanza {@link InvalidPurchaseOrderTransitionException} si {@code from -> to} no está permitida. */
    public static void requireAllowed(PurchaseOrderStatus from, PurchaseOrderStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidPurchaseOrderTransitionException(from, to);
        }
    }
}
