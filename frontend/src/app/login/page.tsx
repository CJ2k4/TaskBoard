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
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <div className="w-full max-w-sm rounded-xl border border-zinc-200 bg-white p-8 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
        <h1 className="text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          Sign in to TaskBoard
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Continue with your Google account.
        </p>

        <div className="mt-6 flex justify-center">
          <GoogleSignInButton onCredential={handleGoogle} />
        </div>

        {error && (
          <p className="mt-4 text-center text-sm text-red-600 dark:text-red-400" role="alert">
            {error}
          </p>
        )}
      </div>
    </main>
  );
}

/** The page frame shown while the client-side form (which reads the URL) hydrates. */
function AuthShell() {
  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <p className="text-sm text-zinc-500 dark:text-zinc-400">Loading…</p>
    </main>
  );
}
