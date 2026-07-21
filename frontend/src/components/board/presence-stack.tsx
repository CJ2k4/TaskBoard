"use client";

import type { PresenceViewer } from "@/lib/board-events";

/**
 * The "who's here right now" avatar cluster (M6), shown in the board header beside the live-dot.
 * It answers a question the connection dot can't: not "is my view current?" but "who am I sharing
 * this board with this very moment?".
 *
 * The list is server-authoritative — it arrives whole in each `PRESENCE` event, derived from live
 * subscriptions — so this component is purely presentational: dedupe (defensively), put *you*
 * first and label you, cap the visible avatars and roll the rest into a "+N".
 */

const MAX_AVATARS = 4;

/** Two-initial monogram from a display name, e.g. "Ada Lovelace" → "AL". */
function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** A stable-per-person hue so an avatar keeps its colour across renders. */
function hue(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 360;
  return h;
}

export function PresenceStack({
  viewers,
  currentUserId,
}: {
  viewers: PresenceViewer[];
  /** The signed-in user, floated to the front and labelled "(you)". */
  currentUserId?: string;
}) {
  // Dedupe by userId (a defensive guard; the server already sends one entry per person).
  const unique = Array.from(new Map(viewers.map((v) => [v.userId, v])).values());
  // You first, then everyone else — so the "+N overflow never hides you.
  const ordered = unique.sort((a, b) => {
    if (a.userId === currentUserId) return -1;
    if (b.userId === currentUserId) return 1;
    return a.name.localeCompare(b.name);
  });

  if (ordered.length === 0) return null;

  const shown = ordered.slice(0, MAX_AVATARS);
  const overflow = ordered.length - shown.length;

  return (
    <div className="flex items-center" aria-label={`${ordered.length} viewing`}>
      {shown.map((v) => {
        const you = v.userId === currentUserId;
        return (
          <span
            key={v.userId}
            title={you ? `${v.name} (you)` : v.name}
            className="-ml-1.5 flex h-6 w-6 items-center justify-center rounded-full border-2 border-zinc-50 text-[10px] font-semibold text-white first:ml-0 dark:border-black"
            style={{ backgroundColor: `hsl(${hue(v.userId)} 55% 45%)` }}
          >
            {initials(v.name)}
          </span>
        );
      })}
      {overflow > 0 && (
        <span
          title={ordered.slice(MAX_AVATARS).map((v) => v.name).join(", ")}
          className="-ml-1.5 flex h-6 w-6 items-center justify-center rounded-full border-2 border-zinc-50 bg-zinc-400 text-[10px] font-semibold text-white dark:border-black dark:bg-zinc-600"
        >
          +{overflow}
        </span>
      )}
    </div>
  );
}
