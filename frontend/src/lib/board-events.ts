/**
 * The real-time wire format (M5) and the pure reducer that folds an event into board state.
 *
 * Kept deliberately free of React: `applyBoardEvent` is a plain `BoardDetail → BoardDetail`
 * function, so the interesting logic (which column does this card belong in now? what order?)
 * can be reasoned about — and later tested — without a component in the way. The board page
 * calls it inside `setBoard`.
 *
 * The shapes mirror the backend's `realtime/dto/BoardEvent` and `board/dto/BoardEventType`.
 * `payload` is `unknown` for the same reason it's `Object` server-side: one event carries every
 * shape, and `type` is the discriminator that says how to read it.
 */

import type { BoardDetail, Card, Column } from "@/lib/boards";

export type BoardEventType =
  | "CARD_CREATED"
  | "CARD_UPDATED"
  | "CARD_MOVED"
  | "CARD_DELETED"
  | "COLUMN_CREATED"
  | "COLUMN_UPDATED"
  | "COLUMN_MOVED"
  | "COLUMN_DELETED"
  | "BOARD_UPDATED"
  | "BOARD_DELETED"
  | "MEMBER_ADDED"
  | "MEMBER_UPDATED"
  | "MEMBER_REMOVED";

/** The payload of any `*_DELETED` event: just the id of the thing that's now gone. */
export type DeletedRef = { id: string };

/**
 * The payload of `BOARD_UPDATED`, mirroring the server's `BoardSummary`. Deliberately carries
 * no `myRole`: that's a fact about whoever asked, and a broadcast has no single asker.
 */
export type BoardSummary = {
  id: string;
  name: string;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
};

/** One message off `/topic/board/{boardId}`, mirroring the server's `BoardEvent`. */
export type BoardEvent = {
  type: BoardEventType;
  boardId: string;
  /** Who made the change. Equal to our own user id when this is the echo of our own write. */
  actorId: string;
  at: string;
  payload: unknown;
};

/** Ascending by the server's LexoRank string — the one true order for cards and columns. */
function byRank<T extends { rank: string }>(a: T, b: T): number {
  return a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : 0;
}

/** Drop a card (by id) from wherever it currently lives, across every column. */
function removeCardEverywhere(board: BoardDetail, cardId: string): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) => ({
      ...c,
      cards: c.cards.filter((card) => card.id !== cardId),
    })),
  };
}

/**
 * Place a card into the column named by its own `columnId`, in rank order. Handles create,
 * edit, and cross-column move with one path: a move is just an update whose column changed, so
 * we first pull the card out of everywhere, then insert the fresh copy where it now belongs.
 *
 * If we don't have that column locally our snapshot is stale (a column created while our socket
 * was briefly down, say) — we drop the event rather than crash. The reconnect resync is what
 * heals a stale snapshot; guessing here would only paper over it.
 */
function upsertCard(board: BoardDetail, card: Card): BoardDetail {
  const without = removeCardEverywhere(board, card.id);
  const target = without.columns.find((c) => c.column.id === card.columnId);
  if (!target) return board;
  return {
    ...without,
    columns: without.columns.map((c) =>
      c.column.id === card.columnId
        ? { ...c, cards: [...c.cards, card].sort(byRank) }
        : c,
    ),
  };
}

/**
 * Upsert a column, keeping any cards we already hold for it. A `ColumnResponse` carries no
 * cards, so on an update/move we must not clobber the ones we have; on a create there are none
 * yet, which is correct. The whole columns list is re-sorted by rank afterward, so a move lands
 * in the right place.
 */
function upsertColumn(board: BoardDetail, column: Column): BoardDetail {
  const exists = board.columns.some((c) => c.column.id === column.id);
  const columns = exists
    ? board.columns.map((c) => (c.column.id === column.id ? { ...c, column } : c))
    : [...board.columns, { column, cards: [] }];
  return { ...board, columns: columns.sort((a, b) => byRank(a.column, b.column)) };
}

/**
 * Fold one event into the board. Pure: same board + event always yields the same result, and
 * the input is never mutated.
 *
 * Only the card and column events change the canvas — those are handled here (Pass A). The
 * board- and member-level events are returned unchanged for now (Pass B); returning the board
 * as-is means an unknown or not-yet-handled type is simply a no-op, never a throw.
 */
export function applyBoardEvent(board: BoardDetail, event: BoardEvent): BoardDetail {
  switch (event.type) {
    case "CARD_CREATED":
    case "CARD_UPDATED":
    case "CARD_MOVED":
      return upsertCard(board, event.payload as Card);
    case "CARD_DELETED":
      return removeCardEverywhere(board, (event.payload as DeletedRef).id);
    case "COLUMN_CREATED":
    case "COLUMN_UPDATED":
    case "COLUMN_MOVED":
      return upsertColumn(board, event.payload as Column);
    case "COLUMN_DELETED":
      return {
        ...board,
        columns: board.columns.filter(
          (c) => c.column.id !== (event.payload as DeletedRef).id,
        ),
      };
    case "BOARD_UPDATED": {
      // A rename is universal — every viewer sees the same new name. Take only the board's own
      // fields; never touch `myRole` (per-caller, absent from the payload) or `columns`.
      const summary = event.payload as BoardSummary;
      return { ...board, name: summary.name, updatedAt: summary.updatedAt };
    }
    default:
      // BOARD_DELETED and MEMBER_* are identity- or navigation-dependent, so the board page
      // handles them where `user`/router/modal state live. No-op here keeps them (and any
      // unknown type) harmless in the pure reducer.
      return board;
  }
}
