"use client";

import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { withNext } from "@/lib/next-url";
import { PageNote } from "@/components/page-note";

/**
 * There's no separate sign-up anymore — Google sign-in auto-creates the account on first use.
 * This route is kept only so existing links/invites don't 404; it forwards to /login (carrying
 * any `next` target so a fresh sign-up via an invite link still lands on that board).
 */
export default function RegisterPage() {
  return (
    <Suspense fallback={<RedirectShell />}>
      <RegisterRedirect />
    </Suspense>
  );
}

function RegisterRedirect() {
  const router = useRouter();
  const searchParams = useSearchParams();

  useEffect(() => {
    router.replace(withNext("/login", searchParams.get("next")));
  }, [router, searchParams]);

  return <RedirectShell />;
}

function RedirectShell() {
  return <PageNote busy>Redirecting to sign in…</PageNote>;
}
