import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import {
  login as apiLogin,
  logout as apiLogout,
  me as apiMe,
  refresh as apiRefresh,
  register as apiRegister,
  type MeResponse,
} from "../api/auth";
import { setAccessToken, setOnSessionExpired } from "../api/client";

type AuthStatus = "loading" | "authenticated" | "unauthenticated";

interface AuthContextValue {
  user: MeResponse | null;
  status: AuthStatus;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, name: string, birthYear: number) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");

  const loadUser = useCallback(async () => {
    const meResponse = await apiMe();
    setUser(meResponse);
    setStatus("authenticated");
  }, []);

  const clearSession = useCallback(() => {
    setAccessToken(null);
    setUser(null);
    setStatus("unauthenticated");
  }, []);

  useEffect(() => {
    // A 401 whose silent refresh also failed means the session is genuinely gone (expired or
    // revoked refresh cookie) — reset state immediately rather than leaving stale
    // "authenticated" status around until something else happens to notice.
    setOnSessionExpired(clearSession);
    return () => setOnSessionExpired(null);
  }, [clearSession]);

  useEffect(() => {
    // The access token is memory-only — on a fresh page load, silently try the refresh
    // cookie to re-attach the session before deciding the user is signed out.
    let cancelled = false;
    (async () => {
      try {
        const { accessToken } = await apiRefresh();
        if (cancelled) return;
        setAccessToken(accessToken);
        await loadUser();
      } catch {
        if (cancelled) return;
        setAccessToken(null);
        setUser(null);
        setStatus("unauthenticated");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [loadUser]);

  const login = useCallback(
    async (email: string, password: string) => {
      const { accessToken } = await apiLogin({ email, password });
      setAccessToken(accessToken);
      await loadUser();
    },
    [loadUser],
  );

  const register = useCallback(async (email: string, password: string, name: string, birthYear: number) => {
    await apiRegister({ email, password, name, birthYear });
  }, []);

  const logout = useCallback(async () => {
    await apiLogout().catch(() => undefined);
    clearSession();
  }, [clearSession]);

  return <AuthContext.Provider value={{ user, status, login, register, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
