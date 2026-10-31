create table if not exists users (
    username text primary key,
    password_hash text not null,
    created_at timestamptz not null default now()
);
