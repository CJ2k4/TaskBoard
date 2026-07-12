import { checkBackendHealth } from "@/lib/health";

// Server Component: fetches during render for a fast first paint (no client
// round-trip, no CORS preflight). This is the M0 "hello world" proving the
// browser, backend, and database all talk to each other.
export default async function Home() {
  const health = await checkBackendHealth();

  return (
    <main className="flex flex-1 items-center justify-center bg-zinc-50 p-8 dark:bg-black">
      <div className="w-full max-w-sm rounded-xl border border-zinc-200 bg-white p-8 text-center shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
        <h1 className="text-xl font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
          TaskBoard
        </h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          Milestone 0 — plumbing check
        </p>

        <div className="mt-6 flex items-center justify-center gap-2">
          <span
            className={`inline-block h-2.5 w-2.5 rounded-full ${
              health.ok ? "bg-green-500" : "bg-red-500"
            }`}
            aria-hidden
          />
          <span
            className={`text-sm font-medium ${
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
