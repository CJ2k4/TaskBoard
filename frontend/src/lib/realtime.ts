"use client";

/**
 * The client half of M5: one STOMP-over-WebSocket connection per open board, feeding
 * `BoardEvent`s to the page. All the socket lifecycle — connect, authenticate, reconnect, tear
 * down — lives here so the board page only sees "here's an event" and "here's the connection
 * state".
 *
 * How auth works, and why it's not on the URL: the browser WebSocket API can't set headers on
 * the HTTP upgrade, so the handshake is anonymous (the backend permits `/ws/**`) and identity is
 * proved one frame later, on STOMP CONNECT, via an `Authorization: Bearer` native header. We
 * read the access token in `beforeConnect` — freshly, every (re)connect — rather than closing
 * over it, so a token refreshed mid-session is picked up automatically.
 *
 * The client never SENDs. Every write goes through the REST API; this socket is a one-way feed.
 */

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";

import { useAuth } from "@/lib/auth-context";
import type { BoardEvent } from "@/lib/board-events";

/** "connecting" before the first frame lands; "live" while subscribed; "offline" when dropped. */
export type ConnectionState = "connecting" | "live" | "offline";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "";
// http(s)://host → ws(s)://host, then the STOMP endpoint. `replace` on the leading http covers
// both http→ws and https→wss.
const BROKER_URL = `${API_URL.replace(/^http/, "ws")}/ws`;

export function useBoardEvents({
  boardId,
  enabled,
  onEvent,
  onResync,
}: {
  boardId: string;
  /** Only connect once the board has loaded — before that there's nothing to keep in sync. */
  enabled: boolean;
  onEvent: (event: BoardEvent) => void;
  /** Called on a *re*-connect (not the first): refetch, since the broker has no replay. */
  onResync: () => void;
}): ConnectionState {
  const { getAccessToken, authFetch } = useAuth();
  const [state, setState] = useState<ConnectionState>("connecting");

  // The callbacks are fresh closures on every render. Holding them in refs — and depending only
  // on `boardId`/`enabled` below — means the socket is built once per board, not rebuilt on
  // every keystroke in a composer (which would drop and re-establish the connection each time).
  const onEventRef = useRef(onEvent);
  const onResyncRef = useRef(onResync);
  const authFetchRef = useRef(authFetch);
  const getTokenRef = useRef(getAccessToken);
  useEffect(() => {
    onEventRef.current = onEvent;
    onResyncRef.current = onResync;
    authFetchRef.current = authFetch;
    getTokenRef.current = getAccessToken;
  });

  useEffect(() => {
    if (!enabled) return;

    // Distinguishes the first successful connect from later ones, so only a genuine *re*-connect
    // triggers a resync. Scoped to this effect run so remounting a board starts clean.
    let hasConnected = false;

    const client = new Client({
      brokerURL: BROKER_URL,
      reconnectDelay: 3000,
      beforeConnect: () => {
        const token = getTokenRef.current();
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        if (hasConnected) {
          // We missed whatever happened while disconnected; the broker won't replay it, so the
          // only honest fix is to reload the whole board.
          onResyncRef.current();
        }
        hasConnected = true;
        setState("live");
        client.subscribe(`/topic/board/${boardId}`, (message) => {
          try {
            onEventRef.current(JSON.parse(message.body) as BoardEvent);
          } catch {
            // A frame we can't parse is not worth tearing the session down for; skip it.
          }
        });
      },
      onWebSocketClose: () => setState("offline"),
      onStompError: () => {
        // The backend answers a bad/expired CONNECT or SUBSCRIBE with an ERROR frame and closes.
        // Most often the 15-minute access token simply aged out: poke authFetch, which refreshes
        // transparently on a 401 (and, if the refresh token is dead too, clears the session so
        // <Protected> sends us to /login). stompjs then auto-reconnects and beforeConnect reads
        // the fresh token.
        authFetchRef.current("/api/me").catch(() => {});
        setState("offline");
      },
    });

    client.activate();
    return () => {
      // deactivate() is idempotent and safe under StrictMode's dev double-mount: the throwaway
      // first client is torn down before the real one connects, so no duplicate subscriptions.
      client.deactivate();
    };
  }, [boardId, enabled]);

  return state;
}
