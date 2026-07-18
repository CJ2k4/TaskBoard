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

import { authApi, type User } from "@/lib/api";

const REFRESH_KEY = "taskboard.refreshToken";

type Status = "loading" | "authenticated" | "unauthenticated";

type AuthContextValue = {
  status: Status;
  user: User | null;
  /** Current access token, or null. Read this when making authenticated calls. */
  getAccessToken: () => string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string) => Promise<void>;
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
  useEffect(() => {
    const stored = localStorage.getItem(REFRESH_KEY);
    if (!stored) {
      setStatus("unauthenticated");
      return;
    }
    // Exchange the stored refresh token for a fresh pair. The backend rotates the
    // refresh token, so we save the new one that comes back.
    authApi
      .refresh(stored)
      .then((res) => applySession(res.accessToken, res.refreshToken, res.user))
      .catch(() => clearSession()); // expired/invalid/tampered → treat as logged out
  }, [applySession, clearSession]);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await authApi.login({ email, password });
      applySession(res.accessToken, res.refreshToken, res.user);
    },
    [applySession],
  );

  const register = useCallback(
    async (name: string, email: string, password: string) => {
      const res = await authApi.register({ name, email, password });
      applySession(res.accessToken, res.refreshToken, res.user);
    },
    [applySession],
  );

  const logout = useCallback(() => clearSession(), [clearSession]);

  const getAccessToken = useCallback(() => accessTokenRef.current, []);

  return (
    <AuthContext.Provider
      value={{ status, user, getAccessToken, login, register, logout }}
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
