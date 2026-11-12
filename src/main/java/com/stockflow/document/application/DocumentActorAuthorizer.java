package com.stockflow.document.application;

import com.stockflow.document.domain.Document;
import com.stockflow.identity.application.UserAdministrationRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Rechecks account state and role in persistence for direct use-case invocations. */
@Component
public final class DocumentActorAuthorizer {
    private final UserAdministrationRepository users;

    public DocumentActorAuthorizer(UserAdministrationRepository users) { this.users = users; }

    public void requireActive(UUID actorId) {
        users.find(actorId).filter(u -> "ACTIVE".equals(u.accountStatus()))
                .filter(u -> "STANDARD".equals(u.role()) || "ADMIN".equals(u.role()))
                .orElseThrow(DocumentActorNotAuthorizedException::new);
    }

    public void requireOwnerOrAdmin(UUID actorId, Document document) {
        var actor = users.find(actorId).filter(u -> "ACTIVE".equals(u.accountStatus()))
                .filter(u -> "STANDARD".equals(u.role()) || "ADMIN".equals(u.role()))
                .orElseThrow(DocumentActorNotAuthorizedException::new);
        if (!"ADMIN".equals(actor.role()) && !actorId.equals(document.ownerId())) {
            throw new DocumentAccessDeniedException();
        }
    }

    public boolean isAdmin(UUID actorId) {
        return users.find(actorId).filter(u -> "ACTIVE".equals(u.accountStatus()))
                .map(u -> "ADMIN".equals(u.role())).orElseThrow(DocumentActorNotAuthorizedException::new);
    }
}
