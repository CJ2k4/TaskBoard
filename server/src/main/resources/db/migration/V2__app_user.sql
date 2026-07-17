-- V2 (M1) — the user account table.
--
-- Named "app_user", not "user": USER is a reserved word in SQL/Postgres, so a
-- table literally called user forces awkward quoting everywhere. The JPA entity
-- maps to this name explicitly (@Table(name = "app_user")).
--
-- password_hash is NULLABLE on purpose: OAuth-only accounts (Google) never set a
-- password. Email/password accounts always have a BCrypt hash here.

CREATE TABLE app_user (
    id            uuid         PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100),
    name          varchar(120) NOT NULL,
    image_url     text,
    created_at    timestamptz  NOT NULL
);

-- Email is our login key and we store it lowercased, so the UNIQUE constraint
-- above already enforces "one account per email". No extra index needed —
-- a UNIQUE constraint is backed by an index Postgres uses for lookups.
