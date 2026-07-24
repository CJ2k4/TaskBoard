"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { safeNext, withNext } from "@/lib/next-url";
import { GoogleAuthSection } from "@/components/google-sign-in-button";

// useSearchParams forces a client-side bailout, which Next requires under a Suspense boundary.
export default function RegisterPage() {
  return (
    <Suspense fallback={<AuthShell />}>
      <RegisterForm />
    </Suspense>
  );
}

function RegisterForm() {
  const { status, register, loginWithGoogle } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  // A fresh sign-up via an invite link should land on that board, not the empty dashboard.
  const nextParam = searchParams.get("next");
  const redirectTo = safeNext(nextParam);

  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  // Per-field messages from a 400 (e.g. "password must be 8–72 characters"),
  // keyed by field name so each shows under its own input.
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (status === "authenticated") {
      router.replace(redirectTo);
    }
  }, [status, router, redirectTo]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register(name, email, password);
      router.replace(redirectTo);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.fieldErrors.length > 0) {
          setFieldErrors(
            Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])),
          );
        } else {
          // 409 (email taken), 0 (backend down), etc.
          setError(err.message);
        }
      } else {
        setError("Something went wrong.");
      }
      setSubmitting(false);
    }
  }

  async function handleGoogle(idToken: string) {
    setError(null);
    setFieldErrors({});
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
          Create account
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Start collaborating on TaskBoard.
        </p>

        <form onSubmit={handleSubmit} className="mt-6 flex flex-col gap-4">
          <Field
            label="Name"
            type="text"
            autoComplete="name"
            value={name}
            onChange={setName}
            error={fieldErrors.name}
          />
          <Field
            label="Email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={setEmail}
            error={fieldErrors.email}
          />
          <Field
            label="Password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={setPassword}
            error={fieldErrors.password}
            hint="At least 8 characters."
          />

          {error && (
            <p className="text-sm text-red-600 dark:text-red-400" role="alert">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="mt-2 rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {submitting ? "Creating…" : "Create account"}
          </button>
        </form>

        <GoogleAuthSection onCredential={handleGoogle} />

        <p className="mt-6 text-center text-sm text-zinc-500 dark:text-zinc-400">
          Already have an account?{" "}
          <Link
            href={withNext("/login", nextParam)}
            className="font-medium text-zinc-900 underline underline-offset-2 dark:text-zinc-100"
          >
            Log in
          </Link>
        </p>
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

/** A labelled text input with an optional hint and per-field error message. */
function Field({
  label,
  type,
  autoComplete,
  value,
  onChange,
  error,
  hint,
}: {
  label: string;
  type: string;
  autoComplete: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-sm font-medium text-zinc-700 dark:text-zinc-300">
        {label}
      </span>
      <input
        type={type}
        required
        autoComplete={autoComplete}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border border-zinc-300 bg-white px-3 py-2 text-sm text-zinc-900 outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100"
      />
      {error ? (
        <span className="text-xs text-red-600 dark:text-red-400">{error}</span>
      ) : hint ? (
        <span className="text-xs text-zinc-400 dark:text-zinc-500">{hint}</span>
      ) : null}
    </label>
  );
}
