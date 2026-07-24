"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Script from "next/script";

const CLIENT_ID = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

// Minimal typing for the slice of Google Identity Services we use (no extra @types dep).
type GoogleCredentialResponse = { credential: string };
type GoogleIdApi = {
  initialize: (config: {
    client_id: string;
    callback: (res: GoogleCredentialResponse) => void;
  }) => void;
  renderButton: (parent: HTMLElement, options: Record<string, unknown>) => void;
};
declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } };
  }
}

/**
 * The "Continue with Google" button, backed by Google Identity Services. It loads the GIS
 * script, initializes it with our public client id, and renders Google's official button.
 * When the user picks an account, GIS hands back an **ID token** (`credential`), which we pass
 * up via `onCredential` for the caller to exchange at the backend.
 *
 * Renders nothing when `NEXT_PUBLIC_GOOGLE_CLIENT_ID` is unset, so the app works with Google
 * unconfigured. The `onCredential` callback is held in a ref so re-renders don't re-init GIS.
 */
export function GoogleSignInButton({
  onCredential,
}: {
  onCredential: (idToken: string) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  const onCredentialRef = useRef(onCredential);
  onCredentialRef.current = onCredential;

  const render = useCallback(() => {
    if (!CLIENT_ID || !window.google || !containerRef.current) return;
    window.google.accounts.id.initialize({
      client_id: CLIENT_ID,
      callback: (res) => onCredentialRef.current(res.credential),
    });
    window.google.accounts.id.renderButton(containerRef.current, {
      theme: "outline",
      size: "large",
      text: "continue_with",
      width: 320,
    });
  }, []);

  useEffect(() => {
    if (ready) render();
  }, [ready, render]);

  if (!CLIENT_ID) return null;

  return (
    <>
      <Script
        src="https://accounts.google.com/gsi/client"
        strategy="afterInteractive"
        onReady={() => setReady(true)}
      />
      <div ref={containerRef} className="flex justify-center" />
    </>
  );
}

/**
 * The "Continue with Google" block for the auth pages: an "or" divider above the Google button.
 * Renders nothing when Google isn't configured, so the divider never appears on its own.
 */
export function GoogleAuthSection({
  onCredential,
}: {
  onCredential: (idToken: string) => void;
}) {
  if (!CLIENT_ID) return null;

  return (
    <div className="mt-6">
      <div className="mb-4 flex items-center gap-3">
        <span className="h-px flex-1 bg-zinc-200 dark:bg-zinc-800" />
        <span className="text-xs font-medium uppercase tracking-wide text-zinc-400">or</span>
        <span className="h-px flex-1 bg-zinc-200 dark:bg-zinc-800" />
      </div>
      <GoogleSignInButton onCredential={onCredential} />
    </div>
  );
}
