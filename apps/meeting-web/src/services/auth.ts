// Auth service — token management, login/logout, session refresh

import { useEffect, useState, useCallback } from "react";
import * as api from "@shared/api/client";
import type { AuthUser } from "@shared/api/types";

interface AuthState {
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

let cachedState: AuthState | null = null;

export function useAuth(): AuthState {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const stored = sessionStorage.getItem("auth");
    if (stored) {
      try {
        const parsed: { token: string; user: AuthUser } = JSON.parse(stored);
        api.setAuthToken(parsed.token);
        setUser(parsed.user);
      } catch {
        sessionStorage.removeItem("auth");
      }
    }
    setIsLoading(false);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await api.login(username, password);
    api.setAuthToken(result.accessToken);
    setUser(result.user);
    sessionStorage.setItem(
      "auth",
      JSON.stringify({ token: result.accessToken, user: result.user }),
    );
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      api.setAuthToken(null);
      setUser(null);
      sessionStorage.removeItem("auth");
    }
  }, []);

  return {
    user,
    isAuthenticated: !!user,
    isLoading,
    login,
    logout,
  };
}
