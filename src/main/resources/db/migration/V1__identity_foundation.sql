CREATE TABLE users (
    id uuid PRIMARY KEY,
    email_normalized varchar(320) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(16) NOT NULL,
    account_status varchar(24) NOT NULL,
    activation_completed_at timestamptz NULL,
    password_reset_required boolean NOT NULL DEFAULT false,
    failed_login_attempts integer NOT NULL DEFAULT 0,
    locked_until timestamptz NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_users_email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'STANDARD')),
    CONSTRAINT ck_users_account_status CHECK (account_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_failed_login_attempts CHECK (failed_login_attempts >= 0)
);

CREATE TABLE auth_sessions (
    jti uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL
);

CREATE INDEX ix_auth_sessions_user_id ON auth_sessions (user_id);

CREATE TABLE one_time_tokens (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users (id),
    purpose varchar(24) NOT NULL,
    token_hash varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz NULL,
    invalidated_at timestamptz NULL,
    CONSTRAINT ck_one_time_tokens_purpose CHECK (purpose IN ('ACTIVATION', 'PASSWORD_RESET')),
    CONSTRAINT uq_one_time_tokens_purpose_hash UNIQUE (purpose, token_hash)
);

CREATE INDEX ix_one_time_tokens_user_id ON one_time_tokens (user_id);

CREATE TABLE outbound_emails (
    id uuid PRIMARY KEY,
    recipient_email varchar(320) NOT NULL,
    template_key varchar(64) NOT NULL,
    payload_ciphertext bytea NULL,
    payload_iv bytea NULL,
    payload_key_id varchar(128) NULL,
    state varchar(16) NOT NULL,
    enqueued_at timestamptz NOT NULL,
    sent_at timestamptz NULL,
    CONSTRAINT ck_outbound_emails_state CHECK (state IN ('PENDING', 'SENT')),
    CONSTRAINT ck_outbound_emails_pending_payload CHECK (
        state <> 'PENDING'
        OR (payload_ciphertext IS NOT NULL AND payload_iv IS NOT NULL AND payload_key_id IS NOT NULL)
    )
);

CREATE INDEX ix_outbound_emails_state_enqueued_at ON outbound_emails (state, enqueued_at);
