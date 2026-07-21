"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { acceptInviteLink } from "@/lib/members";
import { Protected } from "@/components/protected";

/**
 * The invite-link landing page (M6): `/join/{token}`. Wrapped in `<Protected>`, so a signed-out
 * visitor is bounced to `/login?next=/join/{token}` and returns here once authenticated (works for
 * a brand-new sign-up too). Once we know who they are, we redeem the token server-side and send
 * them to the board — or, if the link is dead, say so calmly instead of dumping an error.
 */
export default function JoinPage() {
  return (
    <Protected>
      <JoinContent />
    </Protected>
  );
}

function JoinContent() {
  const { authFetch } = useAuth();
  const router = useRouter();
  const { token } = useParams<{ token: string }>();
  const [state, setState] = useState<"joining" | "invalid" | "error">("joining");

  // Redeem exactly once — guards against StrictMode's dev double-mount firing two POSTs.
  const ran = useRef(false);
  useEffect(() => {
    if (ran.current) return;
    ran.current = true;
    acceptInviteLink(authFetch, token)
      .then((result) => router.replace(`/boards/${result.boardId}`))
      .catch((err) => {
        // 404 = unknown or disabled token; anything else is an unexpected failure.
        setState(err instanceof ApiError && err.status === 404 ? "invalid" : "error");
      });
  }, [authFetch, token, router]);

  if (state === "joining") {
    return <CenteredNote>Joining board…</CenteredNote>;
  }
  if (state === "invalid") {
    return (
      <CenteredNote>
        This invite link is no longer valid.{" "}
        <Link href="/dashboard" className="underline">
          Go to your boards
        </Link>
      </CenteredNote>
    );
  }
  return (
    <CenteredNote>
      Something went wrong joining this board.{" "}
      <Link href="/dashboard" className="underline">
        Back to your boards
      </Link>
    </CenteredNote>
  );
}

function CenteredNote({ children }: { children: React.ReactNode }) {
  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">{children}</p>
    </main>
  );
}
