-- Deleting a card no longer removes the row: it moves to the board's bin, where anyone with
-- edit rights can restore it. A retention job purges cards once they have sat in the bin for
-- longer than the retention window, so the guarantee is "restorable for at least 2 days".
--
-- deleted_by is the actor, kept so the bin can say who binned it. ON DELETE SET NULL matches
-- board_activity.actor_id: deleting a user must never delete board content they touched.
ALTER TABLE card
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by uuid REFERENCES app_user(id) ON DELETE SET NULL;

-- Every board and column read now filters on `deleted_at IS NULL`. A partial index covers only
-- live cards, so the hot path (loading a board) doesn't pay for rows sitting in the bin.
CREATE INDEX idx_card_board_live ON card (board_id) WHERE deleted_at IS NULL;

-- The mirror image: the bin listing (newest first) and the retention purge both look only at
-- binned rows, which are the rare ones.
CREATE INDEX idx_card_binned ON card (board_id, deleted_at DESC) WHERE deleted_at IS NOT NULL;
