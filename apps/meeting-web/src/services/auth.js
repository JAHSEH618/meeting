import { useEffect, useState, useCallback } from "react";
import * as api from "@shared/api/client";
export function useAuth() {
    const [user, setUser] = useState(null);
    const [isLoading, setIsLoading] = useState(true);
    useEffect(() => {
        api.getCurrentUser()
            .then((u) => {
            setUser(u);
        })
            .catch(() => {
            setUser(null);
        })
            .finally(() => {
            setIsLoading(false);
        });
    }, []);
    const login = useCallback(async (username, password) => {
        const result = await api.login(username, password);
        api.setAuthToken(result.accessToken);
        setUser(result.user);
    }, []);
    const logout = useCallback(async () => {
        try {
            await api.logout();
        }
        finally {
            api.setAuthToken(null);
            setUser(null);
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
