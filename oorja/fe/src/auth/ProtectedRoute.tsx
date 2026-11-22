import type { ReactNode } from "react";
import { useEffect } from "react";
import { useAuth } from "./AuthContext";
import { useAuthModal } from "./AuthModalContext";

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated, loading } = useAuth();
  const { openLogin } = useAuthModal();

  useEffect(() => {
    if (!loading && !isAuthenticated) {
      openLogin();
    }
  }, [loading, isAuthenticated, openLogin]);

  if (loading) {
    return (
      <div className="page" style={{ display: "grid", placeItems: "center", minHeight: "50vh" }}>
        <div className="pay-spinner" style={{ borderTopColor: "var(--green, #087a4d)", borderColor: "rgba(8,122,77,.25)" }} />
      </div>
    );
  }

  if (!isAuthenticated) {
    // The login popup shows on top of the page; once the user signs in this
    // component re-renders and reveals the protected content in place.
    return <div className="page" style={{ minHeight: "50vh" }} />;
  }

  return <>{children}</>;
}
