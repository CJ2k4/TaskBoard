# TaskBoard — Implementation Plan

Companion to `project-scope.md`. Ordered, milestone-based build plan. Each milestone is independently demoable and builds on the previous one.

## Guiding principles

- **Vertical slices.** Every milestone ends with something you can run and see, not a half-wired layer.
- **Single-user correct before multi-user.** Get CRUD + drag-and-drop persistent and solid before adding WebSockets. Real-time on top of a broken data model just multiplies bugs.
- **Server authoritative on ordering** from day one, even in the single-user path — so nothing changes when real-time arrives.

## Repo structure

Single **mono-repo**, two top-level apps:

```
TaskBoard/
├── backend/            # Spring Boot (Maven or Gradle)
│   └── src/main/java/com/taskboard/
│       ├── auth/           # security config, JWT, login/register
│       ├── board/          # Board, Column, Card entities + controllers/services
│       ├── membership/     # invites, roles, access checks
│       ├── realtime/       # WebSocket/STOMP config + event publishing
│       └── common/         # ranking util, error handling, config
│   └── src/main/resources/db/migration/   # Flyway SQL
├── frontend/           # Next.js (App Router)
│   ├── app/
│   ├── components/
│   ├── lib/            # api client, stomp client, auth helpers
│   └── ...
├── project-scope.md
└── implementation-plan.md
```

Rationale: one repo keeps the API contract and the two apps in lockstep during early churn; they still deploy independently.

---

## Milestone 0 — Scaffolding & infra

Goal: both apps boot, talk to each other with one trivial endpoint, and the DB is wired.

- [ ] `backend/`: Spring Boot project (Spring Web, Spring Data JPA, Spring Security, Validation, WebSocket, PostgreSQL driver, Flyway). Pick Maven **or** Gradle now.
- [ ] Local Postgres via `docker-compose.yml` (Postgres + optional pgAdmin).
- [ ] Flyway baseline migration (empty or `users` table).
- [ ] Global exception handler + consistent JSON error shape.
- [ ] CORS config allowing the Next.js origin.
- [ ] Health endpoint `GET /api/health` → `{status:"ok"}`.
- [ ] `frontend/`: Next.js app, an API client in `lib/`, a page that calls `/api/health` and renders the result.

**Demo:** open the Next.js page, see backend health status. Both apps run locally.

---

## Milestone 1 — Auth

Goal: a user can register, log in, and hit an authenticated endpoint. Depends on M0.

- [ ] `User` entity + migration (`id`, `email` unique, `passwordHash` nullable, `name`, `imageUrl`, `createdAt`).
- [ ] Password hashing (BCrypt via Spring Security).
- [ ] `POST /api/auth/register`, `POST /api/auth/login` → returns access + refresh JWT.
- [ ] `POST /api/auth/refresh`.
- [ ] JWT filter + Spring Security config; `GET /api/me` returns the current user.
- [ ] Google OAuth login (can be deferred to end of milestone if it slows things — email/password unblocks everything else).
- [ ] Frontend: register/login pages, token storage (httpOnly cookie preferred; or memory + refresh), auth context, protected-route redirect, `/api/me` wired.

**Demo:** register → log in → land on an empty authenticated dashboard.

---

## Milestone 2 — Board / Column / Card CRUD (single-user)

Goal: the owner can build and edit a board's structure. Depends on M1.

- [ ] Entities + migrations: `Board`, `board_column`, `Card` (note reserved-word table name), plus `BoardMembership` with the owner auto-added as `OWNER`/`ACTIVE` on board creation.
- [ ] Ranking utility (`common/`): `rankBetween(prev, next)` LexoRank-style. Unit-test it hard — this is the sharp edge.
- [ ] REST:
  - Boards: `POST/GET/PATCH/DELETE /api/boards`, `GET /api/boards/{id}` (returns board + columns + cards in rank order).
  - Columns: `POST /api/boards/{id}/columns`, `PATCH`, `DELETE` (block if non-empty).
  - Cards: `POST /api/columns/{id}/cards`, `PATCH`, `DELETE`.
- [ ] All writes scoped to the owner for now (membership-role enforcement lands in M4).
- [ ] Frontend: board list, board detail view rendering columns/cards, create/rename/delete UI for each.

