/**
 * Browser-side client for the Spring backend's auth API.
 *
 * Unlike `health.ts` (which runs server-side during render), these calls run in the
 * browser: the JWT lives in client memory, so the component that holds it is the one
 * that must make the request. That's why the URL comes from `NEXT_PUBLIC_API_URL`
 * (shipped to the client) rather than the server-only `API_BASE_URL`.
 *
 * The backend already allows this cross-origin call — CORS is configured for
 * http://localhost:3000 in `auth/security/SecurityConfig.java`.
 */

const API_URL = process.env.NEXT_PUBLIC_API_URL;

/** Public shape of a user, mirroring the backend's `UserResponse` DTO. */
export type User = {
  id: string;
  email: string;
  name: string;
  imageUrl: string | null;
  createdAt: string;
};

/** Response of register/login/refresh, mirroring the backend's `AuthResponse`. */
export type AuthResponse = {
  user: User;
  accessToken: string;
  refreshToken: string;
};

/** One invalid field, from the backend's validation error body. */
export type FieldError = { field: string; message: string };

/**
 * A failed API call, carrying enough of the backend's `ApiError` body for a form to
 * react: the HTTP `status` (e.g. 401 vs 409), a human `message`, and per-field errors
 * on a 400. A `status` of 0 means the request never reached the server (network down).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: FieldError[];

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/** The backend's standard error JSON (`common/dto/ApiError`). All fields optional here. */
type ApiErrorBody = {
  status?: number;
  message?: string;
  fieldErrors?: FieldError[];
};

/**
 * Core fetch: sends JSON, parses JSON, and turns any non-2xx into a thrown {@link ApiError}
 * so callers only deal with success values plus one error type. A total network failure
 * (backend down) also becomes an `ApiError` with status 0 — never an unhandled rejection.
 */
export async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!API_URL) {
    throw new ApiError(0, "NEXT_PUBLIC_API_URL is not set");
  }

  let res: Response;
  try {
    res = await fetch(`${API_URL}${path}`, {
      ...options,
      headers: { "Content-Type": "application/json", ...options.headers },
    });
  } catch {
    // DNS failure, connection refused, CORS rejection, etc. — no HTTP status exists.
    throw new ApiError(0, "Can't reach the server. Is the backend running?");
  }

  if (!res.ok) {
    // Best-effort parse of the standard error body; fall back to the status line.
    const body = (await res.json().catch(() => ({}))) as ApiErrorBody;
    throw new ApiError(
      res.status,
      body.message ?? `Request failed (HTTP ${res.status})`,
      body.fieldErrors ?? [],
    );
  }

  // 204 No Content or an empty body: nothing to parse.
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export type RegisterBody = { email: string; password: string; name: string };
export type LoginBody = { email: string; password: string };

/** Typed wrappers for each auth endpoint. */
export const authApi = {
  register: (body: RegisterBody) =>
    apiFetch<AuthResponse>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  login: (body: LoginBody) =>
    apiFetch<AuthResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  refresh: (refreshToken: string) =>
    apiFetch<AuthResponse>("/api/auth/refresh", {
      method: "POST",
      body: JSON.stringify({ refreshToken }),
    }),

  me: (accessToken: string) =>
    apiFetch<User>("/api/me", {
      headers: { Authorization: `Bearer ${accessToken}` },
    }),
};
