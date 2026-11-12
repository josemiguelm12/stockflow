package com.stockflow.document.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A reviewed-by-human financial extraction associated with exactly one document. */
public final class FinancialExtractionDraft {

    private final UUID id;
    private final UUID documentId;
    private FinancialExtractionProvider provider;
    private UUID modelId;
    private FinancialExtractionProcessingStatus processingStatus;
    private FinancialExtractionReviewStatus reviewStatus;
    private String supplierName;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String currency;
    private BigDecimal totalAmount;
    private final Instant createdAt;
    private Instant updatedAt;

    private FinancialExtractionDraft(UUID id, UUID documentId, FinancialExtractionProvider provider, UUID modelId,
                                     FinancialExtractionProcessingStatus processingStatus,
                                     FinancialExtractionReviewStatus reviewStatus, String supplierName,
                                     String invoiceNumber, LocalDate invoiceDate, String currency,
                                     BigDecimal totalAmount, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.modelId = modelId;
        this.processingStatus = Objects.requireNonNull(processingStatus, "processingStatus");
        this.reviewStatus = Objects.requireNonNull(reviewStatus, "reviewStatus");
        this.supplierName = optionalText(supplierName, "supplierName", 512);
        this.invoiceNumber = optionalText(invoiceNumber, "invoiceNumber", 255);
        this.invoiceDate = invoiceDate;
        this.currency = currency(currency);
        this.totalAmount = total(totalAmount);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        validateState();
    }

    public static FinancialExtractionDraft start(UUID id, UUID documentId, FinancialExtractionProvider provider,
                                                 UUID modelId, Instant now) {
        return new FinancialExtractionDraft(id, documentId, provider, modelId,
                FinancialExtractionProcessingStatus.PROCESSING, FinancialExtractionReviewStatus.PENDING_REVIEW,
                null, null, null, null, null, now, now);
    }

    public static FinancialExtractionDraft restore(UUID id, UUID documentId, FinancialExtractionProvider provider,
                                                   UUID modelId, FinancialExtractionProcessingStatus processingStatus,
                                                   FinancialExtractionReviewStatus reviewStatus, String supplierName,
                                                   String invoiceNumber, LocalDate invoiceDate, String currency,
                                                   BigDecimal totalAmount, Instant createdAt, Instant updatedAt) {
        return new FinancialExtractionDraft(id, documentId, provider, modelId, processingStatus, reviewStatus,
                supplierName, invoiceNumber, invoiceDate, currency, totalAmount, createdAt, updatedAt);
    }

    public void restart(FinancialExtractionProvider newProvider, UUID newModelId, Instant now) {
        provider = Objects.requireNonNull(newProvider, "provider");
        modelId = newModelId;
        processingStatus = FinancialExtractionProcessingStatus.PROCESSING;
        reviewStatus = FinancialExtractionReviewStatus.PENDING_REVIEW;
        clearValues();
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void complete(String supplierName, String invoiceNumber, LocalDate invoiceDate, String currency,
                         BigDecimal totalAmount, Instant now) {
        if (modelId == null) {
            throw new InvalidFinancialExtractionDraftException("modelId is required when extraction completes");
        }
        this.supplierName = optionalText(supplierName, "supplierName", 512);
        this.invoiceNumber = optionalText(invoiceNumber, "invoiceNumber", 255);
        this.invoiceDate = invoiceDate;
        this.currency = currency(currency);
        this.totalAmount = total(totalAmount);
        processingStatus = FinancialExtractionProcessingStatus.EXTRACTED;
        reviewStatus = FinancialExtractionReviewStatus.PENDING_REVIEW;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void fail(FinancialExtractionProcessingStatus terminalStatus, Instant now) {
        if (terminalStatus != FinancialExtractionProcessingStatus.FAILED
                && terminalStatus != FinancialExtractionProcessingStatus.TIMED_OUT) {
            throw new InvalidFinancialExtractionDraftException("terminalStatus must be FAILED or TIMED_OUT");
        }
        processingStatus = terminalStatus;
        reviewStatus = FinancialExtractionReviewStatus.PENDING_REVIEW;
        clearValues();
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void markReviewed(Instant now) {
        if (processingStatus != FinancialExtractionProcessingStatus.EXTRACTED) {
            throw new InvalidFinancialExtractionDraftException("only extracted drafts can be reviewed");
        }
        reviewStatus = FinancialExtractionReviewStatus.REVIEWED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID id() { return id; }
    public UUID documentId() { return documentId; }
    public FinancialExtractionProvider provider() { return provider; }
    public Optional<UUID> modelId() { return Optional.ofNullable(modelId); }
    public FinancialExtractionProcessingStatus processingStatus() { return processingStatus; }
    public FinancialExtractionReviewStatus reviewStatus() { return reviewStatus; }
    public Optional<String> supplierName() { return Optional.ofNullable(supplierName); }
    public Optional<String> invoiceNumber() { return Optional.ofNullable(invoiceNumber); }
    public Optional<LocalDate> invoiceDate() { return Optional.ofNullable(invoiceDate); }
    public Optional<String> currency() { return Optional.ofNullable(currency); }
    public Optional<BigDecimal> totalAmount() { return Optional.ofNullable(totalAmount); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    private void validateState() {
        boolean hasValues = supplierName != null || invoiceNumber != null || invoiceDate != null
                || currency != null || totalAmount != null;
        if (processingStatus == FinancialExtractionProcessingStatus.EXTRACTED && modelId == null) {
            throw new InvalidFinancialExtractionDraftException("extracted drafts require modelId");
        }
        if (processingStatus != FinancialExtractionProcessingStatus.EXTRACTED && hasValues) {
            throw new InvalidFinancialExtractionDraftException("only extracted drafts can contain extracted values");
        }
    }

    private void clearValues() {
        supplierName = null;
        invoiceNumber = null;
        invoiceDate = null;
        currency = null;
        totalAmount = null;
    }

    private static String optionalText(String value, String name, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new InvalidFinancialExtractionDraftException(name + " must be non-blank and at most " + maxLength);
        }
        return normalized;
    }

    private static String currency(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new InvalidFinancialExtractionDraftException("currency must be an ISO-4217 code");
        }
        return normalized;
    }

    private static BigDecimal total(BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new InvalidFinancialExtractionDraftException("totalAmount must not be negative");
        }
        return value;
    }
}
