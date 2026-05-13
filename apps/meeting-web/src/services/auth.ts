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

let currentUser: AuthUser | null = null;
let initialized = false;
const subscribers = new Set<() => void>();

function emit() {
  subscribers.forEach((subscriber) => subscriber());
}

function setCurrentUser(user: AuthUser | null) {
  currentUser = user;
  emit();
}

export function useAuth(): AuthState {
  const [user, setUserState] = useState<AuthUser | null>(currentUser);
  const [isLoading, setIsLoading] = useState(!initialized);

  useEffect(() => {
    const sync = () => {
      setUserState(currentUser);
    };
    subscribers.add(sync);
    return () => {
      subscribers.delete(sync);
    };
  }, []);

  useEffect(() => {
    if (initialized) return;
    api.getCurrentUser()
      .then((u) => {
        setCurrentUser(u);
      })
      .catch(() => {
        setCurrentUser(null);
      })
      .finally(() => {
        initialized = true;
        setIsLoading(false);
      });
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await api.login(username, password);
    api.setAuthToken(result.accessToken);
    initialized = true;
    setCurrentUser(result.user);
    setIsLoading(false);
  }, []);

  const logout = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      api.setAuthToken(null);
      initialized = true;
      setCurrentUser(null);
      setIsLoading(false);
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

export function resetAuthForTests() {
  api.setAuthToken(null);
  currentUser = null;
  initialized = false;
  emit();
}
