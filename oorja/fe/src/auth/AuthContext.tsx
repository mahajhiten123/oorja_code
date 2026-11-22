import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { AuthApi, ProfileApi, getToken, saveSession, clearSession, onUnauthorized, type CustomerProfile, type AuthResult } from "../lib/api";

type AuthState = {
  user: CustomerProfile | null;
  isAuthenticated: boolean;
  /** True while we're checking an existing token against the backend on first load. */
  loading: boolean;
  /** Call after a successful login/register OTP verification. */
  login: (auth: AuthResult) => Promise<void>;
  logout: () => Promise<void>;
  /** Re-fetch the profile from the backend (e.g. after an edit). */
  refreshProfile: () => Promise<void>;
  /** Merge a locally-known update into the cached profile without a round trip. */
  setUser: (user: CustomerProfile) => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<CustomerProfile | null>(null);
  const [loading, setLoading] = useState(true);

  const loadProfile = async () => {
    const token = getToken();
    if (!token) {
      setUserState(null);
      setLoading(false);
      return;
    }
    try {
      const profile = await ProfileApi.me();
      setUserState(profile);
    } catch {
      // Token invalid/expired — the api client already clears it on 401.
      setUserState(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProfile();
    // If any API call anywhere in the app comes back 401, drop the session
    // globally instead of leaving stale UI state around.
    onUnauthorized.handler = () => setUserState(null);
    return () => {
      onUnauthorized.handler = null;
    };
  }, []);

  const login = async (auth: AuthResult) => {
    // Stores the access token *and* the refresh token that keeps the session
    // alive once the short-lived access token expires.
    saveSession(auth, "customer");
    // Immediately hydrate user state from the AuthResult we already have —
    // the OTP/Google response includes name, email etc., so there's no need
    // to wait for a second round-trip before the UI shows the user as logged in.
    setUserState({
      id: auth.userId,
      customerCode: "",
      name: auth.name,
      email: auth.email,
      phone: auth.phone ?? "",
      address: auth.address ?? null,
      status: "ACTIVE",
      plantsCount: 0,
      bookingsCount: 0
    });
    setLoading(false);
    // Follow up with a full profile fetch in the background to get accurate
    // counts, customerCode etc. A failure here is non-fatal — the user is
    // already logged in with the data we have.
    loadProfile().catch(() => {});
  };

  /** Revokes the session server-side (refresh token + current access token) before clearing it locally. */
  const logout = async () => {
    await AuthApi.logout();
    clearSession("customer");
    setUserState(null);
  };

  const refreshProfile = async () => {
    const profile = await ProfileApi.me();
    setUserState(profile);
  };

  return (
    <AuthContext.Provider value={{ user, isAuthenticated: !!user, loading, login, logout, refreshProfile, setUser: setUserState }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
