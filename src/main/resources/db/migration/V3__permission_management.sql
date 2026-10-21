CREATE TABLE permission_requests (
    id uuid PRIMARY KEY,
    requested_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    permission_code varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    requested_at timestamptz NOT NULL,
    decided_by uuid NULL REFERENCES users (id) ON DELETE RESTRICT,
    decided_at timestamptz NULL,
    rejection_reason text NULL,
    applied_by uuid NULL REFERENCES users (id) ON DELETE RESTRICT,
    applied_at timestamptz NULL,
    CONSTRAINT ck_permission_requests_permission_code
        CHECK (permission_code IN ('PURCHASE_ORDER_CREATE')),
    CONSTRAINT ck_permission_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'APPLIED', 'REJECTED')),
    -- Mismas combinaciones que PermissionRequest.restore(...); el motivo se guarda literal, sin trim.
    CONSTRAINT ck_permission_requests_metadata CHECK (
        (status = 'PENDING'
            AND decided_by IS NULL AND decided_at IS NULL AND rejection_reason IS NULL
            AND applied_by IS NULL AND applied_at IS NULL)
        OR (status = 'APPROVED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NULL
            AND applied_by IS NULL AND applied_at IS NULL)
        OR (status = 'REJECTED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND rejection_reason IS NOT NULL AND rejection_reason !~ '^[[:space:]]*$'
            AND applied_by IS NULL AND applied_at IS NULL)
        OR (status = 'APPLIED'
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND rejection_reason IS NULL
            AND applied_by IS NOT NULL AND applied_at IS NOT NULL)
    ),
    CONSTRAINT ck_permission_requests_timeline CHECK (
        (decided_at IS NULL OR decided_at >= requested_at)
        AND (applied_at IS NULL OR applied_at >= decided_at)
    )
);

CREATE INDEX ix_permission_requests_requested_by_requested_at ON permission_requests (requested_by, requested_at);
CREATE INDEX ix_permission_requests_status_requested_at ON permission_requests (status, requested_at);

-- La FK no garantiza que el origen esté APPLIED: lo valida el caso de uso que aplica y concede (A2-T03).
CREATE TABLE user_permissions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    permission_code varchar(64) NOT NULL,
    source_request_id uuid NOT NULL REFERENCES permission_requests (id) ON DELETE RESTRICT,
    granted_at timestamptz NOT NULL,
    CONSTRAINT ck_user_permissions_permission_code
        CHECK (permission_code IN ('PURCHASE_ORDER_CREATE')),
    CONSTRAINT uq_user_permissions_source_request UNIQUE (source_request_id),
    CONSTRAINT uq_user_permissions_user_permission_code UNIQUE (user_id, permission_code)
);
