import { useEffect, useRef, useState } from "react";

/**
 * Loads Google Identity Services (GIS) once per page and renders the official
 * "Sign in with Google" button into a container.
 *
 * The browser only ever receives a Google ID token; it is sent to our backend,
 * which re-verifies it with Google before issuing an Oorja session. Nothing
 * here is trusted client-side.
 *
 * Set VITE_GOOGLE_CLIENT_ID to the OAuth 2.0 Web client ID from Google Cloud
 * Console. If it's missing, the hook reports unconfigured and the caller hides
 * the button rather than rendering something broken.
 */

const GIS_SRC = "https://accounts.google.com/gsi/client";

export const GOOGLE_CLIENT_ID: string = import.meta.env.VITE_GOOGLE_CLIENT_ID || "";

let gisPromise: Promise<void> | null = null;

function loadGis(): Promise<void> {
  if (gisPromise) return gisPromise;
  gisPromise = new Promise<void>((resolve, reject) => {
    if (typeof document === "undefined") return reject(new Error("no document"));
    if ((window as any).google?.accounts?.id) return resolve();

    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SRC}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("Google script failed to load")));
      return;
    }

    const script = document.createElement("script");
    script.src = GIS_SRC;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Google script failed to load"));
    document.head.appendChild(script);
  });
  return gisPromise;
}

type Options = {
  /** Called with the Google ID token once the user completes sign-in. */
  onCredential: (idToken: string) => void;
  /** Skip initialising (e.g. the modal is on a different tab). */
  enabled?: boolean;
};

export function useGoogleSignIn({ onCredential, enabled = true }: Options) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);

  // Keep the latest callback without re-initialising GIS on every render.
  const callbackRef = useRef(onCredential);
  callbackRef.current = onCredential;

  useEffect(() => {
    if (!enabled || !GOOGLE_CLIENT_ID) return;
    let cancelled = false;

    loadGis()
      .then(() => {
        if (cancelled || !containerRef.current) return;
        const google = (window as any).google;
        google.accounts.id.initialize({
          client_id: GOOGLE_CLIENT_ID,
          callback: (response: { credential?: string }) => {
            if (response?.credential) callbackRef.current(response.credential);
          }
        });
        google.accounts.id.renderButton(containerRef.current, {
          theme: "outline",
          size: "large",
          width: containerRef.current.clientWidth || 320,
          text: "continue_with",
          shape: "rectangular"
        });
        setReady(true);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => { cancelled = true; };
  }, [enabled]);

  return {
    containerRef,
    ready,
    failed,
    /** False when VITE_GOOGLE_CLIENT_ID isn't set — caller should hide the button entirely. */
    configured: !!GOOGLE_CLIENT_ID
  };
}