**Demo:** create a board, add columns and cards, reload — everything persists in correct order.

---

## Milestone 3 — Drag-and-drop + ordering

Goal: reorder cards and columns by dragging; order persists. Depends on M2.

- [ ] Move endpoints expressed as **intent**, server computes canonical rank:
  - `PATCH /api/cards/{id}/move` body `{ targetColumnId, beforeCardId?, afterCardId? }`.
  - `PATCH /api/columns/{id}/move` body `{ beforeColumnId?, afterColumnId? }`.
- [ ] Server computes new `rank` via `rankBetween`, updates `columnId` on cross-column moves, returns the resolved rank.
- [ ] Frontend: `@dnd-kit` for card + column dragging; optimistic local move, then reconcile to the server's returned rank.
- [ ] Handle the rank-exhaustion edge case (ranks too close) with a column re-balance routine.

**Demo:** drag cards within/between columns and reorder columns; reload — order holds.

---

## Milestone 4 — Sharing, invites & role enforcement

Goal: multiple real users on one board, with correct permissions. Depends on M2 (M3 not required).

- [ ] `POST /api/boards/{id}/invites` (owner only) — creates a `BoardMembership` (`PENDING` + `invitedEmail`, or `ACTIVE` if the user already exists).
- [ ] On login/register, resolve pending invites matching the user's email → attach `userId`, set `ACTIVE`.
- [ ] `GET /api/boards/{id}/members`, `PATCH` role, `DELETE` member (owner only).
- [ ] Replace owner-only checks with **membership-role checks** via `@PreAuthorize`/service guards: `VIEWER` read-only, `EDITOR` mutate cards/columns, `OWNER` manage board + members.
- [ ] `GET /api/boards` returns boards where the user is any active member, not just owner.
- [ ] Frontend: invite-by-email UI, member list with roles, read-only rendering for viewers.

**Demo:** owner invites a second account as editor; they sign in and see/edit the board. A viewer can see but not edit.

---

## Milestone 5 — Real-time (WebSocket)

Goal: changes appear live for everyone on the board. Depends on M3 + M4.

- [ ] `spring-boot-starter-websocket` + STOMP config; authenticate the WS handshake with the JWT.
- [ ] Subscription authorization: a client may only subscribe to `/topic/board/{id}` if they're an active member.
- [ ] After each successful mutation (create/move/edit/delete of column/card, membership changes), publish the change event to that board's topic **after DB commit**.
- [ ] Conflict policy in effect: server-authoritative ordering (already true from M3), last-write-wins on field edits keyed by `updatedAt`.
- [ ] Frontend: STOMP client, subscribe on board open, apply incoming events to local state, unsubscribe on leave. On reconnect, refetch the full board snapshot.

**Demo:** two browsers on one board; edits/moves in one appear in the other within ~1s; simultaneous moves resolve to one consistent order.

---

## Milestone 6 — Stretch (post-MVP)

Pull in only after M0–M5 are solid.

- [ ] Presence indicators (who's viewing) — piggyback on the WS connection.
- [ ] Activity log (append-only event table + feed UI).
- [ ] Optimistic updates with rollback on server rejection.
- [ ] "Copy invite link" sharing flow.

---

## Cross-cutting (do continuously, not as a final phase)

- **Testing**: unit-test the ranking util exhaustively; controller/integration tests per milestone (Testcontainers for Postgres). At least one frontend e2e (Playwright) covering login → create board → drag card.
- **Validation & errors**: Bean Validation on all request DTOs; never trust client-supplied ranks.
- **Deployment**: Dockerize the backend early (end of M1). Managed Postgres + backend on Railway/Render/Fly; frontend on Vercel. Wire env-based config (DB URL, JWT secret, allowed origins) from the start.

## Critical path & risk notes

- **Ranking is the highest-risk unit.** Build and test it in M2 before any UI depends on it.
- **Do M4 (roles) before M5 (real-time).** Enforcing permissions after opening the WebSocket firehose is much harder to get right.
- **Auth touches everything.** A shaky JWT/refresh setup will bleed into every later milestone — get M1 genuinely solid before moving on.
- Suggested slice for a first end-to-end demo: M0 → M1 → M2 → M3 (single-user, fully working board). Ship/validate that before layering collaboration (M4 → M5).
