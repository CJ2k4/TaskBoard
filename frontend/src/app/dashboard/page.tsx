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
  const { authFetch } = useAuth();

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

  useEffect(() => {
    load();
  }, [load]);

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

  return (
    <main className="animate-page-in min-h-full flex-1 bg-zinc-50 p-8 dark:bg-black">
      <div className="mx-auto w-full max-w-5xl">
        <header className="mb-8">
          <h1 className="text-3xl font-bold tracking-tight text-zinc-900 dark:text-zinc-50">
            Boards
          </h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            Shared with your workspace · changes sync live
          </p>
        </header>

        {status === "loading" && (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">Loading boards…</p>
        )}
        {status === "error" && (
          <p className="text-sm text-red-600 dark:text-red-400">
            Couldn&apos;t load your boards.{" "}
            <button onClick={load} className="underline">
              Retry
            </button>
          </p>
        )}

        {status === "ready" && (
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
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
    <div className="group relative flex min-h-52 flex-col rounded-2xl border border-zinc-200 bg-white p-6 shadow-sm transition hover:shadow-md dark:border-zinc-800 dark:bg-zinc-950">
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
          <div className="flex items-center gap-3 opacity-0 transition group-hover:opacity-100">
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="text-xs font-medium text-zinc-500 transition hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100"
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
          className="relative z-10 w-full rounded-lg border border-zinc-300 bg-white px-2 py-1 text-xl font-semibold text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        />
      ) : (
        <h3 className="pr-16 text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-100">
          {board.name}
        </h3>
      )}

      {board.description && (
        <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">{board.description}</p>
      )}

      <p className="mt-4 font-mono text-xs text-zinc-400 dark:text-zinc-500">
        {board.columnCount} {board.columnCount === 1 ? "column" : "columns"} · {board.cardCount}{" "}
        {board.cardCount === 1 ? "card" : "cards"}
      </p>

      {/* Above the overlay so avatar tooltips work; pointer-events-none lets clicks fall through
          to the link so the whole row still opens the board. */}
      <div className="pointer-events-none relative z-10 mt-auto flex items-end justify-between gap-3 pt-4">
        <AvatarStack members={board.members} />
        <span className="font-mono text-xs text-zinc-400 dark:text-zinc-500">
          {timeAgo(board.updatedAt)}
        </span>
      </div>
    </div>
  );
}

const MAX_AVATARS = 5;

/** Overlapping initial-avatars for a board's members, with a "+N" overflow chip. */
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
          className="-ml-1.5 flex h-7 w-7 items-center justify-center rounded-full border-2 border-white text-[10px] font-semibold text-white first:ml-0 dark:border-zinc-950"
          style={{ backgroundColor: avatarColor(m.userId) }}
        >
          {initials(m.name)}
        </span>
      ))}
      {overflow > 0 && (
        <span className="-ml-1.5 flex h-7 w-7 items-center justify-center rounded-full border-2 border-white bg-zinc-400 text-[10px] font-semibold text-white dark:border-zinc-950 dark:bg-zinc-600">
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
        className="flex min-h-52 items-center justify-center rounded-2xl border border-dashed border-zinc-300 text-sm font-medium text-zinc-500 transition hover:border-zinc-400 hover:text-zinc-700 dark:border-zinc-700 dark:text-zinc-400 dark:hover:border-zinc-600 dark:hover:text-zinc-200"
      >
        + New board
      </button>
    );
  }

  return (
    <form
      onSubmit={submit}
      className="animate-pop-in flex min-h-52 flex-col justify-center gap-3 rounded-2xl border border-dashed border-zinc-300 p-6 dark:border-zinc-700"
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
        className="w-full rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-950 dark:text-zinc-100"
      />
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={creating || name.trim() === ""}
          className="rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {creating ? "Creating…" : "Create"}
        </button>
        <button
          type="button"
          onClick={() => {
            setName("");
            setOpen(false);
          }}
          className="rounded-lg px-3 py-2 text-sm font-medium text-zinc-500 transition hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
