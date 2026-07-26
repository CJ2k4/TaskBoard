"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { useAuth } from "@/lib/auth-context";
import { listBin, restoreCard, type BinnedCard } from "@/lib/bin";
import type { Card } from "@/lib/boards";
import { timeAgo, timeUntil } from "@/lib/time";

/**
 * The board's bin: a right-side drawer of deleted cards, each restorable until it expires. Same
 * portal-drawer idiom as `ActivityPanel`, refetched on a `refreshSignal` the board page bumps
 * whenever a card is binned or restored anywhere — including by someone else, live.
 *
 * Restoring is optimistic in one direction only: the row leaves this list as soon as the server
 * confirms, and the board page puts the card back via `onRestored`. There is no "delete for
 * ever" button, deliberately — the two-day window is a promise the UI shouldn't offer to break.
 */
export function BinPanel({
  boardId,
  canEdit,
  refreshSignal,
  onRestored,
  onClose,
}: {
  boardId: string;
  /** Viewers may look in the bin, but only editors can put a card back. */
  canEdit: boolean;
  refreshSignal?: number;
  onRestored: (card: Card) => void;
  onClose: () => void;
}) {
  const { authFetch } = useAuth();

  const [entries, setEntries] = useState<BinnedCard[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [restoringId, setRestoringId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      setEntries(await listBin(authFetch, boardId));
      setStatus("ready");
    } catch {
      setStatus("error");
    }
  }, [authFetch, boardId]);

  // Load on open and on every refresh signal. The fetch is inlined rather than calling `load()`
  // so no state is set synchronously in the effect body; a refetch keeps the current list on
  // screen instead of flashing the skeleton every time someone bins a card.
  useEffect(() => {
    let cancelled = false;
    listBin(authFetch, boardId)
      .then((rows) => {
        if (cancelled) return;
        setEntries(rows);
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

  async function handleRestore(cardId: string) {
    setRestoringId(cardId);
    setError(null);
    try {
      const restored = await restoreCard(authFetch, cardId);
      setEntries((prev) => prev.filter((e) => e.card.id !== cardId));
      onRestored(restored);
    } catch {
      // The usual cause is that it expired and was purged while the drawer sat open.
      setError("Couldn't restore that card. It may have expired — refreshing the bin.");
      await load();
    } finally {
      setRestoringId(null);
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
                🗑
              </span>
              Bin
            </h2>
            <p className="mt-0.5 text-xs text-zinc-500">
              Deleted cards stay here for 2 days, then they&apos;re gone for good.
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
          {error && (
            <p className="animate-pop-in mb-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
              {error}
            </p>
          )}

          {status === "loading" && (
            <div className="flex flex-col gap-3" aria-label="Loading the bin">
              {[0, 1, 2].map((i) => (
                <div key={i} className="rounded-xl border border-line p-3">
                  <div className="skeleton h-3 w-2/3 rounded" />
                  <div className="skeleton mt-2 h-3 w-1/3 rounded" />
                </div>
              ))}
            </div>
          )}

          {status === "error" && (
            <p className="animate-pop-in rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
              Couldn&apos;t load the bin.{" "}
              <button onClick={load} className="font-semibold underline underline-offset-2">
                Retry
              </button>
            </p>
          )}

          {status === "ready" && entries.length === 0 && (
            <div className="animate-pop-in flex flex-col items-center gap-2 py-12 text-center">
              <span aria-hidden className="text-3xl opacity-30">
                🗑
              </span>
              <p className="text-sm text-zinc-500">The bin is empty.</p>
              <p className="text-xs text-zinc-400">Drag a card onto the bin to delete it.</p>
            </div>
          )}

          {status === "ready" && entries.length > 0 && (
            <ul className="stagger flex flex-col gap-3">
              {entries.map((entry) => (
                <li
                  key={entry.card.id}
                  className="animate-pop-in rounded-xl border border-line bg-paper p-3 shadow-[var(--shadow-sm)]"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      {entry.card.label && (
                        <span className="mb-1 inline-block rounded bg-brand-50 px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wide text-brand-600">
                          {entry.card.label}
                        </span>
                      )}
                      <p className="break-words text-sm font-medium text-zinc-800">
                        {entry.card.title}
                      </p>
                      <p className="mt-1 text-xs text-zinc-500">
                        {entry.columnTitle ? `from ${entry.columnTitle} · ` : ""}
                        {timeAgo(entry.deletedAt)}
                      </p>
                    </div>

                    {canEdit && (
                      <button
                        type="button"
                        onClick={() => handleRestore(entry.card.id)}
                        disabled={restoringId === entry.card.id}
                        className="press shrink-0 rounded-lg border border-line-strong px-2.5 py-1.5 text-xs font-semibold text-zinc-700 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-50"
                      >
                        {restoringId === entry.card.id ? "…" : "Restore"}
                      </button>
                    )}
                  </div>

                  <p className="mt-2 font-mono text-[10px] uppercase tracking-wide text-zinc-400">
                    {timeUntil(entry.purgeAt)}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>
    </div>,
    document.body,
  );
}
