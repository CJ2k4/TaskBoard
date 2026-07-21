/**
 * Helpers for the "come back here after you log in" flow (M6). When `<Protected>` bounces an
 * unauthenticated visitor to `/login`, it stashes where they were headed in a `next` query param;
 * the login/register pages send them there on success instead of the default dashboard.
 *
 * The one rule that matters: `next` is attacker-influenced (it's in a URL anyone can craft), so a
 * redirect target must be a *same-origin relative path*. Anything else — an absolute URL, a
 * protocol-relative `//evil.com`, a backslash trick — is dropped in favour of the fallback. That
 * closes the open-redirect hole where a login link could whisk a user off to a look-alike site.
 */

/** A safe same-origin path to redirect to, or the fallback if `raw` is missing or off-site. */
export function safeNext(raw: string | null | undefined, fallback = "/dashboard"): string {
  if (!raw) return fallback;
  // Must be a single leading slash: rejects "//host", "/\\host", and absolute "https://…".
  if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
    return fallback;
  }
  return raw;
}

/** Append a `next` param to an auth route — only when one is actually present. */
export function withNext(base: string, next: string | null | undefined): string {
  return next ? `${base}?next=${encodeURIComponent(next)}` : base;
}
