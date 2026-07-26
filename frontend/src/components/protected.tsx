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
import { usePathname, useRouter } from "next/navigation";

import { useAuth } from "@/lib/auth-context";
import { withNext } from "@/lib/next-url";
import { PageNote } from "@/components/page-note";

export function Protected({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (status === "unauthenticated") {
      // Remember where they were headed, so login can send them back here (e.g. a /join/{token}
      // link opened while signed out). usePathname doesn't trigger the useSearchParams CSR bailout.
      router.replace(withNext("/login", pathname));
    }
  }, [status, router, pathname]);

  if (status === "authenticated") {
    return <>{children}</>;
  }

  // 'loading', or 'unauthenticated' during the brief moment before the redirect fires.
  return <PageNote busy>Signing you in…</PageNote>;
}
