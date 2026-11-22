import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { AdminAuthApi, getAdminToken, saveSession, clearSession, onAdminUnauthorized } from "../lib/api";

type AdminState = {
  isAdminAuthenticated: boolean;
  adminName: string | null;
  loading: boolean;
  login: (password: string) => Promise<void>;
  loginWithOtp: (email: string, otp: string) => Promise<void>;
  sendOtp: (email: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AdminAuthContext = createContext<AdminState | null>(null);

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getAdminToken());
  const [adminName, setAdminName] = useState<string | null>(() => (getAdminToken() ? "Admin" : null));

  useEffect(() => {
    onAdminUnauthorized.handler = () => {
      setTokenState(null);
      setAdminName(null);
    };
    return () => {
      onAdminUnauthorized.handler = null;
    };
  }, []);

  const login = async (password: string) => {
    const result = await AdminAuthApi.login(password);
    saveSession(result, "admin");
    setTokenState(result.token);
    setAdminName(result.name || "Admin");
  };

  /** Sends a one-time code to the admin's registered email address. */
  const sendOtp = async (email: string) => {
    await AdminAuthApi.sendOtp(email);
  };

  const loginWithOtp = async (email: string, otp: string) => {
    const result = await AdminAuthApi.verifyOtp(email, otp);
    saveSession(result, "admin");
    setTokenState(result.token);
    setAdminName(result.name || "Admin");
  };

  /** Revokes the session server-side (refresh token + current access token) before clearing it locally. */
  const logout = async () => {
    await AdminAuthApi.logout();
    clearSession("admin");
    setTokenState(null);
    setAdminName(null);
  };

  return (
    <AdminAuthContext.Provider value={{ isAdminAuthenticated: !!token, adminName, loading: false, login, loginWithOtp, sendOtp, logout }}>
      {children}
    </AdminAuthContext.Provider>
  );
}

export function useAdminAuth() {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) throw new Error("useAdminAuth must be used within AdminAuthProvider");
  return ctx;
}
