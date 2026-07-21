-- V4 (M6) — the activity log: an append-only history of what happened on a board.
--
-- Rows are written by ActivityRecorder, which listens for the same BoardChangedEvent the
-- real-time broadcaster does, but persists BEFORE_COMMIT so an entry exists iff the change it
-- describes committed. The feed is read newest-first, board-scoped, by any member (VIEWER+).

CREATE TABLE board_activity (
    -- App-generated UUID PK, like every other table.
    id         uuid        PRIMARY KEY,
    -- The board this happened on. Cascades: deleting a board discards its history with it.
    board_id   uuid        NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    -- Who did it. SET NULL (not CASCADE) so history survives the actor's account being deleted —
    -- a log that erased itself when a user left would be a poor log. Nullable for that reason.
    actor_id   uuid                 REFERENCES app_user(id) ON DELETE SET NULL,
    -- The BoardEventType name (stored as a string, per the project's enum convention). No CHECK
    -- here on purpose: this is an audit sink, and only a subset of the enum is ever written; a
    -- CHECK would couple the table to enum churn and turn a new event type into a write failure
    -- on the mutation path rather than a quietly-unlogged event.
    type       varchar(32) NOT NULL,
    -- The rendered predicate, e.g. 'moved card "Design homepage"'. The actor's name is joined at
    -- read time (so it reflects a rename), not baked in here.
    summary    text        NOT NULL,
    created_at timestamptz NOT NULL
);
-- The feed's one access pattern: a board's entries, newest first.
CREATE INDEX idx_activity_board_created ON board_activity (board_id, created_at DESC);
