/**
 * Server-side backend health check.
 *
 * Runs during render in a Server Component (never in the browser), so it calls the
 * Spring backend server-to-server and skips the CORS preflight. `API_BASE_URL` is a
 * server-only env var (no NEXT_PUBLIC_ prefix), so the URL never ships to the client.
 */

/** Shape of the backend's GET /api/health response. */
export type HealthStatus = { status: string };

export type HealthResult =
  | { ok: true; status: string }
  | { ok: false; error: string };

export async function checkBackendHealth(): Promise<HealthResult> {
  const baseUrl = process.env.API_BASE_URL;
  if (!baseUrl) {
    return { ok: false, error: "API_BASE_URL is not set" };
  }

  try {
    // no-store: a health check must never be served from cache.
    const res = await fetch(`${baseUrl}/api/health`, { cache: "no-store" });
    if (!res.ok) {
      return { ok: false, error: `Backend returned HTTP ${res.status}` };
    }
    const body = (await res.json()) as HealthStatus;
    return { ok: true, status: body.status };
  } catch {
    // Backend down / unreachable — surface a friendly state, not a crash.
    return { ok: false, error: "Backend unreachable" };
  }
}
