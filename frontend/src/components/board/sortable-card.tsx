"use client";

import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

import type { Card } from "@/lib/boards";

/** Shared card styling, so a dragged card and the <DragOverlay> copy look identical. */
export const CARD_CLASS =
  "rounded-lg border border-zinc-200 bg-white px-3 py-2 text-left text-sm text-zinc-800 shadow-sm dark:border-zinc-800 dark:bg-zinc-950 dark:text-zinc-100";

/** The plain visual of a card — used inside SortableCard and in the drag overlay. */
export function CardFace({ card }: { card: Card }) {
  return <div className={CARD_CLASS}>{card.title}</div>;
}

/**
 * A card that is both draggable (via @dnd-kit `useSortable`) and clickable (opens the modal).
 * The two don't conflict because the DndContext's pointer sensor only starts a drag after a
 * small movement threshold — a click without movement falls through to `onClick`.
 *
 * The card's sortable `data` carries its `columnId` so the board's drag handlers can tell which
 * column an item currently belongs to (needed for moves across columns). While this card is the
 * one being dragged we fade it in place; the floating copy is drawn by the board's DragOverlay.
 */
export function SortableCard({
  card,
  onClick,
}: {
  card: Card;
  onClick: () => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: card.id, data: { type: "card", columnId: card.columnId } });

  return (
    <button
      ref={setNodeRef}
      type="button"
      onClick={onClick}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.4 : 1,
      }}
      className={`${CARD_CLASS} cursor-grab touch-none transition hover:border-zinc-300 active:cursor-grabbing dark:hover:border-zinc-700`}
      {...attributes}
      {...listeners}
    >
      {card.title}
    </button>
  );
}
