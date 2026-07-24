import Link from "next/link";

/**
 * The app's brand mark: a rounded "T" tile beside the "TaskBoard" wordmark, linking home to the
 * dashboard. Shared by the top nav bar and the in-board header so the brand reads identically
 * wherever it appears.
 */
export function BrandMark() {
  return (
    <Link
      href="/dashboard"
      className="flex items-center gap-2 transition hover:opacity-70"
      aria-label="TaskBoard — go to dashboard"
    >
      <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">
        T
      </span>
      <span className="text-base font-semibold tracking-tight text-zinc-900 dark:text-zinc-50">
        TaskBoard
      </span>
    </Link>
  );
}
