"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { avatarColor, initials } from "@/lib/avatar";
import type { Role } from "@/lib/boards";
import {
  changeMemberRole,
  createInviteLink,
  disableInviteLink,
  getInviteLink,
  inviteMember,
  listMembers,
  removeMember,
  type InvitableRole,
  type InviteLink,
  type Membership,
} from "@/lib/members";
import { InlineConfirmButton } from "@/components/board/inline-confirm-button";

/**
 * The board's sharing panel: who has access, at what role, plus an invite form for the owner.
 * A portal modal on `document.body`, same pattern as `CardModal` (backdrop click or Escape to
 * close, clicks inside don't bubble out).
 *
 * Everything here is owner-only on the server *except* reading the list, so a non-owner gets
 * the roster and nothing else — no invite form, no per-row controls. That isn't cosmetic: the
 * server would answer 403, and offering a control that always fails is a lie.
 */
export function ShareModal({
  boardId,
  isOwner,
  refreshSignal,
  onClose,
}: {
  boardId: string;
  isOwner: boolean;
  /** Bumped by the board page on any live MEMBER_* event, so an open roster stays current. */
  refreshSignal?: number;
  onClose: () => void;
}) {
  const { authFetch } = useAuth();

  const [members, setMembers] = useState<Membership[]>([]);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");

  const load = useCallback(async () => {
    setStatus("loading");
    try {
      setMembers(await listMembers(authFetch, boardId));
      setStatus("ready");
    } catch {
      setStatus("error");
    }
  }, [authFetch, boardId]);

  // Loads on open, and again whenever `refreshSignal` changes — a live membership change
  // elsewhere. The fetch is inlined rather than calling `load()` so nothing sets state
  // synchronously in the effect body; `status` already starts as "loading", and a refetch keeps
  // the current roster on screen instead of flashing the spinner. One refetch per signal.
  useEffect(() => {
    let cancelled = false;
    listMembers(authFetch, boardId)
      .then((rows) => {
        if (cancelled) return;
        setMembers(rows);
        setStatus("ready");
      })
      .catch(() => {
        if (!cancelled) setStatus("error");
      });
    return () => {
      cancelled = true;
    };
  }, [authFetch, boardId, refreshSignal]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function handleInvited(member: Membership) {
    setMembers((prev) => [...prev, member]);
  }

  async function handleRoleChange(membershipId: string, role: InvitableRole) {
    const updated = await changeMemberRole(authFetch, membershipId, role);
    setMembers((prev) => prev.map((m) => (m.id === membershipId ? updated : m)));
  }

  async function handleRemove(membershipId: string) {
    await removeMember(authFetch, membershipId);
    setMembers((prev) => prev.filter((m) => m.id !== membershipId));
  }

  return createPortal(
    <div
      className="animate-fade-in fixed inset-0 z-50 flex items-start justify-center bg-zinc-900/40 p-4 pt-24 backdrop-blur-[3px]"
      onClick={onClose}
    >
      <div
        className="animate-spring-in w-full max-w-lg rounded-2xl border border-line bg-paper p-6 shadow-[var(--shadow-xl)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 className="flex items-center gap-2 text-base font-semibold text-zinc-900">
              <span aria-hidden className="text-brand-500">
                ↗
              </span>
              Share this board
            </h2>
            <p className="mt-0.5 text-xs text-zinc-500">
              {isOwner
                ? "Invite people by email and choose what they can do."
                : "People with access to this board."}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-sm text-zinc-500 transition-all duration-200 hover:rotate-90 hover:bg-sunken hover:text-zinc-900"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        {isOwner && <InviteForm boardId={boardId} onInvited={handleInvited} />}

        {isOwner && <InviteLinkSection boardId={boardId} />}

        {status === "loading" && (
          <div className="flex flex-col gap-3" aria-label="Loading members">
            {[0, 1, 2].map((i) => (
              <div key={i} className="flex items-center justify-between gap-3 py-1">
                <div className="flex-1">
                  <div className="skeleton h-3.5 w-1/3 rounded" />
                  <div className="skeleton mt-1.5 h-3 w-1/2 rounded" />
                </div>
                <div className="skeleton h-6 w-16 rounded-full" />
              </div>
            ))}
          </div>
        )}
        {status === "error" && (
          <p className="animate-pop-in rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            Couldn&apos;t load the member list.{" "}
            <button onClick={load} className="font-semibold underline underline-offset-2">
              Retry
            </button>
          </p>
        )}
        {status === "ready" && (
          <ul className="stagger flex flex-col divide-y divide-line">
            {members.map((member) => (
              <MemberRow
                key={member.id}
                member={member}
                isOwner={isOwner}
                onRoleChange={(role) => handleRoleChange(member.id, role)}
                onRemove={() => handleRemove(member.id)}
              />
            ))}
          </ul>
        )}
      </div>
    </div>,
    document.body,
  );
}

/** Invite by email at a chosen role. Owner-only — the parent decides whether to render it. */
function InviteForm({
  boardId,
  onInvited,
}: {
  boardId: string;
  onInvited: (member: Membership) => void;
}) {
  const { authFetch } = useAuth();
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<InvitableRole>("EDITOR");
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const address = email.trim();
    if (address === "") return;

    setBusy(true);
    setError(null);
    setNote(null);
    try {
      const member = await inviteMember(authFetch, boardId, address, role);
      onInvited(member);
      setEmail("");
      // A pending invite is a real outcome, not a failure — say what will happen next, or the
      // owner is left wondering why the person didn't just appear.
      setNote(
        member.status === "PENDING"
          ? `No account for ${member.invitedEmail} yet — they'll get access the first time they sign up.`
          : `${member.name ?? member.email} now has access.`,
      );
    } catch (err) {
      // 409 (already a member / already invited) and 400 (bad email) are expected answers;
      // the server's own message is more precise than anything we'd invent here.
      setError(
        err instanceof ApiError
          ? (err.fieldErrors[0]?.message ?? err.message)
          : "Couldn't send that invite.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mb-5 flex flex-col gap-2">
      <div className="flex gap-2">
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="name@example.com"
          className="flex-1 rounded-lg border border-line-strong bg-white px-3 py-2 text-sm text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
        />
        <select
          value={role}
          onChange={(e) => setRole(e.target.value as InvitableRole)}
          className="cursor-pointer rounded-lg border border-line-strong bg-white px-2 py-2 text-sm text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
          aria-label="Role"
        >
          <option value="EDITOR">Editor</option>
          <option value="VIEWER">Viewer</option>
        </select>
        <button
          type="submit"
          disabled={busy || email.trim() === ""}
          className="press rounded-lg bg-gradient-to-br from-brand-500 to-violet-600 px-4 py-2 text-sm font-semibold text-white shadow-[var(--shadow-brand)] disabled:opacity-50 disabled:shadow-none"
        >
          {busy ? "Inviting…" : "Invite"}
        </button>
      </div>
      {error && (
        <p className="animate-pop-in text-xs text-red-600" role="alert">
          {error}
        </p>
      )}
      {note && (
        <p className="animate-pop-in rounded-lg bg-brand-50 px-2.5 py-1.5 text-xs text-brand-800">
          {note}
        </p>
      )}
    </form>
  );
}

/**
 * The "share a link" half of sharing (M6), owner-only. A single rotatable link that anyone
 * signed-in can redeem to join at a preset role — the low-friction counterpart to inviting a
 * specific email. Rotating mints a new token (killing the old URL); disabling removes it.
 */
function InviteLinkSection({ boardId }: { boardId: string }) {
  const { authFetch } = useAuth();
  const [link, setLink] = useState<InviteLink | null>(null);
  const [role, setRole] = useState<InvitableRole>("EDITOR");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load any existing link on open. A failure here is non-fatal — the create form still works.
  useEffect(() => {
    getInviteLink(authFetch, boardId)
      .then(setLink)
      .catch(() => setLink({ token: null, role: null }));
  }, [authFetch, boardId]);

  const url =
    link?.token && typeof window !== "undefined"
      ? `${window.location.origin}/join/${link.token}`
      : null;

  async function create() {
    setBusy(true);
    setError(null);
    try {
      setLink(await createInviteLink(authFetch, boardId, role));
      setCopied(false);
    } catch {
      setError("Couldn't create a link.");
    } finally {
      setBusy(false);
    }
  }

  async function disable() {
    setBusy(true);
    setError(null);
    try {
      await disableInviteLink(authFetch, boardId);
      setLink({ token: null, role: null });
    } catch {
      setError("Couldn't disable the link.");
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
    } catch {
      setError("Couldn't copy — copy it manually.");
    }
  }

  return (
    <div className="mb-5 border-t border-line pt-4">
      <p className="mb-2 text-xs font-semibold text-zinc-700">Or share a link</p>

      {url ? (
        <div className="animate-pop-in flex flex-col gap-2">
          <div className="flex gap-2">
            <input
              readOnly
              value={url}
              onFocus={(e) => e.target.select()}
              className="flex-1 rounded-lg border border-line-strong bg-canvas px-3 py-2 font-mono text-xs text-zinc-700 outline-none transition-colors focus:border-brand-400"
            />
            <button
              type="button"
              onClick={copy}
              className={`press w-24 rounded-lg px-3 py-2 text-sm font-semibold text-white shadow-[var(--shadow-sm)] ${
                copied
                  ? "bg-emerald-600"
                  : "bg-gradient-to-br from-brand-500 to-violet-600 shadow-[var(--shadow-brand)]"
              }`}
            >
              {copied ? "✓ Copied" : "Copy"}
            </button>
          </div>
          <p className="text-xs text-zinc-500">
            Anyone who signs in with this link joins as{" "}
            <span className="font-medium">{(link!.role ?? "").toLowerCase()}</span>.{" "}
            <button
              type="button"
              onClick={create}
              disabled={busy}
              className="underline disabled:opacity-50"
            >
              Rotate
            </button>{" "}
            ·{" "}
            <button
              type="button"
              onClick={disable}
              disabled={busy}
              className="underline disabled:opacity-50"
            >
              Disable link
            </button>
          </p>
        </div>
      ) : (
        <div className="flex gap-2">
          <select
            value={role}
            onChange={(e) => setRole(e.target.value as InvitableRole)}
            className="cursor-pointer rounded-lg border border-line-strong bg-white px-2 py-2 text-sm text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
            aria-label="Link role"
          >
            <option value="EDITOR">Editor</option>
            <option value="VIEWER">Viewer</option>
          </select>
          <button
            type="button"
            onClick={create}
            disabled={busy}
            className="press rounded-lg border border-line-strong px-3 py-2 text-sm font-medium text-zinc-700 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:opacity-50"
          >
            {busy ? "Creating…" : "Create link"}
          </button>
        </div>
      )}
      {error && (
        <p className="animate-pop-in mt-1 text-xs text-red-600" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * One row of the roster. The owner's row deliberately carries no controls: the server refuses
 * to re-role or remove it, so there's nothing to offer.
 */
function MemberRow({
  member,
  isOwner,
  onRoleChange,
  onRemove,
}: {
  member: Membership;
  isOwner: boolean;
  onRoleChange: (role: InvitableRole) => Promise<void>;
  onRemove: () => Promise<void>;
}) {
  const [error, setError] = useState<string | null>(null);
  const isOwnerRow = member.role === "OWNER";
  const pending = member.status === "PENDING";
  const manageable = isOwner && !isOwnerRow;

  async function changeRole(role: InvitableRole) {
    setError(null);
    try {
      await onRoleChange(role);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't change that role.");
    }
  }

  return (
    <li className="group/member -mx-2 flex items-center justify-between gap-3 rounded-lg px-2 py-3 transition-colors duration-200 hover:bg-canvas">
      <div className="flex min-w-0 items-center gap-3">
        <span
          className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[11px] font-semibold text-white ring-2 ring-paper transition-transform duration-200 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover/member:scale-110 ${
            pending ? "bg-zinc-300" : ""
          }`}
          style={
            pending || !member.userId
              ? undefined
              : { backgroundColor: avatarColor(member.userId) }
          }
          aria-hidden
        >
          {pending ? "?" : initials(member.name ?? member.email ?? "?")}
        </span>
        <div className="min-w-0">
          <p className="truncate text-sm font-medium text-zinc-900">
            {member.name ?? member.invitedEmail ?? member.email}
            {pending && (
              <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-700">
                Pending
              </span>
            )}
          </p>
          <p className="truncate text-xs text-zinc-500">
            {pending ? "Hasn't signed up yet" : member.email}
          </p>
          {error && <p className="animate-pop-in mt-1 text-xs text-red-600">{error}</p>}
        </div>
      </div>

      <div className="flex shrink-0 items-center gap-3">
        {manageable ? (
          <select
            value={member.role}
            onChange={(e) => changeRole(e.target.value as InvitableRole)}
            className="cursor-pointer rounded-lg border border-line-strong bg-white px-2 py-1 text-xs text-zinc-900 outline-none transition-shadow duration-200 focus:border-brand-400 focus:shadow-[0_0_0_4px_rgba(99,102,241,0.12)]"
            aria-label={`Role for ${member.name ?? member.invitedEmail}`}
          >
            <option value="EDITOR">Editor</option>
            <option value="VIEWER">Viewer</option>
          </select>
        ) : (
          <RoleBadge role={member.role} />
        )}
        {manageable && (
          <InlineConfirmButton onConfirm={onRemove} label={pending ? "Revoke" : "Remove"} />
        )}
      </div>
    </li>
  );
}

/**
 * A small, non-interactive role label — used where the role can't be changed. Each role gets its
 * own tint so "what am I allowed to do here?" is answerable at a glance, without reading.
 */
const ROLE_TINT: Record<Role, string> = {
  OWNER: "border-brand-200 bg-brand-50 text-brand-700",
  EDITOR: "border-emerald-200 bg-emerald-50 text-emerald-700",
  VIEWER: "border-zinc-200 bg-zinc-100 text-zinc-600",
};

export function RoleBadge({ role }: { role: Role }) {
  const label = role.charAt(0) + role.slice(1).toLowerCase();
  return (
    <span
      className={`shrink-0 rounded-full border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${ROLE_TINT[role] ?? ROLE_TINT.VIEWER}`}
    >
      {label}
    </span>
  );
}
