"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragStartEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  horizontalListSortingStrategy,
  SortableContext,
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import {
  createCard,
  createColumn,
  deleteBoard,
  deleteCard,
  deleteColumn,
  getBoard,
  moveCard,
  moveColumn,
  renameBoard,
  renameColumn,
  updateCard,
  type BoardDetail,
  type Card,
  type Column,
  type MoveCardBody,
  type MoveColumnBody,
} from "@/lib/boards";
import { Protected } from "@/components/protected";
import { BoardColumnView } from "@/components/board/board-column-view";
import { CardModal } from "@/components/board/card-modal";
import { CardFace } from "@/components/board/sortable-card";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

// --- pure helpers over the nested board (used by the drag handlers) ---

/** The id of the column that holds a given card, or null. */
function columnIdOfCard(board: BoardDetail, cardId: string): string | null {
  for (const c of board.columns) {
    if (c.cards.some((card) => card.id === cardId)) return c.column.id;
  }
  return null;
}

/** True if a drop target id refers to a column's card area (vs an individual card). */
const CARDS_PREFIX = "cards:";
function isColumnCardsArea(targetId: string): boolean {
  return targetId.startsWith(CARDS_PREFIX);
}

/**
 * The column a card-drop target belongs to. During a card drag the target is either another card
 * (return its column) or a column's card area (`cards:<id>`, for dropping into an empty column).
 */
function columnIdOfTarget(board: BoardDetail, targetId: string): string | null {
  if (isColumnCardsArea(targetId)) return targetId.slice(CARDS_PREFIX.length);
  return columnIdOfCard(board, targetId);
}

function cardsOf(board: BoardDetail, columnId: string): Card[] {
  return board.columns.find((c) => c.column.id === columnId)?.cards ?? [];
}

/** Return a new board with one column's card list replaced. */
function withColumnCards(board: BoardDetail, columnId: string, cards: Card[]): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) =>
      c.column.id === columnId ? { ...c, cards } : c,
    ),
  };
}

/** Replace a card (matched by id) wherever it currently lives — used to reconcile a move. */
function replaceCard(board: BoardDetail, updated: Card): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) => ({
      ...c,
      cards: c.cards.map((card) => (card.id === updated.id ? updated : card)),
    })),
  };
}

