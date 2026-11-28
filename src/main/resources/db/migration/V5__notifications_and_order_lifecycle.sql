-- P5-F1: notificaciones internas, preferencias de correo funcional, propietario y fechas de órdenes y metadatos del
-- outbox (estos últimos los usa F2). Migración aditiva: columnas nullable o con valor constante, sin backfill.

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    -- La bandeja es dato derivado del usuario: CASCADE evita bloquear el borrado de cuentas (p. ej. limpieza de pruebas).
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type varchar(48) NOT NULL,
    event_key varchar(160) NOT NULL,
    title varchar(200) NOT NULL,
    message text NOT NULL,
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN (
        'PERMISSION_REQUEST_CREATED', 'PERMISSION_REQUEST_APPROVED', 'PERMISSION_REQUEST_REJECTED',
        'PURCHASE_ORDER_APPROVED')),
    CONSTRAINT ck_notifications_resource_type CHECK (resource_type IN ('PERMISSION_REQUEST', 'PURCHASE_ORDER')),
    CONSTRAINT ck_notifications_title CHECK (title !~ '^[[:space:]]*$'),
    CONSTRAINT ck_notifications_message CHECK (message !~ '^[[:space:]]*$'),
    CONSTRAINT ck_notifications_read_at CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT uq_notifications_user_event UNIQUE (user_id, event_key)
);

CREATE INDEX ix_notifications_user_created ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id, created_at DESC, id DESC) WHERE read_at IS NULL;

-- Ausencia de fila = correo funcional habilitado. Solo gobierna avisos funcionales, nunca activación ni recuperación.
CREATE TABLE user_notification_preferences (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    functional_email_enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL
);

-- Órdenes históricas conservan propietario y fechas NULL (sin atribución ni backfill).
ALTER TABLE purchase_orders
    ADD COLUMN owner_user_id uuid NULL REFERENCES users (id) ON DELETE RESTRICT,
    ADD COLUMN submitted_at timestamptz NULL,
    ADD COLUMN approved_at timestamptz NULL,
    ADD CONSTRAINT ck_purchase_orders_approval_timeline
        CHECK (approved_at IS NULL OR submitted_at IS NULL OR approved_at >= submitted_at);

CREATE INDEX ix_purchase_orders_owner_state ON purchase_orders (owner_user_id, state)
    WHERE owner_user_id IS NOT NULL;
CREATE INDEX ix_purchase_orders_approved_at ON purchase_orders (approved_at)
    WHERE approved_at IS NOT NULL;
CREATE INDEX ix_purchase_orders_owner_approved_at ON purchase_orders (owner_user_id, approved_at)
    WHERE owner_user_id IS NOT NULL AND approved_at IS NOT NULL;

-- Outbox: F1 solo escribe dedupe_key; el resto lo usa F2 (reintentos acotados). Las filas PENDING históricas, con
-- next_attempt_at NULL, siguen siendo elegibles de inmediato.
ALTER TABLE outbound_emails
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at timestamptz NULL,
    ADD COLUMN next_attempt_at timestamptz NULL,
    ADD COLUMN failed_at timestamptz NULL,
    ADD COLUMN last_error varchar(300) NULL,
    ADD COLUMN dedupe_key varchar(240) NULL;

ALTER TABLE outbound_emails DROP CONSTRAINT ck_outbound_emails_state;
ALTER TABLE outbound_emails
    ADD CONSTRAINT ck_outbound_emails_state CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
    ADD CONSTRAINT ck_outbound_emails_attempt_count CHECK (attempt_count BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_outbound_emails_failed CHECK (state <> 'FAILED' OR failed_at IS NOT NULL);

CREATE UNIQUE INDEX uq_outbound_emails_dedupe_key ON outbound_emails (dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX ix_outbound_emails_pending_next_attempt ON outbound_emails (next_attempt_at, enqueued_at)
    WHERE state = 'PENDING';
