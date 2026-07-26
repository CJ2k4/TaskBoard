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
  // Keep the ref pointing at the latest callback without re-initializing GIS. The write happens
  // in an effect rather than during render so rendering stays free of side effects; GIS only
  // invokes it on user interaction, long after this has run.
  useEffect(() => {
    onCredentialRef.current = onCredential;
  }, [onCredential]);

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

  if (!CLIENT_ID) {
    return (
      <p className="text-center text-sm text-red-600 dark:text-red-400">
        Google sign-in isn&apos;t configured (set NEXT_PUBLIC_GOOGLE_CLIENT_ID).
      </p>
    );
  }

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
