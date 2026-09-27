CREATE TABLE accounts (
                          id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          owner VARCHAR(255) NOT NULL,
                          balance_cents BIGINT NOT NULL CHECK (balance_cents >= 0)
);

CREATE TABLE transfers (
                           id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           idempotency_key VARCHAR(255) NOT NULL,
                           from_account_id BIGINT NOT NULL REFERENCES accounts(id),
                           to_account_id BIGINT NOT NULL REFERENCES accounts(id),
                           amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           UNIQUE (from_account_id, idempotency_key)
);

CREATE TABLE entries (
                         id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         transfer_id BIGINT NOT NULL REFERENCES transfers(id),
                         account_id BIGINT NOT NULL REFERENCES accounts(id),
                         amount_cents BIGINT NOT NULL
);

INSERT INTO accounts (owner, balance_cents)
VALUES ('Alice', 10000);

INSERT INTO accounts (owner, balance_cents)
VALUES ('Bob', 0);

INSERT INTO accounts (owner, balance_cents)
VALUES ('Carol', 0);