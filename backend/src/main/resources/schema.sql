CREATE TABLE IF NOT EXISTS customer (
    id              VARCHAR(50)    PRIMARY KEY,
    status          VARCHAR(20)    NOT NULL,
    credit_limit    DECIMAL(19, 2) NOT NULL,
    approved_amount DECIMAL(19, 2) DEFAULT 0 NOT NULL,
    -- Red de seguridad: la base de datos nunca permite superar el cupo.
    CONSTRAINT ck_customer_approved_amount CHECK (approved_amount >= 0 AND approved_amount <= credit_limit)
);

-- Sin FK a customer: tambien se registran (como rechazadas) solicitudes de clientes inexistentes.
CREATE TABLE IF NOT EXISTS credit_application (
    id                    UUID                     PRIMARY KEY,
    application_reference VARCHAR(50)              NOT NULL,
    customer_id           VARCHAR(50)              NOT NULL,
    amount                DECIMAL(19, 2)           NOT NULL,
    term_months           INT                      NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    rejection_reason      VARCHAR(50),
    processed_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Garantiza idempotencia por referencia incluso con peticiones simultaneas (RF05).
    CONSTRAINT uk_credit_application_reference UNIQUE (application_reference)
);

CREATE INDEX IF NOT EXISTS ix_credit_application_processed_at ON credit_application (processed_at);

-- Registro del consumidor de ejemplo (punto opcional RabbitMQ). En un sistema real viviria en la base del consumidor.
-- La PK sobre message_id hace al consumidor idempotente: un mensaje repetido no genera un segundo registro.
CREATE TABLE IF NOT EXISTS approved_application_audit (
    message_id            VARCHAR(100)             PRIMARY KEY,
    application_reference VARCHAR(50)              NOT NULL,
    customer_id           VARCHAR(50)              NOT NULL,
    amount                DECIMAL(19, 2)           NOT NULL,
    approved_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
