import Link from "next/link";

import { checkBackendHealth } from "@/lib/health";

// Public landing. Stays a Server Component: it fetches the backend health during render
// (server-to-server, no CORS) for a fast first paint, and links into the auth flow. The
// health card is the M0 artifact — kept as a live "is everything wired?" indicator.
export default async function Home() {
  const health = await checkBackendHealth();

  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <div className="w-full max-w-sm rounded-xl border border-zinc-200 bg-white p-8 text-center shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
        <h1 className="text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          TaskBoard
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Real-time collaborative Kanban.
        </p>

        <div className="mt-6 flex flex-col gap-3">
          <Link
            href="/login"
            className="rounded-lg bg-zinc-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            Sign in
          </Link>
        </div>

        <div className="mt-6 flex items-center justify-center gap-2 border-t border-zinc-100 pt-4 dark:border-zinc-800">
          <span
            className={`inline-block h-2 w-2 rounded-full ${
              health.ok ? "bg-green-500" : "bg-red-500"
            }`}
            aria-hidden
          />
          <span
            className={`text-xs font-medium ${
              health.ok
                ? "text-green-700 dark:text-green-400"
                : "text-red-700 dark:text-red-400"
            }`}
          >
            {health.ok ? `Backend: ${health.status}` : health.error}
          </span>
        </div>
      </div>
    </main>
  );
}
