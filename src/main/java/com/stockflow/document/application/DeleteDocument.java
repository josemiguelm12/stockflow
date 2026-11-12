package com.stockflow.document.application;
import com.stockflow.document.domain.Document; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.Clock; import java.time.Instant; import java.util.UUID;
@Service public class DeleteDocument {
 private final DocumentRepository documents; private final DocumentActorAuthorizer authorizer; private final Clock clock;
 public DeleteDocument(DocumentRepository d, DocumentActorAuthorizer a, Clock c){documents=d;authorizer=a;clock=c;}
 @Transactional public void delete(UUID actorId, UUID id){ Document d=documents.lockActiveById(id).orElseThrow(DocumentNotFoundException::new); authorizer.requireOwnerOrAdmin(actorId,d); if(!documents.markDeleted(id, Instant.now(clock))) throw new DocumentNotFoundException(); }
}
