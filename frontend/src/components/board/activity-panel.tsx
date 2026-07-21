"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { useAuth } from "@/lib/auth-context";
import { listActivity, type Activity } from "@/lib/activity";
import { timeAgo } from "@/lib/time";

/**
 * The board's activity feed (M6): a right-side drawer listing "who did what, when", newest first.
 * A portal on `document.body`, closed by the backdrop or Escape — the same idiom as `ShareModal`.
 *
 * It refetches whenever `refreshSignal` changes, which the board page bumps on any live event that
 * gets logged. That deliberately reuses the server's rendered summaries rather than re-deriving a
 * sentence from the wire event on the client — one source of truth for "how a change reads".
 */
export function ActivityPanel({
  boardId,
  currentUserId,
  refreshSignal,
  onClose,
}: {
  boardId: string;
  currentUserId?: string;
  /** Bumped by the board page on any logged live event, so an open feed stays current. */
  refreshSignal?: number;
  onClose: () => void;
}) {
  const { authFetch } = useAuth();

  const [entries, setEntries] = useState<Activity[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [loadingMore, setLoadingMore] = useState(false);
  // Null until we know: true once a "load more" returns a short page (no older history left).
  const [atEnd, setAtEnd] = useState(false);

  const PAGE = 50;

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      const page = await listActivity(authFetch, boardId, { limit: PAGE });
      setEntries(page);
      setAtEnd(page.length < PAGE);
      setStatus("ready");
    } catch {
      setStatus("error");
    }
  }, [authFetch, boardId]);

  // Load on open and on every refresh signal (a logged change happened while we're watching).
  useEffect(() => {
    load();
  }, [load, refreshSignal]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function loadMore() {
    const oldest = entries[entries.length - 1];
    if (!oldest) return;
    setLoadingMore(true);
    try {
      const older = await listActivity(authFetch, boardId, { limit: PAGE, before: oldest.createdAt });
      setEntries((prev) => [...prev, ...older]);
      if (older.length < PAGE) setAtEnd(true);
    } catch {
      // A failed "load more" leaves what we have; the user can try again.
    } finally {
      setLoadingMore(false);
    }
  }

  return createPortal(
    <div className="fixed inset-0 z-50 flex justify-end bg-black/50" onClick={onClose}>
      <aside
        className="flex h-full w-full max-w-sm flex-col border-l border-zinc-200 bg-white shadow-xl dark:border-zinc-800 dark:bg-zinc-950"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-4 border-b border-zinc-200 p-4 dark:border-zinc-800">
          <div>
            <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-50">Activity</h2>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              Everything that&apos;s happened on this board, newest first.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-sm text-zinc-500 transition hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {status === "loading" && (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Loading activity…</p>
          )}
          {status === "error" && (
            <p className="text-sm text-red-600 dark:text-red-400">
              Couldn&apos;t load the activity.{" "}
              <button onClick={load} className="underline">
                Retry
              </button>
            </p>
          )}
          {status === "ready" && entries.length === 0 && (
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Nothing has happened yet.</p>
          )}
          {status === "ready" && entries.length > 0 && (
            <>
              <ul className="flex flex-col gap-3">
                {entries.map((entry) => (
                  <ActivityRow key={entry.id} entry={entry} currentUserId={currentUserId} />
                ))}
              </ul>
              {!atEnd && (
                <button
                  onClick={loadMore}
                  disabled={loadingMore}
                  className="mt-4 w-full rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
                >
                  {loadingMore ? "Loading…" : "Load more"}
                </button>
              )}
            </>
          )}
        </div>
      </aside>
    </div>,
    document.body,
  );
}

/** One "{actor} {summary} · {time}" line. */
function ActivityRow({
  entry,
  currentUserId,
}: {
  entry: Activity;
  currentUserId?: string;
}) {
  // "You" reads better than your own name in your own feed; a deleted account degrades to "Someone".
  const actor =
    entry.actorId && entry.actorId === currentUserId
      ? "You"
      : (entry.actorName ?? "Someone");

  return (
    <li className="text-sm text-zinc-700 dark:text-zinc-300">
      <span className="font-medium text-zinc-900 dark:text-zinc-100">{actor}</span> {entry.summary}
      <span className="ml-1 text-xs text-zinc-400 dark:text-zinc-500">· {timeAgo(entry.createdAt)}</span>
    </li>
  );
}
