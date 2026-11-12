CREATE TABLE documents (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    original_filename varchar(512) NOT NULL,
    media_type varchar(255) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_key uuid NOT NULL,
    uploaded_at timestamptz NOT NULL,
    deleted_at timestamptz NULL,
    CONSTRAINT uq_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_documents_size_bytes_positive CHECK (size_bytes > 0),
    CONSTRAINT ck_documents_original_filename_not_blank CHECK (btrim(original_filename) <> ''),
    CONSTRAINT ck_documents_media_type_not_blank CHECK (btrim(media_type) <> '')
);

CREATE INDEX ix_documents_owner_active_uploaded
    ON documents (owner_id, uploaded_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX ix_documents_active_uploaded
    ON documents (uploaded_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE financial_extraction_drafts (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents (id) ON DELETE RESTRICT,
    provider varchar(32) NOT NULL,
    model_id uuid NULL,
    processing_status varchar(24) NOT NULL,
    review_status varchar(24) NOT NULL,
    supplier_name varchar(512) NULL,
    invoice_number varchar(255) NULL,
    invoice_date date NULL,
    currency char(3) NULL,
    total_amount numeric(19, 4) NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_financial_extraction_drafts_document UNIQUE (document_id),
    CONSTRAINT ck_financial_extraction_drafts_provider CHECK (provider IN ('MINDEE_V2')),
    CONSTRAINT ck_financial_extraction_drafts_processing_status
        CHECK (processing_status IN ('PROCESSING', 'EXTRACTED', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT ck_financial_extraction_drafts_review_status
        CHECK (review_status IN ('PENDING_REVIEW', 'REVIEWED')),
    CONSTRAINT ck_financial_extraction_drafts_supplier_not_blank
        CHECK (supplier_name IS NULL OR btrim(supplier_name) <> ''),
    CONSTRAINT ck_financial_extraction_drafts_invoice_number_not_blank
        CHECK (invoice_number IS NULL OR btrim(invoice_number) <> ''),
    CONSTRAINT ck_financial_extraction_drafts_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_financial_extraction_drafts_total_amount
        CHECK (total_amount IS NULL OR total_amount >= 0),
    CONSTRAINT ck_financial_extraction_drafts_values_when_extracted CHECK (
        (processing_status = 'EXTRACTED' AND model_id IS NOT NULL)
        OR (processing_status <> 'EXTRACTED' AND supplier_name IS NULL AND invoice_number IS NULL
            AND invoice_date IS NULL AND currency IS NULL AND total_amount IS NULL)
    )
);

CREATE INDEX ix_financial_extraction_drafts_status
    ON financial_extraction_drafts (processing_status, updated_at);
