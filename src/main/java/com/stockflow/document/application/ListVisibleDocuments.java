package com.stockflow.document.application;
import com.stockflow.document.domain.DocumentMetadata;
import org.springframework.stereotype.Service;
import java.util.List; import java.util.UUID;
@Service public class ListVisibleDocuments {
 private final DocumentRepository documents; private final DocumentActorAuthorizer authorizer;
 public ListVisibleDocuments(DocumentRepository d, DocumentActorAuthorizer a){documents=d;authorizer=a;}
 public List<DocumentMetadata> list(UUID actorId){ return authorizer.isAdmin(actorId) ? documents.findAllActive() : documents.findActiveByOwnerId(actorId); }
}
