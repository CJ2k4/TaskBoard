-- V3 (M2) — the board graph: board, membership, columns, cards.
--
-- One migration for the whole M2 data model (V1's comment reserved M2 for "the board graph").
-- Ordering is by LexoRank string `rank` columns (see common/ranking/LexoRank). UUID PKs are
-- app-generated; all timestamps are timestamptz (UTC). Enums are stored as varchar guarded by
-- CHECK constraints, never ordinals. Cascade rules follow project-scope.md § Data.

-- A board. owner_id is RESTRICT: you can't delete a user who still owns boards (reassign or
-- delete the board first). Everything hanging off a board cascades when the board is deleted.
CREATE TABLE board (
    id         uuid         PRIMARY KEY,
    name       varchar(160) NOT NULL,
    owner_id   uuid         NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);
CREATE INDEX idx_board_owner_id ON board (owner_id);

-- Who can access a board and at what role. user_id is null while an email invite is pending
-- (M4); M2 only ever creates the owner's own ACTIVE/OWNER row.
CREATE TABLE board_membership (
    id            uuid         PRIMARY KEY,
    board_id      uuid         NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    user_id       uuid                  REFERENCES app_user(id) ON DELETE CASCADE,
    invited_email varchar(255),
    role          varchar(16)  NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    status        varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'PENDING')),
    created_at    timestamptz  NOT NULL,
    -- A user has at most one membership per board. (NULLs are distinct in Postgres, so many
    -- pending invites with null user_id are allowed — those are deduped by the partial index.)
    CONSTRAINT uq_membership_board_user UNIQUE (board_id, user_id),
    -- Every row must identify someone: an existing user or a pending email invite.
    CONSTRAINT ck_membership_identity CHECK (user_id IS NOT NULL OR invited_email IS NOT NULL)
);
-- No duplicate pending invites for the same email on a board.
CREATE UNIQUE INDEX uq_membership_pending_invite
    ON board_membership (board_id, invited_email) WHERE status = 'PENDING';
CREATE INDEX idx_membership_user_id ON board_membership (user_id);
CREATE INDEX idx_membership_invited_email ON board_membership (invited_email);

-- A column on a board. "board_column" because COLUMN is a SQL reserved word.
CREATE TABLE board_column (
    id         uuid         PRIMARY KEY,
    board_id   uuid         NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    title      varchar(160) NOT NULL,
    rank       varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);
CREATE INDEX idx_column_board_id ON board_column (board_id);

-- A card. board_id is denormalized (a card also references its board directly, not only via
-- its column) so board-scoped loads/auth checks don't need to join through columns. It must be
-- kept in sync on cross-column moves (M3) — the target column must belong to the same board.
CREATE TABLE card (
    id          uuid         PRIMARY KEY,
    column_id   uuid         NOT NULL REFERENCES board_column(id) ON DELETE CASCADE,
    board_id    uuid         NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    title       varchar(280) NOT NULL,
    description text,
    rank        varchar(64)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);
CREATE INDEX idx_card_column_id ON card (column_id);
CREATE INDEX idx_card_board_id ON card (board_id);
