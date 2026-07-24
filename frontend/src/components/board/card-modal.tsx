"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { ApiError } from "@/lib/api";
import type { Card } from "@/lib/boards";
import { avatarColor, initials } from "@/lib/avatar";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

/** A person who can be assigned to a card — an active member of the board. */
export type Assignable = { userId: string; name: string };

/**
 * A modal for viewing and editing one card: its label, title, assignee, and description, plus
 * deleting it. Rendered through a portal onto `document.body` so it overlays the whole board
 * regardless of where the clicked card sits in the column layout.
 *
 * `columnTitle` is the card's current column, shown as a read-only "status" chip. `members` are
 * the board's active members, offered as assignee choices — the assignee is stored as a bare
 * user id on the card and its name is resolved here from that roster.
 *
 * Closes on backdrop click or Escape. It never uses `alert/confirm/prompt`; the delete uses the
 * same inline two-step control as the rest of the board.
 *
 * A viewer (`canEdit` false) still opens cards — reading a card is the whole point of having
 * read access — but every field is read-only and Save/Delete are gone, leaving Close.
 *
 * `conflict` reflects a live change to this same card by someone else while it's open (M5). We
 * never overwrite the fields — that would eat the user's draft — so instead we warn: "edited"
 * means saving will overwrite their version (the last-write-wins policy, made visible), and
 * "deleted" means the card is already gone, so there's nothing left to save.
 */
export function CardModal({
  card,
  canEdit,
  columnTitle,
  members,
  conflict = null,
  onClose,
  onSave,
  onDelete,
}: {
  card: Card;
  canEdit: boolean;
  columnTitle: string;
  members: Assignable[];
  conflict?: "edited" | "deleted" | null;
  onClose: () => void;
  onSave: (
    title: string,
    description: string | null,
    label: string | null,
    assigneeId: string | null,
  ) => Promise<void>;
  onDelete: () => Promise<void>;
}) {
  const [title, setTitle] = useState(card.title);
  const [description, setDescription] = useState(card.description ?? "");
  const [label, setLabel] = useState(card.label ?? "");
  const [assigneeId, setAssigneeId] = useState(card.assigneeId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Escape closes the modal — the expected affordance for an overlay.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const assignee = members.find((m) => m.userId === assigneeId) ?? null;

  async function save() {
    if (!title.trim()) {
      setError("Title can't be empty.");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      // Empty strings collapse to null, matching the nullable DB columns.
      await onSave(
        title.trim(),
        description.trim() === "" ? null : description.trim(),
        label.trim() === "" ? null : label.trim(),
        assigneeId === "" ? null : assigneeId,
      );
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't save the card.");
      setSaving(false);
    }
  }

  return createPortal(
    <div
      className="animate-fade-in fixed inset-0 z-50 flex items-start justify-center bg-black/50 p-4 pt-24"
      onClick={onClose}
    >
      <div
        className="animate-pop-in w-full max-w-lg rounded-2xl border border-zinc-200 bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Chips + close. The status chip is the card's column; the label chip shows only when set. */}
        <div className="flex items-start justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-md bg-zinc-100 px-2 py-1 font-mono text-xs font-medium tracking-wide text-zinc-600">
              {columnTitle}
            </span>
            {label.trim() !== "" && (
              <span className="rounded-md bg-indigo-50 px-2 py-1 font-mono text-xs font-semibold uppercase tracking-wide text-indigo-600">
                {label.trim()}
              </span>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-1 -mt-1 rounded-md p-1 text-xl leading-none text-zinc-400 transition hover:bg-zinc-100 hover:text-zinc-700"
          >
            ×
          </button>
        </div>

        {conflict && (
          <p
            className="mt-4 rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800"
            role="alert"
          >
            {conflict === "deleted"
              ? "Someone else deleted this card. Your changes can no longer be saved."
              : "Someone else changed this card. Saving will overwrite their version."}
          </p>
        )}

        {/* Title */}
        <input
          type="text"
          value={title}
          readOnly={!canEdit}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Card title"
          className="mt-4 w-full rounded-lg border border-transparent bg-transparent text-2xl font-bold tracking-tight text-zinc-900 outline-none focus:border-zinc-300 read-only:cursor-default"
        />

        {/* Label */}
        {canEdit && (
          <Section title="Label">
            <input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              maxLength={40}
              placeholder="e.g. Backend"
              className="w-full max-w-xs rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-400"
            />
          </Section>
        )}

        {/* Assignee */}
        <Section title="Assignee">
          <div className="flex items-center gap-3">
            {assignee ? (
              <span
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-xs font-semibold text-white"
                style={{ backgroundColor: avatarColor(assignee.userId) }}
                aria-hidden
              >
                {initials(assignee.name)}
              </span>
            ) : (
              <span
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-zinc-100 text-xs font-semibold text-zinc-400"
                aria-hidden
              >
                —
              </span>
            )}
            {canEdit ? (
              <select
                value={assigneeId}
                onChange={(e) => setAssigneeId(e.target.value)}
                className="rounded-lg border border-zinc-200 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-400"
              >
                <option value="">Unassigned</option>
                {members.map((m) => (
                  <option key={m.userId} value={m.userId}>
                    {m.name}
                  </option>
                ))}
              </select>
            ) : (
              <span className="text-sm text-zinc-800">
                {assignee ? assignee.name : "Unassigned"}
              </span>
            )}
          </div>
        </Section>

        {/* Description */}
        <Section title="Description">
          <textarea
            value={description}
            readOnly={!canEdit}
            onChange={(e) => setDescription(e.target.value)}
            rows={5}
            placeholder={canEdit ? "Add a description…" : "No description"}
            className="w-full resize-y rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-400 read-only:text-zinc-600"
          />
        </Section>

        {error && (
          <p className="mt-3 text-sm text-red-600" role="alert">
            {error}
          </p>
        )}

        <div className="mt-6 flex items-center justify-between">
          {/* A card deleted out from under us can't be deleted or saved — only closed. */}
          {canEdit && conflict !== "deleted" ? (
            <InlineConfirmButton onConfirm={onDelete} label="Delete card" />
          ) : (
            <span className="text-xs text-zinc-500">
              {conflict === "deleted"
                ? "This card no longer exists."
                : "You have view-only access to this board."}
            </span>
          )}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="rounded-lg border border-zinc-300 px-3 py-1.5 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-50"
            >
              {canEdit && conflict !== "deleted" ? "Cancel" : "Close"}
            </button>
            {canEdit && conflict !== "deleted" && (
              <button
                type="button"
                onClick={save}
                disabled={saving}
                className="rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50"
              >
                {saving ? "Saving…" : "Save"}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}

/** A titled section with the small uppercase header the card modal uses throughout. */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mt-6">
      <p className="mb-2 font-mono text-xs font-semibold uppercase tracking-wide text-zinc-400">
        {title}
      </p>
      {children}
    </div>
  );
}
