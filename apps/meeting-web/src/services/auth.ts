import { useEffect, useCallback } from "react";
import * as api from "@shared/api/client";
import type { AuthUser } from "@shared/api/types";
import { useAuthStore } from "@shared/stores/auth";

interface AuthState {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

/**
 * useAuth surface kept stable for existing consumers; backed by zustand
 * store so cross-page subscribers stay in sync without the bespoke
 * pub-sub.
 */
export function useAuth(): AuthState {
  const user = useAuthStore((s) => s.user);
  const ready = useAuthStore((s) => s.ready);

  useEffect(() => {
    if (ready) return;
    let cancelled = false;
    api.getCurrentUser()
      .then((u) => {
        if (!cancelled) useAuthStore.setState({ user: u, ready: true });
      })
      .catch(() => {
        if (!cancelled) useAuthStore.setState({ user: null, ready: true });
      });
    return () => {
      cancelled = true;
    };
  }, [ready]);

  const login = useCallback(async (username: string, password: string) => {
    const result = await api.login(username, password);
    api.setAuthToken(result.accessToken);
    useAuthStore.setState({ user: result.user, ready: true });
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      api.setAuthToken(null);
      useAuthStore.setState({ user: null, ready: true });
    }
  }, []);

  return {
    user,
    isAuthenticated: user !== null,
    isLoading: !ready,
    login,
    logout,
  };
}

export function resetAuthForTests(): void {
  api.setAuthToken(null);
  useAuthStore.setState({ user: null, ready: false });
}
