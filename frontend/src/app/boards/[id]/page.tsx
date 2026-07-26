"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  MeasuringStrategy,
  PointerSensor,
  useSensor,
  useSensors,
  type Active,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type Over,
} from "@dnd-kit/core";
import {
  arrayMove,
  horizontalListSortingStrategy,
  SortableContext,
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import {
  createCard,
  createColumn,
  deleteBoard,
  deleteCard,
  deleteColumn,
  getBoard,
  moveCard,
  moveColumn,
  updateBoard,
  renameColumn,
  updateCard,
  type BoardDetail,
  type Card,
  type Column,
  type MoveCardBody,
  type MoveColumnBody,
} from "@/lib/boards";
import { Protected } from "@/components/protected";
import { PageNote } from "@/components/page-note";
import { BrandMark } from "@/components/brand-mark";
import { BoardColumnView } from "@/components/board/board-column-view";
import { CardModal, type Assignable } from "@/components/board/card-modal";
import { CardFace } from "@/components/board/sortable-card";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";
import { RoleBadge, ShareModal } from "@/components/board/share-modal";
import { PresenceStack } from "@/components/board/presence-stack";
import { ActivityPanel } from "@/components/board/activity-panel";
import { BinPanel } from "@/components/board/bin-panel";
import {
  applyBoardEvent,
  upsertCard,
  type BoardEvent,
  type PresenceViewer,
} from "@/lib/board-events";
import { useBoardEvents, type ConnectionState } from "@/lib/realtime";
import { listMembers, type Membership } from "@/lib/members";

// --- pure helpers over the nested board (used by the drag handlers) ---

/** The id of the column that holds a given card, or null. */
function columnIdOfCard(board: BoardDetail, cardId: string): string | null {
  for (const c of board.columns) {
    if (c.cards.some((card) => card.id === cardId)) return c.column.id;
  }
  return null;
}

/** True if a drop target id refers to a column's card area (vs an individual card). */
const CARDS_PREFIX = "cards:";
function isColumnCardsArea(targetId: string): boolean {
  return targetId.startsWith(CARDS_PREFIX);
}

/**
 * The column a card-drop target belongs to. During a card drag the target is either another card
 * (return its column) or a column's card area (`cards:<id>`, for dropping into an empty column).
 */
function columnIdOfTarget(board: BoardDetail, targetId: string): string | null {
  if (isColumnCardsArea(targetId)) return targetId.slice(CARDS_PREFIX.length);
  return columnIdOfCard(board, targetId);
}

function cardsOf(board: BoardDetail, columnId: string): Card[] {
  return board.columns.find((c) => c.column.id === columnId)?.cards ?? [];
}

/**
 * The index a card lands at in a destination column's list.
 *
 * Over a card, the *dragged card's own body* decides which side of it we land on: past that
 * card's midpoint means after it, otherwise before it. Anchoring only to the hovered card (as
 * this used to) can only ever insert *before* it — which makes the slot after the last card
 * unreachable and pushes every drop one place higher than the user aimed. Over a column's card
 * area (its empty space, or an empty column) there's no neighbour to compare against: append.
 */
function dropIndex(cards: Card[], active: Active, over: Over): number {
  const overId = String(over.id);
  if (isColumnCardsArea(overId)) return cards.length;

  const overIndex = cards.findIndex((c) => c.id === overId);
  if (overIndex === -1) return cards.length;

  const dragged = active.rect.current.translated;
  const pastMidpoint = dragged !== null && dragged.top > over.rect.top + over.rect.height / 2;
  return pastMidpoint ? overIndex + 1 : overIndex;
}

/** Return a new board with one column's card list replaced. */
function withColumnCards(board: BoardDetail, columnId: string, cards: Card[]): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) =>
      c.column.id === columnId ? { ...c, cards } : c,
    ),
  };
}

/** Replace a card (matched by id) wherever it currently lives — used to reconcile a move. */
function replaceCard(board: BoardDetail, updated: Card): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) => ({
      ...c,
      cards: c.cards.map((card) => (card.id === updated.id ? updated : card)),
    })),
  };
}

/** Replace a column's metadata (matched by id), keeping its cards — used to reconcile a move. */
function replaceColumn(board: BoardDetail, updated: Column): BoardDetail {
  return {
    ...board,
    columns: board.columns.map((c) =>
      c.column.id === updated.id ? { ...c, column: updated } : c,
    ),
  };
}

export default function BoardPage() {
  return (
    <Protected>
      <BoardContent />
    </Protected>
  );
}

