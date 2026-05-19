import { useEffect, useState } from "react";
import { authStore, consumeFragmentToken, redirectToLogin } from "@/shared/auth/store";

export function useAuth(): { token: string | null; ready: boolean } {
  const [token, setToken] = useState<string | null>(authStore.get());
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!authStore.get()) consumeFragmentToken();
    const next = authStore.get();
    setToken(next);
    setReady(true);
    if (!next) {
      // Don't bounce in test env (jsdom) — make this opt-in via location.
      if (typeof window !== "undefined" && !window.location.href.includes("playwright-skip-auth")) {
        redirectToLogin();
      }
    }
    return authStore.subscribe((t) => setToken(t));
  }, []);

  return { token, ready };
}
