"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { safeNext } from "@/lib/next-url";
import { GoogleSignInButton } from "@/components/google-sign-in-button";

// useSearchParams forces a client-side bailout, which Next requires under a Suspense boundary.
export default function LoginPage() {
  return (
    <Suspense fallback={<AuthShell />}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const { status, loginWithGoogle } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  // Where to go after logging in — the page we were bounced from, or the dashboard.
  const redirectTo = safeNext(searchParams.get("next"));

  const [error, setError] = useState<string | null>(null);

  // Already logged in (e.g. navigated here by hand)? Skip the sign-in.
  useEffect(() => {
    if (status === "authenticated") {
      router.replace(redirectTo);
    }
  }, [status, router, redirectTo]);

  async function handleGoogle(idToken: string) {
    setError(null);
    try {
      await loginWithGoogle(idToken);
      router.replace(redirectTo);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Google sign-in failed.");
    }
  }

  return (
    <AuthBackdrop>
      <div className="animate-spring-in w-full max-w-sm rounded-2xl border border-line bg-paper p-8 shadow-[var(--shadow-lg)]">
        <span className="mx-auto mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-brand-500 to-violet-600 text-lg font-bold text-white shadow-[var(--shadow-brand)]">
          T
        </span>
        <h1 className="text-center text-xl font-bold tracking-tight text-zinc-900">
          Sign in to TaskBoard
        </h1>
        <p className="mt-1 text-center text-sm text-zinc-500">
          Continue with your Google account.
        </p>

        <div className="mt-6 flex justify-center">
          <GoogleSignInButton onCredential={handleGoogle} />
        </div>

        {error && (
          <p className="animate-pop-in mt-4 rounded-lg bg-red-50 px-3 py-2 text-center text-sm text-red-700" role="alert">
            {error}
          </p>
        )}
      </div>
    </AuthBackdrop>
  );
}

/**
 * The shared frame behind every auth screen: the landing page's ambient colour fields, so
 * arriving from `/` doesn't feel like landing in a different product.
 */
export function AuthBackdrop({ children }: { children: React.ReactNode }) {
  return (
    <main className="relative flex flex-1 items-center justify-center overflow-hidden bg-canvas p-8">
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="animate-float-slow absolute -left-32 -top-32 h-[26rem] w-[26rem] rounded-full bg-brand-300/25 blur-3xl" />
        <div
          className="animate-float-slow absolute -bottom-32 -right-24 h-[24rem] w-[24rem] rounded-full bg-violet-300/20 blur-3xl"
          style={{ animationDelay: "-6s" }}
        />
      </div>
      <div className="relative w-full max-w-sm">{children}</div>
    </main>
  );
}

/** The page frame shown while the client-side form (which reads the URL) hydrates. */
function AuthShell() {
  return (
    <AuthBackdrop>
      <div className="animate-fade-in rounded-2xl border border-line bg-paper p-8">
        <div className="skeleton mx-auto h-12 w-12 rounded-2xl" />
        <div className="skeleton mx-auto mt-5 h-5 w-2/3 rounded" />
        <div className="skeleton mx-auto mt-2 h-3 w-1/2 rounded" />
        <div className="skeleton mt-6 h-10 w-full rounded-lg" />
      </div>
    </AuthBackdrop>
  );
}
