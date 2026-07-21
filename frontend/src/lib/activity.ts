/**
 * Typed client for the activity-log API (Milestone 6). Same shape as `boards.ts`/`members.ts`:
 * every call takes the `authFetch` from `useAuth()`, and the type mirrors the backend's
 * `ActivityResponse`.
 *
 * The feed is read-only from the client's side — entries are written server-side as a side effect
 * of the very mutations this app already makes, so there is no "create activity" call.
 */

import type { AuthFetch } from "@/lib/boards";
import type { BoardEventType } from "@/lib/board-events";

/**
 * One line of history. `summary` is the rendered predicate ("moved card …"); `actorName` is
 * joined server-side and is null when the actor's account is gone (render "Someone").
 */
export type Activity = {
  id: string;
  actorId: string | null;
  actorName: string | null;
  type: BoardEventType;
  summary: string;
  createdAt: string;
};

/**
 * A board's activity, newest first. `before` (an entry's `createdAt`) pages backward for
 * "load more"; `limit` caps the page (server clamps to ≤100, default 50).
 */
export const listActivity = (
  authFetch: AuthFetch,
  boardId: string,
  opts?: { limit?: number; before?: string },
) => {
  const params = new URLSearchParams();
  if (opts?.limit != null) params.set("limit", String(opts.limit));
  if (opts?.before) params.set("before", opts.before);
  const query = params.toString();
  return authFetch<Activity[]>(
    `/api/boards/${boardId}/activity${query ? `?${query}` : ""}`,
  );
};
