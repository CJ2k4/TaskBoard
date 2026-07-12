# TaskBoard — Learning Log

A step-by-step journal of **how** we build TaskBoard, written to teach end-to-end
development. Every entry explains _what_ we did, _why_ we did it that way, and the
_concepts_ worth looking up. Read it top-to-bottom to follow the build like a tutorial.

- **The "what"** (features, data model) lives in `project-scope.md`.
- **The "how" / ordered plan** lives in `implementation-plan.md`.
- **This file** is the narrated walkthrough of actually doing it.

> How to use this file: each step has a **Goal**, the **Files** it touched, a
> **Concepts** box (terms to Google), and a **Why** section. If you only read the
> Why sections, you'll still understand the shape of the app.

---

## The big picture (read this once)

TaskBoard is a real-time collaborative Kanban board. There are two apps:

```
Browser ──HTTP/WebSocket──▶  Backend (Spring Boot, Java)  ──JDBC──▶  Postgres (database)
 (Next.js frontend)            REST API + business logic              stores everything
```

- **Frontend** (Next.js) = what the user sees. Not built yet.
- **Backend** (Spring Boot) = the rules + the API. Lives in `server/`.
- **Database** (Postgres) = durable storage. Runs in Docker.

We build the backend and database first, get them solid, then add the frontend,
then make it multi-user/real-time. That order is deliberate: it's much easier to
add live sync on top of a correct single-user system than to debug both at once.

---

## Milestone 0 — Scaffolding & infra

**Goal of the whole milestone:** both apps boot, talk to each other over one trivial
endpoint, and the database is wired up. Nothing fancy — just proof the plumbing works.

### Step 0.1 — Run Postgres in Docker · _2026-07-12_

**Goal:** have a real PostgreSQL database running locally that the backend can talk to.

**Files:** `docker-compose.yml` (repo root)

> **Concepts:** container · image · Docker Compose · port mapping · volume ·
> healthcheck · environment variables

**What we did:** wrote a `docker-compose.yml` that starts one service, `postgres`,
from the official `postgres:16-alpine` image. Then:

```bash
docker compose up -d      # start it in the background ("detached")
docker compose ps         # see it running
docker compose down       # stop it (add -v to also delete the data)
```

**Why Docker instead of installing Postgres directly?**
A _container_ is a lightweight, throwaway box that runs one program with a known
version and config. Using one means every developer (and eventually the CI server)
gets the _exact_ same Postgres 16 with zero "works on my machine" drift, and you can
delete it cleanly without leaving junk on your laptop.

**The pieces of the compose file, explained:**

- `image: postgres:16-alpine` — which prebuilt program to run. `16` pins the version
  so it never silently upgrades under us; `alpine` is a tiny Linux base.
- `environment:` — Postgres reads these on first boot to create a database named
  `taskboard` with user/password `taskboard`/`taskboard`. (Fine for local dev; real
  secrets never get hardcoded like this in production.)
- `ports: "5432:5432"` — maps the container's internal port 5432 to your laptop's
  port 5432, so the backend can reach it at `localhost:5432`. Left side = your
  machine, right side = inside the container.
- `volumes: taskboard_pgdata:/var/lib/postgresql/data` — a _named volume_ keeps the
  actual data on disk **outside** the container, so restarting the container doesn't
  wipe your tables.
- `healthcheck:` — a command Docker runs repeatedly (`pg_isready`) to know when
  Postgres has finished starting and is actually accepting connections. Without this,
  the backend might try to connect a half-second too early and fail.

### Step 0.2 — Point the backend at the database · _2026-07-12_

**Goal:** tell Spring Boot how to find and log in to Postgres.

**Files:** `server/src/main/resources/application.properties`

> **Concepts:** JDBC · connection URL · connection pool (HikariCP) ·
> `application.properties` · environment-variable override with defaults

**What we added:**

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/taskboard}
spring.datasource.username=${DB_USERNAME:taskboard}
spring.datasource.password=${DB_PASSWORD:taskboard}
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

**Why each line:**

- **`spring.datasource.url`** — a _JDBC URL_ is the standard "address" format for a
  database: `jdbc:postgresql://HOST:PORT/DBNAME`. This is how Java's database layer
  knows which server and which database to open.
- **`${DB_URL:default}` syntax** — "use the `DB_URL` environment variable if it's set,
  otherwise fall back to this local default." This is the trick that lets the _same_
  code run on your laptop (defaults) and in production (env vars point at the real DB)
  without editing the file. **Never hardcode production credentials in source.**
