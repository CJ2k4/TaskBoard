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
  assigneeNameById,
  onRename,
  onDelete,
  onCreateCard,
  onCardClick,
}: {
  column: Column;
  cards: Card[];
  canEdit: boolean;
  /** Maps an assignee's user id → display name, so a card can render its assignee avatar. */
  assigneeNameById: Record<string, string>;
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
  // `isOver` drives the drop-zone highlight — the column visibly opens up to receive the card.
  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `cards:${column.id}`,
    data: { type: "column-cards", columnId: column.id },
  });

  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(column.title);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [newCard, setNewCard] = useState("");
  const [addingCard, setAddingCard] = useState(false);
  // The composer is collapsed to a "+ Add card" affordance until opened.
  const [addingCardOpen, setAddingCardOpen] = useState(false);

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

  // Note on the entrance animation below: it is opacity-only on purpose. A keyframe that set
  // `transform` would outrank dnd-kit's inline transform (animations beat inline styles) and,
  // with `both` fill, would pin the column in place permanently — breaking column dragging.
  return (
    <div
      ref={setColumnRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
      }}
      className={`animate-fade-in group/col flex w-[19rem] shrink-0 flex-col rounded-2xl border bg-sunken/60 p-3 backdrop-blur-sm transition-[border-color,box-shadow,opacity,background-color] duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
        isDragging
          ? "opacity-40 border-brand-300 shadow-[var(--shadow-lg)]"
          : isOver
            ? "border-brand-400 bg-brand-50/50 shadow-[0_0_0_4px_rgba(99,102,241,0.1)]"
            : "border-line hover:border-line-strong"
      }`}
    >
      <div className="mb-3 flex items-start justify-between gap-2 px-1">
        {canEdit && (
          <span
            {...attributes}
            {...listeners}
            className="mt-0.5 cursor-grab select-none text-zinc-300 opacity-0 transition-all duration-200 touch-none hover:text-brand-500 active:cursor-grabbing group-hover/col:opacity-100 focus-visible:opacity-100"
            title="Drag to reorder column"
            aria-label="Drag to reorder column"
          >
            ⠿
          </span>
        )}
        {!canEdit ? (
          <h2 className="flex-1 text-sm font-semibold text-zinc-800">{column.title}</h2>
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
            className="animate-pop-in w-full rounded-lg border border-brand-300 bg-white px-2 py-1 text-sm font-semibold text-zinc-900 outline-none transition-shadow focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
          />
        ) : (
          <h2
            className="flex-1 cursor-text rounded px-1 py-0.5 -mx-1 text-sm font-semibold text-zinc-800 transition-colors duration-200 hover:bg-paper/80 hover:text-brand-700"
            onClick={() => setEditing(true)}
            title="Click to rename"
          >
            {column.title}
          </h2>
        )}

        {/* Card count — a quiet, always-there sense of column weight. */}
        <span className="rounded-full bg-paper px-2 py-0.5 font-mono text-[11px] font-semibold text-zinc-400 transition-colors duration-200 group-hover/col:text-zinc-600">
          {cards.length}
        </span>

        {canEdit && (
          <span className="opacity-0 transition-opacity duration-200 group-hover/col:opacity-100 focus-within:opacity-100">
            <InlineConfirmButton onConfirm={handleDelete} />
          </span>
        )}
      </div>

      {deleteError && <p className="animate-pop-in mb-2 px-1 text-xs text-red-600">{deleteError}</p>}

      <SortableContext items={cards.map((c) => c.id)} strategy={verticalListSortingStrategy}>
        <div
          ref={setDropRef}
          className={`flex flex-col gap-2 rounded-xl transition-all duration-300 ${
            isOver ? "min-h-16 bg-brand-100/30" : "min-h-3"
          }`}
        >
          {cards.map((card) => (
            <SortableCard
              key={card.id}
              card={card}
              canEdit={canEdit}
              assigneeName={card.assigneeId ? assigneeNameById[card.assigneeId] ?? null : null}
              onClick={() => onCardClick(card)}
            />
          ))}
        </div>
      </SortableContext>

      {!canEdit && cards.length === 0 && (
        <p className="py-2 px-1 text-xs text-zinc-400">No cards</p>
      )}

      {canEdit &&
        (addingCardOpen ? (
          <form
            onSubmit={addCard}
            className="animate-pop-in mt-2 rounded-xl border border-brand-300 bg-paper p-3 shadow-[var(--shadow-md)]"
          >
            <input
              autoFocus
              value={newCard}
              onChange={(e) => setNewCard(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setNewCard("");
                  setAddingCardOpen(false);
                }
              }}
              placeholder="Card title…"
              className="w-full border-none bg-transparent px-1 py-1 text-sm text-zinc-900 outline-none placeholder:text-zinc-400"
            />
            <div className="mt-2 flex items-center gap-2">
              <button
                type="submit"
                disabled={addingCard || newCard.trim() === ""}
                className="press rounded-lg bg-gradient-to-br from-brand-500 to-violet-600 px-3 py-1.5 text-sm font-semibold text-white shadow-[var(--shadow-brand)] disabled:opacity-50 disabled:shadow-none"
              >
                {addingCard ? "Adding…" : "Add card"}
              </button>
              <button
                type="button"
                onClick={() => {
                  setNewCard("");
                  setAddingCardOpen(false);
                }}
                className="text-sm font-medium text-zinc-500 transition-colors hover:text-zinc-900"
              >
                Cancel
              </button>
            </div>
          </form>
        ) : (
          <button
            type="button"
            onClick={() => setAddingCardOpen(true)}
            className="group/add mt-2 flex items-center gap-1.5 rounded-lg px-2 py-2 text-left text-sm font-medium text-zinc-500 transition-all duration-200 hover:bg-paper hover:text-brand-700"
          >
            <span className="inline-block transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover/add:rotate-90">
              +
            </span>
            Add card
          </button>
        ))}
    </div>
  );
}
