"use client";

import { useState } from "react";
import { useDroppable } from "@dnd-kit/core";
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

import { ApiError } from "@/lib/api";
import type { Card, Column } from "@/lib/boards";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";
import { SortableCard } from "@/components/board/sortable-card";

/**
 * One column on the board: a header (rename / delete), its cards in rank order, and a composer
 * to add a card. Deleting a non-empty column is refused by the server (409); we catch that and
 * show the message inline rather than letting it bubble.
 *
 * The column is presentational about *its* data but delegates all persistence to the handlers
 * the board page passes down — it never calls the API directly, so the board page stays the
 * single owner of board state.
 *
 * With `canEdit` false (a viewer) every mutating affordance is *absent*, not merely disabled:
 * no grip, no rename, no delete, no card composer. The server would answer 403, and showing a
 * control that can only fail is worse than showing nothing.
 */
export function BoardColumnView({
  column,
  cards,
  canEdit,
  onRename,
  onDelete,
  onCreateCard,
  onCardClick,
}: {
  column: Column;
  cards: Card[];
  canEdit: boolean;
  onRename: (title: string) => Promise<void>;
  onDelete: () => Promise<void>;
  onCreateCard: (title: string) => Promise<void>;
  onCardClick: (card: Card) => void;
}) {
  // The column itself is sortable (draggable among the other columns). Only the grip handle in
  // the header actually starts the drag — see `listeners` below — so the header's rename/delete
  // and the cards inside stay independently interactive.
  const {
    setNodeRef: setColumnRef,
    attributes,
    listeners,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: column.id, data: { type: "column" }, disabled: !canEdit });

  // A separate drop target for *cards* landing in this column, so an empty column still accepts
  // a card. Its id is namespaced (`cards:`) to stay distinct from the column's own sortable id.
  const { setNodeRef: setDropRef } = useDroppable({
    id: `cards:${column.id}`,
    data: { type: "column-cards", columnId: column.id },
  });

  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(column.title);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [newCard, setNewCard] = useState("");
  const [addingCard, setAddingCard] = useState(false);

  async function saveTitle() {
    const next = title.trim();
    if (next === "" || next === column.title) {
      setTitle(column.title);
      setEditing(false);
      return;
    }
    await onRename(next);
    setEditing(false);
  }

  async function handleDelete() {
    setDeleteError(null);
    try {
      await onDelete();
    } catch (err) {
      // Non-empty column → 409; keep the column and explain why.
      setDeleteError(
        err instanceof ApiError ? err.message : "Couldn't delete this column.",
      );
    }
  }

  async function addCard(e: React.FormEvent) {
    e.preventDefault();
    const t = newCard.trim();
    if (t === "") return;
    setAddingCard(true);
    try {
      await onCreateCard(t);
      setNewCard("");
    } finally {
      setAddingCard(false);
    }
  }

  return (
    <div
      ref={setColumnRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.4 : 1,
      }}
      className="flex w-72 shrink-0 flex-col rounded-xl border border-zinc-200 bg-zinc-100/60 p-3 dark:border-zinc-800 dark:bg-zinc-900/40"
    >
      <div className="mb-3 flex items-start justify-between gap-2">
        {canEdit && (
          <span
            {...attributes}
            {...listeners}
            className="mt-0.5 cursor-grab select-none text-zinc-400 touch-none active:cursor-grabbing dark:text-zinc-500"
            title="Drag to reorder column"
            aria-label="Drag to reorder column"
          >
            ⠿
          </span>
        )}
        {!canEdit ? (
          <h2 className="flex-1 text-sm font-semibold text-zinc-800 dark:text-zinc-100">
            {column.title}
          </h2>
        ) : editing ? (
          <input
            autoFocus
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onBlur={saveTitle}
            onKeyDown={(e) => {
              if (e.key === "Enter") saveTitle();
              if (e.key === "Escape") {
                setTitle(column.title);
                setEditing(false);
              }
            }}
            className="w-full rounded border border-zinc-300 bg-white px-2 py-1 text-sm font-semibold text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
          />
        ) : (
          <h2
            className="cursor-text text-sm font-semibold text-zinc-800 dark:text-zinc-100"
            onClick={() => setEditing(true)}
            title="Click to rename"
          >
            {column.title}
          </h2>
        )}
        {canEdit && <InlineConfirmButton onConfirm={handleDelete} />}
      </div>

      {deleteError && (
        <p className="mb-2 text-xs text-red-600 dark:text-red-400">{deleteError}</p>
      )}

      <SortableContext
        items={cards.map((c) => c.id)}
        strategy={verticalListSortingStrategy}
      >
        <div ref={setDropRef} className="flex min-h-3 flex-col gap-2">
          {cards.map((card) => (
            <SortableCard
              key={card.id}
              card={card}
              canEdit={canEdit}
              onClick={() => onCardClick(card)}
            />
          ))}
        </div>
      </SortableContext>

      {!canEdit && cards.length === 0 && (
        <p className="py-2 text-xs text-zinc-400 dark:text-zinc-600">No cards</p>
      )}

      {canEdit && (
        <form onSubmit={addCard} className="mt-2 flex flex-col gap-2">
          <input
            value={newCard}
            onChange={(e) => setNewCard(e.target.value)}
            placeholder="Add a card…"
            className="rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-400 dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-100"
          />
          {newCard.trim() !== "" && (
            <button
              type="submit"
              disabled={addingCard}
              className="rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
            >
              {addingCard ? "Adding…" : "Add card"}
            </button>
          )}
        </form>
      )}
    </div>
  );
}
