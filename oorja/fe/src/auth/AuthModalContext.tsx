import { createContext, useContext, useState, type ReactNode } from "react";

type Mode = "login" | "register";

type AuthModalState = {
  isOpen: boolean;
  mode: Mode;
  /** Open the popup on the sign-in step. */
  openLogin: () => void;
  /** Open the popup on the create-account step. */
  openRegister: () => void;
  close: () => void;
};

const AuthModalContext = createContext<AuthModalState | null>(null);

export function AuthModalProvider({ children }: { children: ReactNode }) {
  const [isOpen, setIsOpen] = useState(false);
  const [mode, setMode] = useState<Mode>("login");

  const openLogin = () => { setMode("login"); setIsOpen(true); };
  const openRegister = () => { setMode("register"); setIsOpen(true); };
  const close = () => setIsOpen(false);

  return (
    <AuthModalContext.Provider value={{ isOpen, mode, openLogin, openRegister, close }}>
      {children}
    </AuthModalContext.Provider>
  );
}

export function useAuthModal() {
  const ctx = useContext(AuthModalContext);
  if (!ctx) throw new Error("useAuthModal must be used within AuthModalProvider");
  return ctx;
}
