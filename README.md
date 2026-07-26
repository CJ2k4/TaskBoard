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

The backend ships as a **Docker image to a Render web service** alongside **Render managed Postgres**
(same region, private networking); the frontend deploys to **Vercel** from the `frontend/` root
directory. The deployed shape is declared in [`render.yaml`](render.yaml). There is **no CD** —
deploys are a deliberate action on both sides.

### First deploy

The three pieces depend on each other in a cycle: the backend's CORS wants the frontend origin, the
frontend **bakes in** the backend origin at build time, and Google Sign-In needs the frontend origin
registered before anyone can log in. It breaks because only one of the three is *build*-time — so
deploy the backend with a placeholder first.

1. **Push to `main`** and confirm CI is green. Both platforms build from GitHub, not from a laptop.
2. **Smoke-test the image locally** (with `docker compose up -d` running):
   ```bash
   cd server
   docker build -t taskboard-api .
   docker run --rm -p 8081:8081 -e PORT=8081 \
     -e DB_HOST=host.docker.internal -e DB_PORT=5432 -e DB_NAME=taskboard \
     -e DB_USERNAME=taskboard -e DB_PASSWORD=taskboard taskboard-api
   curl localhost:8081/api/health   # {"status":"ok"}
   ```
3. **Render → New → Blueprint →** this repo. It prompts for `JWT_SECRET`
   (`openssl rand -base64 48`), `GOOGLE_CLIENT_ID`, and `APP_CORS_ALLOWED_ORIGINS` — set that last
   one to `http://localhost:3000` as a deliberate placeholder. Check the logs for the Flyway V1–V8
   migrations, then `curl https://taskboard-api.onrender.com/api/health`.
4. **Vercel → Import Project.** Root Directory `frontend`, Node 22, and both `NEXT_PUBLIC_*` vars
   below set **before the first build**. Note the production URL.
5. **Google Cloud Console → Credentials → the OAuth 2.0 Web client → Authorized JavaScript origins**
   → add the exact production frontend origin (scheme included, no path, no trailing slash). Sign-in
   is Google-only, so **until this propagates nobody can log in.** No redirect URI is needed.
6. **Render → taskboard-api → Environment** → set `APP_CORS_ALLOWED_ORIGINS` to the Vercel production
   URL. The service restarts; no rebuild and no frontend redeploy.

Adding a custom domain later means redoing steps 5 and 6 with the new origin.

### Environment variables

| Variable | Where | Required | Notes |
|---|---|---|---|
| `PORT` | Render | injected | Render sets it; read via `server.port=${PORT:8080}` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | Render | yes | wired from the managed DB by `render.yaml` |
| `DB_URL` | local / CI | no | full JDBC URL; overrides the composed one |
| `SPRING_PROFILES_ACTIVE` | Render | yes (`prod`) | arms `JwtService`'s weak-secret guard — that is its only job |
| `JWT_SECRET` | Render | yes | ≥ 32 bytes. Rotating it logs everyone out |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | Render | no | ISO-8601; default `PT15M` / `P7D` |
| `GOOGLE_CLIENT_ID` | Render | yes | must equal the frontend's |
| `APP_CORS_ALLOWED_ORIGINS` | Render | yes | comma-separated **exact** origins, **no trailing slash**; covers both REST and the `/ws` handshake |
| `NEXT_PUBLIC_API_URL` | Vercel | yes | **inlined at build time**, no trailing slash. `wss://` is derived from it |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | Vercel | yes | **inlined at build time**; unset = nobody can sign in |

Preview deployments won't be able to sign in: Google's Authorized JavaScript origins accepts no
wildcards, so a fresh preview hostname fails with `origin_mismatch`. For a long-lived branch, add its
deterministic Vercel branch alias to *both* `APP_CORS_ALLOWED_ORIGINS` and the Google client.

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
- On Render's free tier the service spins down after ~15 min idle: the next request pays a JVM +
  Flyway cold start and open sockets are dropped (the client reconnects and resyncs). Free Postgres
  instances expire and are deleted, with no backups worth relying on.

## Docs

- [`project-scope.md`](project-scope.md) — what the product is, plus the full data model
- [`implementation-plan.md`](implementation-plan.md) — the ordered milestones (M0 → M6)
- [`CLAUDE.md`](CLAUDE.md) — architecture, conventions, and the decisions behind them
- [`LEARNING.md`](LEARNING.md) — a step-by-step walkthrough of how it was built
