"use client";

import { Suspense, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { withNext } from "@/lib/next-url";

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
  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">Redirecting…</p>
    </main>
  );
}
