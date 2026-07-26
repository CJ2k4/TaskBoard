/**
 * Typed client for the card bin. Same shape as `boards.ts`/`activity.ts`: every call takes the
 * `authFetch` from `useAuth()`, and the types mirror the backend DTOs.
 *
 * Deleting a card is still `deleteCard` in `boards.ts` — the bin didn't add a "bin this card"
 * call, because `DELETE /api/cards/{id}` *is* the bin now. Only reading the bin and coming back
 * out of it are new.
 */

import type { AuthFetch, Card } from "@/lib/boards";

/**
 * A card sitting in the bin. `columnTitle` is resolved server-side (the card only knows its
 * column's id) and `purgeAt` is when it stops being restorable — both computed per request, so
 * a client never has to know the retention window to render the countdown.
 */
export type BinnedCard = {
  card: Card;
  columnTitle: string | null;
  deletedAt: string;
  deletedBy: string | null;
  purgeAt: string;
};

/** A board's bin, most recently binned first. Readable by any member, viewers included. */
export const listBin = (authFetch: AuthFetch, boardId: string) =>
  authFetch<BinnedCard[]>(`/api/boards/${boardId}/bin`);

/**
 * Put a card back on the board. It returns to the column it came from, appended to the end —
 * the response carries the server's canonical rank, so the caller reconciles rather than
 * guessing where it landed.
 */
export const restoreCard = (authFetch: AuthFetch, cardId: string) =>
  authFetch<Card>(`/api/cards/${cardId}/restore`, { method: "POST" });
