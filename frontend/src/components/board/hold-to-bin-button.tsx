"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/** How long the button must be held before the card is binned. */
const HOLD_MS = 1000;

/**
 * The per-card bin control: press and hold for two seconds to send the card to the bin.
 *
 * The hold *is* the confirmation, which is why there is no dialog. That only works if the
 * commitment is legible while it is happening, so the button fills with red as it charges and
 * announces the remaining seconds — a hold with no feedback is indistinguishable from a stuck
 * click. Releasing early cancels and the fill drains away; nothing is sent until the two
 * seconds are up.
 *
 * It deliberately lives *outside* the card's draggable button rather than inside it. Nesting a
 * button in a button is invalid HTML, and a press here must charge the hold rather than start a
 * drag — being a sibling means the card's drag listeners never see these pointer events.
 *
 * Keyboard users get the same deal via Space/Enter: keydown starts the hold, keyup cancels it.
 * Browsers auto-repeat keydown while a key is held, so `startedRef` keeps the first one.
 */
export function HoldToBinButton({
  title,
  onHoldComplete,
}: {
  /** The card's title, for the accessible label — "Hold to bin" alone says nothing about what. */
  title: string;
  onHoldComplete: () => void;
}) {
  const [holding, setHolding] = useState(false);
  const [remaining, setRemaining] = useState(HOLD_MS);
  const timerRef = useRef<number | null>(null);
  const tickRef = useRef<number | null>(null);
  const startedRef = useRef(false);

  const cancel = useCallback(() => {
    startedRef.current = false;
    if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    if (tickRef.current !== null) window.clearInterval(tickRef.current);
    timerRef.current = null;
    tickRef.current = null;
    setHolding(false);
    setRemaining(HOLD_MS);
  }, []);

  // Any unmount mid-hold (the card is binned by someone else, the board reloads) must not leave
  // a timer alive that fires onHoldComplete against a card that is already gone.
  useEffect(() => cancel, [cancel]);

  const start = useCallback(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    setHolding(true);
    setRemaining(HOLD_MS);

    const startedAt = Date.now();
    tickRef.current = window.setInterval(() => {
      setRemaining(Math.max(0, HOLD_MS - (Date.now() - startedAt)));
    }, 100);

    timerRef.current = window.setTimeout(() => {
      cancel();
      onHoldComplete();
    }, HOLD_MS);
  }, [cancel, onHoldComplete]);

  const secondsLeft = Math.ceil(remaining / 1000);

  return (
    <button
      type="button"
      // Pointer events cover mouse, touch and pen in one path. `onPointerLeave` matters: sliding
      // off the button mid-hold should abort, the same as letting go.
      onPointerDown={start}
      onPointerUp={cancel}
      onPointerLeave={cancel}
      onPointerCancel={cancel}
      onKeyDown={(e) => {
        if (e.key === " " || e.key === "Enter") {
          e.preventDefault(); // stop Space scrolling the board while charging
          start();
        }
      }}
      onKeyUp={cancel}
      onBlur={cancel}
      // The card behind this is clickable (opens the modal) and draggable; neither should react
      // to a press meant for the bin.
      onClick={(e) => e.stopPropagation()}
      aria-label={
        holding
          ? `Keep holding to bin "${title}" — ${secondsLeft} second${secondsLeft === 1 ? "" : "s"} left`
          : `Hold to bin "${title}"`
      }
      title="Hold for 2s to bin"
      className={`group/bin relative z-10 flex h-7 w-7 shrink-0 items-center justify-center overflow-hidden rounded-lg border transition-colors duration-200 ${
        holding
          ? "border-red-300 text-white"
          : "border-transparent text-zinc-300 hover:border-red-200 hover:bg-red-50 hover:text-red-500"
      }`}
    >
      {/* The charge indicator: a red column that rises to fill the button over the hold. Its
          height is driven by a transition rather than per-frame state, so the fill stays smooth
          even while the board is re-rendering around it. */}
      <span
        aria-hidden
        className="absolute inset-x-0 bottom-0 bg-red-500"
        style={{
          height: holding ? "100%" : "0%",
          transition: holding
            ? `height ${HOLD_MS}ms linear`
            : "height 180ms ease-out",
        }}
      />
      <span aria-hidden className="relative text-[13px] leading-none">
        🗑
      </span>
    </button>
  );
}
