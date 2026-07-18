"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/lib/auth-context";
import {
  createBoard,
  deleteBoard,
  listBoards,
  renameBoard,
  type Board,
} from "@/lib/boards";
import { Protected } from "@/components/protected";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

export default function DashboardPage() {
  return (
    <Protected>
      <DashboardContent />
    </Protected>
  );
}

/** Rendered only once <Protected> confirms an authenticated user. */
function DashboardContent() {
  const { user, authFetch, logout } = useAuth();
  const router = useRouter();

  const [boards, setBoards] = useState<Board[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [newName, setNewName] = useState("");
  const [creating, setCreating] = useState(false);

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

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    const name = newName.trim();
    if (name === "") return;
    setCreating(true);
    try {
      const board = await createBoard(authFetch, name);
      setBoards((prev) => [...prev, board]);
      setNewName("");
    } finally {
      setCreating(false);
    }
  }

  async function handleRename(id: string, name: string) {
    const updated = await renameBoard(authFetch, id, name);
    setBoards((prev) => prev.map((b) => (b.id === id ? updated : b)));
  }

  async function handleDelete(id: string) {
    await deleteBoard(authFetch, id);
    setBoards((prev) => prev.filter((b) => b.id !== id));
  }

  function handleLogout() {
    logout();
    router.replace("/login");
  }

  return (
    <main className="min-h-full flex-1 bg-zinc-50 p-8 dark:bg-black">
      <div className="mx-auto w-full max-w-3xl">
        <header className="mb-8 flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
              Your boards
            </h1>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">
              Signed in as {user?.name}
            </p>
          </div>
          <button
            onClick={handleLogout}
            className="rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
          >
            Log out
          </button>
        </header>

        <form onSubmit={handleCreate} className="mb-6 flex gap-2">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="New board name…"
            className="flex-1 rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-950 dark:text-zinc-100"
          />
          <button
            type="submit"
            disabled={creating || newName.trim() === ""}
            className="rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {creating ? "Creating…" : "Create board"}
          </button>
        </form>

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
        {status === "ready" && boards.length === 0 && (
          <p className="rounded-lg border border-dashed border-zinc-300 p-6 text-center text-sm text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
            No boards yet. Create your first one above.
          </p>
        )}

        {status === "ready" && boards.length > 0 && (
          <ul className="flex flex-col gap-2">
            {boards.map((board) => (
              <BoardRow
                key={board.id}
                board={board}
                onRename={handleRename}
                onDelete={handleDelete}
              />
            ))}
          </ul>
        )}
      </div>
    </main>
  );
}

/** One board in the list: open it, rename inline, or delete (two-step). */
function BoardRow({
  board,
  onRename,
  onDelete,
}: {
  board: Board;
  onRename: (id: string, name: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(board.name);

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
    <li className="flex items-center justify-between gap-3 rounded-lg border border-zinc-200 bg-white px-4 py-3 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
      {editing ? (
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
          className="flex-1 rounded border border-zinc-300 bg-white px-2 py-1 text-sm font-medium text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        />
      ) : (
        <Link
          href={`/boards/${board.id}`}
          className="flex-1 text-sm font-medium text-zinc-900 hover:underline dark:text-zinc-100"
        >
          {board.name}
        </Link>
      )}
      <div className="flex shrink-0 items-center gap-3">
        <button
          type="button"
          onClick={() => setEditing(true)}
          className="text-xs font-medium text-zinc-500 transition hover:text-zinc-800 dark:text-zinc-400 dark:hover:text-zinc-100"
        >
          Rename
        </button>
        <InlineConfirmButton onConfirm={() => onDelete(board.id)} />
      </div>
    </li>
  );
}
