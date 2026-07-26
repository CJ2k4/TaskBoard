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

### Step 0.5 — First API route: `GET /api/health` (+ security & CORS) · _2026-07-12_

**Goal:** expose one real HTTP endpoint the frontend can call, and configure the two
gatekeepers every browser API must get past: **authentication** (Spring Security) and
**CORS**.

**Files:**

- `server/src/main/java/org/cj/server/common/HealthController.java`
- `server/src/main/java/org/cj/server/auth/SecurityConfig.java`
- `server/src/main/java/org/cj/server/ServerApplication.java` (one annotation tweak)

> **Concepts:** REST controller · `@RestController` / `@GetMapping` · JSON serialization
> (Jackson) · Spring Security `SecurityFilterChain` · authentication vs authorization ·
> stateless API · CSRF · CORS (same-origin policy, preflight `OPTIONS`) · auto-configuration

**The controller.** `@RestController` + `@GetMapping("/api/health")` maps that URL to a
method. It returns a small Java `record`, and Spring's JSON library (Jackson) turns that
into `{"status":"ok"}` automatically. That record→JSON conversion is the pattern every
endpoint we build will use.

**Why we needed a `SecurityConfig` at all.** Spring Security is on the classpath, and its
default is _paranoid_: **every** endpoint is locked and it invents a random login password
at startup (that WARN line from earlier). That's a safe default, but useless for a JSON API.
So we defined our own `SecurityFilterChain` bean that says:

- `/api/health` → `permitAll()` (public)
- everything else → `authenticated()` (locked until real auth exists in M1)

Two supporting decisions, both standard for a token API:

- **CSRF disabled** — CSRF attacks abuse browser session _cookies_; a stateless token API
  doesn't use those, so the protection is irrelevant and would just block our POSTs later.
- **`SessionCreationPolicy.STATELESS`** — the server keeps no session/memory of you between
  requests; every request must prove who it is on its own. This is the foundation JWT builds
  on in M1.

> **authentication vs authorization** — two different questions. *Authentication* = "who are
> you?" (login). *Authorization* = "are you allowed to do this?" (roles/permissions). M0 has
> neither real one yet; `permitAll`/`authenticated` are the simplest possible rules.

**Why CORS, and what it is.** Browsers enforce the **same-origin policy**: JavaScript on
`http://localhost:3000` (our frontend) is _not_ allowed to call `http://localhost:8080`
(our backend) unless the backend explicitly says "I allow that origin." That permission
system is **CORS**. Our `CorsConfigurationSource` bean whitelists the Next.js origin and the
HTTP methods we'll use. For anything non-trivial the browser first sends a **preflight**
`OPTIONS` request asking permission — you can see it succeed in the test below. (Note: CORS
is a _browser_ rule; `curl` ignores it, which is why we pass an explicit `Origin` header to
simulate a browser.)

**One cleanup — killing the generated password.** Defining a filter chain doesn't stop Boot
from creating that default user; it only backs off once we supply our own user store (M1).
So we excluded `UserDetailsServiceAutoConfiguration` on `ServerApplication` to silence it.
This is a peek at **auto-configuration**: Spring Boot wires up beans it _guesses_ you want
based on the classpath, and `exclude = ...` is how you opt out of a guess.

**How we verified (end-to-end, against the running app):**

```bash
./mvnw clean package -DskipTests          # build the jar
java -jar target/server-0.0.1-SNAPSHOT.jar &   # run it (Postgres must be up)

curl http://localhost:8080/api/health
# → {"status":"ok"}   HTTP 200      ✅ public route works

curl -o /dev/null -w "%{http_code}" http://localhost:8080/api/boards
# → 403                             ✅ everything else is still locked

curl -i -X OPTIONS http://localhost:8080/api/health \
  -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: GET"
# → 200 + Access-Control-Allow-Origin: http://localhost:3000   ✅ CORS preflight OK
```

All three passed, and the "generated security password" line was gone from the logs. Notice
the verification style: we didn't just trust the code compiled — we drove the actual HTTP
behavior and watched it do the right thing (allow, block, and permit-cross-origin).

### Step 0.6 — One consistent shape for every error · _2026-07-12_

**Goal:** make _every_ failure the backend returns look the same, so the frontend can
parse any error with one code path instead of guessing per-endpoint.

**Files:**

- `server/src/main/java/org/cj/server/common/GlobalExceptionHandler.java` (new)
- `server/src/main/java/org/cj/server/common/ApiError.java` (already existed — the shape)
- `server/src/main/java/org/cj/server/common/NotFoundException.java` (already existed)
- `server/src/test/java/org/cj/server/common/GlobalExceptionHandlerTest.java` (new — proof)

> **Concepts:** `@RestControllerAdvice` · `@ExceptionHandler` · exception-to-HTTP mapping ·
> Bean Validation (`@Valid`, `MethodArgumentNotValidException`) · fail-safe vs fail-leaky
> (not exposing internal error messages) · MockMvc standalone setup

**What we did:** added a single `@RestControllerAdvice` class. Think of it as a **catch-net
that sits behind every controller**: whenever a request handler throws, the exception
"bubbles up" and Spring routes it to the `@ExceptionHandler` method whose declared type most
closely matches. Each method converts the exception into the same `ApiError` record from
Step 0.5's sibling work, so the JSON is always `{timestamp, status, error, message, path}`
(plus `fieldErrors` on validation failures).

We handle four cases, most-specific first:

| Exception thrown by our code | HTTP status | Client sees |
|---|---|---|
| `NotFoundException` | 404 | the resource message, e.g. "Board not found" |
| `MethodArgumentNotValidException` (a `@Valid` DTO failed) | 400 | "Validation failed" + a `fieldErrors` list |
| `IllegalArgumentException` | 400 | the bad-argument message |
| anything else (`Exception`) | 500 | a generic "Internal server error" |

**Why a central handler instead of try/catch in each controller?** Because otherwise every
controller re-implements error formatting — inconsistent, repetitive, and easy to forget.
Centralizing it means our services can just `throw new NotFoundException("Board not found")`
and stay completely ignorant of HTTP; the translation to a status code + JSON happens in one
place we can reason about.

**Two decisions worth calling out:**

- **The 500 handler logs the stack trace but hides it from the client.** A 404 or 400 is the
  _caller's_ fault (bad id, bad input), so we don't log those as errors and we echo a helpful
  message. A 500 is _our_ fault (a bug), so we `log.error(...)` the full trace server-side for
  debugging but return only a generic message — leaking internal exception text to a browser
  can expose implementation details an attacker could use. This "log detail, return generic"
  split is a standard security posture.
- **Spring Security's 401/403 don't come through here.** Those are thrown in the security
  _filter chain_, which runs **before** the request ever reaches a controller — so our
  `Exception` catch-all can't (and shouldn't) swallow them. That's why `/api/boards` still
  returns a clean 403 from Step 0.5, unaffected by this change.

**How we verified.** Rather than boot the whole app, we wrote a focused test using **MockMvc
standalone setup**: it wires a throwaway controller (one route per exception type) plus the
handler, with no Spring context, Postgres, or security in the way. Four tests assert each
exception maps to the right status _and_ the right JSON — including that the 500 case returns
`"Internal server error"` and **not** the secret exception message.

```bash
cd server
./mvnw test -Dtest=GlobalExceptionHandlerTest   # 4 passing
./mvnw test                                      # full suite: 5 passing
```

> **Note on reading the logs:** when the 500 test runs, you'll see a `RuntimeException: secret
> internal detail` stack trace printed in the output. That is **not** a failure — it's the
> handler's own `log.error(...)` doing its job. `BUILD SUCCESS` and `Failures: 0` are the truth.

### Step 0.7 — Scaffold the frontend & fetch health · _2026-07-13_

**Goal:** stand up the Next.js app and render the backend's health status on a real web page —
the frontend half of the M0 demo, and the moment all three tiers (browser → backend → database)
are proven to talk to each other.

**Files:**

- `frontend/` (new Next.js app — `create-next-app`)
- `frontend/src/app/page.tsx` (the health page)
- `frontend/src/lib/health.ts` (typed fetch helper)
- `frontend/.env.local` + `frontend/.env.example` (backend URL config)
- `frontend/src/app/layout.tsx` (title tweak)

> **Concepts:** Next.js App Router · React Server Components (RSC) vs Client Components ·
> server-side vs client-side data fetching · first paint / time-to-content · CORS preflight ·
> `fetch` caching (`cache: 'no-store'`) · server-only env vars (no `NEXT_PUBLIC_` prefix) ·
> graceful degradation (friendly failure state)

**What we did.** Scaffolded a separate Next.js app in `frontend/` (TypeScript, App Router,
Tailwind), then replaced the starter homepage with a **Server Component** that calls
`GET /api/health` on the Spring backend while it renders and shows a green "Backend: ok" (or a
red failure state). The backend URL comes from an env var, not a hardcode.

**Why a separate app (not one combined project)?** Frontend and backend have different jobs,
languages, and deploy lifecycles — Next.js/TypeScript for the UI, Spring/Java for the API and
data. Keeping them as two apps in one repo lets each evolve and deploy independently while the
shared contract (the REST API) stays visible in one place.

**The big decision: server-side vs client-side fetching — and why "fast" pointed us to server-side.**
There are two places the health call could happen:

- **Server-side (what we chose):** the Next.js _server_ makes the call during render and sends
  the browser fully-formed HTML with the status already in it. The user sees content on first
  paint — no spinner — and the Next-server→Spring hop is server-to-server, so it skips the
  browser's **CORS preflight** entirely. Faster first load.
- **Client-side:** ship an empty shell, then have the _browser_ fetch after the page loads
  (spinner → data), paying a CORS preflight on the cross-origin call.

For an initial page load, server-side wins on speed, so that's our **app-wide default**: render
initial data on the server. Client-side fetching is reserved for what actually needs it later —
interactive mutations (drag-and-drop) and the real-time WebSocket layer (M5), where we'll add
React Query for caching + optimistic updates. The rule of thumb: **server-render what the user
should see immediately; fetch on the client only what changes after they interact.**

> You can see this decision in Next's build output: route `/` is marked `ƒ (Dynamic)` because
> `cache: 'no-store'` forces a fresh server render every request — exactly right for a health
> check, which must never be stale.

**Why the backend URL is an env var (`API_BASE_URL`).** Same reasoning as the backend's
datasource in Step 0.2: the code shouldn't bake in `localhost:8080`, because production points
at a different host. Note there's **no `NEXT_PUBLIC_` prefix** — that prefix is what exposes a
variable to the browser bundle, and since this fetch runs only on the server, the URL stays
server-side and never leaks to the client.

**Why the friendly failure state.** If the backend is down, a naïve `fetch(...).json()` throws
and Next renders an ugly error page. We wrap the call in try/catch and return a typed result, so
a dead backend shows a calm red "Backend unreachable" instead of a crash. Handling the sad path
is part of "done", not an afterthought.

**How we verified (end-to-end, the whole chain live):**

```bash
docker compose up -d                    # Postgres
cd server && ./mvnw spring-boot:run &   # backend on :8080
cd frontend && npm run dev              # frontend on :3000
```

- Open `http://localhost:3000` → **"Backend: ok"** (green). ✅ browser→backend→db all wired.
- Stop the backend, reload → **"Backend unreachable"** (red), no crash. ✅ sad path.
- Restart the backend, reload → **"Backend: ok"** again. ✅ recovery.
- `npm run build` succeeds (type-checks the whole app). ✅

> **A fun gotcha we hit:** killing the backend with `lsof -ti tcp:8080 | xargs kill` also killed
> the _frontend_ process. Why? Because the Next.js server had an open **client** socket to
> port 8080 (its server-side fetch), so `lsof` listed it too. Concrete proof the fetch really is
> server-to-server — and a reminder that "kill everything on this port" catches both ends of a
> connection.

---

### Where M0 stands

Done: ✅ Postgres running · ✅ backend connected · ✅ migrations wired · ✅ boot verified ·
✅ first endpoint (`/api/health`) with security + CORS · ✅ global exception handler ·
✅ Next.js frontend rendering backend health

**🎉 Milestone 0 complete.** The demo works: open a web page, see the backend's health status —
proving both apps and the database all talk to each other. The plumbing is solid; **next up is
Milestone 1 (auth):** register/login, JWT + refresh, and a `GET /api/me` endpoint.

---

## Milestone 1 — Auth

**Goal of the whole milestone:** a person can register, log in, and hit an endpoint that
requires being logged in. We use **stateless JWT** (JSON Web Tokens): the server hands the
client a signed token at login, the client sends it back on every request, and the server
trusts the signature instead of keeping any session in memory. That statelessness is why the
frontend (a separate origin) can authenticate cleanly.

The build order is deliberate — **data → passwords → tokens → wiring**:

1. **1.1** the `User` table + entity (this step) — nothing to log in _as_ without it.
2. **1.2** hashing + register/login service — turn a password into an account, safely.
3. **1.3** JWT issue/verify — mint the signed tokens.
4. **1.4** the security filter + endpoints — glue it together so a token actually unlocks the API.

### Step 1.1 — The user account: table, entity, repository · _2026-07-15_

**Goal:** create the `app_user` table and the Java objects to read/write it — the foundation
everything else in M1 sits on.

**Files:**

- `server/src/main/resources/db/migration/V2__app_user.sql` (new migration)
- `server/src/main/java/org/cj/server/auth/User.java` (JPA entity)
- `server/src/main/java/org/cj/server/auth/UserRepository.java` (data access)

> **Concepts:** JPA entity · `@Entity` / `@Table` / `@Column` · reserved-word table name ·
> app-generated UUID primary key · nullable columns · Spring Data `JpaRepository` ·
> derived query methods · `ddl-auto=validate` (migration ↔ entity agreement)

**What we did.** Recall from Step 0.3 that **Flyway owns the schema**: to add a table we write
the _next_ numbered migration, `V2__app_user.sql`, and Flyway applies it on startup. Then we
wrote a matching JPA `@Entity` (`User`) so Java code can work with rows as objects, and a
`UserRepository` interface that gives us database operations without writing SQL.

**The three files, and why each is shaped the way it is:**

- **`V2__app_user.sql`** — creates `app_user` with the columns from `project-scope.md`:
  `id` (uuid PK), `email` (unique, not null), `password_hash` (**nullable**), `name`,
  `image_url` (nullable), `created_at`. Two decisions to notice:
  - **Table name is `app_user`, not `user`** — `USER` is a SQL reserved word; naming the
    table that would force us to quote it everywhere. This mapping is a project rule.
  - **`password_hash` is nullable** because OAuth-only accounts (Google, later) never set a
    password. Email/password users always have a BCrypt hash there.
  - The `UNIQUE` on `email` is what guarantees "one account per email" — and Postgres backs
    a unique constraint with an index, so email lookups at login are fast for free.

- **`User.java`** — the entity. `@Table(name = "app_user")` ties it to our reserved-word
  table; each `@Column` maps a field. The subtle part is the **primary key**: we do _not_ use
  `@GeneratedValue`. Instead the id is a `UUID` we set ourselves in Java (`UUID.randomUUID()`
  inside a `create(...)` factory). App-generated UUIDs mean we know a new row's id _before_
  hitting the database — no round-trip — and they're safe for the optimistic client-side
  creates we'll want later. The factory also stamps `createdAt`, so no caller can forget it,
  and the entity never receives a plaintext password — only an already-computed hash.

- **`UserRepository.java`** — extends Spring Data's `JpaRepository<User, UUID>`, which hands
  us `save`, `findById`, `delete`, etc. with **zero implementation code**. The two methods we
  declared, `findByEmail` and `existsByEmail`, are **derived queries**: Spring Data reads the
  _method name_ and generates the SQL (`WHERE email = ?`). `findByEmail` powers login;
  `existsByEmail` lets registration reject a duplicate before trying to insert.

**Why this pairs with `ddl-auto=validate` (Step 0.2).** On boot, Flyway creates the table,
then Hibernate **validates** that our `User` entity matches that table — same columns, types,
nullability. If they drift (say we typo a column), the app refuses to start. So getting the
context to load green is a real proof the migration and the entity agree.

**How we verified.** No new endpoint yet, so the check _is_ the boot: run the suite, watch
Flyway apply V2 and the context come up clean.

```bash
cd server
./mvnw test
# log shows: "Successfully applied 2 migrations ... now at version v2"
# and BUILD SUCCESS — the User entity validated against the new table.
```

