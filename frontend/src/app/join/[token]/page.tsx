"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { acceptInviteLink } from "@/lib/members";
import { Protected } from "@/components/protected";
import { PageNote } from "@/components/page-note";

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
    return <PageNote busy>Joining board…</PageNote>;
  }
  if (state === "invalid") {
    return (
      <PageNote>
        This invite link is no longer valid.{" "}
        <Link href="/dashboard">Go to your boards</Link>
      </PageNote>
    );
  }
  return (
    <PageNote>
      Something went wrong joining this board.{" "}
      <Link href="/dashboard">Back to your boards</Link>
    </PageNote>
  );
}
