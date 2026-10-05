-- Plans, and one instalment per plan per month: the primary key makes a second impossible.
CREATE TABLE plans (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL,
    idempotency_key  text NOT NULL,
    request_hash     text NOT NULL,
    symbol           text NOT NULL,
    amount_paise     bigint NOT NULL CHECK (amount_paise > 0),
    day_of_month     int NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    start_now        boolean NOT NULL,
    status           text NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    start_date       date NOT NULL,          -- the market session it was created in
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX plans_active ON plans (status) WHERE status = 'ACTIVE';

CREATE TABLE instalments (
    plan_id      uuid NOT NULL REFERENCES plans (id),
    month        text NOT NULL CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    status       text NOT NULL CHECK (status IN ('PLACED', 'FILLED', 'SKIPPED')),
    order_id     uuid,
    quantity     bigint,
    price_paise  bigint,
    reason       text,
    at           timestamptz NOT NULL,
    PRIMARY KEY (plan_id, month)
);
