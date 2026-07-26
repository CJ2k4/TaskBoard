import Link from "next/link";

/**
 * The app's brand mark: a rounded "T" tile beside the "TaskBoard" wordmark, linking home to the
 * dashboard. Shared by the top nav bar and the in-board header so the brand reads identically
 * wherever it appears.
 *
 * The tile tilts and lifts on hover — a small, cheap sign that the whole mark is a link.
 */
export function BrandMark() {
  return (
    <Link
      href="/dashboard"
      className="group flex items-center gap-2"
      aria-label="TaskBoard — go to dashboard"
    >
      <span className="relative flex h-7 w-7 items-center justify-center overflow-hidden rounded-lg bg-gradient-to-br from-brand-500 to-violet-600 text-sm font-bold text-white shadow-[var(--shadow-brand)] transition-all duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)] group-hover:-rotate-6 group-hover:scale-110">
        T
      </span>
      <span className="text-base font-semibold tracking-tight text-zinc-900 transition-colors duration-200 group-hover:text-brand-700 dark:text-zinc-50">
        TaskBoard
      </span>
    </Link>
  );
}
