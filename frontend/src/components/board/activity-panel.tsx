"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { useAuth } from "@/lib/auth-context";
import { listActivity, type Activity } from "@/lib/activity";
import { avatarColor, initials } from "@/lib/avatar";
import { timeAgo } from "@/lib/time";

/** Entries fetched per request, both on open and per "load more". */
const PAGE = 50;

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
  // The fetch is inlined rather than calling `load()` so nothing sets state synchronously in the
  // effect body. `status` already starts as "loading", and a refetch deliberately leaves the
  // current entries on screen instead of flashing the spinner on every live event.
  useEffect(() => {
    let cancelled = false;
    listActivity(authFetch, boardId, { limit: PAGE })
      .then((page) => {
        if (cancelled) return;
        setEntries(page);
        setAtEnd(page.length < PAGE);
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });
    return () => {
      cancelled = true;
    };
  }, [authFetch, boardId, refreshSignal]);

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
    <div
      className="animate-fade-in fixed inset-0 z-50 flex justify-end bg-zinc-900/40 backdrop-blur-[3px]"
      onClick={onClose}
    >
      <aside
        className="animate-slide-in-right flex h-full w-full max-w-sm flex-col border-l border-line bg-paper shadow-[var(--shadow-xl)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-4 border-b border-line p-4">
          <div>
            <h2 className="flex items-center gap-2 text-base font-semibold text-zinc-900">
              <span aria-hidden className="text-brand-500">
                ◷
              </span>
              Activity
            </h2>
            <p className="mt-0.5 text-xs text-zinc-500">
              Everything that&apos;s happened on this board, newest first.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-sm text-zinc-500 transition-all duration-200 hover:rotate-90 hover:bg-sunken hover:text-zinc-900"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        <div className="scroll-slim flex-1 overflow-y-auto p-4">
          {status === "loading" && (
            <div className="flex flex-col gap-4" aria-label="Loading activity">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="flex gap-3">
                  <div className="skeleton h-6 w-6 shrink-0 rounded-full" />
                  <div className="flex-1">
                    <div className="skeleton h-3 w-full rounded" />
                    <div className="skeleton mt-1.5 h-3 w-1/3 rounded" />
                  </div>
                </div>
              ))}
            </div>
          )}
          {status === "error" && (
            <p className="animate-pop-in rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              Couldn&apos;t load the activity.{" "}
              <button onClick={load} className="font-semibold underline underline-offset-2">
                Retry
              </button>
            </p>
          )}
          {status === "ready" && entries.length === 0 && (
            <div className="animate-pop-in flex flex-col items-center gap-2 py-12 text-center">
              <span aria-hidden className="text-3xl opacity-30">
                ◷
              </span>
              <p className="text-sm text-zinc-500">Nothing has happened yet.</p>
            </div>
          )}
          {status === "ready" && entries.length > 0 && (
            <>
              {/* The timeline rail: one continuous line the entries hang off. */}
              <ul className="stagger relative flex flex-col gap-3 before:absolute before:bottom-2 before:left-[11px] before:top-2 before:w-px before:bg-line">
                {entries.map((entry) => (
                  <ActivityRow key={entry.id} entry={entry} currentUserId={currentUserId} />
                ))}
              </ul>
              {!atEnd && (
                <button
                  onClick={loadMore}
                  disabled={loadingMore}
                  className="press mt-4 w-full rounded-lg border border-line-strong px-3 py-2 text-sm font-medium text-zinc-700 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-50"
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

/** One "{actor} {summary} · {time}" line, hung off the timeline rail by its actor's avatar. */
function ActivityRow({
  entry,
  currentUserId,
}: {
  entry: Activity;
  currentUserId?: string;
}) {
  // "You" reads better than your own name in your own feed; a deleted account degrades to "Someone".
  const you = Boolean(entry.actorId) && entry.actorId === currentUserId;
  const actor = you ? "You" : (entry.actorName ?? "Someone");

  return (
    <li className="group/row relative flex gap-3">
      {/* Sits on top of the rail, so the line reads as threading through each entry. */}
      <span
        className="relative z-10 mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[9px] font-semibold text-white ring-2 ring-paper transition-transform duration-200 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover/row:scale-110"
        style={{
          backgroundColor: entry.actorId ? avatarColor(entry.actorId) : "#a1a1aa",
        }}
        aria-hidden
      >
        {initials(entry.actorName ?? "?")}
      </span>
      <p className="min-w-0 flex-1 text-sm leading-relaxed text-zinc-700">
        <span className={`font-semibold ${you ? "text-brand-700" : "text-zinc-900"}`}>{actor}</span>{" "}
        {entry.summary}
        <span className="ml-1 whitespace-nowrap font-mono text-xs text-zinc-400">
          · {timeAgo(entry.createdAt)}
        </span>
      </p>
    </li>
  );
}
