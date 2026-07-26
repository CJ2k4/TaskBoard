"use client";

import type { PresenceViewer } from "@/lib/board-events";
import { avatarColor, initials } from "@/lib/avatar";

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
    <div className="group/presence flex shrink-0 items-center" aria-label={`${ordered.length} viewing`}>
      {shown.map((v) => {
        const you = v.userId === currentUserId;
        return (
          // Keyed by userId so React mounts a *new* node when someone arrives — which is what
          // makes the spring entrance fire for the newcomer only, not the whole stack.
          <span
            key={v.userId}
            title={you ? `${v.name} (you)` : `${v.name} is viewing`}
            className={`animate-spring-in -ml-1.5 flex h-6 w-6 items-center justify-center rounded-full border-2 border-paper text-[10px] font-semibold text-white shadow-[var(--shadow-sm)] transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] first:ml-0 hover:z-10 hover:scale-125 group-hover/presence:ml-0.5 group-hover/presence:first:ml-0 ${
              you ? "ring-1 ring-brand-400 ring-offset-1 ring-offset-paper" : ""
            }`}
            style={{ backgroundColor: avatarColor(v.userId) }}
          >
            {initials(v.name)}
          </span>
        );
      })}
      {overflow > 0 && (
        <span
          title={ordered.slice(MAX_AVATARS).map((v) => v.name).join(", ")}
          className="animate-spring-in -ml-1.5 flex h-6 w-6 items-center justify-center rounded-full border-2 border-paper bg-zinc-400 text-[10px] font-semibold text-white transition-all duration-300 group-hover/presence:ml-0.5"
        >
          +{overflow}
        </span>
      )}
    </div>
  );
}
