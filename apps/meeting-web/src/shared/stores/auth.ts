import { create } from "zustand";
import type { AuthUser } from "@shared/api/types";

interface AuthState {
  user: AuthUser | null;
  ready: boolean;
  setUser: (user: AuthUser | null) => void;
  markReady: () => void;
  reset: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  ready: false,
  setUser: (user) => set({ user }),
  markReady: () => set({ ready: true }),
  reset: () => set({ user: null, ready: false }),
}));

export function selectUser(s: AuthState): AuthUser | null { return s.user; }
export function selectReady(s: AuthState): boolean { return s.ready; }
export function selectIsAuthenticated(s: AuthState): boolean { return s.user !== null; }
