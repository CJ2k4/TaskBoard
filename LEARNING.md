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
