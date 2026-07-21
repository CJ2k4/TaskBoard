/**
 * A compact "how long ago" formatter for timestamps the API returns as ISO-8601 strings.
 * Deliberately coarse — a feed wants "5m", "2h", "3d", not a running clock — and it never shows
 * a future time (clock skew just reads as "now").
 */
export function timeAgo(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  const seconds = Math.max(0, Math.floor((now - then) / 1000));

  if (seconds < 45) return "just now";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.floor(days / 7);
  if (weeks < 5) return `${weeks}w ago`;

  // Beyond a month, an absolute date reads better than an ever-growing count.
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
