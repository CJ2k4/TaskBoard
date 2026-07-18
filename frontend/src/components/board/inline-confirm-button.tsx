"use client";

import { useState } from "react";

/**
 * A delete control that confirms in place instead of via a browser dialog. First click swaps
 * the label to a "Confirm? / Cancel" pair; confirming runs `onConfirm`. We avoid
 * `window.confirm` deliberately — a native dialog blocks the page (and would freeze any
 * automated end-to-end run).
 *
 * `onConfirm` may be async; while it runs the buttons disable so a slow delete can't be
 * double-fired.
 */
export function InlineConfirmButton({
  onConfirm,
  label = "Delete",
  className = "",
}: {
  onConfirm: () => void | Promise<void>;
  label?: string;
  className?: string;
}) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);

  async function confirm() {
    setBusy(true);
    try {
      await onConfirm();
      // On success the element is usually unmounted; resetting is harmless if not.
      setArmed(false);
    } finally {
      setBusy(false);
    }
  }

  if (!armed) {
    return (
      <button
        type="button"
        onClick={() => setArmed(true)}
        className={`text-xs font-medium text-zinc-500 transition hover:text-red-600 dark:text-zinc-400 dark:hover:text-red-400 ${className}`}
      >
        {label}
      </button>
    );
  }

  return (
    <span className="inline-flex items-center gap-2 text-xs">
      <button
        type="button"
        onClick={confirm}
        disabled={busy}
        className="font-medium text-red-600 hover:underline disabled:opacity-50 dark:text-red-400"
      >
        {busy ? "Deleting…" : "Confirm?"}
      </button>
      <button
        type="button"
        onClick={() => setArmed(false)}
        disabled={busy}
        className="text-zinc-500 hover:underline disabled:opacity-50 dark:text-zinc-400"
      >
        Cancel
      </button>
    </span>
  );
}
