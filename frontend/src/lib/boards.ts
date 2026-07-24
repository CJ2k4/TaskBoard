/**
 * Typed client for the board / column / card API (Milestone 2, extended in M3 and M4).
 *
 * Every endpoint here is authenticated and role-checked on the server, so each function takes
 * the `authFetch` from `useAuth()` — that wrapper attaches the access token and handles a
 * mid-session token refresh transparently. The types mirror the backend's response DTOs
 * (`BoardResponse`, `BoardDetailResponse`, `ColumnResponse`, `CardResponse`); `rank` is a
 * server-owned LexoRank string the client only reads (never sets — the server resolves moves).
 *
 * Sharing/membership calls live next door in `members.ts`.
 */

/** A function that performs an authenticated API call. Matches `useAuth().authFetch`. */
export type AuthFetch = <T>(path: string, init?: RequestInit) => Promise<T>;

/**
 * A member's role on a board, mirroring the backend enum. Capabilities are cumulative:
 * OWNER ⊃ EDITOR ⊃ VIEWER.
 */
export type Role = "OWNER" | "EDITOR" | "VIEWER";

export type Board = {
  id: string;
  name: string;
  /** A short optional board description, shown on the dashboard overview cards. */
  description: string | null;
  ownerId: string;
  /**
   * The *caller's* role on this board — not a property of the board itself. The server sends it
   * with every board payload so the UI can decide what to render before the user tries anything
   * (and gets a 403 for their trouble).
   */
  myRole: Role;
  createdAt: string;
  updatedAt: string;
};

/** The minimum an avatar needs, mirroring the server's `BoardOverviewResponse.MemberSummary`. */
export type MemberSummary = { userId: string; name: string };

/**
 * A board as the dashboard grid shows it: its own fields plus summary counts and the active
 * member roster (for the avatar stack). Mirrors the server's `BoardOverviewResponse` — richer
 * than {@link Board} because the list endpoint computes this enrichment the others don't need.
 */
export type BoardOverview = Board & {
  columnCount: number;
  cardCount: number;
  members: MemberSummary[];
};

export type Column = {
  id: string;
  boardId: string;
  title: string;
  rank: string;
  createdAt: string;
  updatedAt: string;
};

export type Card = {
  id: string;
  columnId: string;
  boardId: string;
  title: string;
  description: string | null;
  /** Optional free-text tag shown as a chip. */
  label: string | null;
  /** Optional assignee — an app_user id; the name is resolved client-side from the board roster. */
  assigneeId: string | null;
  rank: string;
  createdAt: string;
  updatedAt: string;
};

/** One column with its ordered cards, mirroring `BoardDetailResponse.ColumnWithCards`. */
export type ColumnWithCards = { column: Column; cards: Card[] };

/** The whole board in one payload — board fields plus nested columns/cards, all rank-ordered. */
export type BoardDetail = Board & { columns: ColumnWithCards[] };

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

// --- Boards ---------------------------------------------------------------

export const listBoards = (authFetch: AuthFetch) =>
  authFetch<BoardOverview[]>("/api/boards");

export const createBoard = (authFetch: AuthFetch, name: string) =>
  authFetch<Board>("/api/boards", { method: "POST", ...json({ name }) });

export const getBoard = (authFetch: AuthFetch, id: string) =>
  authFetch<BoardDetail>(`/api/boards/${id}`);

/**
 * Edit a board's name and description together (the server PATCH is a full edit of the board's
 * own fields, so both are always sent — pass the current value for whichever you aren't changing).
 */
export const updateBoard = (
  authFetch: AuthFetch,
  id: string,
  name: string,
  description: string | null,
) =>
  authFetch<Board>(`/api/boards/${id}`, {
    method: "PATCH",
    ...json({ name, description }),
  });

export const deleteBoard = (authFetch: AuthFetch, id: string) =>
  authFetch<void>(`/api/boards/${id}`, { method: "DELETE" });

// --- Columns --------------------------------------------------------------

export const createColumn = (authFetch: AuthFetch, boardId: string, title: string) =>
  authFetch<Column>(`/api/boards/${boardId}/columns`, {
    method: "POST",
    ...json({ title }),
  });

export const renameColumn = (authFetch: AuthFetch, id: string, title: string) =>
  authFetch<Column>(`/api/columns/${id}`, { method: "PATCH", ...json({ title }) });

export const deleteColumn = (authFetch: AuthFetch, id: string) =>
  authFetch<void>(`/api/columns/${id}`, { method: "DELETE" });

/**
 * A column reorder expressed as intent, mirroring the backend's `MoveColumnRequest`.
 * `afterColumnId` wins if both are set; neither means "append to the end". As with cards, the
 * server resolves the neighbours against its own order and returns the canonical `rank`.
 */
export type MoveColumnBody = {
  beforeColumnId?: string;
  afterColumnId?: string;
};

export const moveColumn = (authFetch: AuthFetch, id: string, body: MoveColumnBody) =>
  authFetch<Column>(`/api/columns/${id}/move`, { method: "PATCH", ...json(body) });

// --- Cards ----------------------------------------------------------------

export const createCard = (
  authFetch: AuthFetch,
  columnId: string,
  title: string,
  description?: string,
) =>
  authFetch<Card>(`/api/columns/${columnId}/cards`, {
    method: "POST",
    ...json({ title, description }),
  });

export const updateCard = (
  authFetch: AuthFetch,
  id: string,
  title: string,
  description: string | null,
  label: string | null,
  assigneeId: string | null,
) =>
  authFetch<Card>(`/api/cards/${id}`, {
    method: "PATCH",
    ...json({ title, description, label, assigneeId }),
  });

export const deleteCard = (authFetch: AuthFetch, id: string) =>
  authFetch<void>(`/api/cards/${id}`, { method: "DELETE" });

/**
 * A card move expressed as *intent*, mirroring the backend's `MoveCardRequest`. The card goes
 * into `targetColumnId` (possibly its current one); `afterCardId` wins if both anchors are set,
 * and neither anchor means "append to the end". The client never computes a rank — the server
 * resolves the neighbours against its own order and returns the canonical `rank` to reconcile to.
 */
export type MoveCardBody = {
  targetColumnId: string;
  beforeCardId?: string;
  afterCardId?: string;
};

export const moveCard = (authFetch: AuthFetch, id: string, body: MoveCardBody) =>
  authFetch<Card>(`/api/cards/${id}/move`, { method: "PATCH", ...json(body) });
