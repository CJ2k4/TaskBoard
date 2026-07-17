# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

TaskBoard is a real-time collaborative Kanban board (shared boards → columns → cards, drag-and-drop, live multi-user sync). The project is in early build-out: **read `project-scope.md` (the "what", including the full data model) and `implementation-plan.md` (the ordered "how") first** — they are the source of truth for the schema, milestone sequencing, and the design decisions summarized below. When implementing, follow the milestone order in `implementation-plan.md` (M0 scaffolding → M1 auth → M2 CRUD → M3 drag-and-drop → M4 roles → M5 real-time).

## Current state

**M0 and M1 (backend) are done.** M1's frontend half (login/register pages, auth context, protected routes) is the next piece of work.

- `server/` — Spring Boot backend. Postgres wired via `docker-compose.yml`, Flyway migrations `V1__baseline` (empty) + `V2__app_user`. Auth is complete: register/login/refresh returning JWTs, a JWT filter, and `GET /api/me`. Also `GET /api/health` (M0), a global exception handler, and CORS for the Next.js origin.
- `frontend/` — Next.js App Router app (`src/app`, `src/lib`). Currently renders the backend health status; no auth UI yet.
- Tokens are returned **in the response body** (access + refresh), not httpOnly cookies — the frontend keeps the access token in memory and calls `/api/auth/refresh`. Google OAuth is deliberately deferred (`app_user.password_hash` is nullable to leave room for it).

## Backend commands (run from `server/`)

Requires a **JDK 21** on `JAVA_HOME` (`pom.xml` pins `java.version=21`); the build fails on older JDKs. Uses the Maven wrapper — always `./mvnw`, not a system `mvn`.

```bash
./mvnw spring-boot:run                       # run the app locally
./mvnw clean package                         # compile + test + build jar
./mvnw test                                  # run all tests
./mvnw test -Dtest=ClassName                 # single test class
./mvnw test -Dtest=ClassName#methodName      # single test method
./mvnw clean package -DskipTests             # build without tests
```

**Postgres must be running** (`docker compose up -d` from the repo root) or the Spring context won't start — `spring-boot:run`, `contextLoads`, and `AuthIntegrationTest` all need a live datasource. `AuthIntegrationTest` boots the whole app and drives every auth endpoint, so a green `./mvnw test` is a strong signal the wiring is intact; it's the safety net to lean on when refactoring.

## Stack specifics that will bite if unknown

- **Spring Boot 3.5.16 on Java 21.** Standard Boot 3 starters (`spring-boot-starter-web`, `-data-jpa`, `-security`, `-validation`, `-websocket`), with `spring-boot-starter-test` + `spring-security-test` for tests. Flyway is `flyway-core` + `flyway-database-postgresql`; Postgres driver is `runtime` scope.
- **Base package is `org.cj.server`** (groupId `org.cj`). New code goes under here. The plan's package split (`auth/`, `board/`, `membership/`, `realtime/`, `common/`) lives under `org.cj.server`.
- **Package layout is feature-first, then layered inside each feature.** Follow the shape `auth/` already sets — `board/` and `membership/` must mirror it rather than inventing their own:

  ```
  org.cj.server.auth
  ├── entity/       User                     (JPA entities)
  ├── repository/   UserRepository           (Spring Data)
  ├── dto/          RegisterRequest, AuthResponse, TokenPair, …
  ├── service/      AuthService, JwtService  (business logic, HTTP-agnostic)
  ├── controller/   AuthController, MeController
  └── security/     SecurityConfig, JwtAuthenticationFilter, AuthPrincipal
  ```

  `common/` follows the same idea (`exception/`, `dto/`, `controller/`). Everything stays under `org.cj.server`, so `@SpringBootApplication` component-scans it all with no extra config.

  **Dependency direction:** `controller → service → repository → entity`, with `dto` as the shared vocabulary. **A `dto` must never import a `service`** — that's why the token pair is `dto/TokenPair` rather than a record nested in `JwtService`. Mapping DTO↔entity via a static factory on the DTO (`UserResponse.from(user)`) is the accepted pattern here; don't add a `mapper/` layer at this scale.
- **Database migrations are Flyway** — schema changes go in `src/main/resources/db/migration/` as versioned SQL, never via `ddl-auto`.

## Architecture decisions to respect (from the scope docs)

These are already decided — implement to them rather than re-deriving. The full column-level schema (types, PKs, FKs, constraints, indexes) lives in `project-scope.md` under `## Data`.

- **Ordering / drag-and-drop uses fractional rank strings** (LexoRank-style), stored as a `rank varchar` on Column and Card. Moves are expressed as *intent* (`move card before/after X`); the **server computes the canonical rank and is authoritative** — clients reconcile to the server's result. This holds even in the single-user path so nothing changes when real-time arrives. The ranking utility (`rankBetween`) is the highest-risk unit — unit-test it exhaustively.
- **Access control is per-board membership** (`board_membership` with roles `OWNER`/`EDITOR`/`VIEWER`, status `ACTIVE`/`PENDING`). Enforce roles server-side from the start (Spring Security `@PreAuthorize` / service guards), not later. Viewers are read-only.
- **Auth is JWT (stateless)** via Spring Security — access + refresh tokens, since the Next.js frontend is a separate origin.
- **Real-time is native Spring WebSocket + STOMP** (`/topic/board/{id}`), no external pub/sub. Publish change events **after DB commit**. Conflict policy: server-authoritative ordering + last-write-wins on field edits keyed by `updated_at`. Build roles (M4) before real-time (M5).
- **Reserved-word table names:** `User` → table `app_user`, `Column` → table `board_column`. Use these mappings on the JPA entities.
- **Enums stored as strings** (`@Enumerated(EnumType.STRING)` + a DB `CHECK` constraint), never ordinals.
- **UUID primary keys, app-generated** (not DB serials). Timestamps are `timestamptz` / `Instant`, UTC.
- **`card.board_id` is denormalized** (a card also references its board directly, not only via its column) for board-scoped auth checks and whole-board loads — keep it in sync on cross-column moves.
- Scale target is small (≤10 concurrent users per board); do not over-engineer the real-time layer.