- **`ddl-auto=validate`** — Hibernate (the library that maps Java objects to tables)
  _can_ auto-create tables from your Java classes, but we forbid that. `validate` means
  "only check that my Java classes match the tables that already exist; never change the
  schema yourself." We want migrations (next step) to be the _single_ source of truth for
  the schema — predictable and reviewable. See the Flyway step for why this matters.
- **`open-in-view=false`** — turns off a Spring default that keeps a database session
  open during view rendering. It's a well-known footgun (hidden slow queries); off is the
  professional default.
- **`hibernate.jdbc.time_zone=UTC`** — always store/read timestamps in UTC so users in
  different timezones never see skewed times. Decide this on day one; retrofitting is painful.

**What's a connection pool (HikariCP)?** Opening a fresh database connection is slow, so
Spring keeps a small _pool_ of open connections and reuses them. You saw `HikariPool-1 -
Start completed` in the logs — that's the pool warming up. You get this for free; just
know the term.

### Step 0.3 — Own the schema with Flyway migrations · _2026-07-12_

**Goal:** make every change to the database structure an explicit, version-controlled,
ordered SQL file.

**Files:** `server/src/main/resources/db/migration/V1__baseline.sql`

> **Concepts:** database migration · schema versioning · Flyway naming convention
> (`V<version>__<name>.sql`) · `flyway_schema_history` table · idempotent startup

**What we did:** created an intentionally _empty_ first migration, `V1__baseline.sql`.
It contains only comments. Its job is just to establish the migration history so future
milestones add real tables as `V2`, `V3`, ….

**Why migrations at all?** As the app grows you'll add tables, columns, indexes. You need
those changes to apply **in the same order, exactly once, on every environment** (your
laptop, a teammate's, staging, production). Flyway does this: on startup it looks in
`db/migration/`, sees which `V#` files have already run (recorded in a table it creates
called `flyway_schema_history`), and applies any new ones in order. Run the app ten times
and V1 still only applies once — that's _idempotent_.

**Why not let Hibernate auto-create the tables (`ddl-auto=update`)?** Because that's a
black box: you can't review it, it can't express things like renames or data backfills,
and it behaves differently across versions. Hand-written migrations are code you read in a
pull request. This is why Step 0.2 set `ddl-auto=validate` — the two decisions are a pair:
**Flyway builds the schema, Hibernate only checks its Java classes agree with it.**

**The `V1__baseline.sql` naming rule:** `V` + version number + **two** underscores + a
description. Flyway parses the version out of the filename, so the `__` matters.

### Step 0.4 — Verify the plumbing · _2026-07-12_

**Goal:** prove the backend can actually start and reach the database — before writing any
features on top.

**Files:** `server/src/test/java/org/cj/server/ServerApplicationTests.java` (already existed)

> **Concepts:** integration/"context loads" test · fail-fast verification

**What we did:** ran the one existing test:

```bash
cd server
./mvnw test -Dtest=ServerApplicationTests
```

It passed (`BUILD SUCCESS`). The logs showed the whole chain working: Hikari connected to
`PostgreSQL 16.14`, Flyway created its history table and applied `v1 - baseline`, and JPA
started cleanly.

**Why this tiny test matters.** `contextLoads()` looks like it does nothing, but starting
the full Spring application _is_ the test: it only succeeds if the datasource is
configured, Postgres is reachable, and migrations run. This was previously **failing**
(there was no datasource), so getting it green is real, verified progress — the classic
"make it boot before you build on it" checkpoint. Verifying the plumbing before adding
features is a habit worth keeping: when something breaks later, you know the foundation was
solid.

---

### Where M0 stands

Done: ✅ Postgres running · ✅ backend connected · ✅ migrations wired · ✅ boot verified

Still to do in M0:

- [ ] `GET /api/health` endpoint returning `{status:"ok"}` (our first real API route)
- [ ] Security config so that endpoint is publicly reachable (Spring Security currently
      locks everything by default — you'll learn why the app printed a random password)
- [ ] CORS config so the browser frontend is allowed to call the backend
- [ ] Global exception handler (one consistent JSON shape for all errors)
- [ ] Scaffold the Next.js `frontend/` and have a page fetch `/api/health`

**Demo we're driving toward:** open a web page, see the backend's health status. That's the
"hello world" that proves both apps and the database all talk to each other.

---

## Quick command reference

```bash
# Database (run from repo root)
docker compose up -d        # start Postgres
docker compose ps           # is it running / healthy?
docker compose logs -f      # watch its logs
docker compose down         # stop it (keep data)
docker compose down -v      # stop it AND delete all data (fresh start)

# Backend (run from server/)
./mvnw test                             # run all tests
./mvnw test -Dtest=ServerApplicationTests   # run one test class
./mvnw spring-boot:run                  # run the backend locally
./mvnw clean package                    # compile + test + build a jar
```
