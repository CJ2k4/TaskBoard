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

## Docs

- [`project-scope.md`](project-scope.md) — what the product is, plus the full data model
- [`implementation-plan.md`](implementation-plan.md) — the ordered milestones (M0 → M6)
- [`CLAUDE.md`](CLAUDE.md) — architecture, conventions, and the decisions behind them
- [`LEARNING.md`](LEARNING.md) — a step-by-step walkthrough of how it was built
