## problem

When a number of people work in a team, they lose track of what any member is doing, they lose track of work across scattered tools

## solution

create a shared board where multiple users see updates live solves a genuine coordination problem

## User

Anyone who is working in a team, on a project, on a startup, on any assignment.

## Feature

MVP: Auth, create boards/columns/cards, drag-and-drop persistence, single-user CRUD via REST.
Add real-time: WebSocket layer so multiple users on the same board see live changes.
Stretch: presence indicators (who's viewing), activity log, optimistic updates with rollback.

### Auth (MVP)

- Email + password to start, plus one OAuth provider (Google) — most team users already have a Google account.
- **JWT-based (stateless)** via Spring Security, since the frontend is a separate SPA/origin: short-lived access token + refresh token. Fits Spring cleanly and avoids server-side session storage.

### Sharing & access (MVP — not a stretch item)

A board is only useful if the right people can get onto it, so this ships in the MVP:

- **Invite by email**: board owner enters an email → the invitee gets access on next sign-in (or an invite link if they have no account yet).
- **Roles per board** (membership row, see Data):
  - `owner` — full control, can delete the board and manage members.
  - `editor` — create/move/edit/delete columns and cards.
  - `viewer` — read-only; sees live updates but cannot edit.
- No public/anonymous boards in v1 — every viewer must be signed in.
- Enforced server-side with Spring Security method-level checks (`@PreAuthorize`) against the requester's membership role.

### Real-time (milestone 2)

- Live sync of card/column create, move, edit, delete to all connected members of a board.
- **Transport**: Spring's native WebSocket support (`spring-boot-starter-websocket`) with STOMP. One topic per board, e.g. `/topic/board/{boardId}`; clients subscribe to their board's topic, the REST write path publishes the change after commit. No external pub/sub service needed — Spring Boot holds the connections itself.
- **Basic conflict handling belongs here, not in "stretch":** the moment two editors share a board, simultaneous drags/reorders collide. v1 policy:
  - **Server is authoritative on ordering.** Clients send intent ("move card X after card Y"); server computes the canonical rank and broadcasts it. Clients reconcile to the server's result.
  - **Last-write-wins on field edits** (title/description), keyed on `updated_at`.
- **Reconnect**: on socket reconnect the client refetches the full board snapshot rather than replaying missed events (simpler and correct for v1 scale).

## Data

> Ordering approach: **fractional/rank-based ordering** (LexoRank-style string ranks). Inserting between two cards computes a rank between their neighbors — no bulk re-indexing of siblings on every drag, and it survives concurrent inserts better than integer positions. This is the key drag-and-drop decision.

JPA entities (Spring Data JPA + Hibernate), Postgres. Conventions used below:

- **Types are Postgres column types.** JPA/Java mapping in parentheses where non-obvious.
- Primary keys are `uuid` generated app-side (`UUID.randomUUID()` / Hibernate `@GeneratedValue`), not DB serials — avoids round-trips and is safe for client-optimistic creates.
- All timestamps are `timestamptz` (Java `Instant`), UTC.
- Enums stored as `varchar` via `@Enumerated(EnumType.STRING)` (not ordinals — reordering the Java enum must never corrupt data), guarded by a `CHECK` constraint.
- FKs are `ON DELETE CASCADE` unless noted; see cascade rules below.

### Entities

**User** — table `app_user` (`user` is a SQL reserved word)

| Column | Type | Key / Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | **PK** | app-generated |
| `email` | `varchar(255)` | **UNIQUE**, NOT NULL | store lowercased; citext optional |
| `password_hash` | `varchar(100)` | NULL | BCrypt hash; NULL for OAuth-only accounts |
| `name` | `varchar(120)` | NOT NULL | |
| `image_url` | `text` | NULL | OAuth avatar |
| `created_at` | `timestamptz` | NOT NULL | |

**Board**

| Column | Type | Key / Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | **PK** | |
| `name` | `varchar(160)` | NOT NULL | |
| `owner_id` | `uuid` | **FK → app_user(id)**, NOT NULL, `ON DELETE RESTRICT` | can't delete a user who still owns boards |
| `created_at` | `timestamptz` | NOT NULL | |
| `updated_at` | `timestamptz` | NOT NULL | |

Index: `idx_board_owner_id (owner_id)`.

**BoardMembership** — table `board_membership`; who can access a board and at what role

| Column | Type | Key / Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | **PK** | |
| `board_id` | `uuid` | **FK → board(id)**, NOT NULL, `ON DELETE CASCADE` | |
| `user_id` | `uuid` | **FK → app_user(id)**, NULL, `ON DELETE CASCADE` | NULL while an email invite is pending |
| `invited_email` | `varchar(255)` | NULL | set for pending invites before the user exists |
| `role` | `varchar(16)` | NOT NULL, `CHECK (role IN ('OWNER','EDITOR','VIEWER'))` | |
| `status` | `varchar(16)` | NOT NULL, `CHECK (status IN ('ACTIVE','PENDING'))` | |
| `created_at` | `timestamptz` | NOT NULL | |

Constraints/indexes:
- `UNIQUE (board_id, user_id)` — a user has at most one membership per board.
- Partial unique: `UNIQUE (board_id, invited_email) WHERE status = 'PENDING'` — no duplicate pending invites.
- `CHECK (user_id IS NOT NULL OR invited_email IS NOT NULL)` — a row identifies someone.
- Index `idx_membership_user_id (user_id)` for "boards I belong to" queries; `idx_membership_invited_email (invited_email)` to resolve invites on login.

**Column** — table `board_column` (`column` is a SQL reserved word)

| Column | Type | Key / Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | **PK** | |
| `board_id` | `uuid` | **FK → board(id)**, NOT NULL, `ON DELETE CASCADE` | |
| `title` | `varchar(160)` | NOT NULL | |
| `rank` | `varchar(64)` | NOT NULL | LexoRank string; position among columns in the board |
| `created_at` | `timestamptz` | NOT NULL | |
| `updated_at` | `timestamptz` | NOT NULL | optimistic last-write-wins key |

Index: `idx_column_board_id (board_id)` (query columns of a board in `rank` order).

**Card**

| Column | Type | Key / Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | **PK** | |
| `column_id` | `uuid` | **FK → board_column(id)**, NOT NULL, `ON DELETE CASCADE` | changes on cross-column move |
| `board_id` | `uuid` | **FK → board(id)**, NOT NULL, `ON DELETE CASCADE` | denormalized for fast board-scoped queries & auth checks |
| `title` | `varchar(280)` | NOT NULL | |
| `description` | `text` | NULL | |
| `rank` | `varchar(64)` | NOT NULL | LexoRank string; position within its column |
| `created_at` | `timestamptz` | NOT NULL | |
| `updated_at` | `timestamptz` | NOT NULL | optimistic last-write-wins key |

Index: `idx_card_column_id (column_id)` (list a column's cards in `rank` order); `idx_card_board_id (board_id)`.

> **Denormalization note:** `card.board_id` duplicates `card → column → board`. Keep it in sync on cross-column moves (the target column must belong to the same board — enforce server-side). It exists so board-scoped auth checks and the "load whole board" query don't need to join through columns.

### Relationships

- **User 1—N Board** (as owner, via `board.owner_id`) and **User N—M Board** via `board_membership`.
- **Board 1—N Column** (`board_column.board_id`); **Column 1—N Card** (`card.column_id`); **Board 1—N Card** directly (`card.board_id`, denormalized).
- Moving a card = update `column_id` + `rank`. Moving a column = update `rank`.

### Integrity & ordering rules

- **Cascade:** deleting a Board cascades (DB-level `ON DELETE CASCADE`) to its Columns, Cards, and Memberships. Deleting a User cascades to their Memberships but is **RESTRICTed** if they still own any Board (reassign or delete the board first).
- **Column delete:** v1 = **block if it has cards** (enforced in the service layer — force the user to empty/move first) to avoid accidental data loss, even though the DB FK would cascade.
- **Ranks are not unique at the DB level;** the server resolves collisions by computing a fresh midpoint rank. `rank` is `NOT NULL` and always server-assigned — never trust a client-supplied rank.
- **Timestamps:** `created_at` set once on insert; `updated_at` bumped on every mutation and used as the last-write-wins key for real-time conflict resolution.

## Non-goals (out of scope for v1)

Explicitly NOT building, to keep scope contained:

- Comments, mentions, attachments/file upload on cards.
- Notifications (email/push) and activity feed (activity log is a later stretch).
- Labels, due dates, checklists, sub-tasks, card assignees (can be fast-follows).
- Alternate board views (calendar, timeline/Gantt), search across boards.
- Mobile native app — responsive web only.
- Offline mode / full CRDT conflict resolution.
- Public/shareable read-only board links.

## Tech & architecture

- **Backend**: **Spring Boot (Java)** — Spring Web (REST), Spring Data JPA + Hibernate, Spring Security (JWT), `spring-boot-starter-websocket` (STOMP) for real-time. Build with Maven or Gradle.
- **Database**: Postgres. Schema migrations via **Flyway** (or Liquibase).
- **Frontend**: **Next.js (App Router)** with `@dnd-kit` for drag-and-drop, talking to the Spring API over REST + a STOMP WebSocket client. Deployed separately from the backend.
- **Real-time**: native Spring WebSocket/STOMP — no external pub/sub service, because Spring Boot runs as a persistent server (unlike serverless) and holds the connections directly.
- **Deployment**: containerize the Spring app (Docker); deploy to a persistent host (Railway / Render / Fly.io / AWS). Managed Postgres alongside. Frontend deployed separately (e.g. Vercel/Netlify) or served as static assets.

## Success criteria

- **MVP done**: a signed-in user can create a board, add columns/cards, drag-and-drop them, reload the page, and see the same layout persisted. A second invited editor can see the same board.
- **Real-time done**: two browsers on the same board see each other's create/move/edit/delete within ~1s, and simultaneous card moves resolve to a single consistent order on both without manual refresh.

## Decisions (confirmed)

- **Frontend**: Next.js (App Router).
- **Ordering**: fractional/rank-based (LexoRank-style).
- **Sharing**: invite-by-email for v1. "Copy invite link" flow deferred to a later iteration.
- **Roles**: owner/editor/viewer, enforced from the start.
- **Scale**: ≤ 10 concurrent users per board — keeps WebSocket fan-out and ordering contention trivial; no special tuning needed for v1.
