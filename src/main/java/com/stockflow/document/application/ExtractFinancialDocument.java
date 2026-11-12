package com.stockflow.document.application;

import com.stockflow.document.domain.*;
import com.stockflow.shared.config.MindeeProperties;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Clock; import java.time.Instant; import java.util.UUID;

@Service public class ExtractFinancialDocument {
 private final DocumentContentStorage storage; private final FinancialDocumentExtractionPort provider; private final MindeeProperties config; private final FinancialExtractionDraftLifecycle lifecycle;
 public ExtractFinancialDocument(DocumentRepository d,FinancialExtractionDraftRepository r,DocumentContentStorage s,DocumentActorAuthorizer a,FinancialDocumentExtractionPort p,MindeeProperties c,Clock k, FinancialExtractionDraftLifecycle lifecycle){storage=s;provider=p;config=c;this.lifecycle=lifecycle;}
 public FinancialExtractionDraft extract(UUID actorId,UUID documentId){
  UUID model=modelId(); Document document=lifecycle.start(actorId,documentId,model); FinancialDocumentExtractionPort.ExtractionResult result;
  try(var input=open(document)) { result=provider.extract(new FinancialDocumentExtractionPort.ExtractionRequest(document.id(),model,document.originalFilename(),document.mediaType(),input)); }
  catch(MindeeTimeoutException ex){lifecycle.finish(documentId,null,FinancialExtractionProcessingStatus.TIMED_OUT);throw ex;}
  catch(IOException ex){lifecycle.finish(documentId,null,FinancialExtractionProcessingStatus.FAILED);throw new DocumentStorageException(ex);}
  catch(RuntimeException ex){lifecycle.finish(documentId,null,FinancialExtractionProcessingStatus.FAILED);throw ex;}
  return lifecycle.finish(documentId,result,FinancialExtractionProcessingStatus.EXTRACTED);
 }
 private java.io.InputStream open(Document d){try{if(!storage.exists(d.storageKey()))throw new DocumentContentMissingException();return storage.open(d.storageKey());}catch(DocumentContentMissingException e){throw e;}catch(Exception e){throw new DocumentStorageException(e);}}
 private UUID modelId(){try{return UUID.fromString(config.modelId());}catch(Exception e){throw new MindeeConfigurationException();}}
}
