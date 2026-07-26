"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";

import { useAuth } from "@/lib/auth-context";
import {
  createBoard,
  deleteBoard,
  listBoards,
  updateBoard,
  type BoardOverview,
  type MemberSummary,
} from "@/lib/boards";
import { avatarColor, initials } from "@/lib/avatar";
import { timeAgo } from "@/lib/time";
import { Protected } from "@/components/protected";
import { Navbar } from "@/components/navbar";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";
import { RoleBadge } from "@/components/board/share-modal";

export default function DashboardPage() {
  return (
    <Protected>
      <Navbar />
      <DashboardContent />
    </Protected>
  );
}

/** Rendered only once <Protected> confirms an authenticated user. */
function DashboardContent() {
  const { authFetch, user } = useAuth();

  const [boards, setBoards] = useState<BoardOverview[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

  const load = useCallback(async () => {
    try {
      setBoards(await listBoards(authFetch));
      setStatus("ready");
    } catch {
      setStatus("error");
    }
  }, [authFetch]);

  // Initial load. The fetch is inlined rather than calling `load()` so nothing sets state
  // synchronously in the effect body, and a `cancelled` guard stops a slow response from
  // landing after unmount. `load` stays for imperative refetches from the handlers below.
  useEffect(() => {
    let cancelled = false;
    listBoards(authFetch)
      .then((rows) => {
        if (cancelled) return;
        setBoards(rows);
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });
    return () => {
      cancelled = true;
    };
  }, [authFetch]);

  async function handleCreate(name: string) {
    await createBoard(authFetch, name);
    // Refetch so the new card carries its overview data (owner in the roster, zeroed counts).
    await load();
  }

  async function handleRename(id: string, name: string) {
    const current = boards.find((b) => b.id === id);
    // The board PATCH edits name + description together, so resend the current description.
    const updated = await updateBoard(authFetch, id, name, current?.description ?? null);
    setBoards((prev) =>
      prev.map((b) =>
        b.id === id ? { ...b, name: updated.name, description: updated.description } : b,
      ),
    );
  }

  async function handleDelete(id: string) {
    await deleteBoard(authFetch, id);
    setBoards((prev) => prev.filter((b) => b.id !== id));
  }

  const firstName = user?.name?.trim().split(/\s+/)[0] ?? null;

  return (
    <main className="animate-page-in relative min-h-full flex-1 overflow-hidden bg-canvas p-8">
      {/* A soft colour field behind the grid so the page isn't a flat slab of beige. */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="animate-float-slow absolute -right-40 -top-52 h-[30rem] w-[30rem] rounded-full bg-brand-200/25 blur-3xl" />
        <div
          className="animate-float-slow absolute -left-40 top-1/3 h-[24rem] w-[24rem] rounded-full bg-amber-200/20 blur-3xl"
          style={{ animationDelay: "-7s" }}
        />
      </div>

      <div className="relative mx-auto w-full max-w-5xl">
        <header className="animate-rise-in mb-8">
          <h1 className="text-3xl font-bold tracking-tight text-zinc-900">
            {firstName ? (
              <>
                Welcome back, <span className="text-gradient">{firstName}</span>
              </>
            ) : (
              "Boards"
            )}
          </h1>
          <p className="mt-1.5 text-sm text-zinc-500">
            Shared with your workspace · changes sync live
          </p>
        </header>

        {status === "loading" && <BoardGridSkeleton />}

        {status === "error" && (
          <div className="animate-pop-in rounded-2xl border border-red-200 bg-red-50/70 p-6 text-sm text-red-700">
            Couldn&apos;t load your boards.{" "}
            <button onClick={load} className="font-semibold underline underline-offset-2">
              Retry
            </button>
          </div>
        )}

        {status === "ready" && (
          <div
            key={boards.length}
            className="stagger grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3"
          >
            {boards.map((board) => (
              <BoardCard
                key={board.id}
                board={board}
                onRename={handleRename}
                onDelete={handleDelete}
              />
            ))}
            <NewBoardCard onCreate={handleCreate} />
          </div>
        )}
      </div>
    </main>
  );
}

/** Shimmering placeholders in the exact shape of the grid, so the layout doesn't jump on load. */
function BoardGridSkeleton() {
  return (
    <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3" aria-label="Loading boards">
      {[0, 1, 2, 3].map((i) => (
        <div
          key={i}
          className="animate-fade-in flex min-h-52 flex-col rounded-2xl border border-line bg-paper p-6"
          style={{ animationDelay: `${i * 70}ms` }}
        >
          <div className="skeleton h-5 w-2/3 rounded-md" />
          <div className="skeleton mt-3 h-3 w-full rounded" />
          <div className="skeleton mt-2 h-3 w-1/3 rounded" />
          <div className="mt-auto flex items-center justify-between pt-6">
            <div className="flex">
              {[0, 1, 2].map((a) => (
                <div key={a} className="skeleton -ml-1.5 h-7 w-7 rounded-full first:ml-0" />
              ))}
            </div>
            <div className="skeleton h-3 w-14 rounded" />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * One board as an overview card: name, description, its column/card counts, the member avatar
 * stack, and when it was last touched. Opening it is a click on the title.
 *
 * Since M4 this grid also contains boards shared *with* you, so a card says which is which — a
 * role badge on anything you don't own. Owner-only controls (rename inline, delete two-step) sit
 * in a corner that reveals on hover, keeping the card clean by default.
 */
function BoardCard({
  board,
  onRename,
  onDelete,
}: {
  board: BoardOverview;
  onRename: (id: string, name: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(board.name);
  const isOwner = board.myRole === "OWNER";

  async function save() {
    const next = name.trim();
    if (next === "" || next === board.name) {
      setName(board.name);
      setEditing(false);
      return;
    }
    await onRename(board.id, next);
    setEditing(false);
  }

  return (
    <div className="lift group relative flex min-h-52 flex-col overflow-hidden rounded-2xl border border-line bg-paper p-6 shadow-[var(--shadow-sm)] hover:border-brand-200">
      {/* A brand rule that wipes across the top edge on hover — the card's "you can open me" tell. */}
      <span
        aria-hidden
        className="absolute inset-x-0 top-0 h-0.5 origin-left scale-x-0 bg-gradient-to-r from-brand-500 to-violet-500 transition-transform duration-500 ease-[cubic-bezier(0.22,1,0.36,1)] group-hover:scale-x-100"
      />

      {/* The whole card opens the board: a link overlay fills it, sitting beneath the controls
          (which get a higher z-index) so those stay clickable. Suppressed while renaming. */}
      {!editing && (
        <Link
          href={`/boards/${board.id}`}
          aria-label={`Open ${board.name}`}
          className="absolute inset-0 z-0 rounded-2xl"
        />
      )}

      {/* Top-right: role badge for shared boards, or hover-revealed owner controls. */}
      <div className="absolute right-4 top-4 z-10 flex items-center gap-3">
        {!isOwner && <RoleBadge role={board.myRole} />}
        {isOwner && !editing && (
          <div className="flex translate-y-1 items-center gap-3 opacity-0 transition-all duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] group-hover:translate-y-0 group-hover:opacity-100 focus-within:translate-y-0 focus-within:opacity-100">
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="text-xs font-medium text-zinc-500 transition-colors hover:text-brand-700"
            >
              Rename
            </button>
            <InlineConfirmButton onConfirm={() => onDelete(board.id)} />
          </div>
        )}
      </div>

      {editing && isOwner ? (
        <input
          autoFocus
          value={name}
          onChange={(e) => setName(e.target.value)}
          onBlur={save}
          onKeyDown={(e) => {
            if (e.key === "Enter") save();
            if (e.key === "Escape") {
              setName(board.name);
              setEditing(false);
            }
          }}
          className="animate-pop-in relative z-10 w-full rounded-lg border border-brand-300 bg-white px-2 py-1 text-xl font-semibold text-zinc-900 outline-none transition-shadow focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
        />
      ) : (
        <h3 className="pr-16 text-xl font-semibold tracking-tight text-zinc-900 transition-colors duration-200 group-hover:text-brand-700">
          {board.name}
        </h3>
      )}

      {board.description && (
        <p className="mt-2 line-clamp-2 text-sm text-zinc-500">{board.description}</p>
      )}

      <p className="mt-4 flex items-center gap-2 font-mono text-xs text-zinc-400">
        <span className="inline-flex items-center gap-1">
          <span className="h-1.5 w-1.5 rounded-full bg-brand-400" />
          {board.columnCount} {board.columnCount === 1 ? "column" : "columns"}
        </span>
        <span className="text-zinc-300">·</span>
        <span>
          {board.cardCount} {board.cardCount === 1 ? "card" : "cards"}
        </span>
      </p>

      {/* Above the overlay so avatar tooltips work; pointer-events-none lets clicks fall through
          to the link so the whole row still opens the board. */}
      <div className="pointer-events-none relative z-10 mt-auto flex items-end justify-between gap-3 pt-4">
        <AvatarStack members={board.members} />
        <span className="font-mono text-xs text-zinc-400">{timeAgo(board.updatedAt)}</span>
      </div>
    </div>
  );
}

const MAX_AVATARS = 5;

/**
 * Overlapping initial-avatars for a board's members, with a "+N" overflow chip. The stack fans
 * apart when the card is hovered, so a crowded board becomes readable without a click.
 */
function AvatarStack({ members }: { members: MemberSummary[] }) {
  if (members.length === 0) return <span />;
  const shown = members.slice(0, MAX_AVATARS);
  const overflow = members.length - shown.length;

  return (
    <div className="flex items-center" aria-label={`${members.length} members`}>
      {shown.map((m) => (
        <span
          key={m.userId}
          title={m.name}
          className="-ml-1.5 flex h-7 w-7 items-center justify-center rounded-full border-2 border-paper text-[10px] font-semibold text-white shadow-[var(--shadow-sm)] transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] first:ml-0 group-hover:ml-0.5 group-hover:first:ml-0"
          style={{ backgroundColor: avatarColor(m.userId) }}
        >
          {initials(m.name)}
        </span>
      ))}
      {overflow > 0 && (
        <span className="-ml-1.5 flex h-7 w-7 items-center justify-center rounded-full border-2 border-paper bg-zinc-400 text-[10px] font-semibold text-white transition-all duration-300 group-hover:ml-0.5">
          +{overflow}
        </span>
      )}
    </div>
  );
}

/**
 * The dashed "+ New board" tile that closes the grid. Clicking it reveals an inline
 * name field; submitting creates the board (the one create path there's ever been).
 */
function NewBoardCard({ onCreate }: { onCreate: (name: string) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [creating, setCreating] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = name.trim();
    if (trimmed === "") return;
    setCreating(true);
    try {
      await onCreate(trimmed);
      setName("");
      setOpen(false);
    } finally {
      setCreating(false);
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="group flex min-h-52 flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-line-strong text-sm font-medium text-zinc-500 transition-all duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] hover:-translate-y-1 hover:border-brand-400 hover:bg-brand-50/40 hover:text-brand-700"
      >
        <span className="flex h-11 w-11 items-center justify-center rounded-full border border-line-strong bg-paper text-lg text-zinc-400 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover:rotate-90 group-hover:scale-110 group-hover:border-brand-300 group-hover:bg-brand-500 group-hover:text-white">
          +
        </span>
        New board
      </button>
    );
  }

  return (
    <form
      onSubmit={submit}
      className="animate-pop-in flex min-h-52 flex-col justify-center gap-3 rounded-2xl border border-brand-300 bg-paper p-6 shadow-[var(--shadow-md)]"
    >
      <input
        ref={inputRef}
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            setName("");
            setOpen(false);
          }
        }}
        placeholder="New board name…"
        className="w-full rounded-lg border border-line-strong bg-white px-3 py-2 text-sm text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
      />
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={creating || name.trim() === ""}
          className="press rounded-lg bg-gradient-to-br from-brand-500 to-violet-600 px-4 py-2 text-sm font-semibold text-white shadow-[var(--shadow-brand)] disabled:opacity-50 disabled:shadow-none"
        >
          {creating ? "Creating…" : "Create"}
        </button>
        <button
          type="button"
          onClick={() => {
            setName("");
            setOpen(false);
          }}
          className="rounded-lg px-3 py-2 text-sm font-medium text-zinc-500 transition-colors hover:text-zinc-900"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
