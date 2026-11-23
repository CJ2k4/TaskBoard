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

| Piece | Where | How |
|---|---|---|
| Backend | **Render** | Docker web service built from [`server/Dockerfile`](server/Dockerfile) |
| Database | **Neon** | Postgres 16, AWS `ap-southeast-1`, reached over TLS via `DB_URL` |
| Frontend | **Vercel** | Next.js, from the `frontend/` root directory |

The backend's deployed shape is committed as a Render Blueprint in [`render.yaml`](render.yaml) —
the service and every environment variable except the secrets, which Render prompts for once and
stores itself. There is **no CD**: `autoDeploy` is off and deploys are a deliberate action.

The database is deliberately **not** Render Postgres: a free Render database is deleted 30 days
after it is created, a hard expiry rather than a usage cap. Neon's free tier has no expiry, at the
cost of a connection that crosses the public internet — see [Connecting to Neon](#connecting-to-neon).

Render's free instance is **0.1 CPU / 512 MB and spins down after 15 minutes** without inbound
traffic. See [Known constraints](#known-constraints) before relying on this for anything long-lived.

### First deploy

The pieces depend on each other in a cycle: the backend's CORS wants the frontend origin, the
frontend **bakes in** the backend origin at build time, and Google Sign-In needs the frontend origin
registered before anyone can log in. It breaks because only one of the three is *build*-time — so
deploy the backend with a placeholder origin first and correct it at the end.

1. **Push to `main`** and confirm CI is green. Render builds from GitHub, not from a laptop.
2. **Smoke-test the image under Render's real limits** (with `docker compose up -d` running). The
   resource flags are the point — this is what catches an OOM, or a boot too slow for the deploy
   health check, before Render does:
   ```bash
   cd server
   docker build -t taskboard-api .
   time docker run --rm --memory=512m --cpus=0.1 -p 8081:8081 -e PORT=8081 \
     -e JAVA_OPTS="-Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError" \
     -e DB_HOST=host.docker.internal -e DB_PORT=5432 -e DB_NAME=taskboard \
     -e DB_USERNAME=taskboard -e DB_PASSWORD=taskboard taskboard-api
   curl localhost:8081/api/health   # {"status":"ok"}
   ```
   Note how long the boot takes. `0.1` is a *tenth* of a core, well below anything the JVM flags
   were measured against — that number is the one worth knowing before Render tells you.
3. **Create the Neon project** (Postgres 16, AWS Asia Pacific (Singapore), next to the Render
   region) and take its **direct** connection string, converted to JDBC as described in
   [Connecting to Neon](#connecting-to-neon).
4. **Render → New → Blueprint → this repo.** It reads `render.yaml` and proposes the web service,
   prompting for the `sync: false` values:
   - `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` — from step 3
   - `JWT_SECRET` — `openssl rand -base64 48`
   - `GOOGLE_CLIENT_ID` — the same client id the frontend will use
   - `APP_CORS_ALLOWED_ORIGINS` — a placeholder for now; corrected in step 7

   Watch the deploy log for `PostgreSQL 16.x` and Flyway applying `V1`–`V8` against the fresh
   database, then `curl https://taskboard-api.onrender.com/api/health`.

   Doing it by hand instead? Web Service → Docker, **Root Directory `server`** (also the Docker
   build context, which is why the Dockerfile lives there), health check `/api/health`, auto-deploy
   off, and every variable from the table below — `render.yaml` is the checklist.
5. **Vercel → Import Project.** Root Directory `frontend`, Node 22, and both `NEXT_PUBLIC_*` vars
   set **before the first build** — they are inlined into the bundle, so setting them afterwards
   needs a redeploy, not a restart. Note the production URL.
6. **Google Cloud Console → Credentials → the OAuth 2.0 Web client → Authorized JavaScript origins**
   → add the exact production frontend origin (scheme included, no path, no trailing slash). Sign-in
   is Google-only, so **until this propagates nobody can log in.** No redirect URI is needed.
7. **Render → the web service → Environment** → set `APP_CORS_ALLOWED_ORIGINS` to the Vercel
   production URL, replacing the placeholder. The service restarts; no rebuild and no frontend
   redeploy.

Adding a custom domain later means redoing steps 6 and 7 with the new origin.

### Connecting to Neon

The service reaches [Neon](https://neon.tech) through a single `DB_URL`, which overrides the
`DB_HOST`/`DB_PORT`/`DB_NAME` composition in `application.properties` (those stay unset on Render).
That connection crosses the public internet, which is what the `sslmode`, pool and timeout settings
in `application.properties` are written for. Neon hands out a libpq URI that pgJDBC will not accept
verbatim:

```
postgresql://alice:pw@ep-x-y-pooler.aws.neon.tech/neondb?sslmode=require&channel_binding=require
jdbc:postgresql://ep-x-y.aws.neon.tech/neondb?sslmode=require
```

Three edits, each of which fails differently if missed:

- **Credentials out of the URL** into `DB_USERNAME` / `DB_PASSWORD`. Leaving a bare `@` behind makes
  pgJDBC parse an empty username.
- **Drop `channel_binding`** — libpq's spelling; pgJDBC's is `channelBinding`, and it rejects the
  snake_case one.
- **Use the direct endpoint, not `-pooler`.** Neon's pooled endpoint is PgBouncer in transaction
  mode, where advisory locks are unsupported and Neon itself recommends direct connections for
  schema migrations — and Flyway takes a lock before migrating. The pooler solves a problem this app
  doesn't have (thousands of short-lived serverless connections); one long-running JVM with a
  5-connection Hikari pool wants the direct endpoint, which allows ~100.

Keep `sslmode=require`, choose **PostgreSQL 16** to match `docker-compose.yml` and CI (Flyway 11.7.2
supports up to 17 and warns on 18 — *"support has not been tested"*), and verify before deploying by
running the image against it locally: the boot log should report `PostgreSQL 16.x` and
`Successfully applied 8 migrations`. Neon **autosuspends after ~5 minutes idle**, so the first query
afterwards pays a resume of roughly a second — that is why `connection-timeout` is 30 s rather than
the 250 ms default.

### Environment variables

Everything on Render is set by `render.yaml`; the table is what it sets and why.

| Variable | Where | Required | Notes |
|---|---|---|---|
| `DB_URL` | Render | yes | JDBC URL of Neon's **direct** endpoint with `?sslmode=require` — see [Connecting to Neon](#connecting-to-neon). Overrides `DB_HOST`/`DB_PORT`/`DB_NAME`, which stay unset (they exist for local dev's composed default) |
| `DB_USERNAME` / `DB_PASSWORD` | Render | yes | Neon role and password, kept **out** of `DB_URL` |
| `SPRING_PROFILES_ACTIVE` | Render | yes (`prod`) | arms `JwtService`'s weak-secret guard — that is its only job |
| `JWT_SECRET` | Render | yes | ≥ 32 bytes (`openssl rand -base64 48`). Rotating it logs everyone out |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | Render | no | ISO-8601; default `PT15M` / `P7D` |
| `GOOGLE_CLIENT_ID` | Render | yes | must equal the frontend's |
| `APP_CORS_ALLOWED_ORIGINS` | Render | yes | comma-separated **exact** origins, **no trailing slash**; covers both REST and the `/ws` handshake |
| `PORT` | Render | no | Render injects it (10000 by default) and `application.properties` reads it; pinned to `8080` so the bound port, `EXPOSE` and Render's detection agree |
| `JAVA_OPTS` | Render | no | overrides the Dockerfile's 256 MB-sized defaults without a rebuild — the blueprint sets the 512 MB variant. See below |
| `DB_POOL_SIZE` / `TOMCAT_MAX_THREADS` | Render | no | default `5` / `25`, both sized for a small container |
| `NEXT_PUBLIC_API_URL` | Vercel | yes | **inlined at build time**, no trailing slash. `wss://` is derived from it |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Vercel | yes | **inlined at build time**; unset = nobody can sign in |

Preview deployments won't be able to sign in: Google's Authorized JavaScript origins accepts no
wildcards, so a fresh preview hostname fails with `origin_mismatch`. For a long-lived branch, add its
deterministic Vercel branch alias to *both* `APP_CORS_ALLOWED_ORIGINS` and the Google client.

### Sizing the JVM

Spring Boot + Hibernate on a free-tier container is genuinely tight, so the flags are measured
rather than guessed. Measured locally with `--cpus=0.25` under load — six concurrent users each
creating a board, three columns and 24 cards, then re-reading the whole board:

| `JAVA_OPTS` | Container | Startup | Idle | Peak under load |
|---|---|---|---|---|
| `-XX:MaxRAMPercentage=75` | 256 MB | 120 s | 213 MB | **240 MB (94%)** — 16 MB from the kill |
| tuned, `-Xmx128m` (the Dockerfile default) | 256 MB | **48 s** | 195 MB | **210 MB (82%)** |
| tuned, `-Xmx256m` (what `render.yaml` sets) | 512 MB | 48 s | 198 MB | 214 MB (42%) |

The flag doing the work is the hard `-Xmx`. A percentage-of-RAM heap looks right and isn't: 75% of
256 MB reserves 192 MB for the heap and leaves nothing for the ~100 MB of metaspace Spring and
Hibernate need, which is how the first row ends up 16 MB from an OOM kill under ordinary use.
Capping the heap forces GC to work harder *instead of* the kernel killing the container.
`-XX:TieredStopAtLevel=1` is what cut startup by 60%.

The Dockerfile keeps the 256 MB row as its default so the image runs anywhere; `render.yaml`
overrides `JAVA_OPTS` to the third row, since Render's free instance has 512 MB. That row is also
the one that says what a bigger plan buys: doubling the memory moved peak usage by 4 MB, because the
app's working set is simply ~210 MB. **RAM is not the thing to pay for here — CPU is.**

Which is the caveat on the whole table: every row was measured at 0.25 CPU, and **Render's free
instance is 0.1 CPU.** Memory behaviour carries over unchanged (it is not CPU-dependent), but
startup does not — expect meaningfully more than 48 s, and measure it with step 2's `time docker run`
before blaming Render. Startup time is not cosmetic on a platform that health-checks a new deploy
and spins the instance down when idle: it is paid on every deploy *and* on the first request after
each spin-down.

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
- **A free Render instance spins down after 15 minutes without inbound traffic**, and Render's own
  health checks do not count as traffic. This hurts a realtime app more than a request/response one:
  spinning down kills every open WebSocket, and the next visitor waits out a full cold start on
  0.1 CPU. The client recovers correctly — `useBoardEvents` reconnects and re-runs the page's
  `load()`, because the simple broker has no replay — it just recovers slowly. The two ways out are
  an external pinger every 10 minutes (which burns most of the 750 free instance-hours a month, so
  it is really a way of spending the allowance rather than avoiding the problem) and the paid
  Starter plan, which also lifts the CPU.
- **Neon autosuspends after ~5 minutes idle**, so after a quiet spell the first query also pays a
  compute resume of about a second, on top of any Render cold start. And Neon's own point-in-time
  restore lives inside the same account it protects — it is not a substitute for an off-site backup.

### Backups and restore

[`.github/workflows/db-backup.yml`](.github/workflows/db-backup.yml) dumps the Neon database every
night at 02:00 IST (and on demand), checks that the archive holds data for every app table, encrypts
it with [age](https://age-encryption.org), and keeps it as a workflow artifact for 30 days. **This
repo is public, so anyone can download those artifacts.** That is safe only because the dump is
encrypted on the runner. The private key never goes to GitHub, and **without it every backup is
unreadable, including to you.**

One-time setup:

```bash
brew install age
mkdir -p ~/.config/taskboard
age-keygen -o ~/.config/taskboard/backup-key.txt     # prints "Public key: age1…"
# Also store backup-key.txt in a password manager: lose it and the backups are noise.

gh variable set BACKUP_AGE_RECIPIENT --body "age1…"  # the PUBLIC key (not a secret)
gh secret set BACKUP_DATABASE_URL                    # paste Neon's libpq URI at the prompt
gh workflow run db-backup.yml && gh run watch        # first run, to prove it works
```

`BACKUP_DATABASE_URL` is Neon's connection string **as Neon shows it**
(`postgresql://user:pw@ep-…/neondb?sslmode=require&channel_binding=require`). Unlike `DB_URL`, it is
read by libpq, which needs none of the JDBC edits. If you bump Neon to a newer Postgres major, raise
`PG_IMAGE` in the workflow too, because `pg_dump` refuses to dump a newer server. GitHub emails
whoever last edited the schedule when a run fails. It also **disables scheduled workflows after 60
days without a commit**, so don't ignore that email either.

**Restore drill.** A backup you have never restored is a hope, not a backup. Run this once now and
again after any schema change. It uses port 5433 so it never touches the dev database:

```bash
gh run download --name taskboard-db-<run-number> --dir /tmp/tb-restore
age -d -i ~/.config/taskboard/backup-key.txt /tmp/tb-restore/*.dump.age > /tmp/tb-restore/restore.dump

docker run -d --name tb-restore -e POSTGRES_DB=taskboard -e POSTGRES_USER=taskboard \
  -e POSTGRES_PASSWORD=x -p 5433:5432 postgres:16-alpine
sleep 3
docker exec -i tb-restore pg_restore -U taskboard -d taskboard --no-owner --no-acl \
  < /tmp/tb-restore/restore.dump

cd server && DB_PORT=5433 DB_PASSWORD=x ./mvnw spring-boot:run
# Flyway should report the schema "is up to date" with no migrations applied, and the app
# on localhost should show production's boards.

docker rm -f tb-restore && rm -rf /tmp/tb-restore      # it's real user data: don't keep it
```

Restoring production itself is the same `pg_restore`, pointed at a **fresh** Neon project (its libpq
URI in place of `-U … -d …`). After that, swap `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` on Render and
restart. Restore into a new project rather than over the damaged one, so the damaged one stays
around to inspect.

## Docs

- [`project-scope.md`](project-scope.md) — what the product is, plus the full data model
- [`implementation-plan.md`](implementation-plan.md) — the ordered milestones (M0 → M6)
- [`CLAUDE.md`](CLAUDE.md) — architecture, conventions, and the decisions behind them
- [`LEARNING.md`](LEARNING.md) — a step-by-step walkthrough of how it was built
