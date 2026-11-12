package com.stockflow.document.adapter.in.web;

import com.stockflow.document.application.DeleteDocument;
import com.stockflow.document.application.DownloadDocument;
import com.stockflow.document.application.ListVisibleDocuments;
import com.stockflow.document.application.UploadDocument;
import com.stockflow.document.application.ExtractFinancialDocument;
import com.stockflow.document.application.GetFinancialExtractionDraft;
import com.stockflow.document.domain.Document;
import com.stockflow.document.domain.DocumentMetadata;
import com.stockflow.identity.application.AuthenticatedUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {
    private final UploadDocument upload; private final ListVisibleDocuments list;
    private final DownloadDocument download; private final DeleteDocument delete;
    private final ExtractFinancialDocument extract; private final GetFinancialExtractionDraft drafts;
    DocumentController(UploadDocument upload, ListVisibleDocuments list, DownloadDocument download, DeleteDocument delete, ExtractFinancialDocument extract, GetFinancialExtractionDraft drafts) {
        this.upload=upload; this.list=list; this.download=download; this.delete=delete; this.extract=extract;this.drafts=drafts;
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentResponse> upload(Authentication authentication, @RequestPart("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new com.stockflow.document.domain.InvalidDocumentException("file is required and must not be empty");
        Document d=upload.upload(actor(authentication), new UploadDocument.Upload(file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream()));
        return ResponseEntity.status(201).body(DocumentResponse.from(new DocumentMetadata(d.id(),d.ownerId(),d.originalFilename(),d.mediaType(),d.sizeBytes(),d.uploadedAt())));
    }
    @GetMapping List<DocumentResponse> list(Authentication authentication) { return list.list(actor(authentication)).stream().map(DocumentResponse::from).toList(); }
    @GetMapping("/{id}/download") ResponseEntity<StreamingResponseBody> download(Authentication authentication, @PathVariable UUID id) {
        DownloadDocument.Download result=download.download(actor(authentication),id); Document d=result.document();
        StreamingResponseBody body=out -> { try (var input=result.content()) { input.transferTo(out); } };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(d.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.originalFilename(), StandardCharsets.UTF_8).build().toString()).body(body);
    }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(Authentication authentication,@PathVariable UUID id){ delete.delete(actor(authentication),id); return ResponseEntity.noContent().build(); }
    @PostMapping("/{id}/extract-financial-data") FinancialExtractionDraftResponse extract(Authentication authentication,@PathVariable UUID id){return FinancialExtractionDraftResponse.from(extract.extract(actor(authentication),id));}
    @GetMapping("/{id}/financial-extraction-draft") FinancialExtractionDraftResponse draft(Authentication authentication,@PathVariable UUID id){return FinancialExtractionDraftResponse.from(drafts.get(actor(authentication),id));}
    private static UUID actor(Authentication authentication) { return ((AuthenticatedUser) authentication.getPrincipal()).userId(); }
}