/** Replace a column's metadata (matched by id), keeping its cards — used to reconcile a move. */
function replaceColumn(board: BoardDetail, updated: Column): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) =>
      c.column.id === updated.id ? { ...c, column: updated } : c,
    ),
  };
}

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

  // --- drag-and-drop (cards and columns) ---

  // Whichever item is being dragged (drawn in the DragOverlay), and a snapshot to roll back to if
  // the server rejects the move. Because every board update is immutable, the snapshot's arrays
  // stay intact even as we optimistically replace them.
  const [activeCard, setActiveCard] = useState<Card | null>(null);
  const [activeColumn, setActiveColumn] = useState<Column | null>(null);
  const snapshotRef = useRef<BoardDetail | null>(null);
  const [moveError, setMoveError] = useState<string | null>(null);

  // For cards, a click and a drag start the same way; the distance constraint means a plain click
  // (no movement) still falls through to the card's onClick (open modal), and only real dragging
  // begins a drag. Columns drag only from their grip handle. KeyboardSensor adds a11y.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  // One DndContext holds two kinds of draggable (cards and columns), so restrict collision
  // candidates to targets of the matching kind — otherwise a dragged column would try to "drop
  // into" a card, and vice versa.
  const collisionDetection: CollisionDetection = (args) => {
    const draggingColumn = args.active.data.current?.type === "column";
    const candidates = args.droppableContainers.filter((c) => {
      const targetType = c.data.current?.type;
      return draggingColumn
        ? targetType === "column"
        : targetType === "card" || targetType === "column-cards";
    });
    return closestCorners({ ...args, droppableContainers: candidates });
  };

  function handleDragStart(event: DragStartEvent) {
    if (board === null) return;
    snapshotRef.current = board;
    const activeId = String(event.active.id);
    if (event.active.data.current?.type === "column") {
      const found = board.columns.find((c) => c.column.id === activeId);
      setActiveColumn(found?.column ?? null);
      return;
    }
    const columnId = columnIdOfCard(board, activeId);
    const card = columnId ? cardsOf(board, columnId).find((c) => c.id === activeId) : null;
    setActiveCard(card ?? null);
  }

  async function handleDragEnd(event: DragEndEvent) {
    setActiveCard(null);
    setActiveColumn(null);
    if (board === null || event.over === null) return;
    if (event.active.data.current?.type === "column") {
      await moveColumnEnd(event);
    } else {
      await moveCardEnd(event);
    }
  }

  async function moveCardEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (board === null || over === null) return;

    const cardId = String(active.id);
    const fromColumnId = columnIdOfCard(board, cardId);
    const toColumnId = columnIdOfTarget(board, String(over.id));
    if (fromColumnId === null || toColumnId === null) return;

    const fromCards = cardsOf(board, fromColumnId);
    const fromIndex = fromCards.findIndex((c) => c.id === cardId);
    const moved = fromCards[fromIndex];

    // Where in the destination column did we drop? Over a card → at that card's index;
    // over the column's own card area (empty space / empty column) → append.
    const overId = String(over.id);
    const destCards = cardsOf(board, toColumnId);
    const overIndex = isColumnCardsArea(overId)
      ? destCards.length
      : destCards.findIndex((c) => c.id === overId);

    // Build the optimistic next board and find the moved card's final neighbours.
    let next: BoardDetail;
    let finalCards: Card[];
    let finalIndex: number;

    if (fromColumnId === toColumnId) {
      const target = overIndex === -1 ? fromCards.length - 1 : overIndex;
      if (target === fromIndex) return; // dropped in place — nothing to do
      finalCards = arrayMove(fromCards, fromIndex, target);
      finalIndex = finalCards.findIndex((c) => c.id === cardId);
      next = withColumnCards(board, toColumnId, finalCards);
    } else {
      const sourceCards = fromCards.filter((c) => c.id !== cardId);
      const insertAt = overIndex === -1 ? destCards.length : overIndex;
      finalCards = [...destCards.slice(0, insertAt), moved, ...destCards.slice(insertAt)];
      finalIndex = insertAt;
      next = withColumnCards(
        withColumnCards(board, fromColumnId, sourceCards),
        toColumnId,
        finalCards,
      );
    }

    setBoard(next);

    // Express the move as intent (never a rank): anchor to the card now above, else below, else
    // append. "after" wins server-side, so prefer the above-neighbour.
    const above = finalCards[finalIndex - 1];
    const below = finalCards[finalIndex + 1];
    const body: MoveCardBody = above
      ? { targetColumnId: toColumnId, afterCardId: above.id }
      : below
        ? { targetColumnId: toColumnId, beforeCardId: below.id }
        : { targetColumnId: toColumnId };

    try {
      const saved = await moveCard(authFetch, cardId, body);
      // Reconcile: order already matches; adopt the server's canonical rank/columnId/updatedAt.
      setBoard((prev) => (prev === null ? prev : replaceCard(prev, saved)));
    } catch {
      if (snapshotRef.current) setBoard(snapshotRef.current);
      setMoveError("Couldn't move that card. Put it back — try again.");
    }
  }

  async function moveColumnEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (board === null || over === null) return;

    const columnId = String(active.id);
    const overColumnId = String(over.id);
    const fromIndex = board.columns.findIndex((c) => c.column.id === columnId);
    const toIndex = board.columns.findIndex((c) => c.column.id === overColumnId);
    if (fromIndex === -1 || toIndex === -1 || fromIndex === toIndex) return;

    const columns = arrayMove(board.columns, fromIndex, toIndex);
    setBoard({ ...board, columns });

    // Intent: anchor to the column now to the left (after), else the one to the right (before).
    const finalIndex = columns.findIndex((c) => c.column.id === columnId);
    const left = columns[finalIndex - 1];
    const right = columns[finalIndex + 1];
    const body: MoveColumnBody = left
      ? { afterColumnId: left.column.id }
      : right
        ? { beforeColumnId: right.column.id }
        : {};

    try {
      const saved = await moveColumn(authFetch, columnId, body);
      setBoard((prev) => (prev === null ? prev : replaceColumn(prev, saved)));
    } catch {
      if (snapshotRef.current) setBoard(snapshotRef.current);
      setMoveError("Couldn't move that column. Put it back — try again.");
    }
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

      {moveError && (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300">
          <span>{moveError}</span>
          <button onClick={() => setMoveError(null)} className="font-medium underline">
            Dismiss
          </button>
        </div>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={collisionDetection}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
        onDragCancel={() => {
          setActiveCard(null);
          setActiveColumn(null);
        }}
      >
        <SortableContext
          items={board.columns.map((c) => c.column.id)}
          strategy={horizontalListSortingStrategy}
        >
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
        </SortableContext>

        <DragOverlay>
          {activeCard ? (
            <CardFace card={activeCard} />
          ) : activeColumn ? (
            <div className="w-72 rounded-xl border border-zinc-300 bg-zinc-100 p-3 text-sm font-semibold text-zinc-800 shadow-lg dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100">
              {activeColumn.title}
            </div>
          ) : null}
        </DragOverlay>
      </DndContext>

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
