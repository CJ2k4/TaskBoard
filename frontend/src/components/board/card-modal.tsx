"use client";

import { useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { ApiError } from "@/lib/api";
import type { Card } from "@/lib/boards";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

/**
 * A modal for viewing and editing one card's title + description, plus deleting it. Rendered
 * through a portal onto `document.body` so it overlays the whole board regardless of where the
 * clicked card sits in the column layout.
 *
 * Closes on backdrop click or Escape. It never uses `alert/confirm/prompt`; the delete uses the
 * same inline two-step control as the rest of the board.
 *
 * A viewer (`canEdit` false) still opens cards — reading a description is the whole point of
 * having read access — but the fields are `readOnly` and Save/Delete are gone, leaving Close.
 */
export function CardModal({
  card,
  canEdit,
  onClose,
  onSave,
  onDelete,
}: {
  card: Card;
  canEdit: boolean;
  onClose: () => void;
  onSave: (title: string, description: string | null) => Promise<void>;
  onDelete: () => Promise<void>;
}) {
  const [title, setTitle] = useState(card.title);
  const [description, setDescription] = useState(card.description ?? "");
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

  async function save() {
    if (!title.trim()) {
      setError("Title can't be empty.");
      return;
    }
    setError(null);
    setSaving(true);
    try {
      // Send null (not "") when description is cleared, matching the nullable DB column.
      await onSave(title.trim(), description.trim() === "" ? null : description.trim());
      onClose();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't save the card.");
      setSaving(false);
    }
  }

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/50 p-4 pt-24"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-6 shadow-xl dark:border-zinc-800 dark:bg-zinc-950"
        onClick={(e) => e.stopPropagation()}
      >
        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">Title</span>
          <input
            type="text"
            value={title}
            readOnly={!canEdit}
            onChange={(e) => setTitle(e.target.value)}
            className="rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm font-medium text-zinc-900 outline-none focus:border-zinc-500 read-only:text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:read-only:text-zinc-400"
          />
        </label>

        <label className="mt-4 flex flex-col gap-1">
          <span className="text-xs font-medium text-zinc-500 dark:text-zinc-400">
            Description
          </span>
          <textarea
            value={description}
            readOnly={!canEdit}
            onChange={(e) => setDescription(e.target.value)}
            rows={5}
            placeholder={canEdit ? "Add more detail…" : "No description"}
            className="resize-y rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 read-only:text-zinc-600 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:read-only:text-zinc-400"
          />
        </label>

        {error && (
          <p className="mt-3 text-sm text-red-600 dark:text-red-400" role="alert">
            {error}
          </p>
        )}

        <div className="mt-6 flex items-center justify-between">
          {canEdit ? (
            <InlineConfirmButton onConfirm={onDelete} label="Delete card" />
          ) : (
            <span className="text-xs text-zinc-500 dark:text-zinc-400">
              You have view-only access to this board.
            </span>
          )}
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="rounded-lg border border-zinc-300 px-3 py-1.5 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
            >
              {canEdit ? "Cancel" : "Close"}
            </button>
            {canEdit && (
              <button
                type="button"
                onClick={save}
                disabled={saving}
                className="rounded-lg bg-zinc-900 px-3 py-1.5 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
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
