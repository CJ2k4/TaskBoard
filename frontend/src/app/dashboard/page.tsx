"use client";

import { useRouter } from "next/navigation";

import { Protected } from "@/components/protected";
import { useAuth } from "@/lib/auth-context";

export default function DashboardPage() {
  return (
    <Protected>
      <DashboardContent />
    </Protected>
  );
}

/**
 * Rendered only once <Protected> confirms an authenticated user, so `user` is safe to
 * read here. For M1 this is intentionally bare — the empty authenticated landing spot the
 * milestone's demo asks for. Boards fill it in M2.
 */
function DashboardContent() {
  const { user, logout } = useAuth();
  const router = useRouter();

  function handleLogout() {
    logout();
    router.replace("/login");
  }

  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <div className="w-full max-w-md rounded-xl border border-zinc-200 bg-white p-8 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
        <p className="text-sm text-zinc-500 dark:text-zinc-400">Signed in as</p>
        <h1 className="mt-1 text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          {user?.name}
        </h1>
        <p className="text-sm text-zinc-500 dark:text-zinc-400">{user?.email}</p>

        <div className="mt-6 rounded-lg border border-dashed border-zinc-300 p-6 text-center dark:border-zinc-700">
          <p className="text-sm text-zinc-500 dark:text-zinc-400">
            Your boards will appear here.
          </p>
          <p className="mt-1 text-xs text-zinc-400 dark:text-zinc-500">
            Coming in Milestone 2.
          </p>
        </div>

        <button
          onClick={handleLogout}
          className="mt-6 w-full rounded-lg border border-zinc-300 px-3 py-2 text-sm font-medium text-zinc-700 transition hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-900"
        >
          Log out
        </button>
      </div>
    </main>
  );
}
