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

Deferred within M1 (optional / later):

- [ ] Google OAuth login (email/password already unblocks everything else — the plan lets this slip).
- [ ] Frontend register/login pages + token storage + protected-route redirect (next, honoring
      `frontend/AGENTS.md`'s "read the Next.js docs first" note).

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
```
