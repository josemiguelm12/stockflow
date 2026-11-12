package com.stockflow.document.application;
import com.stockflow.document.domain.Document; import org.springframework.stereotype.Service;
import java.io.InputStream; import java.util.UUID;
@Service public class DownloadDocument {
 private final DocumentRepository documents; private final DocumentContentStorage storage; private final DocumentActorAuthorizer authorizer;
 public DownloadDocument(DocumentRepository d, DocumentContentStorage s, DocumentActorAuthorizer a){documents=d;storage=s;authorizer=a;}
 public Download download(UUID actorId, UUID id){ Document d=documents.findActiveById(id).orElseThrow(DocumentNotFoundException::new); authorizer.requireOwnerOrAdmin(actorId,d); try { if(!storage.exists(d.storageKey())) throw new DocumentContentMissingException(); return new Download(d, storage.open(d.storageKey())); } catch(DocumentContentMissingException e){throw e;} catch(Exception e){throw new DocumentStorageException(e);} }
 public record Download(Document document, InputStream content) { }
}