Flyway logged `now at version v2` and all 5 existing tests stayed green — the table exists,
the mapping is correct, and nothing else broke. Foundation laid; next we make passwords safe.

> **Heads-up (Postgres must be running):** if `./mvnw test` fails with `Connection to
> localhost:5432 refused`, the database container isn't up. Run `docker compose up -d` from
> the repo root first (see Step 0.1). The tests boot the real app, which needs the real DB.

### Step 1.2 — Passwords done safely: hashing + register/login logic · _2026-07-15_

**Goal:** turn a submitted email/password into a stored account, and verify a login — without
ever keeping the password itself. All the _logic_ (no HTTP, no tokens yet) lives in a service
we can unit-test in isolation.

**Files:**

- `server/src/main/java/org/cj/server/auth/SecurityConfig.java` (added a `PasswordEncoder` bean)
- `server/src/main/java/org/cj/server/auth/RegisterRequest.java` · `LoginRequest.java` (request DTOs)
- `server/src/main/java/org/cj/server/auth/AuthService.java` (register + authenticate)
- `server/src/main/java/org/cj/server/common/ConflictException.java` (new 409 exception)
- `server/src/main/java/org/cj/server/common/GlobalExceptionHandler.java` (map 409 + 401)
- `server/src/test/java/org/cj/server/auth/AuthServiceTest.java` (6 tests, real BCrypt)

> **Concepts:** password hashing vs encryption · BCrypt (salt + work factor) · never store
> plaintext · DTO + Bean Validation · service layer (HTTP-agnostic) · user enumeration &
> generic error messages · `@Transactional` · unit test with mocks + a real encoder

**The one rule that drives this step: never store the password.** A password isn't
*encrypted* (which is reversible) — it's **hashed**: run through a one-way function so the
database only ever holds a fingerprint. At login we hash the submitted password the same way
and compare fingerprints. If the database leaks, attackers get hashes, not passwords.

We use **BCrypt** (the `PasswordEncoder` bean added to `SecurityConfig`). Two properties make
it the right tool:

- **Per-password salt** — a random value mixed in and stored inside the hash, so two users
  with the same password get different hashes (defeats precomputed "rainbow table" attacks).
- **Work factor** — it's intentionally *slow*, and you can dial the cost up as hardware gets
  faster, keeping brute-force expensive. `encode()` hashes; `matches(raw, hash)` verifies.

**The pieces:**

- **Request DTOs (`RegisterRequest`, `LoginRequest`).** Small `record`s carrying exactly the
  fields each endpoint accepts, annotated with Bean Validation (`@Email`, `@NotBlank`,
  `@Size`). When the controller marks them `@Valid` (M1.4), Spring rejects malformed input
  with a 400 *before* our code runs — reusing the exact validation→`ApiError` path from Step
  0.6. Note the password cap of **72**: BCrypt only reads the first 72 bytes, so promising more
  would be a lie. And `LoginRequest` deliberately has *no* size rules — a wrong password must
  return one generic 401, never a 400 that hints the input was "too short".

- **`AuthService`.** The brain, and pointedly **HTTP-agnostic** — it takes DTOs, returns a
  `User`, or throws. Keeping it out of the controller makes it reusable (OAuth can call it
  later) and unit-testable without a web server.
  - `register(...)`: normalize the email (trim + lowercase, so uniqueness is consistent),
    reject duplicates with a **409 `ConflictException`**, BCrypt-hash the password, save.
  - `authenticate(...)`: look up by email, then check the hash. Crucially, **both** "no such
    email" and "wrong password" throw the *same* `BadCredentialsException` → **401 with one
    generic message**. Telling them apart would let an attacker probe which emails have
    accounts — that's **user enumeration**, and we design it out. OAuth-only accounts (null
    hash) also can't log in by password.
  - `@Transactional` wraps each so the DB work commits or rolls back as a unit (read-only on
    login, a small correctness/efficiency hint to Hibernate).

- **New error mappings.** `ConflictException` (mirroring `NotFoundException`) → **409**, and
  Spring Security's `BadCredentialsException` → **401** (generic message), both added to the
  central `GlobalExceptionHandler`. Every failure still comes out as the same `ApiError` JSON.

**How we verified.** A focused unit test, `AuthServiceTest`: the repository is **mocked** (no
database needed) but it uses a **real `BCryptPasswordEncoder`**, so we're actually exercising
hashing and verification, not a stub. Six cases: registration hashes the password (asserts the
stored value is *not* the plaintext yet `matches()` succeeds) and lowercases the email;
duplicate email → 409 and never saves; correct login returns the user; wrong password, unknown
email, and OAuth-only account each → `BadCredentialsException`.

```bash
cd server
./mvnw test -Dtest=AuthServiceTest   # 6 passing (no DB required — repository is mocked)
./mvnw test                          # full suite: 11 passing
```

All green. We can now create and verify accounts; next (1.3) we mint the JWTs that a
successful login will hand back.

### Step 1.3 — Minting the tokens: JWT issue & verify · _2026-07-15_

**Goal:** be able to hand a logged-in user a signed token they send back on future requests,
and to verify such a token without keeping any server-side session. No endpoints yet — just
the token engine and its tests.

**Files:**

- `server/pom.xml` (added the `jjwt` library — api + impl + jackson)
- `server/src/main/resources/application.properties` (JWT secret + token lifetimes)
- `server/src/main/java/org/cj/server/auth/JwtService.java` (issue + verify)
- `server/src/test/java/org/cj/server/auth/JwtServiceTest.java` (5 tests)

> **Concepts:** JWT structure (`header.payload.signature`) · claims · HMAC / HS256 signing ·
> symmetric secret · stateless auth · access vs refresh tokens · token expiry · signature
> verification · ISO-8601 durations · env-overridable config

**What a JWT actually is.** A JWT is three base64 chunks joined by dots:
`header.payload.signature`. The **payload** is JSON "claims" — who the user is (`sub`), when
the token expires (`exp`), and any extras we add. The **signature** is an HMAC of the first
two parts using a secret only our server knows. Because we can recompute and check that
signature, we can trust a token *on sight* — no database lookup, no session table. That's
**stateless authentication**, and it's exactly what lets a separate-origin frontend log in.

> **Signed, not secret.** A JWT is *readable* by anyone (base64, not encryption) — never put
> a real secret in the payload. The signature guarantees it wasn't *altered*, not that it's
> private. Our claims (id, email, name) are fine to be visible.

**Two token types, and why.** `issueTokens()` returns a pair:

- an **access token**, short-lived (~15 min), sent on every request; and
- a **refresh token**, long-lived (~7 days), used *only* to obtain a new access token.

The split limits damage: a leaked access token stops working in minutes, yet the user isn't
forced to re-enter their password for a week. We tag each with a `type` claim so later code
can insist "this endpoint only accepts a *refresh* token" (the filter, conversely, only
accepts *access* tokens).

**The pieces:**

- **`pom.xml`** gains `jjwt` in three parts: `jjwt-api` (compile-time types) plus
  `jjwt-impl` and `jjwt-jackson` as **runtime** — a common jjwt layout where you code against
  the API and the implementation is only needed when the app actually runs.

- **`application.properties`** adds `app.jwt.secret` and the two TTLs, all
  `${ENV:default}`-style (Step 0.2's pattern) so prod overrides them. The dev-default secret is
  clearly labelled insecure: **whoever holds the secret can forge tokens**, so a real one only
  ever comes from the environment.

- **`JwtService`** wraps it all. It builds an HS256 signing key from the secret — and jjwt
  *refuses* a secret under 32 bytes, a built-in guard against a weak key. `issueAccessToken`
  puts `sub`=user id plus email/name; `issueRefreshToken` stays minimal. `parse()` verifies
  the signature **and** expiry, returning the claims or throwing — any `JwtException` means
  "not authenticated". Small helpers (`userId`, `isRefresh`) read the verified claims.

**How we verified.** `JwtServiceTest` constructs the service directly (no Spring) so it can
also mint an already-expired token with a *negative* TTL — deterministic, no `sleep`. Five
cases: an access token round-trips with its claims and reads as non-refresh; a refresh token
reads as refresh; a **tampered** token is rejected; a token signed with a **different secret**
is rejected; an **expired** token throws `ExpiredJwtException`. Those last three are the whole
point — they prove the signature and expiry checks actually protect us.

```bash
cd server
./mvnw test -Dtest=JwtServiceTest   # 5 passing (no DB, no Spring context)
./mvnw test                         # full suite: 16 passing
```

We can now create accounts (1.2) and mint/verify tokens (1.3). The final step wires them
together: endpoints that hand out tokens, and a filter that turns an access token on an
incoming request into an authenticated user.

### Step 1.4 — Wiring it together: filter, endpoints, `/api/me` · _2026-07-15_

**Goal:** the real thing — register/login endpoints that return tokens, a filter that turns
an access token into an authenticated request, and a protected `GET /api/me` that only works
with a valid token. This is the milestone's payoff.

**Files (new unless noted):**

- `auth/AuthController.java` — `POST /api/auth/{register,login,refresh}`
- `auth/MeController.java` — protected `GET /api/me`
- `auth/JwtAuthenticationFilter.java` — reads the `Bearer` token, sets the principal
- `auth/JwtAuthenticationEntryPoint.java` — unauthenticated → 401 JSON
- `auth/AuthPrincipal.java` — the identity attached to a request
- `auth/UserResponse.java` · `AuthResponse.java` · `RefreshRequest.java` — response/request DTOs
- `auth/SecurityConfig.java` (edited) — register filter + entry point, open `/api/auth/**`
- `auth/AuthService.java` (edited) — added `getById` for refresh / `/api/me`
- `test/.../auth/AuthIntegrationTest.java` — 10 full-stack tests

> **Concepts:** servlet filter · `OncePerRequestFilter` · `SecurityContextHolder` /
> principal · `@AuthenticationPrincipal` · bearer token · filter chain ordering ·
> `AuthenticationEntryPoint` · 401 vs 403 · DTO mapping to avoid leaking fields · token rotation

**How a request becomes "authenticated".** Every incoming request passes through a chain of
**filters** before it reaches a controller. We insert `JwtAuthenticationFilter` early in that
chain. Its logic is small and deliberate:

1. Look for an `Authorization: Bearer <token>` header. No header → do nothing, move on.
2. If present, `JwtService.parse()` verifies the signature and expiry.
3. If valid **and** it's an access token (not a refresh token), build an `AuthPrincipal`
   (user id + email) and place it in the `SecurityContextHolder`.
4. Any problem — bad signature, expired, wrong type — is swallowed: the request just stays
   anonymous. The filter **never** rejects; it only ever *grants*.

That last point is the key mental model: **the filter authenticates when it can; the
authorization rules decide what anonymous is allowed to do.** For `/api/me` (which requires
auth) an anonymous request is then stopped by `JwtAuthenticationEntryPoint` with a **401**.
For `/api/health` (public) it sails through. This separation keeps each piece simple.

> **401 vs 403.** We added the entry point so an unauthenticated request gets **401
> Unauthorized** ("I don't know who you are") in our standard `ApiError` JSON, instead of
> Spring's default bare 403. 403 ("I know you, but you can't") is reserved for the per-board
> role checks coming in M4.

**The endpoints.**

- **`AuthController`** — thin: `@Valid` the request (reusing the 400 path from 0.6), delegate
  to `AuthService` + `JwtService`, return `AuthResponse` (user + token pair). `register` → 201,
  `login` → 200. `refresh` accepts a refresh token, rejects anything that isn't a valid,
  non-expired **refresh** token (→ 401), and issues a *fresh pair* — "rotation", so the client
  always moves forward to new tokens.
- **`MeController`** — `@AuthenticationPrincipal AuthPrincipal` hands us whoever the filter
  authenticated. We reload the user by id so the response is current (not stale token data).
- **DTO mapping matters for safety.** Responses go through `UserResponse`, which simply has no
  `passwordHash` field — so the hash *cannot* leak into JSON even by accident. The integration
  test asserts the word "password" never appears in an auth response.

**How we verified — two ways.**

First, `AuthIntegrationTest` (`@SpringBootTest` + MockMvc) boots the real app against real
Postgres and the real filter chain, and drives 10 scenarios over HTTP: register returns
tokens + a hash-free user; the access token unlocks `/api/me`; no token and garbage tokens
give 401; duplicate email → 409; invalid payloads → 400 with field errors; login right/wrong;
refresh yields a working access token; and an access token sent to `/refresh` is rejected.

Second — the habit this project keeps — we drove the **actual running jar** with `curl`:

```bash
# after: ./mvnw clean package -DskipTests && java -jar target/server-*.jar
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"hunter2secret","name":"Demo"}'
# → 201 { "user": {…no passwordHash…}, "accessToken":"eyJ…", "refreshToken":"eyJ…" }

curl localhost:8080/api/me                                  # → 401 {"status":401,…}
curl localhost:8080/api/me -H "Authorization: Bearer <access>"   # → 200 {the user}
# duplicate register → 409 · login wrong password → 401 · refresh → 200 new token that works
```

Every status matched. If you base64-decode the middle of the access token you'll see the
claims (`sub`, `type:"access"`, `exp`, `email`, `name`) — and the header shows `HS384`, which
jjwt auto-selected from our key length. **The auth loop is real and end-to-end.**

---

### Step 1.5 — Tidying the house: layered packages · _2026-07-17_

**Goal:** M1 worked, but all 15 auth classes sat flat in one package — entity, DTOs, repository,
service, controllers and security wiring all jumbled together. Give them a structure *before*
M2–M5 copy the mess three more times.

**Files:** every class under `auth/` and `common/` moved (no logic changed). New:
`auth/dto/TokenPair.java`. Note the file paths in steps 1.1–1.4 above describe the **old**
flat layout — this step is what moved them.

> **Concepts:** package structure · feature-first vs layer-first · separation of concerns ·
> dependency direction · layering violation · refactoring under test · behavior-preserving change

**The new shape.** Two ways to organize a codebase: **layer-first** (top-level `entity/`,
`service/`, `controller/` holding *every* feature's classes) or **feature-first** (top-level
`auth/`, `board/`, holding everything about one feature). We use **feature-first, layered
inside** — because when you work on boards, everything about boards is in one place, but
within it you can still tell a DTO from an entity at a glance:

```
org.cj.server.auth
├── entity/       User                     (JPA — the DB shape)
├── repository/   UserRepository           (data access)
├── dto/          RegisterRequest, AuthResponse, TokenPair, …   (the API shape)
├── service/      AuthService, JwtService  (business logic, knows nothing about HTTP)
├── controller/   AuthController, MeController
└── security/     SecurityConfig, JwtAuthenticationFilter, AuthPrincipal
```

Nothing needed re-wiring for Spring: it component-scans everything under `org.cj.server`, so
moving classes *within* that tree is invisible to it.

**Dependency direction — the actual point.** Structure isn't decoration; it makes bad
dependencies *visible*. The rule: `controller → service → repository → entity`, with `dto` as
the shared vocabulary everyone speaks. Arrows point one way.

Splitting the packages instantly exposed a violation we'd been living with. `AuthResponse` (a
DTO) had this:

```java
public static AuthResponse of(User user, JwtService.Tokens tokens)   // ❌ dto → service
```

`Tokens` was a record nested inside `JwtService`, so a **DTO had to import a service** just to
describe its own shape. In one flat package that's invisible — same package, no import needed,
nothing looks wrong. The moment `dto/` and `service/` became separate, it showed up as an
import pointing the wrong way. Fix: pull the record out into its own DTO, `dto/TokenPair`.
Now the service *returns* it and the response *consumes* it, and neither depends on the other:

```java
public static AuthResponse of(User user, TokenPair tokens)           // ✅ dto → dto
```

> **The lesson:** a flat package doesn't mean you have no layering problems — it means you
> can't *see* them. Good structure is an early-warning system.

**One judgment call kept as-is:** `UserResponse.from(user)` still has the DTO reading the
entity. That arrow is fine (dto ← entity is the normal direction for mapping), and a dedicated
`mapper/` layer would be ceremony at this size. Structure should pay for itself.

**How we verified — this is why the tests were worth writing.** A refactor's whole promise is
"nothing behaves differently", and that's a claim you can only make if you can *check* it:

