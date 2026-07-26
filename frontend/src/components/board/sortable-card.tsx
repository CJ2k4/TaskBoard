"use client";

import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

import type { Card } from "@/lib/boards";
import { avatarColor, initials } from "@/lib/avatar";

/** Shared card styling, so a dragged card and the <DragOverlay> copy look identical. */
export const CARD_CLASS =
  "rounded-xl border border-line bg-paper px-4 py-3.5 text-left text-[15px] font-medium text-zinc-800 shadow-[var(--shadow-sm)]";

/**
 * A label chip. The tint is derived from the label text itself, so "Backend" is the same colour
 * on every card and across every board without anyone having to configure a palette.
 */
const LABEL_TINTS = [
  "bg-brand-50 text-brand-700 ring-brand-100",
  "bg-emerald-50 text-emerald-700 ring-emerald-100",
  "bg-amber-50 text-amber-700 ring-amber-100",
  "bg-rose-50 text-rose-700 ring-rose-100",
  "bg-sky-50 text-sky-700 ring-sky-100",
  "bg-violet-50 text-violet-700 ring-violet-100",
];

function labelTint(label: string): string {
  let hash = 0;
  for (let i = 0; i < label.length; i++) hash = (hash * 31 + label.charCodeAt(i)) >>> 0;
  return LABEL_TINTS[hash % LABEL_TINTS.length];
}

function LabelChip({ label }: { label: string }) {
  return (
    <span
      className={`mb-2 inline-block rounded-md px-2 py-0.5 font-mono text-[11px] font-semibold uppercase tracking-wide ring-1 ring-inset ${labelTint(label)}`}
    >
      {label}
    </span>
  );
}

function AssigneeDot({ userId, name }: { userId: string; name: string }) {
  return (
    <span
      className="mt-3 flex h-7 w-7 items-center justify-center rounded-full text-[11px] font-semibold text-white shadow-[var(--shadow-sm)] ring-2 ring-paper transition-transform duration-200 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover/card:scale-110"
      style={{ backgroundColor: avatarColor(userId) }}
      title={name}
      aria-label={`Assigned to ${name}`}
    >
      {initials(name)}
    </span>
  );
}

/**
 * The plain visual of a card — used inside SortableCard and in the drag overlay. Shows the
 * label chip (if set) above the title, and the assignee's avatar (if assigned and its name is
 * known) below. `assigneeName` is resolved by the board from its roster, since the card only
 * stores the assignee's id.
 *
 * `floating` is set by the DragOverlay copy: the card you're holding tilts and casts a deeper
 * shadow, so it visibly leaves the surface of the board.
 */
export function CardFace({
  card,
  assigneeName = null,
  floating = false,
}: {
  card: Card;
  assigneeName?: string | null;
  floating?: boolean;
}) {
  return (
    <div
      className={`${CARD_CLASS} group/card ${
        floating ? "rotate-2 scale-[1.03] shadow-[var(--shadow-xl)] ring-2 ring-brand-300" : ""
      }`}
    >
      {card.label && <LabelChip label={card.label} />}
      <div className="leading-snug">{card.title}</div>
      {assigneeName && <AssigneeDot userId={card.assigneeId as string} name={assigneeName} />}
    </div>
  );
}

/**
 * A card that is both draggable (via @dnd-kit `useSortable`) and clickable (opens the modal).
 * The two don't conflict because the DndContext's pointer sensor only starts a drag after a
 * small movement threshold — a click without movement falls through to `onClick`.
 *
 * The card's sortable `data` carries its `columnId` so the board's drag handlers can tell which
 * column an item currently belongs to (needed for moves across columns). While this card is the
 * one being dragged we fade it in place; the floating copy is drawn by the board's DragOverlay.
 *
 * For a viewer (`canEdit` false) the sortable is `disabled` — dnd-kit's own switch, which keeps
 * the component tree identical across roles — but the card stays clickable: opening a card to
 * *read* its description is exactly what a viewer is allowed to do.
 *
 * Motion note: dnd-kit owns the outer element's `transform` (that's how it animates re-ordering),
 * so the hover lift lives on an *inner* layer. Putting both on one node would mean the hover
 * transform silently clobbering the drag transform mid-drag.
 */
export function SortableCard({
  card,
  onClick,
  canEdit,
  assigneeName = null,
}: {
  card: Card;
  onClick: () => void;
  canEdit: boolean;
  assigneeName?: string | null;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: card.id,
    data: { type: "card", columnId: card.columnId },
    disabled: !canEdit,
  });

  return (
    <button
      ref={setNodeRef}
      type="button"
      onClick={onClick}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.35 : 1,
      }}
      className={`group/card block w-full touch-none rounded-xl ${
        canEdit ? "cursor-grab active:cursor-grabbing" : "cursor-pointer"
      }`}
      {...attributes}
      {...listeners}
    >
      <div
        className={`${CARD_CLASS} transition-all duration-200 ease-[cubic-bezier(0.22,1,0.36,1)] group-hover/card:-translate-y-0.5 group-hover/card:border-brand-200 group-hover/card:shadow-[var(--shadow-md)] group-active/card:translate-y-0 ${
          isDragging ? "shadow-none" : ""
        }`}
      >
        {card.label && <LabelChip label={card.label} />}
        <div className="leading-snug">{card.title}</div>
        {assigneeName && <AssigneeDot userId={card.assigneeId as string} name={assigneeName} />}
      </div>
    </button>
  );
}
