# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

TaskBoard is a real-time collaborative Kanban board (shared boards → columns → cards, drag-and-drop, live multi-user sync). The project is in early build-out: **read `project-scope.md` (the "what", including the full data model) and `implementation-plan.md` (the ordered "how") first** — they are the source of truth for the schema, milestone sequencing, and the design decisions summarized below. When implementing, follow the milestone order in `implementation-plan.md` (M0 scaffolding → M1 auth → M2 CRUD → M3 drag-and-drop → M4 roles → M5 real-time).

## Current state

- `server/` — Spring Boot backend. Currently a generated skeleton: `ServerApplication` (empty `@SpringBootApplication`), `application.properties` with only the app name, no entities/controllers/migrations yet.
- Frontend (planned: Next.js App Router) is **not yet scaffolded** — expected as a separate top-level app when M0 lands.

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

**Caveat:** JPA + Flyway + PostgreSQL are on the classpath but `application.properties` has **no datasource configured**. Until a Postgres datasource is set, the Spring context won't fully start, so `spring-boot:run` and the `contextLoads` test will fail. Configuring local Postgres (e.g. via docker-compose) and datasource properties is part of Milestone 0.

## Stack specifics that will bite if unknown

- **Spring Boot 3.5.16 on Java 21.** Standard Boot 3 starters (`spring-boot-starter-web`, `-data-jpa`, `-security`, `-validation`, `-websocket`), with `spring-boot-starter-test` + `spring-security-test` for tests. Flyway is `flyway-core` + `flyway-database-postgresql`; Postgres driver is `runtime` scope.
- **Base package is `org.cj.server`** (groupId `org.cj`). New code goes under here. The plan's package split (`auth/`, `board/`, `membership/`, `realtime/`, `common/`) should live under `org.cj.server`.
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
