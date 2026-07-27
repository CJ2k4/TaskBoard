# TaskBoard

[![CI](https://github.com/CJ2k4/TaskBoard/actions/workflows/ci.yml/badge.svg)](https://github.com/CJ2k4/TaskBoard/actions/workflows/ci.yml)

A real-time collaborative Kanban board — shared boards with columns and cards, drag-and-drop
reordering, and live multi-user sync. Everyone looking at a board sees moves, edits and new cards
stream in over WebSockets, with per-board roles (owner / editor / viewer), an activity log, presence,
and shareable invite links.

**Stack:** Spring Boot 3.5 on Java 21, Postgres + Flyway, STOMP over WebSocket, JWT auth with Google
sign-in — and a Next.js 16 (App Router) frontend with React 19, Tailwind 4 and `@dnd-kit`.

## Running it locally

Requires **JDK 21**, **Node 20.9+**, and **Docker** (for Postgres).

```bash
# 1. Database
docker compose up -d

# 2. Backend — http://localhost:8080
cd server && ./mvnw spring-boot:run

# 3. Frontend — http://localhost:3000
cd frontend && cp .env.example .env.local && npm install && npm run dev
```

`docker compose --profile dev up` additionally starts pgAdmin on http://localhost:5050 (dev only).

## Tests and checks

These are exactly what CI runs, so a clean pass locally means a green badge.

```bash
cd server   && ./mvnw verify        # full suite (needs Postgres running)
cd frontend && npm run typecheck && npm run lint && npm run build
```

## Deploying

Three providers, because no one of them does all three jobs well:

| Piece | Where | How |
|---|---|---|
| Backend | **Back4App Containers** | Docker image built from [`server/Dockerfile`](server/Dockerfile) |
| Database | **Neon** | serverless Postgres, reached over TLS from the container |
| Frontend | **Vercel** | Next.js, from the `frontend/` root directory |

Back4App has no managed Postgres for containers (its PostgreSQL offering belongs to the Parse BaaS
product, which is Parse's own schema rather than a JDBC target), so the database lives elsewhere and
the connection crosses the public internet — hence `sslmode=require` and the pool tuning in
`application.properties`. There is **no CD**; deploys are a deliberate action.

### First deploy

The pieces depend on each other in a cycle: the backend's CORS wants the frontend origin, the
frontend **bakes in** the backend origin at build time, and Google Sign-In needs the frontend origin
registered before anyone can log in. It breaks because only one of the three is *build*-time — so
deploy the backend with a placeholder origin first and correct it at the end.

1. **Push to `main`** and confirm CI is green. Every platform builds from GitHub, not from a laptop.
2. **Smoke-test the image under the target's real limits** (with `docker compose up -d` running).
   The resource flags are the point — this is what catches an OOM before Back4App does:
   ```bash
   cd server
   docker build -t taskboard-api .
   docker run --rm --memory=256m --cpus=0.25 -p 8081:8081 -e PORT=8081 \
     -e DB_HOST=host.docker.internal -e DB_PORT=5432 -e DB_NAME=taskboard \
     -e DB_USERNAME=taskboard -e DB_PASSWORD=taskboard taskboard-api
   curl localhost:8081/api/health   # {"status":"ok"}
   ```
3. **Neon → create a project.** Choose **PostgreSQL 16**, matching `docker-compose.yml` and CI —
   Flyway 11.7.2 supports up to 17 and warns on 18 (*"support has not been tested"*), and matching
   dev is what makes a production bug reproducible locally. Then convert the connection string to
   JDBC form; Neon shows a libpq URI, which pgJDBC will not accept verbatim:
   ```
   postgresql://alice:pw@ep-x-y-pooler.aws.neon.tech/neondb?sslmode=require&channel_binding=require
   jdbc:postgresql://ep-x-y.aws.neon.tech/neondb?sslmode=require
   ```
   Three edits, each of which fails differently if missed:
   - **Credentials out of the URL** into `DB_USERNAME` / `DB_PASSWORD`. Leaving a bare `@` behind
     makes pgJDBC parse an empty username.
   - **Drop `channel_binding`** — libpq's spelling; pgJDBC's is `channelBinding`, and it rejects the
     snake_case one.
   - **Use the direct endpoint, not `-pooler`.** Neon's pooled endpoint is PgBouncer in transaction
     mode, where advisory locks are unsupported and Neon itself recommends direct connections for
     schema migrations — and Flyway takes a lock before migrating. The pooler solves a problem this
     app doesn't have (thousands of short-lived serverless connections); one long-running JVM with a
     5-connection Hikari pool wants the direct endpoint, which allows ~100.

   Keep `sslmode=require`. Verify before deploying by running the image against it locally — the boot
   log should report `PostgreSQL 16.x` and `Successfully applied 8 migrations`.
4. **Back4App → Containers → deploy from this repo.** Root Directory **`server`** (this is also the
   Docker build context, which is why the Dockerfile lives there), branch `main`. Then in App
   Settings set the port to **8080** and the health check to **`/api/health`**, and add the
   environment variables below. Watch the logs for the Flyway `V1`–`V8` migrations against the fresh
   Neon database, then `curl https://<your-app>.b4a.run/api/health`.
5. **Vercel → Import Project.** Root Directory `frontend`, Node 22, and both `NEXT_PUBLIC_*` vars
   set **before the first build** — they are inlined into the bundle, so setting them afterwards
   needs a redeploy, not a restart. Note the production URL.
6. **Google Cloud Console → Credentials → the OAuth 2.0 Web client → Authorized JavaScript origins**
   → add the exact production frontend origin (scheme included, no path, no trailing slash). Sign-in
   is Google-only, so **until this propagates nobody can log in.** No redirect URI is needed.
7. **Back4App → Environment** → set `APP_CORS_ALLOWED_ORIGINS` to the Vercel production URL,
   replacing the placeholder. The container restarts; no rebuild and no frontend redeploy.

Adding a custom domain later means redoing steps 6 and 7 with the new origin.

### Environment variables

| Variable | Where | Required | Notes |
|---|---|---|---|
| `DB_URL` | Back4App | yes | full JDBC URL incl. `?sslmode=require`. See step 3 for the conversion |
| `DB_USERNAME` / `DB_PASSWORD` | Back4App | yes | Neon's role and password, kept out of the URL |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | — | no | alternative to `DB_URL` when a platform exposes the parts separately; local dev's default |
| `SPRING_PROFILES_ACTIVE` | Back4App | yes (`prod`) | arms `JwtService`'s weak-secret guard — that is its only job |
| `JWT_SECRET` | Back4App | yes | ≥ 32 bytes (`openssl rand -base64 48`). Rotating it logs everyone out |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | Back4App | no | ISO-8601; default `PT15M` / `P7D` |
| `GOOGLE_CLIENT_ID` | Back4App | yes | must equal the frontend's |
| `APP_CORS_ALLOWED_ORIGINS` | Back4App | yes | comma-separated **exact** origins, **no trailing slash**; covers both REST and the `/ws` handshake |
| `PORT` | — | no | Back4App does not inject it; the `8080` default binds, matching `EXPOSE` and App Settings |
| `JAVA_OPTS` | Back4App | no | overrides the Dockerfile's tuned flags without a rebuild — see below |
| `DB_POOL_SIZE` / `TOMCAT_MAX_THREADS` | Back4App | no | default `5` / `25`, both sized for a 256 MB container |
| `NEXT_PUBLIC_API_URL` | Vercel | yes | **inlined at build time**, no trailing slash. `wss://` is derived from it |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Vercel | yes | **inlined at build time**; unset = nobody can sign in |

Preview deployments won't be able to sign in: Google's Authorized JavaScript origins accepts no
wildcards, so a fresh preview hostname fails with `origin_mismatch`. For a long-lived branch, add its
deterministic Vercel branch alias to *both* `APP_CORS_ALLOWED_ORIGINS` and the Google client.

### Fitting in 256 MB

Back4App's free plan is 0.25 CPU / 256 MB, which is genuinely tight for Spring Boot + Hibernate.
Measured locally with `--memory=256m --cpus=0.25` under load — six concurrent users each creating a
board, three columns and 24 cards, then re-reading the whole board:

| `JAVA_OPTS` | Container | Startup | Idle | Peak under load |
|---|---|---|---|---|
| `-XX:MaxRAMPercentage=75` | 256 MB | 120 s | 213 MB | **240 MB (94%)** — 16 MB from the kill |
| tuned (the Dockerfile default) | 256 MB | **48 s** | 195 MB | **210 MB (82%)** |
| tuned, `-Xmx256m` | 512 MB | 48 s | 198 MB | 214 MB (42%) |

The flag doing the work is the hard `-Xmx128m`. A percentage-of-RAM heap looks right and isn't: 75%
of 256 MB reserves 192 MB for the heap and leaves nothing for the ~100 MB of metaspace Spring and
Hibernate need, which is how the first row ends up 16 MB from an OOM kill under ordinary use.
Capping the heap forces GC to work harder *instead of* the kernel killing the container.
`-XX:TieredStopAtLevel=1` is what cut startup by 60%, which matters because a slow boot fails the
deploy health check.

The third row is the one that decides the plan: doubling the memory moves peak usage by 4 MB,
because the app's working set is simply ~210 MB. **Paying for the 512 MB plan buys nothing here** —
if you outgrow the free tier it will be CPU you want, not RAM.

### Known constraints

- **The backend must run as exactly one instance.** Three independent reasons: the STOMP broker is
  Spring's in-JVM simple broker, `PresenceTracker` holds per-process maps, and `BinPurgeJob`'s
  `@Scheduled` purge has no leader election. Scaling out needs an external broker relay, shared
  presence, and job locking — in that order.
- `/api/health` is a **liveness ping only** and does not touch the database. That is deliberate: a
  restart on a brief DB hiccup would drop every open board's WebSocket, and Flyway already proves the
  database at startup.
- Refresh tokens live in `localStorage` and are **not revocable** (no `jti`, no denylist) — logout is
  client-side, and rotating `JWT_SECRET` is the only kill switch.
- **Neon autosuspends after ~5 minutes idle.** The first query afterwards pays a resume of roughly a
  second, which is why `connection-timeout` is 30 s rather than the 250 ms default — a timeout there
  would turn a normal cold start into a visible error.
- **Free-tier ceilings that are time-based, not usage-based:** Back4App allows 600 active hours per
  month against a 720-hour month, and Neon 100 compute-hours per project. Exceeding either suspends
  the service until the next cycle.

## Docs

- [`project-scope.md`](project-scope.md) — what the product is, plus the full data model
- [`implementation-plan.md`](implementation-plan.md) — the ordered milestones (M0 → M6)
- [`CLAUDE.md`](CLAUDE.md) — architecture, conventions, and the decisions behind them
- [`LEARNING.md`](LEARNING.md) — a step-by-step walkthrough of how it was built
