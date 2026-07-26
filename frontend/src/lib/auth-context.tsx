"use client";

/**
 * The client-side session: who's logged in, and the token used to prove it.
 *
 * Token strategy (a deliberate learning-time trade-off — see LEARNING.md):
 *  - The **access token** lives only in memory (React state + a ref). It vanishes on
 *    reload, which is the point — a short-lived credential shouldn't linger on disk.
 *  - The **refresh token** is persisted to `localStorage`, so a reload can exchange it
 *    for a fresh access token and keep the user logged in. This is XSS-exposed; we chose
 *    it over httpOnly cookies to keep the flow simple while learning.
 *
 * The `status` field is a three-state machine, and the third state matters: on first load
 * we don't yet know if the stored refresh token is still valid, so we sit in `loading`
 * until the bootstrap call resolves. Without it, a protected page would briefly see
 * "no user" and wrongly bounce a logged-in user to /login.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { ApiError, apiFetch, authApi, type User } from "@/lib/api";

const REFRESH_KEY = "taskboard.refreshToken";

type Status = "loading" | "authenticated" | "unauthenticated";

type AuthContextValue = {
  status: Status;
  user: User | null;
  /** Current access token, or null. Read this when making authenticated calls. */
  getAccessToken: () => string | null;
  /**
   * Make an authenticated API call. Attaches the access token, and on a 401 transparently
   * refreshes once and retries — so a token that expired mid-session doesn't surface as an
   * error to the caller. If the refresh itself fails, the session is cleared and the error
   * rethrown (the route guard then redirects to /login).
   */
  authFetch: <T>(path: string, init?: RequestInit) => Promise<T>;
  /** Sign in with a Google ID token (from Google Identity Services) — the only sign-in path. */
  loginWithGoogle: (idToken: string) => Promise<void>;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>("loading");
  const [user, setUser] = useState<User | null>(null);
  // The access token is also held in a ref so callers can read the latest value
  // synchronously (not tied to a render), which authenticated fetches will want.
  const accessTokenRef = useRef<string | null>(null);

  /** Commit a fresh auth result: access token in memory, refresh token to storage. */
  const applySession = useCallback(
    (accessToken: string, refreshToken: string, nextUser: User) => {
      accessTokenRef.current = accessToken;
      localStorage.setItem(REFRESH_KEY, refreshToken);
      setUser(nextUser);
      setStatus("authenticated");
    },
    [],
  );

  const clearSession = useCallback(() => {
    accessTokenRef.current = null;
    localStorage.removeItem(REFRESH_KEY);
    setUser(null);
    setStatus("unauthenticated");
  }, []);

  // Bootstrap once on mount: try to restore a session from the stored refresh token.
  // Exchange it for a fresh pair — the backend rotates the refresh token, so we save the new one
  // that comes back. Having nothing stored resolves to null and lands in the same place as a
  // failed refresh (expired/invalid/tampered): logged out. Routing both paths through the promise
  // is what keeps the effect body itself free of synchronous state updates.
  useEffect(() => {
    let cancelled = false;
    const stored = localStorage.getItem(REFRESH_KEY);
    Promise.resolve(stored ? authApi.refresh(stored) : null)
      .then((res) => {
        if (cancelled) return;
        if (res) applySession(res.accessToken, res.refreshToken, res.user);
        else clearSession();
      })
      .catch(() => {
        if (!cancelled) clearSession();
      });
    return () => {
      cancelled = true;
    };
  }, [applySession, clearSession]);

  const loginWithGoogle = useCallback(
    async (idToken: string) => {
      const res = await authApi.google(idToken);
      applySession(res.accessToken, res.refreshToken, res.user);
    },
    [applySession],
  );

  const logout = useCallback(() => clearSession(), [clearSession]);

  const getAccessToken = useCallback(() => accessTokenRef.current, []);

  /** apiFetch, but with the current access token attached as a Bearer header. */
  const fetchWithToken = useCallback(
    <T,>(path: string, init: RequestInit): Promise<T> =>
      apiFetch<T>(path, {
        ...init,
        headers: {
          ...init.headers,
          Authorization: `Bearer ${accessTokenRef.current}`,
        },
      }),
    [],
  );

  const authFetch = useCallback(
    async <T,>(path: string, init: RequestInit = {}): Promise<T> => {
      try {
        return await fetchWithToken<T>(path, init);
      } catch (err) {
        // Only a 401 is worth a refresh attempt; anything else is the caller's to handle.
        if (!(err instanceof ApiError) || err.status !== 401) {
          throw err;
        }
        const stored = localStorage.getItem(REFRESH_KEY);
        if (!stored) {
          clearSession();
          throw err;
        }
        try {
          const res = await authApi.refresh(stored);
          applySession(res.accessToken, res.refreshToken, res.user);
        } catch {
          // The refresh token is dead too — this is a real logout, not a blip.
          clearSession();
          throw err;
        }
        // Retry once with the freshly minted access token.
        return fetchWithToken<T>(path, init);
      }
    },
    [fetchWithToken, applySession, clearSession],
  );

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        getAccessToken,
        authFetch,
        loginWithGoogle,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

/** Read the auth session. Throws if used outside <AuthProvider> (a wiring mistake). */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an <AuthProvider>");
  }
  return ctx;
}
