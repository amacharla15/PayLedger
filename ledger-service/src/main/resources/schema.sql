create table if not exists accounts (
    id bigserial primary key,
    owner_id text not null,
    balance_cents bigint not null default 0 check (balance_cents >= 0),
    created_at timestamptz not null default now()
);
create index if not exists accounts_owner on accounts (owner_id);

create table if not exists transfers (
    id bigserial primary key,
    from_account_id bigint not null references accounts (id),
    to_account_id bigint not null references accounts (id),
    amount_cents bigint not null check (amount_cents > 0),
    idempotency_key text unique not null,
    created_at timestamptz not null default now()
);
create index if not exists transfers_from on transfers (from_account_id, id);
create index if not exists transfers_to on transfers (to_account_id, id);

create table if not exists entries (
    id bigserial primary key,
    account_id bigint not null references accounts (id),
    transfer_id bigint references transfers (id),
    kind text not null,
    amount_cents bigint not null,
    created_at timestamptz not null default now()
);
create index if not exists entries_account on entries (account_id);

create table if not exists outbox (
    id bigserial primary key,
    topic text not null,
    msg_key text not null,
    payload text not null,
    created_at timestamptz not null default now(),
    sent_at timestamptz
);
create index if not exists outbox_unsent on outbox (id) where sent_at is null;
