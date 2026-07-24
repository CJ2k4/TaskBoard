"use client";

import { useCallback, useEffect, useState } from "react";
import { createPortal } from "react-dom";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
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
  // elsewhere. `load` is stable, so this is one refetch per signal, no churn.
  useEffect(() => {
    load();
  }, [load, refreshSignal]);

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
      className="animate-fade-in fixed inset-0 z-50 flex items-start justify-center bg-black/50 p-4 pt-24"
      onClick={onClose}
    >
      <div
        className="animate-pop-in w-full max-w-lg rounded-xl border border-zinc-200 bg-white p-6 shadow-xl dark:border-zinc-800 dark:bg-zinc-950"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold text-zinc-900 dark:text-zinc-50">
              Share this board
            </h2>
            <p className="text-xs text-zinc-500 dark:text-zinc-400">
              {isOwner
                ? "Invite people by email and choose what they can do."
                : "People with access to this board."}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-sm text-zinc-500 transition hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-zinc-100"
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        {isOwner && <InviteForm boardId={boardId} onInvited={handleInvited} />}

        {isOwner && <InviteLinkSection boardId={boardId} />}

        {status === "loading" && (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">Loading members…</p>
        )}
        {status === "error" && (
          <p className="text-sm text-red-600 dark:text-red-400">
            Couldn&apos;t load the member list.{" "}
            <button onClick={load} className="underline">
              Retry
            </button>
          </p>
        )}
        {status === "ready" && (
          <ul className="flex flex-col divide-y divide-zinc-200 dark:divide-zinc-800">
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
          className="flex-1 rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
        />
        <select
          value={role}
          onChange={(e) => setRole(e.target.value as InvitableRole)}
          className="rounded-lg border border-zinc-300 bg-white px-2 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
          aria-label="Role"
        >
          <option value="EDITOR">Editor</option>
          <option value="VIEWER">Viewer</option>
        </select>
        <button
          type="submit"
          disabled={busy || email.trim() === ""}
          className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
        >
          {busy ? "Inviting…" : "Invite"}
        </button>
      </div>
      {error && (
        <p className="text-xs text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      )}
      {note && <p className="text-xs text-zinc-500 dark:text-zinc-400">{note}</p>}
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
    <div className="mb-5 border-t border-zinc-200 pt-4 dark:border-zinc-800">
      <p className="mb-2 text-xs font-medium text-zinc-700 dark:text-zinc-300">
        Or share a link
      </p>

      {url ? (
        <div className="flex flex-col gap-2">
          <div className="flex gap-2">
            <input
              readOnly
              value={url}
              onFocus={(e) => e.target.select()}
              className="flex-1 rounded-lg border border-zinc-300 bg-zinc-50 px-3 py-2 text-xs text-zinc-700 outline-none dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-300"
            />
            <button
              type="button"
              onClick={copy}
              className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
            >
              {copied ? "Copied" : "Copy"}
            </button>
          </div>
          <p className="text-xs text-zinc-500 dark:text-zinc-400">
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
            className="rounded-lg border border-zinc-300 bg-white px-2 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
            aria-label="Link role"
          >
            <option value="EDITOR">Editor</option>
            <option value="VIEWER">Viewer</option>
          </select>
          <button
            type="button"
            onClick={create}
            disabled={busy}
            className="rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 disabled:opacity-50 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
          >
            {busy ? "Creating…" : "Create link"}
          </button>
        </div>
      )}
      {error && (
        <p className="mt-1 text-xs text-red-600 dark:text-red-400" role="alert">
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
    <li className="flex items-center justify-between gap-3 py-3">
      <div className="min-w-0">
        <p className="truncate text-sm font-medium text-zinc-900 dark:text-zinc-100">
          {member.name ?? member.invitedEmail ?? member.email}
          {pending && (
            <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:bg-amber-950 dark:text-amber-400">
              Pending
            </span>
          )}
        </p>
        <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">
          {pending ? "Hasn't signed up yet" : member.email}
        </p>
        {error && <p className="mt-1 text-xs text-red-600 dark:text-red-400">{error}</p>}
      </div>

      <div className="flex shrink-0 items-center gap-3">
        {manageable ? (
          <select
            value={member.role}
            onChange={(e) => changeRole(e.target.value as InvitableRole)}
            className="rounded-lg border border-zinc-300 bg-white px-2 py-1 text-xs text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
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

/** A small, non-interactive role label — used where the role can't be changed. */
export function RoleBadge({ role }: { role: Role }) {
  const label = role.charAt(0) + role.slice(1).toLowerCase();
  return (
    <span className="rounded-full border border-zinc-300 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-zinc-500 dark:border-zinc-700 dark:text-zinc-400">
      {label}
    </span>
  );
}
