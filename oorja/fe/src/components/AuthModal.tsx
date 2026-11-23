import { AlertCircle, ArrowRight, Lock, MapPin, ShieldCheck, UserRound, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useAuthModal } from "../auth/AuthModalContext";
import { useAdminAuth } from "../admin/AdminAuthContext";
import { AuthApi, ProfileApi, ApiError, type AuthResult } from "../lib/api";
import { useGoogleSignIn } from "../auth/useGoogleSignIn";
import { addNotification } from "../data/notificationsStore";

type Step = "form" | "profile";
type Portal = "customer" | "admin";

export function AuthModal() {
  const { isOpen, close } = useAuthModal();
  const { login, refreshProfile } = useAuth();
  const { login: adminLogin } = useAdminAuth();
  const navigate = useNavigate();

  const [portal, setPortal] = useState<Portal>("customer");
  const [step, setStep] = useState<Step>("form");

  // Admin fields
  const [adminPassword, setAdminPassword] = useState("");
  const [adminBusy, setAdminBusy] = useState(false);

  // Post-Google "complete your profile" step (a fresh Google account has no phone/address yet).
  const [profilePhone, setProfilePhone] = useState("");
  const [profileAddress, setProfileAddress] = useState("");
  const [profileName, setProfileName] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [googleBusy, setGoogleBusy] = useState(false);

  const [error, setError] = useState<string | null>(null);

  // Reset the form whenever the popup is opened fresh.
  useEffect(() => {
    if (isOpen) {
      setPortal("customer");
      setStep("form");
      setAdminPassword("");
      setProfilePhone("");
      setProfileAddress("");
      setError(null);
    }
  }, [isOpen]);

  // Lock page scroll behind the popup while it's open.
  useEffect(() => {
    if (!isOpen) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = prev; };
  }, [isOpen]);

  // Close on Escape.
  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") close(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isOpen, close]);

  const googleHandlerRef = useRef<(idToken: string) => void>(() => {});
  const google = useGoogleSignIn({
    enabled: isOpen && portal === "customer" && step === "form",
    onCredential: (idToken) => googleHandlerRef.current(idToken)
  });

  if (!isOpen) return null;

  /**
   * Completes an Oorja sign-in from a Google ID token. A brand-new Google
   * account has no phone or address yet, so the backend flags needsProfile and
   * we collect those before dropping the user into the app.
   */
  const handleGoogleCredential = async (idToken: string) => {
    setError(null);
    setGoogleBusy(true);
    try {
      const auth: AuthResult = await AuthApi.google(idToken);
      await login(auth);
      if (auth.needsProfile) {
        setProfileName(auth.name || "");
        setProfilePhone(auth.phone || "");
        setProfileAddress(auth.address || "");
        setStep("profile");
        return;
      }
      addNotification("login", "Login successful", `Welcome, ${(auth.name || "there").split(" ")[0]}.`);
      close();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Google sign-in failed. Please try again.");
    } finally {
      setGoogleBusy(false);
    }
  };

  const handleSaveProfile = async () => {
    setError(null);
    setSavingProfile(true);
    try {
      await ProfileApi.update({
        name: profileName.trim(),
        phone: profilePhone.trim(),
        address: profileAddress.trim() || undefined
      });
      await refreshProfile();
      addNotification("login", "Login successful", `Welcome, ${(profileName || "there").split(" ")[0]}.`);
      close();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save your details. Please try again.");
    } finally {
      setSavingProfile(false);
    }
  };

  googleHandlerRef.current = handleGoogleCredential;

  const handleAdminLogin = async () => {
    setError(null);
    setAdminBusy(true);
    try {
      await adminLogin(adminPassword);
      addNotification("login", "Admin login successful", "Welcome to the Oorja on Move admin console.");
      setAdminPassword("");
      close();
      navigate("/admin");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Incorrect admin password. Please try again.");
    } finally {
      setAdminBusy(false);
    }
  };

  const switchPortal = (next: Portal) => {
    setPortal(next);
    setStep("form");
    setAdminPassword("");
    setError(null);
  };

  return (
    <div className="auth-modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) close(); }}>
      <div className="auth-modal-card" role="dialog" aria-modal="true">
        <button className="auth-modal-close" aria-label="Close" onClick={close}><X size={18} /></button>
        <div className="auth-form-inner">
          <div className="auth-logo-wrap"><img src="/assets/logo.png" alt="Oorja on Move" className="auth-form-logo" /></div>

          <div className="portal-toggle">
            <button className={portal === "customer" ? "active" : ""} onClick={() => switchPortal("customer")}><UserRound size={15} /> Customer</button>
            <button className={portal === "admin" ? "active" : ""} onClick={() => switchPortal("admin")}><ShieldCheck size={15} /> Admin</button>
          </div>

          {portal === "admin" ? (
            <>
              <span className="kicker">ADMIN CONSOLE</span>
              <h2>Admin sign in</h2>
              <p>Sign in with the admin password to manage members, bookings, quotations, technicians and refunds.</p>
              <label>Admin password<div className="phone-input" style={{ padding: "0 12px" }}><Lock size={17} style={{ color: "var(--muted)" }} /><input type="password" value={adminPassword} onChange={e => { setAdminPassword(e.target.value); setError(null); }} onKeyDown={e => { if (e.key === "Enter" && adminPassword && !adminBusy) handleAdminLogin(); }} placeholder="Enter admin password" style={{ border: 0, marginLeft: 8 }} /></div></label>
              {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
              <button className="btn btn-primary full" disabled={!adminPassword || adminBusy} onClick={handleAdminLogin}>{adminBusy ? "Signing in…" : "Sign in as Admin"} <ArrowRight size={18} /></button>
            </>
          ) : (
            <>
              {step === "form" && (
                <>
                  <span className="kicker">CUSTOMER ACCESS</span>
                  <h2>Sign in with Google</h2>
                  <p>Fast, secure 1-click access to manage your rooftop solar plants, schedule service visits, and view reports.</p>

                  <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 14, margin: "28px 0 16px" }}>
                    {google.configured ? (
                      <>
                        <div
                          ref={google.containerRef}
                          style={{
                            display: "flex",
                            justifyContent: "center",
                            minHeight: 46,
                            width: "100%",
                            opacity: googleBusy ? 0.6 : 1,
                            pointerEvents: googleBusy ? "none" : "auto"
                          }}
                        />
                        {googleBusy && <p className="field-hint" style={{ color: "var(--green)", fontWeight: 600 }}>Signing you in with Google…</p>}
                        {google.failed && (
                          <p className="field-error" style={{ textAlign: "center" }}>
                            <AlertCircle size={14} /> Google Sign-In script failed to load. Please check your network or ad-blocker.
                          </p>
                        )}
                      </>
                    ) : (
                      <div style={{ background: "#f0f7f4", border: "1px dashed var(--green)", padding: "18px 20px", borderRadius: 14, textAlign: "center", width: "100%" }}>
                        <p style={{ margin: "0 0 6px", fontWeight: 700, color: "var(--deep)", fontSize: 15 }}>Google OAuth Setup Required</p>
                        <p style={{ margin: 0, fontSize: 13, color: "#4f6762", lineHeight: 1.5 }}>
                          To activate 1-click Google login, please provide your Google Cloud OAuth Client ID.
                        </p>
                      </div>
                    )}
                  </div>

                  {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
                </>
              )}

              {step === "profile" && (
                <>
                  <span className="kicker">COMPLETE PROFILE</span>
                  <h2>Almost there</h2>
                  <p>Please confirm your name and contact details so our technicians can reach you for site visits.</p>
                  <label>Full name<div className="phone-input" style={{ padding: "0 12px" }}><UserRound size={17} style={{ color: "var(--muted)" }} /><input value={profileName} onChange={e => setProfileName(e.target.value)} placeholder="Your full name" style={{ border: 0, marginLeft: 8 }} /></div></label>
                  <label>Mobile number<div className="phone-input"><span className="country-select">+91</span><input inputMode="numeric" maxLength={10} value={profilePhone} onChange={e => { setProfilePhone(e.target.value.replace(/\D/g, "")); setError(null); }} placeholder="10-digit mobile number" /></div></label>
                  <label>Service address<div className="phone-input" style={{ padding: "0 12px" }}><MapPin size={17} style={{ color: "var(--muted)" }} /><input value={profileAddress} onChange={e => setProfileAddress(e.target.value)} placeholder="House no., street, city" style={{ border: 0, marginLeft: 8 }} /></div></label>
                  {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
                  <button className="btn btn-primary full" disabled={savingProfile || profileName.trim().length < 2 || profilePhone.length < 10} onClick={handleSaveProfile}>{savingProfile ? "Saving…" : "Save & Continue"} <ArrowRight size={18} /></button>
                </>
              )}
            </>
          )}

          <div className="auth-support">
            <span>Need help?</span>
            <a href="mailto:info@oorjaonmove.com">info@oorjaonmove.com</a>
          </div>
          <small className="terms">By continuing, you agree to our Terms of Service &amp; Privacy Policy.</small>
        </div>
      </div>
    </div>
  );
}
