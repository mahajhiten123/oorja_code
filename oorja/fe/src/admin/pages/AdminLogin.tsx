import { AlertCircle, ArrowRight, Lock, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { useLocation, useNavigate, Navigate } from "react-router-dom";
import { useAdminAuth } from "../AdminAuthContext";
import { ApiError } from "../../lib/api";

export function AdminLogin() {
  const { isAdminAuthenticated, login } = useAdminAuth();
  const navigate = useNavigate();
  const location = useLocation() as { state?: { from?: { pathname: string } } };

  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (isAdminAuthenticated) {
    return <Navigate to={location.state?.from?.pathname || "/admin"} replace />;
  }

  const handleSubmit = async () => {
    setError(null);
    setBusy(true);
    try {
      await login(password);
      navigate(location.state?.from?.pathname || "/admin", { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't sign in. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page" style={{ display: "grid", placeItems: "center", minHeight: "70vh" }}>
      <div className="auth-modal-card" style={{ position: "static", maxWidth: 380, width: "100%" }}>
        <div className="auth-form-inner">
          <div className="auth-logo-wrap" style={{ background: "var(--deep)" }}><ShieldCheck size={26} color="#fff" /></div>
          <span className="kicker">OORJA ON MOVE</span>
          <h2>Admin console</h2>
          <p>Sign in with the admin password to manage members, bookings, quotations, technicians and refunds.</p>
          <label>
            Admin password
            <div className="phone-input" style={{ padding: "0 12px" }}>
              <Lock size={17} style={{ color: "var(--muted)" }} />
              <input
                type="password"
                value={password}
                onChange={e => { setPassword(e.target.value); setError(null); }}
                onKeyDown={e => { if (e.key === "Enter" && password && !busy) handleSubmit(); }}
                placeholder="Enter admin password"
                style={{ border: 0, marginLeft: 8 }}
                autoFocus
              />
            </div>
          </label>
          {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
          <button className="btn btn-primary full" disabled={!password || busy} onClick={handleSubmit}>
            {busy ? "Signing in…" : "Sign in"} <ArrowRight size={18} />
          </button>
        </div>
      </div>
    </div>
  );
}
