-- V5 (M6) — the shareable invite link: a rotatable token an owner can hand out so anyone
-- signed-in who opens it joins the board at a preset role.
--
-- One link per board, held right on the board row (not a separate table): the scale is tiny and
-- a board never needs two live links at once. Both columns null = no active link. Rotating =
-- writing a fresh token; disabling = setting both back to null. Redeeming never grants OWNER, so
-- the role is constrained to the two invitable roles.

ALTER TABLE board ADD COLUMN invite_token uuid;
ALTER TABLE board ADD COLUMN invite_link_role varchar(16)
    CHECK (invite_link_role IN ('EDITOR', 'VIEWER'));

-- The token is looked up on redeem and must be unguessably unique. A unique index over a
-- nullable column is exactly right: Postgres treats NULLs as distinct, so the many boards with
-- no link don't collide, while any two real tokens must differ.
CREATE UNIQUE INDEX uq_board_invite_token ON board (invite_token);