function BoardContent() {
  const { authFetch, user } = useAuth();
  const router = useRouter();
  const { id } = useParams<{ id: string }>();

  const [board, setBoard] = useState<BoardDetail | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "notfound" | "error">(
    "loading",
  );
  // The card currently open in the modal, or null. Held by id-carrying object so edits
  // reflect immediately when we refresh it from state.
  const [openCard, setOpenCard] = useState<Card | null>(null);
  // Someone else touched the open card while we had it open. We never overwrite the modal's
  // fields (that's the user's draft) — just flag it, so the modal can warn before a save clobbers
  // their change ("edited" here) or tell us the card is gone ("deleted").
  const [openCardConflict, setOpenCardConflict] = useState<"edited" | "deleted" | null>(null);
  const [sharing, setSharing] = useState(false);
  const [activityOpen, setActivityOpen] = useState(false);
  const [binOpen, setBinOpen] = useState(false);
  // Bumped whenever a card is binned or restored (by anyone), so an open bin drawer refetches.
  const [binNonce, setBinNonce] = useState(0);
  // Bumped on any logged live event so an open Activity panel refetches (server-rendered
  // summaries stay the single source of truth). Mirrors memberEventNonce → ShareModal.
  const [activityNonce, setActivityNonce] = useState(0);
  // Set when the board is pulled out from under us mid-view — deleted by its owner, or our own
  // access revoked. Holds the message to show instead of yanking the page away. (M5 Pass B.)
  const [goneReason, setGoneReason] = useState<string | null>(null);
  // Bumped on any MEMBER_* event, so an open Share modal knows to refetch its roster.
  const [memberEventNonce, setMemberEventNonce] = useState(0);
  // Who has this board open right now — server-authoritative, replaced wholesale by each
  // PRESENCE event (derived from live subscriptions, not stored anywhere).
  const [viewers, setViewers] = useState<PresenceViewer[]>([]);
  // The board roster — used to offer assignee choices in the card modal and to resolve an
  // assignee id → name/avatar on the card face. Refetched whenever a MEMBER_* event bumps
  // memberEventNonce, so a newly added/removed member shows up (or disappears) without a reload.
  const [members, setMembers] = useState<Membership[]>([]);

  // Permissions, derived once from the role the server sent with the board and then threaded
  // down as plain booleans — components shouldn't each re-derive policy from a role string.
  // Null-safe: before the board loads nobody may edit anything.
  const canEdit = board !== null && board.myRole !== "VIEWER";
  const isOwner = board !== null && board.myRole === "OWNER";

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      setBoard(await getBoard(authFetch, id));
      setStatus("ready");
    } catch (err) {
      setStatus(err instanceof ApiError && err.status === 404 ? "notfound" : "error");
    }
  }, [authFetch, id]);

  // Initial load. The fetch is inlined rather than calling `load()` so nothing sets state
  // synchronously in the effect body; `status` already starts as "loading". `load` stays for the
  // imperative paths — the retry button and the realtime `onResync`, where flipping back to
  // "loading" is wanted.
  useEffect(() => {
    let cancelled = false;
    getBoard(authFetch, id)
      .then((next) => {
        if (cancelled) return;
        setBoard(next);
        setStatus("ready");
      })
      .catch((err) => {
        if (cancelled) return;
        setStatus(err instanceof ApiError && err.status === 404 ? "notfound" : "error");
      });
    return () => {
      cancelled = true;
    };
  }, [authFetch, id]);

  // Keep the roster fresh for the assignee picker. Non-fatal on failure — the picker just stays
  // empty; a VIEWER may still read cards. memberEventNonce re-runs it after any MEMBER_* event.
  useEffect(() => {
    let cancelled = false;
    listMembers(authFetch, id)
      .then((rows) => {
        if (!cancelled) setMembers(rows);
      })
      .catch(() => {
        /* ignore — assignee choices are a convenience, not a gate */
      });
    return () => {
      cancelled = true;
    };
  }, [authFetch, id, memberEventNonce]);

  // Active members with a real account, keyed by id → name, for assignee resolution + choices.
  const assigneeNameById = useMemo(() => {
    const map: Record<string, string> = {};
    for (const m of members) {
      if (m.status === "ACTIVE" && m.userId && m.name) map[m.userId] = m.name;
    }
    return map;
  }, [members]);
  const assignableMembers = useMemo<Assignable[]>(
    () => Object.entries(assigneeNameById).map(([userId, name]) => ({ userId, name })),
    [assigneeNameById],
  );

  // --- real-time: apply other people's changes as they happen (M5) ---

  function handleBoardEvent(event: BoardEvent) {
    // Presence is handled *before* the echo-skip below: it carries a null actorId and must reach
    // everyone — including whoever's arrival or departure triggered it. It's the whole live viewer
    // list, so we just replace ours with it.
    if (event.type === "PRESENCE") {
      setViewers(event.payload as PresenceViewer[]);
      return;
    }

    // Any logged change refreshes an open Activity feed — bumped *before* the echo-skip so our
    // own actions show up in our own feed too (everything but PRESENCE and BOARD_DELETED is logged
    // server-side). Harmless when the panel is closed: nothing is mounted to refetch.
    if (event.type !== "BOARD_DELETED") setActivityNonce((n) => n + 1);

    // Same idea for an open Bin drawer: a card binned or restored anywhere changes what's in it.
    // Also before the echo-skip, so our own bin/restore refreshes our own drawer.
    if (event.type === "CARD_DELETED" || event.type === "CARD_RESTORED") {
      setBinNonce((n) => n + 1);
    }

    // Our own change, echoed back. We already applied it optimistically and reconciled to the
    // server's rank; re-applying would fight an in-flight drag. (The server broadcasts to the
    // actor too, precisely so we can recognize and skip it here.) This also means an owner never
    // processes the echo of their own rename/delete/member-management — the optimistic paths stand.
    if (event.actorId === user?.id) return;

    // --- board- and member-level events (Pass B): identity- or navigation-dependent ---

    if (event.type === "BOARD_DELETED") {
      // The owner deleted the board. Show it, rather than snatching the page away.
      setGoneReason("This board was deleted.");
      return;
    }
    if (event.type.startsWith("MEMBER_")) {
      const member = event.payload as Membership;
      const aboutMe = member.userId === user?.id;
      if (event.type === "MEMBER_REMOVED" && aboutMe) {
        setGoneReason("You no longer have access to this board.");
        return;
      }
      if (event.type === "MEMBER_UPDATED" && aboutMe) {
        // My role changed live: canEdit / isOwner are derived from board.myRole, so patching it
        // makes a demotion hide the edit controls (and a promotion reveal them) with no reload.
        setBoard((prev) => (prev === null ? prev : { ...prev, myRole: member.role }));
      }
      // Any membership change nudges an open Share modal to refetch its roster.
      setMemberEventNonce((n) => n + 1);
      return;
    }

    // If the change touches the card we have open, flag it — but never rewrite the fields the
    // user is editing. A delete wins over a prior edit flag: the card is simply gone.
    if (openCard) {
      const payloadId = (event.payload as { id?: string })?.id;
      if (payloadId === openCard.id) {
        if (event.type === "CARD_DELETED") setOpenCardConflict("deleted");
        else if (event.type === "CARD_UPDATED" || event.type === "CARD_MOVED") {
          setOpenCardConflict((prev) => (prev === "deleted" ? prev : "edited"));
        }
      }
    }

    setBoard((prev) => (prev === null ? prev : applyBoardEvent(prev, event)));
  }

  const connection: ConnectionState = useBoardEvents({
    boardId: id,
    enabled: status === "ready",
    onEvent: handleBoardEvent,
    onResync: load,
  });

  // --- local state helpers (keep the nested board in sync with server responses) ---

  const replaceColumnCards = useCallback(
    (columnId: string, updater: (cards: Card[]) => Card[]) =>
      setBoard((prev) =>
        prev === null
          ? prev
          : {
              ...prev,
              columns: prev.columns.map((c) =>
                c.column.id === columnId ? { ...c, cards: updater(c.cards) } : c,
              ),
            },
      ),
    [],
  );

  async function handleAddColumn(title: string) {
    const column = await createColumn(authFetch, id, title);
    setBoard((prev) =>
      prev === null ? prev : { ...prev, columns: [...prev.columns, { column, cards: [] }] },
    );
  }

  async function handleRenameColumn(columnId: string, title: string) {
    const column = await renameColumn(authFetch, columnId, title);
    setBoard((prev) =>
      prev === null
        ? prev
        : {
            ...prev,
            columns: prev.columns.map((c) =>
              c.column.id === columnId ? { ...c, column } : c,
            ),
          },
    );
  }

  async function handleDeleteColumn(columnId: string) {
    // May throw 409 if the column still has cards — the column view surfaces that.
    await deleteColumn(authFetch, columnId);
    setBoard((prev) =>
      prev === null
        ? prev
        : { ...prev, columns: prev.columns.filter((c) => c.column.id !== columnId) },
    );
  }

  async function handleAddCard(columnId: string, title: string) {
    const card = await createCard(authFetch, columnId, title);
    replaceColumnCards(columnId, (cards) => [...cards, card]);
  }

  async function handleSaveCard(
    title: string,
    description: string | null,
    label: string | null,
    assigneeId: string | null,
  ) {
    if (openCard === null) return;
    const updated = await updateCard(
      authFetch,
      openCard.id,
      title,
      description,
      label,
      assigneeId,
    );
    replaceColumnCards(updated.columnId, (cards) =>
      cards.map((c) => (c.id === updated.id ? updated : c)),
    );
  }

  async function handleDeleteCard() {
    if (openCard === null) return;
    await deleteCard(authFetch, openCard.id);
    replaceColumnCards(openCard.columnId, (cards) =>
      cards.filter((c) => c.id !== openCard.id),
    );
    setBinNonce((n) => n + 1);
    closeCard();
  }

  /**
   * A card's bin control finished its hold. The two-second hold *is* the confirmation, so this
   * commits straight away — the same `deleteCard` the card modal uses, since deleting is binning
   * server-side. Nothing is destroyed either way: the card is restorable from the bin for two
   * days, which is what makes a no-dialog delete reasonable in the first place.
   */
  async function handleBinCard(card: Card) {
    // Optimistic: the card leaves immediately, which is the whole point of holding to commit.
    replaceColumnCards(card.columnId, (cards) => cards.filter((c) => c.id !== card.id));
    try {
      await deleteCard(authFetch, card.id);
      setBinNonce((n) => n + 1);
    } catch {
      // Put it back — the hold was honoured locally but the server refused.
      setBoard((prev) => (prev === null ? prev : upsertCard(prev, card)));
      setMoveError("Couldn't bin that card. Try again.");
    }
  }

  /** A card came back out of the bin — drop it into its column at the server's rank. */
  function handleRestored(card: Card) {
    setBoard((prev) => (prev === null ? prev : upsertCard(prev, card)));
    setBinNonce((n) => n + 1);
  }

  /** Open a card in the modal, starting from a clean (no-conflict) slate. */
  function openCardModal(card: Card) {
    setOpenCardConflict(null);
    setOpenCard(card);
  }

  function closeCard() {
    setOpenCard(null);
    setOpenCardConflict(null);
  }

  // --- drag-and-drop (cards and columns) ---

  // Whichever item is being dragged (drawn in the DragOverlay), and a snapshot to roll back to if
  // the server rejects the move. Because every board update is immutable, the snapshot's arrays
  // stay intact even as we optimistically replace them.
  const [activeCard, setActiveCard] = useState<Card | null>(null);
  const [activeColumn, setActiveColumn] = useState<Column | null>(null);
  const snapshotRef = useRef<BoardDetail | null>(null);
  // Where the dragged card sat when the drag began. Since `handleDragOver` relocates the card as
  // you drag, the board at drop time no longer says where it came from — so "did this actually
  // move?" has to be asked of this, not of current state.
  const dragOriginRef = useRef<{ columnId: string; index: number } | null>(null);
  const [moveError, setMoveError] = useState<string | null>(null);

  // For cards, a click and a drag start the same way; the distance constraint means a plain click
  // (no movement) still falls through to the card's onClick (open modal), and only real dragging
  // begins a drag. Columns drag only from their grip handle. KeyboardSensor adds a11y.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  // One DndContext holds two kinds of draggable (cards and columns), so restrict collision
  // candidates to targets of the matching kind — otherwise a dragged column would try to "drop
  // into" a card, and vice versa.
  const collisionDetection: CollisionDetection = (args) => {
    const draggingColumn = args.active.data.current?.type === "column";

    const candidates = args.droppableContainers.filter((c) => {
      const targetType = c.data.current?.type;
      // A column can only land among columns; a card can land among cards, on a column's card
      // area, or on the bin. The bin is card-only — dragging a column past it must not arm it.
      return draggingColumn
        ? targetType === "column"
        : targetType === "card" || targetType === "column-cards";
    });
    return closestCorners({ ...args, droppableContainers: candidates });
  };

  function handleDragStart(event: DragStartEvent) {
    // Every sortable is already `disabled` for a viewer; this is the belt to that pair of
    // braces, since a sensor we add later shouldn't be able to start a move behind our back.
    if (board === null || !canEdit) return;
    snapshotRef.current = board;
    const activeId = String(event.active.id);
    if (event.active.data.current?.type === "column") {
      const found = board.columns.find((c) => c.column.id === activeId);
      setActiveColumn(found?.column ?? null);
      return;
    }
    const columnId = columnIdOfCard(board, activeId);
    const cards = columnId ? cardsOf(board, columnId) : [];
    const index = cards.findIndex((c) => c.id === activeId);
    dragOriginRef.current = columnId !== null && index !== -1 ? { columnId, index } : null;
    setActiveCard(index === -1 ? null : cards[index]);
  }

  /**
   * A card changes column *while* you drag, not on drop. That's what makes "drop it between
   * these two" work across columns: the destination's `SortableContext` only displaces its cards
   * around one it actually contains, so until the card is moved in, no gap opens and there is
   * nothing to aim at. Moving it here means what you see mid-drag is already the outcome, and
   * `moveCardEnd` is left with nothing to decide but the final index.
   */
  function handleDragOver(event: DragOverEvent) {
    const { active, over } = event;
    if (over === null || !canEdit) return;
    if (active.data.current?.type === "column") return; // columns settle on drop only

    setBoard((prev) => {
      if (prev === null) return prev;
      const cardId = String(active.id);
      const fromColumnId = columnIdOfCard(prev, cardId);
      const toColumnId = columnIdOfTarget(prev, String(over.id));
      // Same column: the sortable strategy already previews the reorder, and the drop settles it.
      if (fromColumnId === null || toColumnId === null || fromColumnId === toColumnId) return prev;

      const fromCards = cardsOf(prev, fromColumnId);
      const moved = fromCards.find((c) => c.id === cardId);
      if (moved === undefined) return prev;

      const destCards = cardsOf(prev, toColumnId);
      const insertAt = dropIndex(destCards, active, over);
      return withColumnCards(
        withColumnCards(
          prev,
          fromColumnId,
          fromCards.filter((c) => c.id !== cardId),
        ),
        toColumnId,
        [...destCards.slice(0, insertAt), moved, ...destCards.slice(insertAt)],
      );
    });
  }

  async function handleDragEnd(event: DragEndEvent) {
    setActiveCard(null);
    setActiveColumn(null);
    if (board === null || !canEdit) return;
    if (event.over === null) {
      // Dropped on nothing. For a card, `handleDragOver` may already have relocated it locally —
      // put the board back rather than leaving a move nobody asked for (and the server never saw).
      if (event.active.data.current?.type !== "column" && snapshotRef.current) {
        setBoard(snapshotRef.current);
      }
      return;
    }
    if (event.active.data.current?.type === "column") {
      await moveColumnEnd(event);
    } else {
      await moveCardEnd(event);
    }
  }

  async function moveCardEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (board === null || over === null) return;

    const cardId = String(active.id);

    // `handleDragOver` has already put the card in whichever column it's being dropped on, so
    // "from" here is normally the destination already; all that's left is its index within it.
    const fromColumnId = columnIdOfCard(board, cardId);
    const toColumnId = columnIdOfTarget(board, String(over.id));
    if (fromColumnId === null || toColumnId === null) return;

    const fromCards = cardsOf(board, fromColumnId);
    const fromIndex = fromCards.findIndex((c) => c.id === cardId);
    if (fromIndex === -1) return;

    // Build the optimistic next board and find the moved card's final neighbours.
    let next = board;
    let finalCards = fromCards;
    let finalIndex = fromIndex;

    if (fromColumnId === toColumnId) {
      // Reorder within one column — the same list the sortable strategy has been previewing.
      const overId = String(over.id);
      const overIndex = isColumnCardsArea(overId)
        ? fromCards.length - 1
        : fromCards.findIndex((c) => c.id === overId);
      const target = overIndex === -1 ? fromCards.length - 1 : overIndex;
      if (target !== fromIndex) {
        finalCards = arrayMove(fromCards, fromIndex, target);
        finalIndex = finalCards.findIndex((c) => c.id === cardId);
        next = withColumnCards(board, toColumnId, finalCards);
      }
    } else {
      // A drop that never produced a drag-over (a keyboard drag straight onto another column):
      // relocate the card here instead.
      const destCards = cardsOf(board, toColumnId);
      const insertAt = dropIndex(destCards, active, over);
      finalCards = [
        ...destCards.slice(0, insertAt),
        fromCards[fromIndex],
        ...destCards.slice(insertAt),
      ];
      finalIndex = insertAt;
      next = withColumnCards(
        withColumnCards(
          board,
          fromColumnId,
          fromCards.filter((c) => c.id !== cardId),
        ),
        toColumnId,
        finalCards,
      );
    }

    // Ended exactly where it started — nothing to persist. Asked of the drag-start snapshot,
    // since the board itself has been following the card around since `handleDragOver`.
    const origin = dragOriginRef.current;
    if (origin !== null && origin.columnId === toColumnId && origin.index === finalIndex) {
      return;
    }

    setBoard(next);

    // Express the move as intent (never a rank): anchor to the card now above, else below, else
    // append. "after" wins server-side, so prefer the above-neighbour.
    const above = finalCards[finalIndex - 1];
    const below = finalCards[finalIndex + 1];
    const body: MoveCardBody = above
      ? { targetColumnId: toColumnId, afterCardId: above.id }
      : below
        ? { targetColumnId: toColumnId, beforeCardId: below.id }
        : { targetColumnId: toColumnId };

    try {
      const saved = await moveCard(authFetch, cardId, body);
      // Reconcile: order already matches; adopt the server's canonical rank/columnId/updatedAt.
      setBoard((prev) => (prev === null ? prev : replaceCard(prev, saved)));
    } catch {
      if (snapshotRef.current) setBoard(snapshotRef.current);
      setMoveError("Couldn't move that card. Put it back — try again.");
    }
  }

  async function moveColumnEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (board === null || over === null) return;

    const columnId = String(active.id);
    const overColumnId = String(over.id);
    const fromIndex = board.columns.findIndex((c) => c.column.id === columnId);
    const toIndex = board.columns.findIndex((c) => c.column.id === overColumnId);
    if (fromIndex === -1 || toIndex === -1 || fromIndex === toIndex) return;

    const columns = arrayMove(board.columns, fromIndex, toIndex);
    setBoard({ ...board, columns });

    // Intent: anchor to the column now to the left (after), else the one to the right (before).
    const finalIndex = columns.findIndex((c) => c.column.id === columnId);
    const left = columns[finalIndex - 1];
    const right = columns[finalIndex + 1];
    const body: MoveColumnBody = left
      ? { afterColumnId: left.column.id }
      : right
        ? { beforeColumnId: right.column.id }
        : {};

    try {
      const saved = await moveColumn(authFetch, columnId, body);
      setBoard((prev) => (prev === null ? prev : replaceColumn(prev, saved)));
    } catch {
      if (snapshotRef.current) setBoard(snapshotRef.current);
      setMoveError("Couldn't move that column. Put it back — try again.");
    }
  }

  // The board PATCH edits name + description together, so each handler resends the other field.
  async function handleRenameBoard(name: string) {
    const updated = await updateBoard(authFetch, id, name, board?.description ?? null);
    setBoard((prev) =>
      prev === null ? prev : { ...prev, name: updated.name, description: updated.description },
    );
  }

  async function handleUpdateDescription(description: string | null) {
    if (board === null) return;
    const updated = await updateBoard(authFetch, id, board.name, description);
    setBoard((prev) =>
      prev === null ? prev : { ...prev, name: updated.name, description: updated.description },
    );
  }

  async function handleDeleteBoard() {
    await deleteBoard(authFetch, id);
    router.replace("/dashboard");
  }

  // The board was deleted or our access was revoked mid-view — takes precedence over whatever
  // we were showing. A calm note beats yanking the page out from under the user.
  if (goneReason !== null) {
    return (
      <PageNote>
        {goneReason}{" "}
        <Link href="/dashboard" className="underline">
          Back to your boards
        </Link>
      </PageNote>
    );
  }
  if (status === "loading") {
    return <BoardSkeleton />;
  }
  if (status === "notfound") {
    return (
      <PageNote>
        Board not found.{" "}
        <Link href="/dashboard" className="underline">
          Back to your boards
        </Link>
      </PageNote>
    );
  }
  if (status === "error" || board === null) {
    return (
      <PageNote>
        Something went wrong loading this board.{" "}
        <button onClick={load} className="underline">
          Retry
        </button>
      </PageNote>
    );
  }

  // Note the absence of `overflow-hidden` on <main>: it would make main a scroll container, and
  // a `sticky` child only sticks within its nearest scrollport. Main never scrolls (it grows to
  // fit its content while the document scrolls), so clipping here would quietly stop both the
  // header and the bin bar from sticking at all. The ambient wash clips itself.
  return (
    <main className="animate-page-in relative flex min-h-full flex-1 flex-col bg-canvas">
      {/* Ambient wash, so the board sits on a surface rather than a flat fill. */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="animate-float-slow absolute -right-56 -top-40 h-[28rem] w-[28rem] rounded-full bg-brand-200/20 blur-3xl" />
      </div>

      <header className="glass sticky top-0 z-30 flex h-14 items-center justify-between gap-4 border-b border-line px-6">
        <div className="flex min-w-0 items-center gap-3">
          <BrandMark />
          <span className="text-lg text-zinc-300" aria-hidden>
            /
          </span>
          {isOwner ? (
            <BoardTitle name={board.name} onRename={handleRenameBoard} />
          ) : (
            <h1 className="truncate text-lg font-semibold tracking-tight text-zinc-900">
              {board.name}
            </h1>
          )}
          {/* Owning is the unremarkable case — only a shared-in role is worth labelling. */}
          {!isOwner && <RoleBadge role={board.myRole} />}
          <ConnectionDot state={connection} />
          <PresenceStack viewers={viewers} currentUserId={user?.id} />
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <HeaderButton onClick={() => setBinOpen(true)} icon="🗑" label="Bin" />
          <HeaderButton onClick={() => setActivityOpen(true)} icon="◷" label="Activity" />
          <HeaderButton onClick={() => setSharing(true)} icon="↗" label="Share" />
          {isOwner && (
            <span className="ml-1">
              <InlineConfirmButton onConfirm={handleDeleteBoard} label="Delete board" />
            </span>
          )}
        </div>
      </header>

      <div className="relative flex flex-1 flex-col p-6">
      <BoardDescription
        description={board.description}
        canEdit={isOwner}
        onSave={handleUpdateDescription}
      />
      {moveError && (
        <div
          role="alert"
          className="animate-pop-in mb-4 flex items-center justify-between gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-2.5 text-sm text-red-700 shadow-[var(--shadow-sm)]"
        >
          <span className="flex items-center gap-2">
            <span aria-hidden>⚠</span>
            {moveError}
          </span>
          <button
            onClick={() => setMoveError(null)}
            className="press rounded-lg px-2 py-1 font-semibold hover:bg-red-100"
          >
            Dismiss
          </button>
        </div>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={collisionDetection}
        // `handleDragOver` moves a card between columns mid-drag, which changes the layout under
        // the cursor. Without re-measuring, collisions keep resolving against the rects captured
        // when the drag began, and the card lands by stale geometry.
        measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        onDragCancel={() => {
          setActiveCard(null);
          setActiveColumn(null);
          // A cancelled card drag has to undo whatever `handleDragOver` did on the way.
          if (snapshotRef.current) setBoard(snapshotRef.current);
        }}
      >

        <SortableContext
          items={board.columns.map((c) => c.column.id)}
          strategy={horizontalListSortingStrategy}
        >
          <div className="scroll-slim flex flex-1 items-start gap-4 overflow-x-auto pb-4">
            {board.columns.map(({ column, cards }) => (
              <BoardColumnView
                key={column.id}
                column={column}
                cards={cards}
                canEdit={canEdit}
                assigneeNameById={assigneeNameById}
                onRename={(title) => handleRenameColumn(column.id, title)}
                onDelete={() => handleDeleteColumn(column.id)}
                onCreateCard={(title) => handleAddCard(column.id, title)}
                onCardClick={openCardModal}
                onBinCard={handleBinCard}
              />
            ))}
            {canEdit && <AddColumn onAdd={handleAddColumn} />}
          </div>
        </SortableContext>

        {/* The floating copy of whatever is being dragged. It's deliberately *not* a 1:1 clone:
            it tilts and casts a deeper shadow, so the held item reads as lifted off the board. */}
        {/* Portalled to <body>, and that is load-bearing rather than tidiness. DragOverlay is
            `position: fixed`, but <main> carries `.animate-page-in`, whose `fill-mode: both`
            leaves a transform applied for the life of the page — and an element with a transform
            becomes the containing block for its fixed descendants. Rendered in place, the held
            card therefore resolved against <main> instead of the viewport and floated exactly
            `scrollY` pixels away from the cursor: fine at the top of a board, visibly wrong once
            you scrolled down to reach a card. The portal moves it out from under that ancestor.
            React context flows through portals, so DragOverlay still sees its DndContext. */}
        {createPortal(
          <DragOverlay dropAnimation={{ duration: 220, easing: "cubic-bezier(0.22, 1, 0.36, 1)" }}>
            {activeCard ? (
            <CardFace
                card={activeCard}
                floating
                assigneeName={
                  activeCard.assigneeId ? assigneeNameById[activeCard.assigneeId] ?? null : null
                }
              />
            ) : activeColumn ? (
              <div className="w-[19rem] rotate-1 rounded-2xl border border-brand-300 bg-sunken p-3 shadow-[var(--shadow-xl)] ring-2 ring-brand-200">
                <div className="flex items-center gap-2">
                  <span className="text-zinc-400" aria-hidden>
                    ⠿
                  </span>
                  <span className="text-sm font-semibold text-zinc-800">{activeColumn.title}</span>
                </div>
              </div>
            ) : null}
          </DragOverlay>,
          document.body,
        )}
      </DndContext>
      </div>

      {openCard && (
        <CardModal
          card={openCard}
          canEdit={canEdit}
          columnTitle={
            board.columns.find((c) => c.column.id === openCard.columnId)?.column.title ?? ""
          }
          members={assignableMembers}
          conflict={openCardConflict}
          onClose={closeCard}
          onSave={handleSaveCard}
          onDelete={handleDeleteCard}
        />
      )}

      {sharing && (
        <ShareModal
          boardId={id}
          isOwner={isOwner}
          refreshSignal={memberEventNonce}
          onClose={() => setSharing(false)}
        />
      )}


      {binOpen && (
        <BinPanel
          boardId={id}
          canEdit={canEdit}
          refreshSignal={binNonce}
          onRestored={handleRestored}
          onClose={() => setBinOpen(false)}
        />
      )}

      {activityOpen && (
        <ActivityPanel
          boardId={id}
          currentUserId={user?.id}
          refreshSignal={activityNonce}
          onClose={() => setActivityOpen(false)}
        />
      )}
    </main>
  );
}

/**
 * The board's description, shown under the header. An owner can click to edit it inline (and add
 * one when there's none yet); everyone else sees the text, or nothing when it's empty. Saving
 * sends the whole board edit (name unchanged) — this is the only place a description is set, and
 * it's what the dashboard overview cards read.
 */
function BoardDescription({
  description,
  canEdit,
  onSave,
}: {
  description: string | null;
  canEdit: boolean;
  onSave: (description: string | null) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(description ?? "");

  async function save() {
    const next = value.trim();
    if (next === (description ?? "")) {
      setEditing(false);
      return;
    }
    await onSave(next === "" ? null : next);
    setEditing(false);
  }

  if (editing) {
    return (
      <input
        autoFocus
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={save}
        onKeyDown={(e) => {
          if (e.key === "Enter") save();
          if (e.key === "Escape") {
            setValue(description ?? "");
            setEditing(false);
          }
        }}
        maxLength={280}
        placeholder="Add a description…"
        className="animate-pop-in mb-4 w-full max-w-xl rounded-lg border border-brand-300 bg-white px-3 py-2 text-sm text-zinc-700 outline-none transition-shadow focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
      />
    );
  }

  if (!description && !canEdit) return null;

  return (
    <p
      onClick={canEdit ? () => setEditing(true) : undefined}
      title={canEdit ? "Click to edit the description" : undefined}
      className={`mb-4 -mx-2 max-w-xl rounded-lg px-2 py-1 text-sm transition-colors duration-200 ${
        description ? "text-zinc-500" : "text-zinc-400"
      } ${canEdit ? "cursor-text hover:bg-paper/70 hover:text-zinc-700" : ""}`}
    >
      {description ?? "Add a description…"}
    </p>
  );
}

function BoardTitle({
  name,
  onRename,
}: {
  name: string;
  onRename: (name: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name);

  async function save() {
    const next = value.trim();
    if (next === "" || next === name) {
      setValue(name);
      setEditing(false);
      return;
    }
    await onRename(next);
    setEditing(false);
  }

  if (editing) {
    return (
      <input
        autoFocus
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={save}
        onKeyDown={(e) => {
          if (e.key === "Enter") save();
          if (e.key === "Escape") {
            setValue(name);
            setEditing(false);
          }
        }}
        className="animate-pop-in rounded-lg border border-brand-300 bg-white px-2 py-1 text-lg font-semibold text-zinc-900 outline-none transition-shadow focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
      />
    );
  }
  return (
    <h1
      onClick={() => setEditing(true)}
      title="Click to rename"
      className="-mx-2 cursor-text truncate rounded-lg px-2 py-0.5 text-lg font-semibold tracking-tight text-zinc-900 transition-colors duration-200 hover:bg-paper hover:text-brand-700"
    >
      {name}
    </h1>
  );
}

/** A header action: an icon that slides in on hover beside its label. */
function HeaderButton({
  onClick,
  icon,
  label,
}: {
  onClick: () => void;
  icon: string;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="press group flex items-center gap-1.5 rounded-lg border border-line-strong bg-paper/70 px-3 py-1.5 text-sm font-medium text-zinc-700 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700"
    >
      <span
        aria-hidden
        className="text-zinc-400 transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover:scale-125 group-hover:text-brand-500"
      >
        {icon}
      </span>
      {label}
    </button>
  );
}

/** The "add a column" affordance at the end of the columns row. */
function AddColumn({ onAdd }: { onAdd: (title: string) => Promise<void> }) {
  const [title, setTitle] = useState("");
  const [adding, setAdding] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const t = title.trim();
    if (t === "") return;
    setAdding(true);
    try {
      await onAdd(t);
      setTitle("");
    } finally {
      setAdding(false);
    }
  }

  return (
    <form
      onSubmit={submit}
      className="animate-fade-in flex w-[19rem] shrink-0 flex-col gap-2 rounded-2xl border border-dashed border-line-strong p-3 transition-colors duration-300 hover:border-brand-300 hover:bg-brand-50/30"
    >
      <input
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        placeholder="Add a column…"
        className="rounded-lg border border-line bg-paper px-3 py-2 text-sm text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
      />
      {title.trim() !== "" && (
        <button
          type="submit"
          disabled={adding}
          className="press animate-pop-in rounded-lg bg-gradient-to-br from-brand-500 to-violet-600 px-3 py-1.5 text-sm font-semibold text-white shadow-[var(--shadow-brand)] disabled:opacity-50"
        >
          {adding ? "Adding…" : "Add column"}
        </button>
      )}
    </form>
  );
}

/**
 * A quiet live-connection indicator. It answers one question — "is what I'm looking at current?"
 * — so when the socket drops, the user knows the board may be going stale rather than assuming
 * nothing's happening.
 *
 * Live is an outward pulse (something is flowing); reconnecting is a spinner (something is being
 * attempted). Two different shapes of motion for two different meanings, not just two colours.
 */
function ConnectionDot({ state }: { state: ConnectionState }) {
  const live = state === "live";
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-medium transition-colors duration-300 ${
        live
          ? "border-emerald-200 bg-emerald-50 text-emerald-700"
          : "border-amber-200 bg-amber-50 text-amber-700"
      }`}
      title={live ? "Live — updates appear as they happen" : "Reconnecting…"}
    >
      {live ? (
        <span className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full rounded-full bg-emerald-500 [animation:pulse-ring_2.4s_var(--ease-out-soft)_infinite]" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
        </span>
      ) : (
        <span className="animate-spin-slow inline-block h-2.5 w-2.5 rounded-full border-2 border-amber-500 border-t-transparent" />
      )}
      {live ? "Live" : "Reconnecting…"}
    </span>
  );
}

/** The board's loading state: the real layout, in placeholder form, so nothing jumps on arrival. */
function BoardSkeleton() {
  return (
    <main className="animate-fade-in flex min-h-full flex-1 flex-col bg-canvas">
      <div className="glass flex h-14 items-center gap-3 border-b border-line px-6">
        <div className="skeleton h-7 w-7 rounded-lg" />
        <div className="skeleton h-4 w-32 rounded" />
        <div className="skeleton ml-auto h-7 w-20 rounded-lg" />
        <div className="skeleton h-7 w-16 rounded-lg" />
      </div>
      <div className="flex flex-1 gap-4 p-6" aria-label="Loading board">
        {[3, 2, 4].map((cards, i) => (
          <div
            key={i}
            className="animate-fade-in flex w-[19rem] shrink-0 flex-col gap-2 rounded-2xl border border-line bg-sunken/60 p-3"
            style={{ animationDelay: `${i * 90}ms` }}
          >
            <div className="skeleton mb-2 h-4 w-24 rounded" />
            {Array.from({ length: cards }).map((_, c) => (
              <div key={c} className="skeleton h-16 rounded-xl" />
            ))}
          </div>
        ))}
      </div>
    </main>
  );
}
