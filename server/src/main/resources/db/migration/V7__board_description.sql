-- A short board description, shown on the dashboard overview cards beneath the board name.
-- Nullable and free-text; the 280-char cap matches the DTO's @Size guard.
ALTER TABLE board ADD COLUMN description varchar(280);
