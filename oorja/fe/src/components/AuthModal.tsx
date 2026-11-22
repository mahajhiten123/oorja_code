import { AlertCircle, ArrowRight, Lock, Mail, MapPin, ShieldCheck, UserRound, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { useAuthModal } from "../auth/AuthModalContext";
import { useAdminAuth } from "../admin/AdminAuthContext";
import { AuthApi, ProfileApi, ApiError, type AuthResult } from "../lib/api";
import { useGoogleSignIn } from "../auth/useGoogleSignIn";
import { addNotification } from "../data/notificationsStore";

type Step = "form" | "otp" | "profile";
type Portal = "customer" | "admin";

export function AuthModal() {
  const { isOpen, mode, openLogin, openRegister, close } = useAuthModal();
  const { login, refreshProfile } = useAuth();
  const { login: adminLogin } = useAdminAuth();
  const navigate = useNavigate();

  const [portal, setPortal] = useState<Portal>("customer");
  const [step, setStep] = useState<Step>("form");

  // Admin fields
  const [adminPassword, setAdminPassword] = useState("");
  const [adminBusy, setAdminBusy] = useState(false);

  // Login fields
  const [email, setEmail] = useState("");

  // Register fields
  const [name, setName] = useState("");
  const [regEmail, setRegEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");

  // Post-Google "complete your profile" step (a fresh Google account has no phone/address yet).
  const [profilePhone, setProfilePhone] = useState("");
  const [profileAddress, setProfileAddress] = useState("");
  const [profileName, setProfileName] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [googleBusy, setGoogleBusy] = useState(false);

  const [otp, setOtp] = useState("");
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resendIn, setResendIn] = useState(0);

  const activeEmail = mode === "login" ? email : regEmail;

  // Reset the form whenever the popup is opened fresh.
  useEffect(() => {
    if (isOpen) {
      setPortal("customer");
      setStep("form");
      setOtp("");
      setAdminPassword("");
      setProfilePhone("");
      setProfileAddress("");
      setError(null);
    }
  }, [isOpen, mode]);

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

  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setInterval(() => setResendIn(s => Math.max(0, s - 1)), 1000);
    return () => clearInterval(t);
  }, [resendIn]);

  // Hooks must run unconditionally, so the GIS hook is set up above the early
  // return and dispatches through a ref to the handler defined further down.
  const googleHandlerRef = useRef<(idToken: string) => void>(() => {});
  const google = useGoogleSignIn({
    enabled: isOpen && portal === "customer" && step === "form",
    onCredential: (idToken) => googleHandlerRef.current(idToken)
  });

  if (!isOpen) return null;

  const canSendLogin = /\S+@\S+\.\S+/.test(email);
  const canSendRegister = name.trim().length > 1 && /\S+@\S+\.\S+/.test(regEmail) && phone.replace(/\D/g, "").length >= 10;

  const handleSendOtp = async () => {
    setError(null);
    setSending(true);
    try {
      if (mode === "login") {
        await AuthApi.sendLoginOtp(email.trim());
      } else {
        await AuthApi.sendRegisterOtp({ name: name.trim(), email: regEmail.trim(), phone: phone.trim(), address: address.trim() || undefined });
      }
      addNotification("otp", "OTP sent", `A verification code was sent to ${mode === "login" ? email.trim() : regEmail.trim()}.`);
      setOtp("");
      setStep("otp");
      setResendIn(30);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't send the OTP. Please try again.");
    } finally {
      setSending(false);
    }
  };

  const handleVerify = async () => {
    setError(null);
    setVerifying(true);
    try {
      const auth = mode === "login"
        ? await AuthApi.verifyLoginOtp(email.trim(), otp)
        : await AuthApi.verifyRegisterOtp({ name: name.trim(), email: regEmail.trim(), phone: phone.trim(), address: address.trim() || undefined, otp });
      await login(auth);
      addNotification("login", "Login successful", `Welcome${mode === "register" ? "" : " back"}, ${auth.name.split(" ")[0]}.`);
      // Reset local state and hand control back to whatever triggered the popup.
      setEmail(""); setName(""); setRegEmail(""); setPhone(""); setAddress(""); setOtp("");
      close();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Verification failed. Please try again.");
    } finally {
      setVerifying(false);
    }
  };

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

  const switchMode = (next: "login" | "register") => {
    if (next === "login") openLogin(); else openRegister();
    setStep("form");
    setOtp("");
    setError(null);
  };

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
    setOtp("");
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
          <span className="kicker">CUSTOMER ACCOUNT</span>
          <h2>{step === "profile" ? "Almost there" : step === "otp" ? "Enter your OTP" : mode === "login" ? "Welcome back" : "Create your account"}</h2>
          <p>{step === "profile"
            ? "We just need a contact number and address so our technicians can reach you."
            : step === "otp"
            ? <>We've sent a 6-digit verification code to <b>{activeEmail}</b>.</>
            : mode === "login" ? "Sign in to manage your solar services and bookings." : "Tell us a bit about yourself to get started."}</p>

          {step === "form" && mode === "login" && (
            <>
              <label>Email address<div className="phone-input" style={{ padding: "0 12px" }}><Mail size={17} style={{ color: "var(--muted)" }} /><input type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="you@example.com" style={{ border: 0, marginLeft: 8 }} /></div></label>
              {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
              <button className="btn btn-primary full" disabled={!canSendLogin || sending} onClick={handleSendOtp}>{sending ? "Sending…" : "Send OTP"} <ArrowRight size={18} /></button>
              {google.configured && (
                <>
                  <div className="divider-line">OR</div>
                  <div ref={google.containerRef} style={{ display: "flex", justifyContent: "center", minHeight: 44, opacity: googleBusy ? 0.6 : 1, pointerEvents: googleBusy ? "none" : "auto" }} />
                  {google.failed && <p className="field-hint">Couldn't load Google sign-in. Please use email instead.</p>}
                  {googleBusy && <p className="field-hint">Signing you in with Google…</p>}
                </>
              )}
              <div className="divider-line">NEW HERE?</div>
              <button className="social-btn" style={{ width: "100%" }} onClick={() => switchMode("register")}><UserRound size={16} /> Create an account</button>
            </>
          )}

          {step === "form" && mode === "register" && (
            <>
              <label>Full name<div className="phone-input" style={{ padding: "0 12px" }}><UserRound size={17} style={{ color: "var(--muted)" }} /><input value={name} onChange={e => setName(e.target.value)} placeholder="Your full name" style={{ border: 0, marginLeft: 8 }} /></div></label>
              <label>Email address<div className="phone-input" style={{ padding: "0 12px" }}><Mail size={17} style={{ color: "var(--muted)" }} /><input type="email" value={regEmail} onChange={e => setRegEmail(e.target.value)} placeholder="you@example.com" style={{ border: 0, marginLeft: 8 }} /></div></label>
              <label>Mobile number<div className="phone-input"><span className="country-select">+91</span><input inputMode="numeric" maxLength={10} value={phone} onChange={e => setPhone(e.target.value.replace(/\D/g, ""))} placeholder="10-digit mobile number" /></div></label>
              <label>Address (optional)<div className="phone-input" style={{ padding: "0 12px" }}><MapPin size={17} style={{ color: "var(--muted)" }} /><input value={address} onChange={e => setAddress(e.target.value)} placeholder="House no., street, city" style={{ border: 0, marginLeft: 8 }} /></div></label>
              {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
              <button className="btn btn-primary full" disabled={!canSendRegister || sending} onClick={handleSendOtp}>{sending ? "Sending…" : "Send verification code"} <ArrowRight size={18} /></button>
              {google.configured && (
                <>
                  <div className="divider-line">OR</div>
                  <div ref={google.containerRef} style={{ display: "flex", justifyContent: "center", minHeight: 44, opacity: googleBusy ? 0.6 : 1, pointerEvents: googleBusy ? "none" : "auto" }} />
                  {google.failed && <p className="field-hint">Couldn't load Google sign-in. Please use email instead.</p>}
                  {googleBusy && <p className="field-hint">Signing you in with Google…</p>}
                </>
              )}
              <div className="divider-line">ALREADY HAVE AN ACCOUNT?</div>
              <button className="social-btn" style={{ width: "100%" }} onClick={() => switchMode("login")}><UserRound size={16} /> Log in instead</button>
            </>
          )}

          {step === "profile" && (
            <>
              <label>Full name<div className="phone-input" style={{ padding: "0 12px" }}><UserRound size={17} style={{ color: "var(--muted)" }} /><input value={profileName} onChange={e => setProfileName(e.target.value)} placeholder="Your full name" style={{ border: 0, marginLeft: 8 }} /></div></label>
              <label>Mobile number<div className="phone-input"><span className="country-select">+91</span><input inputMode="numeric" maxLength={10} value={profilePhone} onChange={e => { setProfilePhone(e.target.value.replace(/\D/g, "")); setError(null); }} placeholder="10-digit mobile number" /></div></label>
              <label>Address<div className="phone-input" style={{ padding: "0 12px" }}><MapPin size={17} style={{ color: "var(--muted)" }} /><input value={profileAddress} onChange={e => setProfileAddress(e.target.value)} placeholder="House no., street, city" style={{ border: 0, marginLeft: 8 }} /></div></label>
              {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
              <button className="btn btn-primary full" disabled={savingProfile || profileName.trim().length < 2 || profilePhone.length < 10} onClick={handleSaveProfile}>{savingProfile ? "Saving…" : "Continue"} <ArrowRight size={18} /></button>
            </>
          )}

          {step === "otp" && (
            <>
              <p className="field-hint" style={{ background: "#fff8e6", color: "#92660a", padding: "10px 12px", borderRadius: 10, marginTop: -8 }}>
                Didn't get the email? Check spam — or, in a local/dev environment without SMTP configured, use the dev fallback OTP that's printed in the backend's console/README.
              </p>
              <label>One-time password<input className="otp-input" inputMode="numeric" maxLength={6} value={otp} onChange={e => { setOtp(e.target.value.replace(/\D/g, "")); setError(null); }} placeholder="••••••" /></label>
              {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
              <div className="otp-row">
                <button className="text-link" onClick={() => { setStep("form"); setOtp(""); setError(null); }}>Change {mode === "login" ? "email" : "details"}</button>
                <span>{resendIn > 0 ? `Resend in ${resendIn}s` : <button className="text-link" onClick={handleSendOtp}>Resend OTP</button>}</span>
              </div>
              <button className="btn btn-primary full" disabled={otp.length !== 6 || verifying} onClick={handleVerify}>{verifying ? "Verifying…" : "Verify & Continue"} <ArrowRight size={18} /></button>
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
