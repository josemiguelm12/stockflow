package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.ListUsers;
import com.stockflow.identity.application.UserAdministrationRepository.UserSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Página segura del listado: sin hash, fallos, bloqueo, tokens, sesiones ni datos del outbox. */
record AdminUserPageResponse(List<Item> items, int page, int size, long totalElements, long totalPages) {

    record Item(UUID id, String email, String role, String accountStatus, boolean passwordResetRequired,
                Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return "Item[id=" + id + ", role=" + role + ", accountStatus=" + accountStatus + "]";
        }

        static Item from(UserSummary user) {
            return new Item(user.id(), user.email(), user.role(), user.accountStatus(), user.passwordResetRequired(),
                    user.createdAt(), user.updatedAt());
        }
    }

    /** Spring registra la respuesta escrita en DEBUG: solo totales, nunca la lista de emails. */
    @Override
    public String toString() {
        return "AdminUserPageResponse[page=" + page + ", size=" + size + ", totalElements=" + totalElements + "]";
    }

    static AdminUserPageResponse from(ListUsers.UserPage page) {
        return new AdminUserPageResponse(page.items().stream().map(Item::from).toList(), page.page(), page.size(),
                page.totalElements(), page.totalPages());
    }
}
