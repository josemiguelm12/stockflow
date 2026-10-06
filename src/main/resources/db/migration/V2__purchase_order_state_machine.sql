CREATE TABLE purchase_orders (
    id uuid PRIMARY KEY,
    supplier_id uuid NULL,
    state varchar(16) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_purchase_orders_state
        CHECK (state IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RECEIVED', 'CANCELLED'))
);
