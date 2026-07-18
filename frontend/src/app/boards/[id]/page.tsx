"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import {
  createCard,
  createColumn,
  deleteBoard,
  deleteCard,
  deleteColumn,
  getBoard,
  renameBoard,
  renameColumn,
  updateCard,
  type BoardDetail,
  type Card,
} from "@/lib/boards";
import { Protected } from "@/components/protected";
import { BoardColumnView } from "@/components/board/board-column-view";
import { CardModal } from "@/components/board/card-modal";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

export default function BoardPage() {
  return (
    <Protected>
      <BoardContent />
    </Protected>
  );
}

function BoardContent() {
  const { authFetch } = useAuth();
  const router = useRouter();
  const { id } = useParams<{ id: string }>();

  const [board, setBoard] = useState<BoardDetail | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "notfound" | "error">(
    "loading",
  );
  // The card currently open in the modal, or null. Held by id-carrying object so edits
  // reflect immediately when we refresh it from state.
  const [openCard, setOpenCard] = useState<Card | null>(null);

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      setBoard(await getBoard(authFetch, id));
      setStatus("ready");
    } catch (err) {
      setStatus(err instanceof ApiError && err.status === 404 ? "notfound" : "error");
    }
  }, [authFetch, id]);

  useEffect(() => {
    load();
  }, [load]);

  // --- local state helpers (keep the nested board in sync with server responses) ---

  const replaceColumnCards = useCallback(
    (columnId: string, updater: (cards: Card[]) => Card[]) =>
      setBoard((prev) =>
        prev === null
          ? prev
          : {
              ...prev,
              columns: prev.columns.map((c) =>
                c.column.id === columnId ? { ...c, cards: updater(c.cards) } : c,
              ),
            },
      ),
    [],
  );

  async function handleAddColumn(title: string) {
    const column = await createColumn(authFetch, id, title);
    setBoard((prev) =>
      prev === null ? prev : { ...prev, columns: [...prev.columns, { column, cards: [] }] },
    );
  }

  async function handleRenameColumn(columnId: string, title: string) {
    const column = await renameColumn(authFetch, columnId, title);
    setBoard((prev) =>
      prev === null
        ? prev
        : {
            ...prev,
            columns: prev.columns.map((c) =>
              c.column.id === columnId ? { ...c, column } : c,
            ),
          },
    );
  }

  async function handleDeleteColumn(columnId: string) {
    // May throw 409 if the column still has cards — the column view surfaces that.
    await deleteColumn(authFetch, columnId);
    setBoard((prev) =>
      prev === null
        ? prev
        : { ...prev, columns: prev.columns.filter((c) => c.column.id !== columnId) },
    );
  }

  async function handleAddCard(columnId: string, title: string) {
    const card = await createCard(authFetch, columnId, title);
    replaceColumnCards(columnId, (cards) => [...cards, card]);
  }

  async function handleSaveCard(title: string, description: string | null) {
    if (openCard === null) return;
    const updated = await updateCard(authFetch, openCard.id, title, description);
    replaceColumnCards(updated.columnId, (cards) =>
      cards.map((c) => (c.id === updated.id ? updated : c)),
    );
  }

  async function handleDeleteCard() {
    if (openCard === null) return;
    await deleteCard(authFetch, openCard.id);
    replaceColumnCards(openCard.columnId, (cards) =>
      cards.filter((c) => c.id !== openCard.id),
    );
    setOpenCard(null);
  }

  async function handleRenameBoard(name: string) {
    const updated = await renameBoard(authFetch, id, name);
    setBoard((prev) => (prev === null ? prev : { ...prev, name: updated.name }));
  }

  async function handleDeleteBoard() {
    await deleteBoard(authFetch, id);
    router.replace("/dashboard");
  }

  if (status === "loading") {
    return <CenteredNote>Loading board…</CenteredNote>;
  }
  if (status === "notfound") {
    return (
      <CenteredNote>
        Board not found.{" "}
        <Link href="/dashboard" className="underline">
          Back to your boards
        </Link>
      </CenteredNote>
    );
  }
  if (status === "error" || board === null) {
    return (
      <CenteredNote>
        Something went wrong loading this board.{" "}
        <button onClick={load} className="underline">
          Retry
        </button>
      </CenteredNote>
    );
  }

  return (
    <main className="flex min-h-full flex-1 flex-col bg-zinc-50 p-6 dark:bg-black">
      <header className="mb-6 flex items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <Link
            href="/dashboard"
            className="text-sm text-zinc-500 hover:underline dark:text-zinc-400"
          >
            ← Boards
          </Link>
          <BoardTitle name={board.name} onRename={handleRenameBoard} />
        </div>
        <InlineConfirmButton onConfirm={handleDeleteBoard} label="Delete board" />
      </header>

      <div className="flex flex-1 items-start gap-4 overflow-x-auto pb-4">
        {board.columns.map(({ column, cards }) => (
          <BoardColumnView
            key={column.id}
            column={column}
            cards={cards}
            onRename={(title) => handleRenameColumn(column.id, title)}
            onDelete={() => handleDeleteColumn(column.id)}
            onCreateCard={(title) => handleAddCard(column.id, title)}
            onCardClick={setOpenCard}
          />
        ))}
        <AddColumn onAdd={handleAddColumn} />
      </div>

      {openCard && (
        <CardModal
          card={openCard}
          onClose={() => setOpenCard(null)}
          onSave={handleSaveCard}
          onDelete={handleDeleteCard}
        />
      )}
    </main>
  );
}

function BoardTitle({
  name,
  onRename,
}: {
  name: string;
  onRename: (name: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name);

  async function save() {
    const next = value.trim();
    if (next === "" || next === name) {
      setValue(name);
      setEditing(false);
      return;
    }
    await onRename(next);
    setEditing(false);
  }

  if (editing) {
    return (
      <input
        autoFocus
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={save}
        onKeyDown={(e) => {
          if (e.key === "Enter") save();
          if (e.key === "Escape") {
            setValue(name);
            setEditing(false);
          }
        }}
        className="rounded border border-zinc-300 bg-white px-2 py-1 text-lg font-semibold text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
      />
    );
  }
  return (
    <h1
      onClick={() => setEditing(true)}
      title="Click to rename"
      className="cursor-text text-lg font-semibold tracking-tight text-zinc-900 dark:text-zinc-50"
    >
      {name}
    </h1>
  );
}

/** The "add a column" affordance at the end of the columns row. */
function AddColumn({ onAdd }: { onAdd: (title: string) => Promise<void> }) {
  const [title, setTitle] = useState("");
  const [adding, setAdding] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const t = title.trim();
    if (t === "") return;
    setAdding(true);
    try {
      await onAdd(t);
      setTitle("");
    } finally {
      setAdding(false);
    }
  }

  return (
    <form
      onSubmit={submit}
      className="flex w-72 shrink-0 flex-col gap-2 rounded-xl border border-dashed border-zinc-300 p-3 dark:border-zinc-700"
    >
      <input
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder="Add a column…"
        className="rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-400 dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-100"
      />
      {title.trim() !== "" && (
        <button
          type="submit"
          disabled={adding}
          className="rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {adding ? "Adding…" : "Add column"}
        </button>
      )}
    </form>
  );
}

function CenteredNote({ children }: { children: React.ReactNode }) {
  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">{children}</p>
    </main>
  );
}
