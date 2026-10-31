package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.identity.application.UserAdministrationRepository.ManagedUser;

import java.util.UUID;

/**
 * Revalidación común del actor de las transiciones de órdenes. SecurityConfig ya filtró el rol al recibir la
 * petición; aquí se vuelve a leer el rol y el estado persistidos dentro de la transacción del caso de uso.
 */
final class PurchaseOrderActors {

    private static final String ADMIN = "ADMIN";
    private static final String STANDARD = "STANDARD";
    private static final String ACTIVE = "ACTIVE";

    private PurchaseOrderActors() {
    }

    /** STANDARD o ADMIN y {@code ACTIVE}; devuelve el actor para decisiones que dependen del rol. */
    static ManagedUser requireActiveUser(UserAdministrationRepository users, UUID actorId) {
        return users.find(actorId)
                .filter(actor -> ACTIVE.equals(actor.accountStatus()))
                .filter(actor -> ADMIN.equals(actor.role()) || STANDARD.equals(actor.role()))
                .orElseThrow(PurchaseOrderActorNotAuthorizedException::new);
    }

    static void requireActiveAdmin(UserAdministrationRepository users, UUID actorId) {
        if (!isAdmin(requireActiveUser(users, actorId))) {
            throw new PurchaseOrderActorNotAuthorizedException();
        }
    }

    static boolean isAdmin(ManagedUser actor) {
        return ADMIN.equals(actor.role());
    }
}
