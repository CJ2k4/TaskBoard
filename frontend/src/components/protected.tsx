"use client";

/**
 * Gate for pages that require a logged-in user. Wrap a page's content in <Protected>
 * and it will:
 *  - show a quiet loading state while the session is still bootstrapping,
 *  - redirect to /login if the visitor turns out to be unauthenticated,
 *  - render the children only once we're sure they're authenticated.
 *
 * The redirect lives in an effect (not in render) because navigation is a side effect;
 * calling router.replace during render is not allowed.
 */

import { useEffect } from "react";
import { useRouter } from "next/navigation";

import { useAuth } from "@/lib/auth-context";

export function Protected({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "unauthenticated") {
      router.replace("/login");
    }
  }, [status, router]);

  if (status === "authenticated") {
    return <>{children}</>;
  }

  // 'loading', or 'unauthenticated' during the brief moment before the redirect fires.
  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">Loading…</p>
    </main>
  );
}
