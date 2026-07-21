/**
 * Typed client for the sharing / membership API (Milestone 4). Same shape as `boards.ts`:
 * every function takes the `authFetch` from `useAuth()`, and the types mirror the backend's
 * `MembershipResponse`.
 *
 * Server-side rules worth knowing here, because the UI should never offer what they forbid:
 * inviting or managing members is **owner-only**, `OWNER` is not an invitable role (a board has
 * exactly one owner), and the owner's own membership row can be neither re-roled nor removed.
 */

import type { AuthFetch, Role } from "@/lib/boards";

/**
 * ACTIVE — a real account with access right now. PENDING — an invited email address with no
 * account yet; the row activates by itself the first time that email signs in.
 */
export type MembershipStatus = "ACTIVE" | "PENDING";

/**
 * One member or pending invite. Two shapes share this type: an ACTIVE row identifies a real
 * user (`userId`/`name`/`email`), while a PENDING row has only `invitedEmail` — which is why
 * the account fields are nullable.
 */
export type Membership = {
  id: string;
  boardId: string;
  userId: string | null;
  name: string | null;
  email: string | null;
  invitedEmail: string | null;
  role: Role;
  status: MembershipStatus;
  createdAt: string;
};

/** The roles an invite may use — never OWNER, which the server rejects with a 400. */
export type InvitableRole = Exclude<Role, "OWNER">;

const json = (body: unknown): RequestInit => ({ body: JSON.stringify(body) });

/** A board's members and pending invites, oldest first (the owner naturally comes first). */
export const listMembers = (authFetch: AuthFetch, boardId: string) =>
  authFetch<Membership[]>(`/api/boards/${boardId}/members`);

/**
 * Invite an email to a board. Returns the created row: ACTIVE if that email already has an
 * account, PENDING if not. Throws `ApiError` 409 when they're already a member or an identical
 * invite is already outstanding.
 */
export const inviteMember = (
  authFetch: AuthFetch,
  boardId: string,
  email: string,
  role: InvitableRole,
) =>
  authFetch<Membership>(`/api/boards/${boardId}/invites`, {
    method: "POST",
    ...json({ email, role }),
  });

/** Re-role a member (EDITOR ↔ VIEWER). Addressed by membership id, not board + user. */
export const changeMemberRole = (
  authFetch: AuthFetch,
  membershipId: string,
  role: InvitableRole,
) =>
  authFetch<Membership>(`/api/memberships/${membershipId}`, {
    method: "PATCH",
    ...json({ role }),
  });

/** Remove a member, or revoke a pending invite — the same row either way. */
export const removeMember = (authFetch: AuthFetch, membershipId: string) =>
  authFetch<void>(`/api/memberships/${membershipId}`, { method: "DELETE" });

// ---------------------------------------------------------------- invite links (M6)

/**
 * A board's shareable invite link. `token` is null when no link is active — the server answers
 * 200 with a null token (not a 404) so the owner UI can tell "no link yet" apart from an error.
 */
export type InviteLink = { token: string | null; role: InvitableRole | null };

/** The outcome of redeeming a link: which board the caller now has, and at what role. */
export type JoinResult = { boardId: string; role: Role };

/** The board's current link (owner only). Null token = none active yet. */
export const getInviteLink = (authFetch: AuthFetch, boardId: string) =>
  authFetch<InviteLink>(`/api/boards/${boardId}/invite-link`);

/** Create or rotate the link (owner only). Rotating invalidates any URL already shared. */
export const createInviteLink = (
  authFetch: AuthFetch,
  boardId: string,
  role: InvitableRole,
) =>
  authFetch<InviteLink>(`/api/boards/${boardId}/invite-link`, {
    method: "POST",
    ...json({ role }),
  });

/** Disable the link (owner only); an outstanding URL stops working at once. */
export const disableInviteLink = (authFetch: AuthFetch, boardId: string) =>
  authFetch<void>(`/api/boards/${boardId}/invite-link`, { method: "DELETE" });

/**
 * Redeem a link: the signed-in caller joins the board it points to. Throws `ApiError` 404 when
 * the token is unknown or the link has been disabled.
 */
export const acceptInviteLink = (authFetch: AuthFetch, token: string) =>
  authFetch<JoinResult>(`/api/invite-links/${token}/accept`, { method: "POST" });
