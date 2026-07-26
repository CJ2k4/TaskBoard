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
        className={`rounded-md px-1.5 py-0.5 text-xs font-medium text-zinc-500 transition-colors duration-200 hover:bg-red-50 hover:text-red-600 ${className}`}
      >
        {label}
      </button>
    );
  }

  // The armed state animates in as a unit, so the swap reads as one control changing its mind
  // rather than two buttons blinking into existence.
  return (
    <span className="animate-pop-in inline-flex items-center gap-1.5 rounded-md bg-red-50 px-1.5 py-0.5 text-xs ring-1 ring-inset ring-red-200">
      <button
        type="button"
        onClick={confirm}
        disabled={busy}
        className="font-semibold text-red-700 transition-colors hover:text-red-900 disabled:opacity-50"
      >
        {busy ? "Deleting…" : "Confirm?"}
      </button>
      <span aria-hidden className="text-red-300">
        ·
      </span>
      <button
        type="button"
        onClick={() => setArmed(false)}
        disabled={busy}
        className="text-zinc-500 transition-colors hover:text-zinc-900 disabled:opacity-50"
      >
        Cancel
      </button>
    </span>
  );
}
