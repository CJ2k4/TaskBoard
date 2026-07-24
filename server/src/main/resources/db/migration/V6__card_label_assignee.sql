-- Card enrichment: an optional free-text label ("chip") and an optional assignee.
--
-- assignee_id references app_user with ON DELETE SET NULL, mirroring board_activity.actor_id:
-- deleting a user un-assigns their cards rather than cascading the cards away. label is a short
-- free-text tag rendered as a chip; the 40-char cap matches the DTO's @Size guard.
ALTER TABLE card ADD COLUMN label varchar(40);
ALTER TABLE card ADD COLUMN assignee_id uuid REFERENCES app_user (id) ON DELETE SET NULL;