```bash
./mvnw clean test     # BEFORE:  Tests run: 26, Failures: 0  ← the baseline
# …move every file, rewrite every package + import…
./mvnw clean test     # AFTER:   Tests run: 26, Failures: 0  ← unchanged
```

Take the baseline **first**. A green run after only means something if you know it was green
before. `AuthIntegrationTest` is the real prize here: it boots the whole app, so if a move had
broken component scanning or bean wiring, it would fail loudly rather than at 3am in prod.
Then we booted the actual server and re-ran the same 8 curl checks from 1.4 — health, register
201, `/api/me` 401 then 200, duplicate 409, wrong password 401, refresh 200, access-token-to-
refresh 401. All matched.

> **Refactor discipline:** change *structure* or *behavior*, never both at once. Then any test
> that goes red is unambiguously your move, not a new bug you introduced.

---

### Where M1 stands

Done: ✅ `app_user` table + `User` entity · ✅ BCrypt hashing + register/login service ·
✅ JWT issue/verify (access + refresh) · ✅ JWT filter + security wiring · ✅ endpoints:
`POST /api/auth/register|login|refresh`, `GET /api/me` · ✅ 26 tests + live curl smoke ·
✅ layered packages (`entity/ repository/ dto/ service/ controller/ security/`) for M2 to mirror.

**🎉 Milestone 1 (backend) complete.** Register → log in → call an authenticated endpoint all
work, statelessly, over real HTTP.

---

### Step 1.6 — The frontend auth flow: forms, context, protected routes · _2026-07-18_

**Goal:** make the browser actually *use* the auth API. Register and login pages, a place to
keep the logged-in session, and a dashboard you can only reach when logged in — the milestone's
demo: register → log in → land on an authenticated dashboard.

**Files (all new unless noted):**

- `src/lib/api.ts` — typed browser client for `register/login/refresh/me`; an `ApiError`
  class carrying the backend's status + message + field errors.
- `src/lib/auth-context.tsx` — `AuthProvider` + `useAuth()`; the session state machine.
- `src/components/protected.tsx` — the redirect gate for logged-in-only pages.
- `src/app/login/page.tsx` · `register/page.tsx` · `dashboard/page.tsx` — the three screens.
- `src/app/layout.tsx` (edited) — wraps the app in `<AuthProvider>`.
- `src/app/page.tsx` (edited) — landing page: kept the M0 health card, added auth links.
- `.env.local` / `.env.example` (edited) — added `NEXT_PUBLIC_API_URL`.

> **Concepts:** Server vs Client Components · `"use client"` boundary · React Context ·
> `NEXT_PUBLIC_` env vars · in-memory vs persisted tokens · token bootstrap on reload ·
> three-state auth machine · protected routes · CORS · controlled inputs

**Server code can't hold your login — so this half lives in the browser.** M0's health check
runs on the *server* during render (`src/lib/health.ts`, server-only `API_BASE_URL`). Auth is
the opposite: the access token lives in the browser's memory, so the code that holds it and
attaches it to requests must run in the browser too. That's the `"use client"` directive at
the top of the context and pages — it marks the boundary where server-rendering stops and
interactive, stateful React begins. Because these calls now leave the browser and hit a
*different* origin (`:3000` → `:8080`), they need the backend's CORS allowance (already set in
M1) and a **browser-visible** URL: `NEXT_PUBLIC_API_URL`. The `NEXT_PUBLIC_` prefix is a
deliberate opt-in — Next strips every other env var out of the client bundle so secrets can't
leak; this one is just a public URL, so it's fine to ship.

**Where the tokens live — the trade-off, made concrete.**

- **Access token → memory only** (a React ref). Short-lived, and gone the instant you reload.
  That's *good*: a credential sent on every request shouldn't sit on disk.
- **Refresh token → `localStorage`.** This is the one that survives a reload, so on the next
  visit the app can trade it for a new access token and keep you logged in.

The cost: `localStorage` is readable by any JavaScript on the page, so a script-injection (XSS)
bug could steal the refresh token. The more secure option is an **httpOnly cookie** (invisible
to JS), but that needs cookie/CSRF plumbing we skipped to keep the learning curve gentle. Worth
knowing this is the deliberate soft spot, and the thing a real deployment would harden first.

**The three-state session — and why "loading" earns its place.** The natural instinct is a
boolean: logged in, or not. But there's a third state that matters. On a fresh page load we
have a refresh token in storage but don't *yet* know if it's still valid — verifying means an
async round-trip to `/api/auth/refresh`. So the machine is:

```
loading         ← just mounted; trying to restore the session from the stored refresh token
authenticated   ← we have a user + access token
unauthenticated ← no token, or the stored one was rejected
```

Without the `loading` state, the dashboard's guard would look at that first render — user still
null — and instantly bounce a *logged-in* person to `/login`, only for the refresh to succeed a
moment later. The guard (`<Protected>`) instead waits: it shows "Loading…" until the bootstrap
resolves, redirects only on a definite `unauthenticated`, and renders the page only on
`authenticated`. The redirect lives in a `useEffect`, not in render, because navigation is a
side effect — changing routes while React is drawing is not allowed.

**Errors are values here, not surprises.** `api.ts` turns every non-2xx into one thrown
`ApiError` carrying the HTTP status, so a form can branch cleanly: a 400 spreads its
`fieldErrors` under the matching inputs, a 409 shows "email already registered", a 401 shows
"invalid email or password" — and a total network failure becomes status `0`, "Can't reach the
server", instead of an unhandled promise rejection. The sad path is part of the feature.

**How we verified — drove the real browser, not just the types.** After `npm run build`
type-checked all five routes, we clicked through every branch against the live backend:

1. Register → landed on `/dashboard` as the new user. ✅
2. **Reloaded `/dashboard` → still logged in** — proof the localStorage→refresh→me bootstrap
   works. ✅
3. Logged out → sent to `/login`; hitting `/dashboard` by hand while logged out → bounced. ✅
4. Logged back in with the right password → dashboard. ✅
5. Wrong password → inline "Invalid email or password" (401), no crash. ✅
6. Duplicate email → inline "Email already registered" (409). ✅
7. Stopped the backend, tried to log in → friendly "Can't reach the server", not a hang. ✅

> **A gotcha worth remembering:** programmatically *setting* an input's value (the way some
> automation tools do) doesn't fire React's `onChange`, so the controlled state stays empty and
> the form submits nothing. Real keystrokes do fire it. A neat reminder that in React the state
> is the source of truth, not what the DOM element visibly shows.

---

### Where M1 stands

Backend: ✅ `app_user` + `User` · ✅ BCrypt + register/login service · ✅ JWT issue/verify ·
✅ JWT filter + security wiring · ✅ `register|login|refresh` + `/api/me` · ✅ 26 tests ·
✅ layered packages.

Frontend: ✅ register/login pages · ✅ auth context (in-memory access + localStorage refresh) ·
✅ protected dashboard + redirect guard · ✅ reload-survives-login · ✅ friendly error states ·
✅ verified end-to-end in a real browser.

**🎉 Milestone 1 complete — front to back.** A person can create an account in the browser, log
in, land on an authenticated dashboard, and stay logged in across reloads.

Deferred (optional / later):

- [ ] Google OAuth login (email/password already unblocks everything else — the plan lets this slip).
- [ ] Silent access-token auto-refresh on mid-session 401s (bootstrap-on-load is enough for now;
      the reusable retry belongs with M2's authenticated data calls).

Next: **M2 — Board / Column / Card CRUD**, starting with the `rankBetween` ordering utility.

---

## Milestone 2 — Board / Column / Card CRUD (single-user)

**Goal of the whole milestone:** a signed-in owner can build and edit a board — create it,
add columns and cards, rename and delete them — and everything persists in the right order
across reloads. This is the "get it correct for one user before adding real-time" slice.
Drag-and-drop *moves* come in M3; roles and sharing in M4; live sync in M5.

We build it back-to-front in the backend first: the ordering primitive, then the data model,
then CRUD one entity at a time, then the whole-board read. (The browser UI is the phase after.)

### Step 2.1 — The ordering primitive: `rankBetween` · _2026-07-18_

**Goal:** be able to compute a position "between" two others so inserting or moving a card
never has to renumber its siblings — the single most important decision behind drag-and-drop.

**Files:**

- `server/src/main/java/org/cj/server/common/ranking/LexoRank.java`
- `server/src/main/java/org/cj/server/common/ranking/RankExhaustedException.java`
- `server/src/test/java/org/cj/server/common/ranking/LexoRankTest.java` (11 tests)

> **Concepts:** fractional / LexoRank-style indexing · lexicographic vs numeric ordering ·
> base-36 digits · midpoint between two strings · open bounds (`null`) · rank exhaustion &
> re-balancing · pure function / unit-testability · property/stress testing with a fixed seed

**The problem it solves.** A Kanban board needs an *order* for cards in a column and columns
on a board. The naive approach — an integer `position` 0,1,2,… — means every insert or move
has to shift all the siblings after it (and two people dragging at once collide on the same
number). Instead we give each item a **string `rank`** and sort by it. To drop an item between
two neighbours, we compute a string that sorts *between* their ranks. No siblings change. This
is why the schema stores `rank varchar`, not an integer.

**The trick that makes it work.** A rank is read as a fraction in base-36 with an implicit
"`0.`" in front, using digits `0-9a-z`. Those digits are in ASCII order, so **plain string
comparison gives the same answer as comparing the numbers** — and a shorter string acts like
it's padded with the smallest digit. So `between(a, b)` just has to find a base-36 fraction
strictly between two others: walk the digits, and at the first place they differ, either drop
in a midpoint digit or — if the two are *adjacent* (like `i` and `j`) — take the lower digit
and descend one more place, growing the string to make room. `null` bounds mean "open end", so
`between(null, null)` is the first-ever rank, `between(last, null)` appends, and
`between(null, first)` prepends.

**Why a hard length cap.** Repeatedly inserting in the *same* spot makes keys grow one digit at
a time. Rather than loop forever on degenerate input, `between` throws `RankExhaustedException`
once a key would exceed a length cap — the signal that a column should be *re-balanced*
(re-spaced). That re-balance is an M3 concern; in M2's modest insert volumes it never trips,
but wiring the tripwire now keeps the primitive honest.

**Why it's a pure static function.** `LexoRank.between` takes strings and returns a string —
no Spring, no database, no clock. That makes it trivial to test in isolation and to reason
about, which matters because *everything* leans on it.

**How we verified — the point of this step.** `LexoRankTest` asserts the core invariant
(`prev < result < next`) across every shape: first/append/prepend, adjacent digits, shared
prefixes. The two that matter most:

- a **randomized stress test** — 2000 insertions at random positions (including both ends),
  seeded for reproducibility, asserting the result is strictly between its neighbours *every
  time* and the whole list stays strictly sorted and duplicate-free; and
- an **exhaustion test** proving the length cap trips (`RankExhaustedException`) instead of
  hanging on adversarial input.

```bash
cd server
./mvnw test -Dtest=LexoRankTest   # 11 passing, incl. a 2000-insert property test
```

All green. With a trustworthy ordering primitive in hand, we can build the data model that
uses it.

### Step 2.2 — The board graph: migration, entities, repositories · _2026-07-18_

**Goal:** create the four tables that hold a board's structure — `board`, `board_membership`,
`board_column`, `card` — and the Java objects to read and write them. This is the data
foundation the CRUD steps sit on.

**Files:**

- `server/src/main/resources/db/migration/V3__board_graph.sql`
- `server/src/main/java/org/cj/server/board/entity/{Board,BoardMembership,BoardColumn,Card,Role,MembershipStatus}.java`
- `server/src/main/java/org/cj/server/board/repository/{Board,BoardMembership,BoardColumn,Card}Repository.java`

> **Concepts:** one-migration-per-milestone · foreign keys & cascade rules (`ON DELETE
> CASCADE` vs `RESTRICT`) · `CHECK` constraints for enums · partial unique index ·
> denormalization · reserved-word table names · JPA `@Enumerated(STRING)` · FK-as-plain-UUID
> vs `@ManyToOne` · derived query methods

**One migration for the whole graph.** Back in Step 0.3 the baseline migration's comment
reserved M2 for "the board graph", so all four tables land together in `V3__board_graph.sql`
rather than dribbling out one per sub-step. The columns/types/constraints come straight from
`project-scope.md § Data`. The details that matter:

- **Cascade rules encode the delete policy.** Everything under a board — memberships, columns,
  cards — is `ON DELETE CASCADE`, so deleting a board cleans up its whole subtree in the DB.
  But `board.owner_id → app_user` is `ON DELETE RESTRICT`: you can't delete a user who still
  owns boards. The database enforces this regardless of any bug in our code.
- **Enums are strings with a `CHECK`.** `role` and `status` are `varchar(16)` guarded by
  `CHECK (role IN ('OWNER','EDITOR','VIEWER'))` etc. — never integer ordinals, because
  reordering a Java enum must never silently corrupt stored data.
- **A partial unique index** enforces "no duplicate *pending* invites":
  `UNIQUE (board_id, invited_email) WHERE status = 'PENDING'`. It only constrains pending
  rows — a clever, precise use of Postgres you'll want to recognize.
- **`card.board_id` is denormalized on purpose** (a card already reaches its board via its
  column). Storing it directly lets board-scoped reads and auth checks skip a join; the cost is
  we must keep it correct on cross-column moves (M3).
- **Reserved-word table names**: `board_column` (not `column`), consistent with `app_user`.

**The entities mirror `User` exactly.** Each is a plain JPA `@Entity` with an app-generated
`UUID` id (no `@GeneratedValue`), a `protected` no-arg constructor for Hibernate, a `private`
all-args constructor, and a static `create(...)` factory that stamps `createdAt`/`updatedAt`.
Two deliberate style choices:

- **Foreign keys are plain `UUID` fields** (`ownerId`, `boardId`, `columnId`), not
  `@ManyToOne` associations. At this scale that's simpler, avoids lazy-loading surprises, and
  fits the denormalized-id approach the schema already takes.
- **Mutations live on the entity** (`board.rename(...)`, `card.edit(...)`) and bump
  `updatedAt` themselves, so a service can never forget to. `updatedAt` is also the
  last-write-wins key real-time will use in M5.
- The Java type is `BoardColumn` (not `Column`) to avoid colliding with JPA's own `@Column`
  annotation — a small but real gotcha of the reserved-word mapping.

**The repositories** are Spring Data interfaces — method names generate the queries. M2 needs
just a handful: `findByOwnerIdOrderByCreatedAtDesc` (a user's boards),
`findByBoardIdOrderByRankAsc` (columns/cards in order), `findFirstBy…OrderByRankDesc` (the last
sibling, whose rank is the lower bound when appending), and `existsByColumnId` (to block
deleting a non-empty column).

**How we verified.** The migration + mappings are proved by *booting the app*: Flyway applies
`V3`, then — because `ddl-auto=validate` (Step 0.2) — Hibernate checks all four entities match
the new tables, refusing to start on any mismatch.

```bash
cd server
./mvnw test -Dtest=ServerApplicationTests
# log: "Successfully applied 1 migration ... now at version v3" + BUILD SUCCESS
```

The context came up clean at `v3`: tables exist, mappings agree. Next we put them to work with
the first CRUD surface — boards.

### Step 2.3 — Board CRUD + the ownership guard · _2026-07-18_

**Goal:** let a signed-in user create, list, view, rename, and delete their own boards — and
make sure one user can never touch another's. This introduces the access pattern every later
board-scoped operation reuses.

**Files:**

- `server/src/main/java/org/cj/server/common/exception/ForbiddenException.java` (+ a 403 handler
  in `GlobalExceptionHandler`)
- `server/src/main/java/org/cj/server/board/dto/{CreateBoardRequest,UpdateBoardRequest,BoardResponse}.java`
- `server/src/main/java/org/cj/server/board/service/BoardService.java`
- `server/src/main/java/org/cj/server/board/controller/BoardController.java`
- `server/src/test/java/org/cj/server/board/BoardIntegrationTest.java` (7 tests)

> **Concepts:** REST resource design · the service guard pattern · resource ownership /
> authorization · 404-vs-403 and not leaking existence · transactional multi-row writes ·
> `@AuthenticationPrincipal` → service · DB cascade on delete

**The shape.** `BoardController` is thin, exactly like `AuthController`: it reads the caller
from `@AuthenticationPrincipal AuthPrincipal`, `@Valid`-ates the body, and hands
`me.userId()` plus the data to `BoardService`. Five routes — `POST` (201), `GET` list,
`GET /{id}`, `PATCH /{id}` (rename), `DELETE /{id}` (204). No `SecurityConfig` change was
needed: `anyRequest().authenticated()` from M1 already locks every new `/api/boards` route, so
the "create requires auth → 401" test passes for free.

**The idea worth internalizing: one guard, reused everywhere.**
`BoardService.requireOwnedBoard(boardId, userId)` loads the board and asserts the user may
access it, or throws. *Every* board-scoped operation funnels through it — and so will the
column and card services in the next steps. Centralizing the check means authorization can't be
accidentally forgotten on one endpoint, and when M4 upgrades it from "owns" to "has a
sufficient membership role", every operation upgrades at once.

**Why not-yours returns 404, not 403.** The guard throws `NotFoundException` for both a missing
board *and* a board owned by someone else. Returning 403 would confirm the board exists; 404
reveals nothing. (We still added `ForbiddenException`/403 to the toolkit — it's the right code
for M4's "you're a member but a *viewer*, so you can't edit". The two coexist: non-members get
404, under-privileged members get 403.)

**Create writes two rows atomically.** `create` saves the `Board` **and** the owner's
`OWNER`/`ACTIVE` `BoardMembership` in one `@Transactional` method — either both land or neither
does. We write that membership now, before anything reads it, purely so M4's switch to
membership-based auth needs no data backfill. **Delete** just removes the board row and lets the
DB `ON DELETE CASCADE` (from `V3`) sweep the memberships/columns/cards — the test confirms the
owner's membership row vanishes afterward.

**How we verified.** `BoardIntegrationTest` boots the real app and drives the endpoints with a
real token (from registering): create returns the board *and* the owner-membership row exists;
list returns only the caller's boards; a second user gets 404 on GET/PATCH/DELETE of a board
they don't own; rename persists across a re-GET; delete → 204 then 404 and the membership
cascade; unauthenticated → 401; blank name → 400.

```bash
cd server
./mvnw test -Dtest=BoardIntegrationTest   # 7 passing
```

All green. With boards and a reusable access guard in place, columns slot in on top.

### Step 2.4 — Column CRUD (append by rank; block deleting non-empty) · _2026-07-18_

**Goal:** add, rename, and delete a board's columns — with new columns landing at the end in a
stable order, and a guard against accidentally deleting a column full of cards.

**Files:**

- `server/src/main/java/org/cj/server/board/dto/{CreateColumnRequest,UpdateColumnRequest,ColumnResponse}.java`
- `server/src/main/java/org/cj/server/board/service/ColumnService.java`
- `server/src/main/java/org/cj/server/board/controller/ColumnController.java`
- `server/src/test/java/org/cj/server/board/ColumnIntegrationTest.java` (6 tests)

> **Concepts:** appending with a fractional rank · server-authoritative ordering · nested vs
> flat REST routes · reusing an authorization guard across services · guard-rail delete (block
> vs cascade)

**Ordering starts here — even without drag.** When you create a column we **append** it: read
the current last column (`findFirstByBoardIdOrderByRankDesc`), take its rank as the lower bound,
and compute the new rank with `LexoRank.between(lastRank, null)` (Step 2.1). The very first
column uses `between(null, null)`. The client never sends a rank — **the server is the
authority**, which is the invariant that lets M3's drag-and-drop drop in later without changing
anything about how data is stored. The test asserts three appends come back with strictly
increasing ranks.

**Authorization is free here.** `ColumnService` doesn't re-implement any access check — it
calls `boardService.requireOwnedBoard(...)` (from Step 2.3). A column is only reachable through
a board, so "can this user touch this column?" reduces to "does this user own its board?". A
stranger creating/renaming/deleting hits 404, and creating a column under a non-existent board
is 404 — all for free, because the guard lives in one place.

**Route shape.** Create is **nested** — `POST /api/boards/{boardId}/columns` — because a new
column needs its parent board in the URL. Rename and delete address the column **directly** by
its own id (`/api/columns/{id}`), since the id is globally unique and the service resolves the
board from it. This "create-under-parent, mutate-by-id" split is a common, tidy REST shape.

**Delete blocks instead of cascading.** The DB *would* cascade-delete a column's cards, but
that's dangerous — one click could silently vaporize a lot of work. So the service checks
`cards.existsByColumnId(...)` and throws a **409 `ConflictException`** if the column isn't
empty, forcing the user to clear it first. This is a deliberate case where the *application*
rule is stricter than the *database* rule.

**How we verified.** `ColumnIntegrationTest` drives it over HTTP: appends yield increasing
ranks; rename persists; deleting an empty column works; deleting a non-empty one is 409; a
non-owner gets 404 on every route; a missing board is 404. The non-empty case seeds a card
straight through `CardRepository` (card *endpoints* don't exist until the next step) — a handy
reminder that the persistence layer is usable independently of the web layer.

```bash
cd server
./mvnw test -Dtest=ColumnIntegrationTest   # 6 passing
```

All green. Cards are the same shape one level down — and introduce the denormalized `board_id`.

### Step 2.5 — Card CRUD (append by rank; keep `board_id` in sync) · _2026-07-18_

**Goal:** add, edit, and delete cards within a column — the last write surface of the board.

**Files:**

- `server/src/main/java/org/cj/server/board/dto/{CreateCardRequest,UpdateCardRequest,CardResponse}.java`
- `server/src/main/java/org/cj/server/board/service/CardService.java`
- `server/src/main/java/org/cj/server/board/controller/CardController.java`
- `server/src/test/java/org/cj/server/board/CardIntegrationTest.java` (6 tests)

> **Concepts:** denormalization kept correct *by construction* · append via rank (again) ·
> optional fields · reusing the board guard a level deeper · consistent REST shape

**Almost identical to columns — on purpose.** `CardService` mirrors `ColumnService`: append a
new card with `LexoRank.between(lastCardInColumn, null)`, authorize through
`boardService.requireOwnedBoard(...)`, mutate-by-id for edit/delete, nested create under the
parent (`POST /api/columns/{columnId}/cards`). Consistency is a feature — once you understand
one CRUD surface here, you understand them all.

**The one genuinely new idea: the denormalized `board_id`.** A card stores *both* its
`columnId` and its `boardId`, even though the board is reachable via the column (Step 2.2). The
trick is *where* the value comes from: on create we read it **from the column**
(`column.getBoardId()`) rather than trusting any client input — so the denormalized copy is
correct *by construction* and can't drift. That copy is what makes the next step's whole-board
read a single query instead of a join. (When M3 moves a card to another column, that same field
must be re-synced — the reason it's called out as a moving part.)

**Edit is last-write-wins-ready.** `card.edit(title, description)` replaces both editable
fields and bumps `updatedAt` — the timestamp M5 will use to resolve concurrent edits. Nothing
special to do now; we just have to keep bumping it, which the entity does for us.

**How we verified.** `CardIntegrationTest`: two appends come back with increasing ranks *and*
the correct `boardId`/`columnId`; edit persists title + description; delete → 204; a non-owner
gets 404 on create/edit/delete; a missing column is 404; blank title is 400.

```bash
cd server
./mvnw test -Dtest=CardIntegrationTest   # 6 passing
```

All green. Every write path exists. The last piece is the read that returns a whole board at
once — and it's where the denormalized `board_id` pays off.

### Step 2.6 — The whole-board read: `GET /api/boards/{id}` · _2026-07-18_

**Goal:** return an entire board — its columns, each with its cards, all in order — in one
response, so the frontend can render everything from a single fetch.

**Files:**

- `server/src/main/java/org/cj/server/board/dto/BoardDetailResponse.java` (nested read model)
- `server/src/main/java/org/cj/server/board/service/BoardService.java` (`getDetail` + two repos)
- `server/src/main/java/org/cj/server/board/controller/BoardController.java` (`GET /{id}` now returns detail)
- `server/src/test/java/org/cj/server/board/BoardDetailIntegrationTest.java` (2 tests)

> **Concepts:** aggregate / read model (a DTO shaped for the screen, not the tables) · nested
> records · grouping in memory · where the denormalization pays off (one query, no join) ·
> preserving sort order through a group-by

**A response shaped for the UI, not the schema.** `BoardDetailResponse` is a *read model*: the
board's fields plus a `List<ColumnWithCards>`, where each `ColumnWithCards` pairs a column with
its ordered cards. It doesn't mirror any single table — it mirrors what the board *looks like*.
Building a dedicated DTO for a read is a common and healthy move; don't force the screen to
match the storage layout.

**Two queries, no joins — the denormalization dividend.** `getDetail` loads the columns
(`findByBoardIdOrderByRankAsc`) and **all** the board's cards in one shot
(`cards.findByBoardIdOrderByRankAsc`) — possible only because every card carries its
`board_id` directly (Step 2.2/2.5). Without that denormalized column we'd have to fetch cards
per-column or join through columns. Then we group the flat card list by `columnId` in memory.

**The ordering subtlety worth noticing.** The cards come back sorted by rank *globally*, and
we group them preserving encounter order (a `LinkedHashMap` + append). Because filtering a
rank-sorted list down to one column yields exactly that column's cards in rank order, each
column's `cards` list is correctly ordered **without a second sort**. Small detail, satisfying
when you see why it holds.

`GET /api/boards/{id}` now returns this detail view (replacing the flat `BoardResponse` from
2.3); the list endpoint still returns the lightweight summaries.

**How we verified — tests *and* the real thing.** `BoardDetailIntegrationTest` builds a board
with two columns and three cards and asserts the nested shape and both ordering levels, plus an
empty board returning an empty `columns` array. Then, per this project's habit, we drove the
**running jar** end-to-end with `curl`: register → create board → two columns → three cards →
`GET /api/boards/{id}`, which returned exactly the nested, rank-ordered structure (columns
`i` < `r`, cards `i` < `r` within a column), followed by non-empty-column delete → 409, board
delete → 204, and a final GET → 404.

```bash
cd server
./mvnw test          # whole suite: 58 passing
# (live smoke: build the jar, run it, curl the create→read→delete flow — all statuses matched)
```

> **A real gotcha we hit, worth remembering.** The first live smoke test returned `500`s — but
> the tests were green. The cause: a **stale server from an earlier run was still holding port
> 8080**, so the freshly built jar silently failed to start ("Port 8080 was already in use")
> and our `curl`s were hitting the *old* process. Lesson: when the live app disagrees with a
> green test suite, suspect *what's actually running* before the code. `lsof -nP -iTCP:8080
> -sTCP:LISTEN` shows who owns the port; kill it and relaunch.

---

### Where M2 stands

Done (backend): ✅ `LexoRank` ordering util (+ 2000-insert property test) · ✅ board-graph
migration `V3` + entities + repositories · ✅ Board CRUD with a reusable ownership guard +
owner auto-membership · ✅ Column CRUD (append; block-delete-if-non-empty) · ✅ Card CRUD
(append; denormalized `board_id` kept correct) · ✅ whole-board aggregate read · ✅ **58 tests**
+ live curl smoke.

**🎉 Milestone 2 (backend) complete.** A signed-in owner can build and edit a board's whole
structure over the API, everything persists in rank order, and access is scoped per-owner.

---

### Step 2.7 — The board UI: list, detail, and a card modal · _2026-07-19_

**Goal:** put a real interface on the M2 API — see your boards, open one, and create / rename /
delete columns and cards, with everything persisting in order. This is the milestone's payoff:
the app finally *looks* like a Kanban board.

**Files (all new unless noted):**

- `src/lib/boards.ts` — typed client for the board/column/card endpoints.
- `src/lib/auth-context.tsx` (edited) — added `authFetch` (see below); `src/lib/api.ts`
  (edited) — exported `apiFetch` so the context can wrap it.
- `src/app/dashboard/page.tsx` (rewritten) — the board list.
- `src/app/boards/[id]/page.tsx` — the board detail screen.
- `src/components/board/` — `board-column-view.tsx`, `card-modal.tsx`, `inline-confirm-button.tsx`.

> **Concepts:** dynamic route segment (`[id]`) · `useParams` · token refresh-on-401 ·
> optimistic local state from server responses · React portal · lifting state up ·
> two-step confirm vs `window.confirm` · surfacing a 409 in the UI

**The token that expires while you're working.** M1 left one thread loose: the access token
lives ~15 minutes, but a board session easily outlasts that. So the first thing M2's frontend
needed was `authFetch` in the auth context — it attaches the access token, and if a call comes
back **401**, it silently uses the stored refresh token to get a new access token and *retries
the request once*. Only if that refresh also fails does it treat you as logged out. The result:
a token quietly expiring mid-session is invisible to you, exactly as it should be. Every board
API call goes through it, so this is handled in one place, not sprinkled across components.

**One dynamic page for every board.** The board detail route is `app/boards/[id]/page.tsx`. The
`[id]` folder name is Next's **dynamic segment** — one file serves `/boards/<any-id>`. Because
this page is interactive (a Client Component), it reads the id with the `useParams()` hook
rather than the server-side `params` prop. A bad or non-owned id comes back as a 404, which we
catch and render as a friendly "Board not found" rather than a crash — the same
errors-are-values discipline from M1.

**Who owns the board's state?** The board page holds the whole nested board (columns → cards)
in one piece of React state, and passes *slices* down: each `BoardColumnView` gets its column
and its cards plus callbacks, but never fetches anything itself. This is "lifting state up" —
one owner of the truth, children just render and report intent. When a mutation returns the
updated entity from the server, we splice it into that state (append a created card, replace an
edited one, drop a deleted one) instead of re-fetching the whole board. Responsive, and it's
the exact shape M3's drag-and-drop will update.

**The card modal, and why a portal.** Clicking a card opens a modal to edit its title and
description. It's rendered with `createPortal` to `document.body` — otherwise it'd be nested
deep inside a column's DOM and could be clipped or mis-stacked by the columns' overflow/scroll.
A portal lets the component *live* in the React tree (props, state, callbacks all normal) while
its DOM *renders* at the top level, above everything.

**Deletes confirm in place — deliberately no `window.confirm`.** Every destructive action
(board, column, card) uses one small `InlineConfirmButton`: the first click swaps it to
"Confirm? / Cancel". We avoided the native `confirm()` dialog for two reasons — it's a jarring
OS popup, and (the practical one) a native dialog *blocks the page*, which would freeze the
automated browser test we use to verify. Building the confirm in React keeps both the UX and
the test smooth.

**Letting the server's "no" through.** Deleting a column that still has cards is refused by the
backend with a **409** ("Column is not empty; move or delete its cards first"). The column view
catches that specific error and shows the server's message inline, right under the column
header — the UI doesn't second-guess the rule, it just surfaces the server's answer.

**How we verified — the whole loop, in a real browser.** After `npm run build` type-checked all
routes, we drove the live app end to end: created a board, opened it, added "To Do" / "Done"
columns and two cards (they render in creation order); opened a card, edited its title +
description in the modal, saved (reflected on the board); deleted a card via the modal's
two-step confirm; tried to delete the non-empty column and got the inline 409; **reloaded the
page and everything persisted in order**; renamed the board inline; deleted the board and got
bounced back to the dashboard; and deep-linked a bogus board id to confirm the "not found"
state. Every step behaved.

---

### Where M2 stands

Backend: ✅ `LexoRank` + board graph + Board/Column/Card CRUD + ownership guard + 58 tests.

Frontend: ✅ board list (create/rename/delete) · ✅ board detail with columns + cards ·
✅ create/rename/delete columns and cards · ✅ card modal (portal) for edit/delete ·
✅ `authFetch` refresh-on-401 · ✅ inline two-step deletes · ✅ 409 surfaced inline ·
✅ reload-persists · ✅ verified end-to-end in a real browser.

**🎉 Milestone 2 complete — front to back.** You can build and edit a whole board's structure
in the browser, and it all persists in rank order.

Next: **M3 — drag-and-drop moves** (the `move` endpoints + `@dnd-kit`), reordering cards within
and across columns and reordering columns, reconciling to the server's canonical rank.

---

## Milestone 3 — Drag-and-drop moves

**Goal of the whole milestone:** reorder cards (within and across columns) and reorder
columns, with the order persisting. This phase builds the **backend half**: `move` endpoints
that accept *intent* ("put this card after that one") and reply with the server-computed
canonical rank. The `@dnd-kit` browser drag UI is the phase after.

Why intent instead of "here's my new rank"? Because the server must stay the **single
authority on ordering** (the M5 real-time story depends on it: two people dragging at once
both get valid, converging results). A client that computed its own rank could be stale,
malicious, or just wrong; a client that says "after card X" can be safely interpreted against
the server's current truth.

### Step 3.1 — `LexoRank.spread`: the re-balance primitive · _2026-07-19_

**Goal:** be able to hand a container (a column's cards, a board's columns) a fresh set of
short, evenly spaced rank keys — the escape hatch for when repeated inserts in one spot have
ground the rank space down to nothing.

**Files:**

- `server/src/main/java/org/cj/server/common/ranking/LexoRank.java` (new `spread(count)`)
- `server/src/test/java/org/cj/server/common/ranking/LexoRankTest.java` (4 new tests → 15)

> **Concepts:** rank exhaustion · re-balancing / re-spacing · fixed-width base-36 rendering ·
> even distribution & maximal gaps · designing the primitive before the routine that uses it

**The problem, concretely.** Back in Step 2.1, `between` got a length cap: keys grow a digit
each time you subdivide the same gap, and past `MAX_LENGTH` (48) it throws
`RankExhaustedException` rather than growing forever. M2 never hits that; M3's endless
dragging eventually can. The fix is a **re-balance**: forget the old cramped keys, deal every
item in the container a brand-new short key, evenly spaced, in the same order — then the
failed insert retries against roomy gaps.

**What `spread(count)` does.** It returns `count` strictly increasing keys distributed evenly
across the whole rank space:

1. Pick the smallest fixed width `w` with `36^w ≥ count + 2` — the `+2` guarantees a usable
   gap *before the first* and *after the last* key, not just between them.
2. Step through the numeric space in increments of `36^w / (count + 1)`, rendering each value
   as exactly `w` base-36 digits (leading zeros kept — fixed width is what makes string
   comparison keep matching numeric comparison).

So 10 items get 2-char keys, 1000 items get 2-char keys, and every neighbouring pair is left
with the *maximum possible* room for future inserts. A pleasing consistency: `spread(1)`
yields `["i"]` — the exact key `between(null, null)` gives a first item.

**Why build the primitive first, separately.** The re-balance *routine* (catch the exception,
reassign, retry) lives in the move services (Steps 3.2/3.3) and touches entities,
repositories, and transactions. The *math* of generating good keys is pure and testable in
isolation — same reasoning as Step 2.1: get the sharp edge airtight before wiring it in.

**How we verified.** Four new tests: strictly-increasing + unique for counts 1/2/35/36/100/1000
(the 35→36 boundary crosses the width-1→2 threshold); keys stay short (≤ 2–3 chars); an
interleaving test proving `between` works before the first, between every pair, and after the
last spread key (the exact operations a post-re-balance drag performs); and non-positive
counts are rejected.

```bash
cd server
./mvnw test -Dtest=LexoRankTest   # 15 passing (11 old + 4 new)
```

All green. Now the endpoint that puts it to work.

### Step 3.2 — Moving cards: `PATCH /api/cards/{id}/move` · _2026-07-19_

**Goal:** the write behind every card drag — reorder within a column, move across columns,
land in an empty column — expressed as intent and answered with the canonical rank.

**Files:**

- `server/src/main/java/org/cj/server/board/dto/MoveCardRequest.java`
- `server/src/main/java/org/cj/server/board/entity/Card.java` (`moveTo`, `rebalanceRank`)
- `server/src/main/java/org/cj/server/board/service/CardService.java` (`move` + placement + re-balance)
- `server/src/main/java/org/cj/server/board/controller/CardController.java` (the route)
- `server/src/test/java/org/cj/server/board/CardMoveIntegrationTest.java` (9 tests)

> **Concepts:** intent-based API design · resolving intent against server truth · anchor
> (before/after) semantics · catch-repair-retry (re-balance) · why re-balance must not bump
> `updatedAt` · sabotage-style testing

**The request is a sentence, not a number.** `MoveCardRequest` carries `targetColumnId` plus
at most one anchor: `afterCardId` ("put it right after this card"), or `beforeCardId`, or
neither ("append"). No rank anywhere. The service turns that sentence into rank bounds by
loading the target column's cards **in its own current order** (minus the moving card — it
may already be in that column), finding the anchor, and taking the neighbour on the far side
from the *server's* list, not the client's. Then `LexoRank.between(prev, next)` produces the
rank, and the response returns the card with its resolved `rank` and `columnId` — what the
client reconciles to.

Why this matters: a client that drags against a *stale* view still names a real card, and the
server places the moved card relative to where that anchor **actually is now**. Wrong-by-a-
little beats corrupt. This is the property that makes M5's concurrent dragging convergent.

**The re-balance routine — catch, repair, retry.** If the anchor's gap has been subdivided to
death, `between` throws `RankExhaustedException` (Step 2.1's tripwire). The service then:
(1) deals the column's cards fresh keys from `spread` (Step 3.1) in unchanged order,
(2) re-resolves the placement — the old bounds are dead — and (3) retries `between` once,
which now succeeds against roomy gaps. One subtlety: re-balanced siblings get their rank via
`rebalanceRank`, which deliberately does **not** bump `updatedAt` — re-spacing is server
bookkeeping, and an untouched card must never look "recently edited" to M5's last-write-wins
conflict rule. The *moved* card, by contrast, goes through `moveTo`, which does bump it — a
move is a real user action.

**Guardrails.** The target column must belong to the card's own board (cross-board moves are
refused with 400) — which is exactly what keeps the denormalized `card.board_id` forever
correct. An anchor that isn't in the target column is 400. Unknown card/column → 404;
non-owner → 404 via the same `requireOwnedBoard` guard as everything else.

**How we verified.** Nine full-stack tests, all asserting through `GET /api/boards/{id}` —
the order a client actually sees: after/before/append within a column; prepend before the
first card; cross-column move (columnId changes, boardId doesn't, both columns' orders
right); move into an empty column; wrong-column anchor → 400; cross-board target → 400;
unknown ids → 404 and missing target → 400; non-owner → 404. And the fun one — a **sabotage
test** for the re-balance: create two cards, then reach *under* the API via the repository
and hand them adversarially adjacent ranks (`"a"` and `"a0…01"` at the length cap), so the
very first `between` must throw. Drive the real endpoint to move a third card into that gap:
the move lands exactly where intended **and** every rank in the column comes back short.

```bash
cd server
./mvnw test -Dtest=CardMoveIntegrationTest   # 9 passing
```

All green. Columns move the same way, one level up.

### Step 3.3 — Moving columns: `PATCH /api/columns/{id}/move` · _2026-07-19_

**Goal:** reorder the columns themselves — the other half of drag-and-drop.

**Files:**

- `server/src/main/java/org/cj/server/board/dto/MoveColumnRequest.java`
- `server/src/main/java/org/cj/server/board/entity/BoardColumn.java` (`moveTo`, `rebalanceRank`)
- `server/src/main/java/org/cj/server/board/service/ColumnService.java` (`move`)
- `server/src/main/java/org/cj/server/board/controller/ColumnController.java` (the route)
- `server/src/test/java/org/cj/server/board/ColumnMoveIntegrationTest.java` (4 tests)

> **Concepts:** the same pattern one level up · deliberate duplication vs premature
> abstraction · smoke-testing on an alternate port

**Deliberately the same shape as 3.2.** `MoveColumnRequest` names an optional neighbour
(`afterColumnId` / `beforeColumnId`, neither = append); the service loads the board's columns
in current order minus the moving one, resolves the anchor into prev/next bounds, computes the
rank with `between`, and on `RankExhaustedException` re-spaces via `spread` and retries once.
The only differences: siblings come from the *board* rather than a column, and there's no
cross-container concern at all — columns can never change boards, so the request has no
target-board field.

**On the duplication.** `CardService.move` and `ColumnService.move` share their skeleton
(`Placement`, `resolvePlacement`, `rebalance`) with different entity types. We *could* extract
a generic helper — and chose not to: two small, readable copies beat one abstraction invented
for exactly two users ("rule of three": wait until a third case shows what the abstraction
really is). If M6+ adds another ranked container, that's the moment to unify.

**How we verified.** Four tests: after/before/append reorder the board (asserted through the
aggregate); prepend before the first; an anchor column from a *different* board → 400; unknown
column / non-owner → 404. Then the full suite — **75 tests** — and a live curl smoke of the
whole M3 flow. One operational note: port 8080 was already occupied (a dev server), and
instead of killing it we ran the smoke jar with `--server.port=8081` — Spring Boot properties
are overridable from the command line, and testing on an alternate port beats killing a
process you didn't start.

The smoke transcript, condensed — every move answered with the resolved rank, and a fresh GET
("reload") showed it all persisted:

```text
start:      To do: A B C | Done:
C after A → To do: A C B          (resolved rank "m" — between A's "i" and B's "r")
B → Done  → To do: A C | Done: B  (columnId changed, boardId didn't)
Done before To do → Done: B | To do: A C   (rank "9" < "i")
reload    → Done: B | To do: A C  ✓ persisted
anchor from wrong column → HTTP 400 ✓
```

---

### Where M3 stands

Backend: ✅ `LexoRank.spread` re-balance primitive · ✅ `PATCH /api/cards/{id}/move` (intent →
canonical rank; cross-column with `board_id` intact; exhaustion → re-space → retry) ·
✅ `PATCH /api/columns/{id}/move` · ✅ **75 tests** + live curl smoke.

**🎉 Milestone 3 backend complete.** Every reorder a drag can express is now a one-request,
server-authoritative write.

---

### Step 3.4 — Dragging cards in the browser (`@dnd-kit`, Pass A) · _2026-07-19_

**Goal:** actually *drag* cards — reorder within a column and move across columns — with the
board updating instantly and then reconciling to the server's canonical rank. (Dragging whole
columns is a deliberate Pass B; cards are the higher-value interaction, verified first.)

**Files (all new unless noted):**

- `src/components/board/sortable-card.tsx` — a card wrapped in `@dnd-kit`'s `useSortable`,
  plus a shared `CardFace` for the drag preview.
- `src/components/board/board-column-view.tsx` (edited) — cards wrapped in a `SortableContext`;
  the card area made a droppable so empty columns accept drops.
- `src/app/boards/[id]/page.tsx` (edited) — the `DndContext`, sensors, drag handlers, the
  optimistic move + reconcile + rollback, and the `DragOverlay`.
- `src/lib/boards.ts` (edited) — `moveCard` + `MoveCardBody`.
- `package.json` — added `@dnd-kit/core`, `/sortable`, `/utilities`.

> **Concepts:** drag intent vs rank · optimistic UI · reconcile-to-server · snapshot rollback ·
> `@dnd-kit` DndContext / SortableContext / useSortable / DragOverlay · droppable containers ·
> pointer activation distance (click vs drag) · collision detection

**Move as intent, never a rank — the frontend's half of a decision made back in M2.** The client
does *not* compute a LexoRank. On drop it sends *where* the card landed —
`{ targetColumnId, afterCardId }` (or `beforeCardId`, or neither = append) — and the server
returns the canonical `rank`. So the browser only ever describes intent; the server stays the
single source of truth on ordering. This is why nothing about the client changes when real-time
arrives in M5: everyone's already reconciling to the server.

**Optimistic, then reconcile.** A drag that waits for the network feels broken. So on drop we
*immediately* rewrite local state to the new order (the card jumps to where you dropped it),
then fire the move request in the background. When it returns we splice the server's version of
that card back in — same position, but now with the authoritative `rank`/`updatedAt` for the
*next* drag. If the request fails, we restore a **snapshot** taken at drag start and show an
error banner: the board snaps back to exactly what it was. (Because every state update is
immutable, that snapshot's arrays stay untouched while we optimistically replace them — a quiet
payoff of never mutating in place.)

**One knob that makes click *and* drag both work: activation distance.** A card is clickable
(opens the modal) *and* draggable. Those fight unless you tell the pointer sensor "don't start a
drag until the pointer has moved ~8px." A plain click never crosses that threshold, so it falls
through to `onClick`; a real drag does. One number resolves the whole ambiguity.

**Dropping into an empty column.** `@dnd-kit`'s sortable only knows about *cards*, so an empty
column has nothing to sort against. The fix: make each column's card area a **droppable** keyed
by the column id. When you drop over empty space, the collision result is the column id → we
append. Over a card → we read that card's index and insert there.

**A verification catch worth keeping.** The first drag failed with the board snapping back and
an error banner. The frontend was fine — the *running backend was stale*: it had been started
before the M3 move endpoints existed, so `PATCH /api/cards/{id}/move` hit "no such route" and
404'd. Restarting the backend fixed it instantly. Two lessons: (1) `spring-boot:run` doesn't
hot-reload new endpoints — restart after backend changes; (2) the failure accidentally *proved*
the rollback path — the optimistic move reverted cleanly and surfaced the error, exactly as
designed.

**How we verified — real drags in a real browser.** After `npm run build`, we drove it: clicked
a card (modal still opened — activation distance intact); dragged a card down within a column;
dragged a card into the empty column (append); dragged a card above a specific card in another
column (insert-before); and **reloaded after each — every order persisted server-side.** The
rollback path we saw for real via the stale-backend catch above.

---

### Step 3.5 — Dragging columns too (`@dnd-kit`, Pass B) · _2026-07-19_

**Goal:** the other half of drag-and-drop — reorder whole *columns* by dragging, same
optimistic + reconcile model as cards. This completes M3's frontend.

**Files:** `src/components/board/board-column-view.tsx` (edited — column now `useSortable`, grip
handle, renamed card-drop id), `src/app/boards/[id]/page.tsx` (edited — horizontal
`SortableContext`, a type-aware collision detector, a column branch in the drag handlers,
column overlay), `src/lib/boards.ts` (edited — `moveColumn`).

> **Concepts:** drag handle · mixed draggables in one DndContext · custom collision detection ·
> horizontal sortable · id namespacing to avoid collisions · not regressing an existing feature

**A column isn't a card — it needs a drag *handle*.** A card is a single clickable thing, so the
whole card is draggable (with an activation distance to still allow clicks). A column is the
opposite: it's a *container* full of controls — a click-to-rename title, a delete button, an
add-card input, and the draggable cards themselves. Make the whole column draggable and it
fights every one of them. The fix is a dedicated **grip handle** (`⠿`) in the header: only the
grip carries the drag listeners, so everything else in the column stays normally interactive.
This is why the card and column drag ergonomics differ — the right affordance follows from what
the thing *is*.

**One DndContext, two kinds of draggable — kept apart by a custom collision detector.** Cards and
columns now live in the same `DndContext`. Two problems fall out. First, an *id collision*: the
column was already a droppable keyed by its id (so empty columns accept card drops), and making
it `useSortable` would reuse that same id for a second purpose. Fix: namespace the card-drop
target as `cards:<columnId>` and keep the bare id for the column sortable. Second, *cross-talk*:
without help, a dragged column would try to "drop into" a card, and a dragged card would collide
with column handles. Fix: a **custom `collisionDetection`** that reads what's being dragged
(`active.data.current.type`) and only considers targets of the matching kind — columns collide
with columns, cards with cards and card-areas. Each draggable/droppable tags itself with a
`type` in its `data`, and the detector filters on it. This is the standard dnd-kit answer for a
board that drags at two levels.

**The rest mirrors cards.** Columns sit in a **horizontal** `SortableContext`
(`horizontalListSortingStrategy`); on drop we `arrayMove` the columns optimistically, express the
move as intent (`afterColumnId` = the column now to the left, else `beforeColumnId` = the one to
the right, else append), call `moveColumn`, reconcile the returned rank, and roll back to the
drag-start snapshot on failure — the exact shape Pass A established.

**The discipline that mattered most: don't regress Pass A.** Adding a second draggable type is
exactly the kind of change that quietly breaks the first. So verification re-tested *card*
dragging explicitly, not just the new column dragging. In a real browser we: dragged a column by
its grip to the front, the middle, and the end — each persisted through a reload (confirmed
against the server's stored ranks); confirmed cards ride along with their column; then went back
and dragged a *card* within a column (still works), clicked a card (modal still opens), and
renamed a column (the grip didn't swallow the title click). All green — the collision filter
cleanly separates the two interactions.

> **A verification habit:** when a screenshot mid-sequence looked "off," we didn't trust the
> pixels — we queried the server's actual column ranks and re-ran a single clean drag in
> isolation. Ground truth lives in the database, not a cramped screenshot.

---

### Where M3 stands

Backend: ✅ move endpoints (intent → canonical rank, cross-column, exhaustion re-space) · 75 tests.

Frontend: ✅ drag cards within/across columns (incl. empty) · ✅ **drag columns to reorder** ·
✅ optimistic move + reconcile to server rank · ✅ snapshot rollback · ✅ card click-to-open and
column rename/delete all intact under a two-level DndContext · ✅ verified end-to-end in a real
browser (front/middle/end column moves persisted).

**🎉 Milestone 3 complete — front to back.** Every reorder a drag can express — cards within a
column, cards across columns, and columns among themselves — is a one-request, server-authoritative
write, reflected instantly and reconciled to the canonical rank.

Next: **M4 — sharing, invites & role enforcement** (owner/editor/viewer), then **M5** (real-time).

---

## Milestone 4 — Sharing, invites & role enforcement

**Goal of the whole milestone:** a board stops being single-user. The owner invites people by
email; invitees get access on their next sign-in; and every endpoint enforces per-board roles —
`VIEWER` read-only, `EDITOR` mutates columns/cards, `OWNER` additionally manages the board and
its members. This phase is the **backend**; the invite/member UI is the phase after.

The satisfying part: M2 already did the groundwork. The `board_membership` table, its
constraints (including the partial-unique pending-invite index), and the owner's auto-created
`OWNER`/`ACTIVE` row have been in place since `V3` — so M4 needs **no migration and no
backfill**. We planted this deliberately; now it pays off.

Build order: **data first, meaning second.** 4.1 creates memberships via the API (owner-only
management), 4.2 makes pending invites resolve at sign-in, and only then does 4.3 flip the
authorization switch so memberships actually grant access. Mid-phase, an invite exists but
grants nothing — a harmless, reviewable intermediate state.

### Step 4.1 — Invites & member management · _2026-07-19_

**Goal:** the owner can invite an email, see the member list, change a member's role, and
remove members or revoke invites.

**Files:**

- `board/entity/BoardMembership.java` (new factories `invitePending`/`inviteActive`, mutators
  `activate`/`changeRole`)
- `board/repository/BoardMembershipRepository.java` (four new derived queries)
- `board/dto/{CreateInviteRequest,UpdateMembershipRequest,MembershipResponse}.java`
- `board/service/MembershipService.java` · `board/controller/MembershipController.java`
- `test/.../board/MembershipIntegrationTest.java` (10 tests)

> **Concepts:** invite-by-email design (pending vs immediate) · one row, two shapes · email
> normalization as a matching contract · batch loading (avoiding N+1) · single-owner
> invariant enforced in code · joining display data into a response DTO

**One row, two shapes.** A `board_membership` row is either an **ACTIVE member** (has a
`user_id`) or a **PENDING invite** (only an `invited_email` — the person hasn't registered
yet). `invite(...)` picks the shape at creation time: if `UserRepository.findByEmail` finds an
account, the membership is born ACTIVE and takes effect immediately; otherwise it's born
PENDING and waits for that email to sign up (Step 4.2). The V3 `CHECK (user_id IS NOT NULL OR
invited_email IS NOT NULL)` guarantees every row identifies *someone*.

**Email normalization is a contract, not a nicety.** Registration lowercases+trims emails
(Step 1.2). Invites must normalize the *same way*, or `Bob@X.com`'s invite would never match
`bob@x.com`'s sign-in and would hang pending forever. The test invites a mixed-case address
and asserts the stored invite is lowercase.

**The single-owner invariant, enforced three ways.** A board has exactly one OWNER, so:
inviting *as* OWNER → 400; re-roling anyone *to* OWNER → 400; changing or removing the owner's
own row → 400. (Inviting the owner's email is a 409 — they're already a member.) Ownership
transfer is deliberately out of scope for v1.

**Small performance habit worth seeing.** `listMembers` needs each member's name/email, which
live in `app_user`, not on the membership row. The naive loop would issue one user query per
member — the classic **N+1 problem**. Instead we collect the user ids and load them in one
`findAllById` batch, then map in memory. At ≤10 members it wouldn't matter; the habit does.

**Routes** follow the established shape — create/list under the parent
(`POST /api/boards/{id}/invites`, `GET /api/boards/{id}/members`), mutate by id
(`PATCH`/`DELETE /api/memberships/{id}`). All owner-guarded via the same
`requireOwnedBoard` as everything else (4.3 relaxes the member list to any member).

**How we verified.** Ten full-stack tests: unknown email → PENDING (and listed after the
owner); mixed-case email stored normalized; existing user → ACTIVE with `name` joined from
`app_user`; duplicate active + duplicate pending → 409; owner self-invite → 409 and
role=OWNER → 400; EDITOR↔VIEWER re-role works but →OWNER → 400; the owner row can't be
changed or removed; removing a member and revoking a pending invite shrink the list back to
just the owner; a non-owner gets 404 on all four routes; malformed email → 400.

```bash
cd server
./mvnw test -Dtest=MembershipIntegrationTest   # 10 passing
```

All green. The rows exist — next, pending invites come alive when their email signs in.

### Step 4.2 — Pending invites resolve at sign-in (application events) · _2026-07-19_

**Goal:** the moment an invited email registers (or logs in), its PENDING invites flip to
ACTIVE memberships — the shared board is just *there* on their first dashboard load.

**Files:**

- `auth/service/UserSignedInEvent.java` (the announcement)
- `auth/service/AuthService.java` (publishes it in `register` + `authenticate`)
- `board/service/PendingInviteResolver.java` (the listener)
- `test/.../board/InviteResolutionIntegrationTest.java` (3 tests)
- `test/.../auth/service/AuthServiceTest.java` (constructor gained the publisher — no-op lambda)

> **Concepts:** Spring application events · dependency direction & package cycles ·
> `@TransactionalEventListener(AFTER_COMMIT)` · `REQUIRES_NEW` · foreign keys vs uncommitted
> rows · idempotent listeners

**Why an event and not a method call.** The requirement reads "on login/register, resolve
pending invites" — which sounds like `AuthService` should call the board feature. But the
dependency arrow already points the other way (`board → auth`: board controllers use
`AuthPrincipal`, the membership service reads `UserRepository`). Auth calling board back would
create a **package cycle** — the classic sign that two modules are dissolving into one. A
Spring **application event** inverts it: `AuthService` publishes `UserSignedInEvent(userId,
email)` and knows nothing else; `PendingInviteResolver` in the board feature listens. Auth
stays reusable; features stay separable.

**The transaction choreography — where the real bug lived.** First draft: a plain
`@EventListener` with `REQUIRES_NEW`. Looks fine, and would have **failed on every
registration**: the event publishes *inside* `register()`'s transaction, so a listener opening
its *own* transaction can't see the not-yet-committed user row — and inserting a membership
that references it violates the `board_membership.user_id` foreign key. The fix is the
canonical pairing:

- `@TransactionalEventListener(phase = AFTER_COMMIT)` — don't run until the signup transaction
  has committed and the user row exists for everyone; plus
- `@Transactional(REQUIRES_NEW)` — the original transaction is finished, so the listener's
  writes need a fresh one. (Also neatly covers login, whose transaction is *read-only* — it
  still commits, the event still fires, and the writes happen in the new transaction.)

Worth remembering as a rule of thumb: **a listener that writes rows referencing data from the
publishing transaction should be `AFTER_COMMIT` + `REQUIRES_NEW`.**

**The listener itself is small and idempotent.** Find PENDING invites for the email; for each,
attach the user id and flip to ACTIVE. One edge: if the user somehow already holds a
membership on that board (double-invited by different routes), `UNIQUE(board_id, user_id)`
forbids a second row — the stale pending invite is deleted instead. Firing again on a later
login finds nothing pending and does nothing.

**How we verified.** Three full-stack tests where the *trigger is the auth endpoint itself*:
invite an unregistered email → PENDING → that email registers → the members list shows ACTIVE
with the user id, the invited role kept, and the name now joined from `app_user`; a later
login is harmless (idempotence); and two boards inviting the same unregistered email both
resolve on one registration. (The compile also flushed out that `AuthServiceTest` constructs
`AuthService` by hand — it now passes a no-op `event -> { }` publisher, keeping the unit test
focused on auth logic.)

```bash
cd server
./mvnw test -Dtest='InviteResolutionIntegrationTest,AuthServiceTest'   # 9 passing
```

All green. Memberships now exist and resolve — the finale makes them *mean* something.

### Step 4.3 — Role enforcement: membership becomes the source of truth · _2026-07-20_

**Goal:** flip the switch. Until now every board/column/card operation asked "do you *own* this
board?"; from here it asks "what is your *role* on this board, and is it enough?" — the moment
an invite actually buys the invitee something.

**Files:**

- `board/service/BoardService.java` (`requireOwnedBoard` → `requireBoardAccess(…, Role)`;
  `listOwned` → `listAccessible`)
- `board/service/{ColumnService,CardService,MembershipService}.java` (call sites pass the role
  each operation needs)
- `board/repository/BoardRepository.java` (`findByOwnerIdOrderByCreatedAtDesc` →
  `findByIdInOrderByCreatedAtDesc`)
- `board/dto/{BoardResponse,BoardDetailResponse,BoardWithRole}.java` (`myRole` on the wire)
- `board/controller/BoardController.java`
- `test/.../board/RoleEnforcementIntegrationTest.java` (8 tests)

> **Concepts:** capability ladder on an enum · 403 vs 404 as an information-disclosure choice ·
> one guard, many required levels · authorization at the service layer · avoiding N+1 when a
> response needs per-row context

**One guard, three levels.** The temptation is a method per role — `requireOwner`,
`requireEditor`, `requireViewer`. Instead there's a single `requireBoardAccess(boardId, userId,
Role required)`, and the ladder lives on the enum:

```java
public boolean atLeast(Role required) {
    return this.ordinal() <= required.ordinal();   // OWNER < EDITOR < VIEWER, declaration order
}
```

Cumulative capabilities fall out for free: an OWNER passes an EDITOR check, an EDITOR passes a
VIEWER check. Using `ordinal()` is normally a code smell — it's only safe here **because
storage is `@Enumerated(STRING)`**, so the ordinal never reaches the database and reordering
the constants can't corrupt data. (It would still change the meaning of the ladder, which is
why the enum's javadoc now says "declared strongest-first" out loud.)

**403 and 404 mean different things, and the difference is a security decision.**

| Caller | Result |
|---|---|
| no ACTIVE membership | **404** — same answer as a board that doesn't exist |
| ACTIVE member, role too weak | **403** — you're here, just not allowed *this* |

Returning 403 to a stranger would confirm the board is real: an attacker with a list of UUIDs
could map which ones exist. A non-member gets the same reply for a real board and a fictional
one. A *member* already knows the board exists, so hiding behind 404 would only confuse them —
403 is both honest and more useful. A PENDING invite deliberately grants nothing; only
`status = ACTIVE` counts.

**Ownership stopped being a column and became a row.** `requireBoardAccess` never reads
`board.ownerId` — it reads the caller's `board_membership` row and checks the role. The OWNER
row written back in M2 alongside every board (planted for exactly this day) is what proves
ownership now. One table answers every access question; `board.owner_id` survives only as
display data.

**The change that actually made sharing visible.** Guards alone weren't enough: `GET
/api/boards` still ran `findByOwnerId…`, so an invited editor could open a shared board by URL
but never *see* it listed. It became `listAccessible` — walk the caller's ACTIVE memberships,
fetch those boards. Sharing you can't discover isn't sharing.

That same query hands back the role for free, which is why `BoardWithRole` exists: the
controller needs `myRole` per board, and re-querying it per row would be a textbook N+1. The
one place a role isn't looked up at all is board *create* and *rename* — creating makes you the
owner, and renaming is owner-only, so a successful call already proves the role.

**`myRole` on the wire.** Both `BoardResponse` and `BoardDetailResponse` now carry the caller's
role. Without it the frontend would have to discover permissions by attempting writes and
reading 403s — the UI must know *before* it renders whether to show an "add card" box.

**How we verified.** `RoleEnforcementIntegrationTest` drives real HTTP as real signed-in users.
The trick that gives it teeth: the *entire* write surface is built once as a list of request
builders and replayed per role — editor gets 2xx on all eight, viewer gets 403 on all eight,
stranger gets 404 on all eight. Checking a sample instead of the whole list is exactly how a
permission hole ships. Plus: role appears correctly in each user's board list, removing a
member makes the board invisible again, and a demoted editor keeps read access while losing
writes.

The strongest signal, though, is that **all 88 pre-existing tests passed unchanged** — they act
as the board owner throughout, so an owner's experience had to be byte-for-byte identical after
swapping the entire authorization model underneath.

```bash
cd server
./mvnw test    # 96 passing (88 existing + 8 new)
```

---

### Where M4 stands

Backend: ✅ invites (pending + immediate) · ✅ member list / re-role / remove · ✅ pending invites
resolve at sign-in · ✅ **role enforcement across every endpoint** · ✅ shared boards appear in
`GET /api/boards` with `myRole` · 96 tests.

Frontend: ⬜ not started — invite form, member list with roles, and read-only rendering for
viewers.

**Milestone 4 backend complete.** A board is now genuinely multi-user: the owner shares it, the
invitee finds it waiting at next sign-in, and the server — not the UI — decides what each of
them may do.

Next: **M4 frontend**, then **M5** (real-time), whose WebSocket subscription check will reuse
`requireBoardAccess` as-is.

### Step 4.4 — The sharing UI and read-only viewers · _2026-07-20_

**Goal:** make the backend's roles visible and usable — invite people from the app, see who has
access, and render a board a viewer *can't* edit as a board that doesn't *offer* editing.

**Files:**

- `lib/boards.ts` (`Role` type; `myRole` on `Board`, inherited by `BoardDetail`)
- `lib/members.ts` (new — the membership API client)
- `components/board/share-modal.tsx` (new — invite form, roster, `RoleBadge`)
- `components/board/{board-column-view,sortable-card,card-modal}.tsx` (`canEdit`)
- `app/boards/[id]/page.tsx`, `app/dashboard/page.tsx`

> **Concepts:** permissions as derived UI state · hiding vs disabling vs render-then-reject ·
> `useSortable({ disabled })` · defence in depth on the client · expected errors as UI copy

**Derive once, pass booleans down.** The board page computes two values from the role the
server sent:

```tsx
const canEdit = board !== null && board.myRole !== "VIEWER";
const isOwner = board !== null && board.myRole === "OWNER";
```

and hands `canEdit` to the columns, cards, and card modal. The components never see the role
string. That's deliberate: if every component re-derived policy from `myRole`, the rule "what
may an editor do?" would live in eight places and drift in seven of them. Note the explicit
null guard — `board?.myRole !== "VIEWER"` would be `true` while the board is still loading,
which is the wrong default for a permission.

**Hide, don't disable, and never render-then-reject.** Three options for a control a viewer
can't use: render it and let the 403 come back, render it greyed out, or don't render it. We
chose the third. A 403 after clicking teaches the user nothing until they've already failed;
a disabled button advertises a capability they don't have. So viewers get no add-card box, no
add-column form, no delete buttons, no grip handles, and a plain `<h1>` where the owner has a
click-to-rename title. **This is why the server sends `myRole` with the board payload** — the
UI has to know before it renders, and a separate round trip to ask "what am I?" would be a
second source of truth.

**Dragging is switched off with dnd-kit's own flag.** `useSortable({ disabled: !canEdit })`
rather than conditionally removing the `DndContext` or the sortable hooks. Hooks can't be
called conditionally anyway, and keeping the tree identical across roles means no remount when
a role changes. The drag handlers *also* start with `if (!canEdit) return`. Redundant today —
belt and braces — because a sensor added later shouldn't be able to fire a move behind our
back. Client-side checks are UX, never security: the server refuses regardless.

**Expected errors are copy, not crashes.** Inviting someone twice is a 409, and a malformed
address is a 400 with `fieldErrors`. Both are *normal things a user does*, so the invite form
prints the server's own message inline ("Already a member of this board") — more precise than
anything we'd invent. Pending invites get the same care: a PENDING row shows a badge and
"Hasn't signed up yet", and the success note says the invitee will get access when they sign
up. Without that, an owner invites an unregistered friend, sees nothing happen, and invites
again.

**The one control we refuse to draw.** The owner's own row has no role dropdown and no remove
button, because the server forbids re-roling or removing it. Offering a control that can only
fail is a lie the UI tells about itself.

**How we verified** (two accounts, real browser): owner invites a registered user → ACTIVE
immediately with their name; invites an unknown address → PENDING; invites the same person
twice → inline 409. As the viewer: the board appears on their dashboard with a Viewer badge and
no rename/delete, the board page has no edit affordances at all, a card opens read-only, and a
drag does nothing. Promoted to EDITOR: the grips, composers and delete buttons come back, a
card actually moves and *persists through a reload*, a new card saves — but the board title
stays unclickable and "Delete board" never appears. Removed entirely: "Board not found" and an
empty dashboard, on the very next request.

```bash
cd frontend
npm run build   # clean
```

---

### Where M4 stands

Backend: ✅ invites · ✅ member management · ✅ invites resolve at sign-in · ✅ role enforcement ·
96 tests.

Frontend: ✅ share modal (invite by email + role, roster, pending badges, re-role, remove) ·
✅ shared boards on the dashboard with role badges · ✅ viewers fully read-only (no controls,
no drag, read-only card modal) · ✅ editors edit content but can't administer the board ·
✅ verified end-to-end across owner / editor / viewer / removed-member.

**🎉 Milestone 4 complete — front to back.** TaskBoard is now genuinely collaborative: a board
has people on it, each with a role the server enforces and the UI honours.

Next: **M5 — real-time (WebSocket + STOMP)**. Roles came first on purpose — enforcing
permissions after opening the event firehose is far harder. The subscription check will reuse
`requireBoardAccess` unchanged.

---

## Milestone 5 — Real-time (WebSocket + STOMP)

Goal: two browsers on one board see each other's work within a second. Everything before this
milestone made a *correct* board; this one makes it a *shared* one.

### Step 5.1 — The event pipeline: publish after commit, subscribe by membership · _2026-07-20_

**Goal:** stand up the socket, prove identity on it, refuse anyone who isn't a member of the
board they ask for, and broadcast every mutation to the board's topic — without the board
services learning that WebSockets exist.

**Files:**

- `realtime/config/WebSocketConfig.java` (new — STOMP endpoint `/ws`, simple broker `/topic`)
- `realtime/security/StompAuthChannelInterceptor.java` (new — CONNECT auth + SUBSCRIBE authz)
- `realtime/security/StompSecurityException.java` (new)
- `realtime/service/BoardEventBroadcaster.java` (new — the `AFTER_COMMIT` listener)
- `realtime/dto/BoardEvent.java` (new — the wire format)
- `board/service/BoardChangedEvent.java` (new — the domain event)
- `board/dto/{BoardEventType,DeletedRef,BoardSummary}.java` (new)
- `board/service/{Card,Column,Board,Membership}Service.java` (publish on every mutation)
- `auth/security/SecurityConfig.java` (`/ws/**` handshake public)
- `test/.../realtime/RealtimeIntegrationTest.java` (9 tests)

> **Concepts:** application events as a dependency-inversion tool · `@TransactionalEventListener`
> and why `AFTER_COMMIT` is non-negotiable · authenticating a protocol that has no headers ·
> authorizing *subscriptions*, not just requests · testing a socket with a real socket

**The services still don't know the socket exists.** `CardService.create` doesn't call a
broadcaster; it publishes a `BoardChangedEvent` and moves on. The `realtime` package listens.
This is the same trick M4.2 used to resolve pending invites, for the same reason: the arrow
runs `realtime → board` only, so deleting the entire real-time package would leave the REST API
working. A service that both writes a card *and* pushes a WebSocket frame is a service doing
two jobs, and the second one fails in ways the first shouldn't care about.

**`AFTER_COMMIT` is the whole design.** The event is published *inside* the write transaction
but broadcast only once that transaction commits:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onBoardChanged(BoardChangedEvent event) {
    messaging.convertAndSend("/topic/board/" + event.boardId(), BoardEvent.from(event));
}
```

Broadcasting earlier would announce changes that can still roll back — subscribers would render
a card that never existed. Worse than the phantom card is the *correction*: a client that reacts
by refetching the board would read pre-commit state and "fix" itself to stale data. The cost of
this choice is worth knowing — with the default `fallbackExecution = false`, an event published
outside any transaction is **silently dropped**. That's acceptable only because every publisher
is a `@Transactional` service method.

**A protocol with no headers.** The browser's WebSocket API gives you no way to set an
`Authorization` header on the upgrade request — so `/ws/**` is `permitAll()` at the HTTP layer.
That looks alarming and isn't: the handshake is public, but the *connection* proves itself one
frame later, on STOMP CONNECT, where a native header can carry the token.

```java
if (StompCommand.CONNECT.equals(command)) {
    accessor.setUser(authenticate(accessor));      // verify JWT → principal on the session
} else if (StompCommand.SUBSCRIBE.equals(command)) {
    authorizeSubscription(accessor);               // membership check on the board topic
}
```

One subtlety that costs an afternoon if you miss it: you must take the **mutable** accessor via
`MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class)`. The more obvious
`StompHeaderAccessor.wrap(message)` returns a *copy*, which accepts `setUser(…)` and then
discards it — the session ends up anonymous with no error anywhere.

**SUBSCRIBE is the real authorization moment.** A REST endpoint is checked per request; a
subscription is checked *once* and then streams indefinitely. So the topic is parsed for its
board id and run through `BoardService.requireBoardAccess(boardId, userId, Role.VIEWER)` — the
exact guard the REST layer uses, called from a new place. Without it, any signed-in user could
subscribe to any board id and watch it live: a hole straight through the permission model M4
built so carefully, and one no REST test would ever catch.

Failure here is **refusal, not degradation** — the opposite of the HTTP filter, which leaves a
bad token anonymous and lets a 401 happen later. On a socket there is no "later": a subscription
allowed to proceed is already streaming. So the interceptor throws, and Spring answers with an
ERROR frame and closes the session. The refusal message is also deliberately vague — the guard
distinguishes 404 from 403, but collapsing both here avoids handing out a board-existence
oracle.

**What the events carry.** Every message is `{type, boardId, actorId, at, payload}`. Two details
earn their keep:

- **`actorId`** — events go to *everyone*, including whoever caused them. The frontend already
  applies drags optimistically, so it needs to recognize the echo of its own change and skip it.
- **`MEMBER_REMOVED` carries the whole membership, not just an id** — the removed person is
  almost certainly subscribed right now, and the `userId` is how they learn the person shown the
  door was *them*.

A related non-event: when a move triggers a **rank re-balance**, only the moved card is
announced, even though every sibling's rank changed. Subscribers holding old ranks still sort
into the same order (`spread` re-spaces without reordering), and clients never compute
placements locally anyway — a move is intent the server resolves. And `BOARD_UPDATED` sends a
new `BoardSummary` rather than `BoardResponse`, because `BoardResponse` carries `myRole`, which
is a fact about *one* member and has no business in a message sent to all of them.

**How we verified.** `RealtimeIntegrationTest` drives a real STOMP client over a real WebSocket
against a real port — asserting that the broadcaster calls a mocked template would test the
wiring *diagram* rather than the wiring. The REST half still goes through MockMvc, which shares
the context, so a MockMvc write really does travel through the listener, the broker, and out to
the socket. Both halves of the property get tests: changes **reach members** (card
create/update/move/delete, column and board events, viewers included — read-only is not the
same as offline), and changes **reach no one else** (non-member's SUBSCRIBE closes the session;
events don't cross board topics; garbage, missing, and *refresh* tokens are all refused).

Two failures worth recording, because both looked like "the server is broken" and neither was:

1. **Six tests failed on silence.** The server log said `Broadcasting to 1 sessions` — it was
   working perfectly. The test client used `StringMessageConverter`, which accepts `text/plain`
   only and dropped every `application/json` frame without a word. Reading the server's own
   debug log, rather than the test's assertion, is what found it in one pass.
2. **The refusal tests asserted the error text** and got `ConnectionLostException: Connection
   closed`. The server closes the socket so fast the ERROR frame loses the race. The assertion
   was wrong, not the code: what matters is that no usable session comes back.

```bash
cd server
./mvnw test    # 105 passing (96 existing + 9 new)
```

---

### Step 5.x — Live board updates in the browser (Pass A) · _2026-07-20_

**Goal:** the payoff. Two people on one board see each other's cards and columns move in real
time, no reload. This pass does the transport plus the events that change the canvas
(`CARD_*`, `COLUMN_*`); the board/member events are Pass B.

**Files:**

- `lib/realtime.ts` (new — the `useBoardEvents` socket hook)
- `lib/board-events.ts` (new — the pure `applyBoardEvent` reducer + wire types)
- `app/boards/[id]/page.tsx` (wire the hook, echo-skip, connection dot, open-card conflict)
- `components/board/card-modal.tsx` (the "changed elsewhere" notice)

> **Concepts:** STOMP over WebSocket · auth on CONNECT vs the handshake · a one-way feed ·
> pure reducer over event stream · echo suppression via `actorId` · rank-not-arrival ordering ·
> refetch-on-reconnect · the `useEffect` closure-in-deps trap

**Why auth rides the CONNECT frame, not the URL.** The browser `WebSocket` API gives you no way
to set headers on the HTTP upgrade — so the handshake is necessarily anonymous (the backend
permits `/ws/**`) and identity is proved one frame later, on STOMP CONNECT, via an
`Authorization: Bearer` native header. The hook reads the token in `beforeConnect` *every*
(re)connect rather than closing over it, so a token refreshed mid-session is picked up for free.

**The client never SENDs.** There's no `/app` prefix server-side and none here: every write
still goes through the authenticated REST API, and the socket is a pure one-way feed out. That's
a security property, not a limitation — the write path can't be reached over the socket, so it
can't dodge the REST guards.

**A hook owns the socket; a pure function owns the state.** `useBoardEvents` deals only with
connect/auth/reconnect/teardown. `applyBoardEvent(board, event)` is a plain
`BoardDetail → BoardDetail` with no React in it — the part worth reasoning about carefully lives
where it can be read (and later unit-tested) in isolation. One nice consequence: `CARD_CREATED`,
`CARD_UPDATED`, and `CARD_MOVED` are *one* code path — pull the card out of wherever it is,
insert it into the column its own `columnId` names, re-sort. A move is just an update whose
column changed.

**Order comes from `rank`, never arrival order.** Every insert appends then sorts by the
server's LexoRank string, exactly as the REST path has since M3. Two events can arrive in any
order and still settle into the same board.

**Skip your own echo.** The server broadcasts to the actor too — on purpose. We already applied
our own change optimistically and reconciled to the server's rank, so re-applying the echo would
fight an in-flight drag. `event.actorId === user.id` → ignore. (Verified: dragging a card
in-browser lands it once, no flicker, while the echo sails past unhandled.)

**Reconnect refetches the whole board.** The simple broker has no replay, so anything that
happened while the socket was down is gone. Pretending otherwise would leave a subtly-wrong
board; the honest fix is to reload. The hook tells a *re*-connect from the first connect with a
flag and calls the page's existing `load()` — the same function the initial render already uses.

**The trap I was watching for, and it's real.** `onEvent`/`onResync` are fresh closures every
render. If they go in the socket effect's dependency array, the socket tears down and rebuilds
on *every keystroke* in a card composer — works in a ten-second demo, falls apart the moment
someone types. The fix is to hold them in refs updated by a separate effect and depend only on
`boardId`/`enabled`. Confirmed by typing into a composer with the connection indicator watched:
it stayed "Live" throughout, no reconnect churn.

**Someone edits the card you have open.** We never touch the modal's fields — that's the user's
draft. Instead a flag drives an amber notice ("Saving will overwrite their version" for an edit;
"no longer exists" for a delete, with Save hidden). This is the last-write-wins policy
`project-scope.md` already commits to, just made visible instead of silent. Verified live: with a
half-typed description open, the other user's edit raised the notice, the draft stayed intact,
and the card *behind* the modal updated.

**How we verified** (two accounts, one browser + curl for the other user): a card created,
renamed, moved across columns, and deleted by the other user each appeared without a reload; a
column reorder rearranged live; echo-suppression kept an own-drag clean; the open-modal notice
fired without eating the draft; a fresh load connects straight to "Live". The reconnect path —
`onWebSocketClose → offline` (amber "Reconnecting…"), stompjs auto-reconnect, and
`onConnect`-when-already-connected → resync — was confirmed by a manual drop-and-recover: killing
the backend flipped the dot to amber, and restarting it returned to green "Live" on its own with
no page reload. (I couldn't capture this one in-session, because the sandbox tears the browser
tab down whenever the backend process is killed.)

```bash
cd frontend
npm run build   # clean
```

### Step 5.x-B — Board & membership events, live (Pass B) · _2026-07-20_

**Goal:** finish M5. The five events Pass A left as no-ops — `BOARD_UPDATED`, `BOARD_DELETED`,
and the three `MEMBER_*` — now do something: a rename lands in place, a role change flips your
edit controls without a reload, and losing access sends you home gracefully.

**Files:**

- `lib/board-events.ts` (`BOARD_UPDATED` case + `BoardSummary` type)
- `app/boards/[id]/page.tsx` (the identity/navigation events + a "gone" terminal state)
- `components/board/share-modal.tsx` (`refreshSignal` → refetch roster)

> **Concepts:** pure-vs-identity event split · deriving UI (`canEdit`) from live state · a
> terminal note instead of a redirect · refetch-by-signal for state you don't own

**The split that organizes the whole pass.** Pass A's card/column events change the shared canvas
*identically for everyone*, so a pure `applyBoardEvent(board, event)` reducer handles them. These
five don't fit that mould:

- `BOARD_UPDATED` (rename) *is* universal → it's the one case that joins the reducer. It patches
  `name` only — never `myRole` (the payload is `BoardSummary`, which deliberately omits it,
  because role is per-caller and a broadcast has no single caller) and never `columns`.
- The other four are **identity-dependent** (does this role change / removal concern *me*?) or
  **side-effectful** (a deleted board has to take me somewhere). A pure reducer has neither the
  user id nor a router. So they stay in the page's `handleBoardEvent`, where `user`, the board
  state, and the modal live. Trying to force them into the reducer would mean threading identity
  and navigation through a function whose whole value is being pure.

**Role changes are just `myRole` state.** `canEdit`/`isOwner` are already derived from
`board.myRole` (M4). So a live demotion is one line — `setBoard(prev => ({ ...prev, myRole:
member.role }))` — and every consequence (grips, composers, delete buttons, the badge) falls out
of the existing render. Verified by demoting an Editor mid-view: the controls vanished; promoting
back, they returned. No reload, no special-casing each control.

**Being sent home is a state, not a redirect.** When the board is deleted under you, or your
access is revoked, the tempting move is `router.replace("/dashboard")` — but yanking the page
away mid-glance is jarring and easy to miss. Instead a `goneReason` string drives a calm terminal
note ("This board was deleted." / "You no longer have access to this board.") with a back link,
reusing the exact `CenteredNote` the not-found branch already uses. No new navigation, and the
user leaves on their own terms.

**Refetch the roster on a signal, don't re-derive it.** The share modal owns its own member list.
When a live `MEMBER_*` event arrives, rather than teach the page to merge added/updated/removed
rows into that list (duplicating reducer logic for a handful of rows), the page bumps a nonce and
the modal refetches when it changes. Refetch is always correct and the list is tiny. It only
matters while the modal is open — closed, it's unmounted and loads fresh on next open. Verified:
with a non-owner's roster open, an invite made elsewhere appeared in it live.

**Echo suppression carries over for free.** The `actorId === user.id` early-return added in Pass A
means an owner never processes the echo of their own rename / delete / member change — the
optimistic paths (`handleRenameBoard`, `handleDeleteBoard`, the modal's own handlers) already
updated the view, so the broadcast is correctly ignored.

**How we verified** (two accounts, browser as the member + curl as the owner): rename appeared
live with role/columns intact; a demote hid every edit control and a promote restored them; an
open roster picked up a new invite; an eviction and a board deletion each showed their terminal
note with no console errors and no stuck socket.

```bash
cd frontend
npm run build   # clean
```

### Where M5 stands

Backend: ✅ STOMP endpoint with JWT-authenticated CONNECT · ✅ membership-checked SUBSCRIBE ·
✅ every card/column/board/member mutation broadcast after commit · 105 tests.

Frontend: ✅ **(Pass A)** STOMP client, live `CARD_*`/`COLUMN_*`, own-echo skip, connection
indicator, refetch-on-reconnect, open-card conflict notice. ✅ **(Pass B)** `BOARD_UPDATED`
rename in place, live role change (`canEdit` flips), graceful eviction / board-deletion terminal
state, roster refresh on a signal.

**🎉 Milestone 5 complete — front to back. And with it, M0–M5: the project's entire planned
scope.** TaskBoard is a real-time collaborative Kanban board — shared boards with per-role
permissions, drag-and-drop ordering the server arbitrates, and every change flowing to everyone
watching, live.

---

## Milestone 6 — Stretch features

With the planned scope done, M6 is the polish list from `implementation-plan.md`: presence, an
activity log, and a copy-able invite link. (The fourth item — optimistic updates with rollback —
was already true from M3/M5.) Each is a full slice, and each leans on infrastructure M5 already
built rather than adding new plumbing.

### Step 6.1 — Presence: who's viewing, derived from the socket itself · _2026-07-21_

The goal: little avatars of everyone with the board open, updating as people come and go. The
temptation is to have each client *send* "I'm here" pings — but the M5 socket is deliberately a
**one-way feed** (no `/app` prefix; the client never SENDs), and that invariant is worth keeping.

The insight is that we don't need pings. Spring already publishes `SessionSubscribeEvent`,
`SessionUnsubscribeEvent`, and `SessionDisconnectEvent` for every STOMP session, and each carries
the `Principal` the CONNECT interceptor attached. So **subscribing to a board is the "I'm here"
signal** — presence falls out of the lifecycle for free. `PresenceTracker` listens for those three
events and keeps a per-board `userId → open-tab count`:

- **Count, don't flag.** One person with two tabs is present once; closing one tab must not evict
  them. A user drops out of the set only when their last tab's subscription goes.
- **A disconnect only gives you a session id** — no destination, no board. So every subscription is
  filed under `sessionId + subscriptionId`, letting an unsubscribe undo exactly one and a
  disconnect undo all of that session's at once.

Any real change to a board's viewer set re-broadcasts the whole list on the **same**
`/topic/board/{id}` — a new `PRESENCE` `BoardEvent` with a **null `actorId`**. Two subtleties made
that work:

1. **Null actor, handled first.** The board page skips echoes of its own actions
   (`actorId === user.id`); presence must reach *everyone including whoever just joined*, so the
   client branches on `PRESENCE` *before* the echo-skip, and a null actor never matches anyone.
2. **The existing tests had to learn to ignore it.** Presence now lands on the topic whenever
   anyone subscribes, so the M5 delivery tests (which assert "the next event is `CARD_CREATED`")
   grew a `nextEvent` helper that skips `PRESENCE` frames, and `assertSilent` that tolerates them.
   A good reminder that adding a message type to a shared channel is a change to every reader.

One refactor paid for itself: the topic string `/topic/board/{id}` was now built or parsed in three
places (the SUBSCRIBE guard, the broadcaster, the tracker). A drift between them would be a *security*
bug — the guard authorizing one destination while events fan out on another — so it moved into one
`BoardTopic` helper.

### Step 6.2 — Activity log: append-only history, written BEFORE_COMMIT · _2026-07-21_

An activity feed ("Alice moved card 'X'") needs a durable record of every change. The clean way in:
it hangs off the **same `BoardChangedEvent`** the broadcaster already publishes, so the services stay
ignorant of it — one more listener, not edits to every mutation.

The interesting decision is *when* the listener runs, and it's the **mirror image of the
broadcaster**:

- `BoardEventBroadcaster` uses `AFTER_COMMIT` — a message about a change that later rolled back
  would be a lie on the wire.
- `ActivityRecorder` uses **`BEFORE_COMMIT`** — the log entry must be part of the *same atomic
  unit* as the change, committing or rolling back with it. `BEFORE_COMMIT` runs inside the still-open
  transaction, so the `save` joins it.

The recorder turns each event into a rendered predicate — `moved card "Design homepage"` — but
stores the **actor's name nowhere**: `actor_id` is joined against `app_user` at read time, so the
feed reflects a later rename and degrades to "Someone" if the account is deleted (`ON DELETE SET
NULL`, so history outlives its author). `BOARD_DELETED` is skipped (the board and its cascading rows
are on the way out); rank re-balances were never broadcast, so they stay unlogged for free.

The endpoint is `GET /api/boards/{id}/activity?limit=&before=`, guarded by the same
`requireBoardAccess(…, VIEWER)` as everything else — a non-member gets the identical 404, so the log
leaks neither a board's existence nor its contents. On the client, an open feed refetches on a
nonce the page bumps for any logged live event (reusing the server's rendered summaries rather than
re-deriving a sentence from the wire event — one source of truth for "how a change reads").

### Step 6.3 — Copy invite link: a token anyone signed-in can redeem · _2026-07-21_

Inviting by email is precise but high-friction. A shareable link is the opposite: an owner mints one,
hands it around, and anyone who opens it joins. The whole board graph already keys off
`board_membership`, so the link is just a **rotatable token on the board row** (`invite_token` +
`invite_link_role`, both null = no link) plus one un-owner-scoped endpoint.

The security posture is the point:

- **Holding the token is the authorization** — so `POST /api/invite-links/{token}/accept` is *not*
  owner-gated, unlike every other sharing verb. But it's still **authenticated**: an anonymous
  visitor is bounced to log in and comes back (below). The token is an unguessable UUID.
- **Never OWNER.** The link role is constrained to EDITOR/VIEWER at the DB (`CHECK`), the service
  (rejects OWNER, like `invite`), and it can't be otherwise — a public link that minted owners would
  be a way to seize a board.
- **Rotating mints a fresh token**, so re-generating silently kills the old URL; disabling nulls both
  columns and an outstanding link stops resolving at once. A unique index over the nullable token
  column is exactly right (Postgres treats NULLs as distinct, so the many linkless boards don't
  collide).
- **Idempotent redeem.** Already a member? Join nothing, return the role you already have — so the
  owner testing their own link stays OWNER, and a double-click doesn't create a duplicate.

The client gap this exposed: `/join/{token}` is behind `<Protected>`, which hard-redirected to
`/login` and **dropped where you were going**. Since the link's whole job is onboarding — often a
brand-new user — that's the flow that matters most. So `Protected` now stashes the path in
`/login?next=…`, and login *and register* honour it (a fresh sign-up via a link lands on the board,
not the empty dashboard). `next` is attacker-influenced, so a `safeNext` helper drops anything that
isn't a same-origin relative path — closing the open-redirect hole. One Next.js gotcha: reading
`useSearchParams` opts a page into a client bailout that **must** sit under a `<Suspense>` boundary,
or the production build fails — so the login/register forms are now wrapped in one.

```bash
# Backend: real STOMP presence test + activity + invite-link integration tests
cd server && ./mvnw test            # 118 tests, green

# Frontend: type-check + production build both clean
cd frontend && npm run build
```

### Where M6 stands

Backend: ✅ presence via STOMP session events on the shared topic · ✅ append-only `board_activity`
written BEFORE_COMMIT off the existing event · ✅ rotatable board invite-link token with an
authenticated, idempotent redeem · **118 tests**.

Frontend: ✅ live presence avatar stack · ✅ activity drawer (server-rendered summaries, live
refresh, "load more") · ✅ copy/rotate/disable link in the Share modal + a `/join/{token}` redeem
page, with post-login return-to across login and register.

**🎉 Milestone 6 complete.** Beyond the planned scope: you can see who's here, what's happened, and
share a board with a single link.

---

## Post-M6 — UI polish & card enrichment

A run of front-end polish, then the first real schema change since M6: cards gained a **label** and
an **assignee**.

### Step P.1 — Light theme, one variant flip · _2026-07-24_

Every component was written `bg-white dark:bg-zinc-950`-style from day one, so the app already *had*
a light theme — it was just losing to the OS `prefers-color-scheme: dark`. Two lines in
`globals.css` settle it: drop the `@media (prefers-color-scheme: dark)` override, and redefine
Tailwind v4's dark variant as **class-based** — `@custom-variant dark (&:where(.dark, .dark *));`.
No `.dark` class is ever added to the tree, so every `dark:` utility goes inert and the light base
always wins — the whole app flips with no component edits. Alongside it: the dashboard became a card
grid closed by a `+ New board` tile, and the top bar became a real nav bar (warm off-white
`#FBFAF7`, hairline bottom border `rgba(29,28,24,.09)`) shared in spirit by the dashboard `Navbar`
and the board header.

### Step P.2 — Card labels & assignees (a schema change that rides the existing pipes) · _2026-07-24_

The card modal grew to match a fuller design: a status chip (its column), an editable **label**
chip, an **assignee** (avatar + picker), and the description. Two of those are newly persisted
fields, so this touched every layer — but the interesting part is how little *new* plumbing it
needed.

**Backend.** `V6__card_label_assignee.sql` adds `label varchar(40)` and
`assignee_id uuid REFERENCES app_user(id) ON DELETE SET NULL` — the same delete posture as
`board_activity.actor_id`: deleting a user un-assigns their cards, never deletes them. The `Card`
entity carries `assigneeId` as a **bare `UUID`, not a JPA `@ManyToOne`** — deliberately, so the
whole-board load stays the join-free read it has always been and `CardResponse.from(card)` stays a
pure entity→DTO map. Both fields are last-write-wins, so they join `title`/`description` in
`Card.edit(...)` and bump `updatedAt`. The one new rule: an assignee must be an **active member of
the card's board**, validated in `CardService.update` against `BoardMembershipRepository` (a 400
otherwise; `null` = unassigned is always allowed).

**Why the wire needed almost nothing.** `CardResponse` is the single record both the REST endpoints
*and* the real-time `CARD_*` events carry — so adding `label`/`assigneeId` to it makes the new
fields flow through the live event stream **for free**. The client's pure `applyBoardEvent` folds
the whole `CardResponse` into state, so another user's label/assignee change lands live with zero
new event wiring, no new `BoardEventType`, no new broadcast path.

**Frontend.** A card stores only `assigneeId`; the *name* (for the avatar monogram) is resolved
client-side from the board roster — the board page fetches `listMembers` (refetched on any
`MEMBER_*` via the existing nonce) and threads an `id → name` map down to the card face, while the
modal offers the active members as assignee choices. The `initials`/`hue`/`avatarColor` helpers the
presence stack already used were lifted into `src/lib/avatar.ts` and shared, so an assignee's colour
matches their presence dot. The label renders as an indigo chip on both the card face and the modal.

```bash
cd server && ./mvnw test          # 118 tests, still green (V6 applies cleanly)
cd frontend && npx tsc --noEmit   # clean
```

The lesson worth keeping: **a well-placed DTO is a multiplier.** Because ordering, auth, and
real-time were already funnelled through `CardResponse` + the board guard + the after-commit event,
a two-column schema change reached the live multi-user UI without inventing a single new endpoint,
event type, or broadcast path.

### Step P.3 — Continuous integration (and a lockfile that only broke on Linux) · _2026-07-26_

Everything up to here was verified by *remembering* to run the right command. **CI** — continuous
integration — is a robot that stops relying on memory: on every push it checks out the repo, builds
it, runs the tests, and reports pass/fail on the commit.

**Why this project needs it more than most.** Two reasons, both structural:

1. The backend suite is almost entirely `@SpringBootTest` integration tests, so it needs a **live
   Postgres and JDK 21**. If the DB container isn't up the context won't even start — which makes it
   tempting to skip `./mvnw test` on a frontend-only change, and vice versa.
2. The two halves are separate builds that **share a wire format**. Rename a field on
   `CardResponse` and the server still compiles perfectly; the break lands in `frontend/src/lib/*.ts`
   and stays invisible until someone happens to run `tsc`.

**The workflow.** One file, `.github/workflows/ci.yml`, with two jobs that run **in parallel** — a
red run then points straight at the guilty half, and the wall-clock is the slower of the two rather
than their sum. The backend job declares a `services.postgres` container; the neat part is that its
credentials are copied from `docker-compose.yml`, which is exactly what
`application.properties` already defaults to (`jdbc:postgresql://localhost:5432/taskboard`). There is
no `src/test/resources/application*.properties` overriding it — so **the test suite ran in CI with
zero configuration changes**. It runs `./mvnw -B clean verify` (not `test`) so a green run also
proves the jar still packages. The frontend job runs `npm ci` → `typecheck` → `lint` → `build` on
Node 22, with placeholder env vars so the build never depends on a real secret.

**What it immediately found.** Turning lint on surfaced six errors from the React Compiler–era rules
that ship with `eslint-config-next` 16 — five `set-state-in-effect` and one `refs`. They weren't
bugs, but fixing them properly turned out to be a genuine improvement. The old shape was
`useEffect(() => { load(); }, [load])`; the new one inlines the fetch as a promise chain, sets state
only inside `.then`/`.catch`, and guards it with a `cancelled` flag:

```ts
useEffect(() => {
  let cancelled = false;
  listBoards(authFetch)
    .then((rows) => { if (cancelled) return; setBoards(rows); setStatus("ready"); })
    .catch(() => { if (!cancelled) setStatus("error"); });
  return () => { cancelled = true; };
}, [authFetch]);
```

That `cancelled` flag closes a real race the old code had — a slow response landing *after* the
component unmounted or after the board id changed. The shape wasn't invented for the linter, either:
the `listMembers` effect on the board page already looked like this and was the one effect the rule
never complained about. `load` stays for the imperative paths (the retry button, the realtime
`onResync`).

**Then the part worth the whole section.** The first CI run went red on something no amount of local
testing would have caught. `npm ci` failed:

```
npm error `npm ci` can only install packages when your package.json and
npm error package-lock.json are in sync.
npm error Missing: @emnapi/runtime@1.11.3 from lock file
```

The distinction: **`npm install` is lenient** — it quietly reconciles a drifted lockfile and moves
on. **`npm ci` is strict** — it refuses, because a lockfile that doesn't match `package.json` means
the install isn't reproducible. Local development had never noticed because `npm install` kept
papering over it, and an existing `node_modules` masked the rest.

Running `npm install` to resync it *still* failed on the second run — and the reason is the actually
interesting bit. `@emnapi/runtime` and `@emnapi/core` are **optional, platform-specific** transitive
dependencies (wasm/linux variants). npm resolving the tree on macOS had simply omitted the ones
macOS can't use, producing a lockfile that was internally consistent *on darwin* and incomplete on
the Linux runner. The fix is to generate the lockfile somewhere that sees every platform:

```bash
docker run --rm -v "$PWD":/w -w /w node:22-alpine \
  sh -c "npm install --package-lock-only"
```

The regenerated lockfile carries **both** platforms — `darwin-arm64`/`darwin-x64` *and*
`linux-x64-gnu`/`linux-arm64-gnu` — and `npm ci` passes on macOS and Linux alike.

```bash
# what CI runs, reproduced locally — note `npm ci`, not `npm install`
cd server   && ./mvnw -B clean verify        # 113 tests green, jar packaged
cd frontend && npm ci && npm run typecheck && npm run lint && npm run build
```

The lesson worth keeping: **CI's value isn't running tests you could have run yourself — it's
running them somewhere that isn't your laptop.** The lockfile bug was invisible on macOS by
construction, would have broken every fresh clone and every future deploy, and was found within
ninety seconds of switching CI on. A green badge means "this builds on a machine that has never seen
your `node_modules`", which is a much stronger claim than "it works here".

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
./mvnw spring-boot:run                  # run the backend locally (:8080)
./mvnw clean package                    # compile + test + build a jar

# Frontend (run from frontend/)
npm run dev                             # run the Next.js dev server (:3000)
npm run build                           # production build + full type-check
npm run lint                            # eslint
npm run typecheck                       # tsc --noEmit on its own
npm ci                                  # clean install, exactly as CI does (strict about the lockfile)

# CI (run from anywhere in the repo)
gh run list --limit 5                   # recent workflow runs and their status
gh run watch                            # follow the run in progress
gh run view --log-failed                # just the failing steps' logs
```
