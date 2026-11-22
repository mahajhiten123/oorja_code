import { AlertCircle, Bell, CheckCircle2, ChevronRight, ClipboardCheck, CreditCard, HelpCircle, LifeBuoy, Loader2, LogOut, MapPin, Pencil, Phone, ArrowRight, Sun, UserRound, Wallet2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { ApiError, PlantsApi, ProfileApi, type Plant } from "../lib/api";

export function Profile() {
  const { user, logout, setUser } = useAuth();
  const navigate = useNavigate();

  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(user?.name ?? "");
  const [phone, setPhone] = useState(user?.phone ?? "");
  const [address, setAddress] = useState(user?.address ?? "");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const [plants, setPlants] = useState<Plant[] | null>(null);

  useEffect(() => {
    if (user) {
      setName(user.name);
      setPhone(user.phone);
      setAddress(user.address ?? "");
    }
  }, [user?.id]);

  useEffect(() => {
    PlantsApi.mine().then(setPlants).catch(() => setPlants([]));
  }, []);

  const quickAccess = [
    { icon: Sun, label: "My Plants", sub: `${plants?.length ?? 0} active site${(plants?.length ?? 0) === 1 ? "" : "s"}`, action: () => navigate("/plants") },
    // Payments are per-transaction through Razorpay — the app never stores
    // card/UPI details, so there's no saved "payment methods" list to
    // manage. What actually exists is the payment record on each invoice
    // (method, reference, amount), so this tile opens that instead of
    // silently landing on My Bookings.
    { icon: CreditCard, label: "Invoices & Payments", sub: "Tax invoices, credit notes & payment history", action: () => navigate("/invoices") },
    { icon: ClipboardCheck, label: "Service Reports", sub: "Visit checklists, before/after output & photos", action: () => navigate("/service-reports") },
    { icon: Wallet2, label: "Refunds", sub: "Track refund status & settlement", action: () => navigate("/refunds") },
    { icon: LifeBuoy, label: "Support Tickets", sub: "Raise an issue & track replies", action: () => navigate("/support") },
    { icon: Bell, label: "Notifications", sub: "Booking & service alerts", action: () => {} },
    { icon: HelpCircle, label: "Help & Support", sub: "FAQs, cancellation & refund policy", action: () => navigate("/help") }
  ];

  const handleLogout = async () => {
    // Await so the server-side revoke actually goes out before we navigate away.
    await logout();
    navigate("/");
  };

  const handleSave = async () => {
    setSaveError(null);
    setSaving(true);
    try {
      const updated = await ProfileApi.update({ name: name.trim(), phone: phone.trim(), address: address.trim() || undefined });
      setUser(updated);
      setEditing(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2500);
    } catch (e) {
      setSaveError(e instanceof ApiError ? e.message : "Couldn't save your changes. Please try again.");
    } finally {
      setSaving(false);
    }
  };

  return <div className="page profile-page">
    <div className="profile-head">
      <div className="avatar">{(user?.name || "U").charAt(0).toUpperCase()}</div>
      <div><span className="kicker">MY PROFILE</span><h1>{user?.name || "Your account"}</h1><p><CheckCircle2 size={16} /> {user?.customerCode} · Verified customer</p></div>
      <button className="icon-text" onClick={() => { setSaveError(null); setEditing(!editing); }}><Pencil size={16} /> {editing ? "Cancel" : "Edit profile"}</button>
    </div>
    <div className="profile-grid">
      <section className="profile-card">
        <h3>Personal details</h3>
        <label>Full name<input disabled={!editing} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>Mobile number{editing
          ? <input value={phone} onChange={e => setPhone(e.target.value.replace(/[^\d+ ]/g, ""))} />
          : <div className="readonly"><Phone size={16} /> {user?.phone ?? "—"} <CheckCircle2 size={15} /></div>}</label>
        <label>Email<div className="readonly"><CheckCircle2 size={15} /> {user?.email ?? "—"}</div></label>
        <label>Service address{editing
          ? <input value={address} onChange={e => setAddress(e.target.value)} placeholder="House no., street, city, state - PIN" />
          : <div className="readonly"><MapPin size={16} /> {user?.address || "Not set yet"}</div>}</label>
        {saveError && <p className="field-error"><AlertCircle size={14} /> {saveError}</p>}
        {saved && <p className="field-hint"><CheckCircle2 size={14} style={{ verticalAlign: -2 }} /> Profile updated.</p>}
        {editing && <button className="btn btn-primary" disabled={saving || name.trim().length < 2 || phone.trim().length < 8} onClick={handleSave}>{saving ? <><Loader2 size={16} className="spin-icon" /> Saving…</> : "Save changes"}</button>}
      </section>
      <section className="profile-card">
        <h3>Quick access</h3>
        <div className="profile-quick">
          {quickAccess.map(item => (
            <div className="profile-row" key={item.label} onClick={item.action}>
              <div className="profile-row-icon"><item.icon size={18} /></div>
              <div><b>{item.label}</b><span>{item.sub}</span></div>
              <ChevronRight size={18} />
            </div>
          ))}
          <div className="profile-row danger" onClick={handleLogout}>
            <div className="profile-row-icon"><LogOut size={18} /></div>
            <div><b>Logout</b><span>Sign out of your account</span></div>
            <ChevronRight size={18} />
          </div>
        </div>
      </section>

      <section className="profile-card" style={{ gridColumn: "1 / -1" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <h3 style={{ margin: 0 }}>My Plants</h3>
          <button className="text-link" onClick={() => navigate("/plants")}>Manage plants <ArrowRight size={15} /></button>
        </div>
        {plants === null && <p style={{ color: "var(--muted)", fontSize: 13, marginTop: 10 }}>Loading your plants…</p>}
        {plants && plants.length === 0 && (
          <div className="empty-state" style={{ padding: "30px 10px" }}>
            <b>No plants registered yet</b>
            <p>Add your first solar plant or site to start booking services against it.</p>
            <button className="btn btn-primary sm" style={{ marginTop: 12 }} onClick={() => navigate("/plants")}>Add a plant</button>
          </div>
        )}
        {plants && plants.length > 0 && (
          <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
            {plants.slice(0, 4).map(p => (
              <div key={p.id} className="profile-row" style={{ cursor: "pointer" }} onClick={() => navigate("/plants")}>
                <div className="profile-row-icon"><Sun size={18} /></div>
                <div><b>{p.name}</b><span>{p.plantCode} · {p.capacityDisplay} · {p.type.charAt(0) + p.type.slice(1).toLowerCase()} · {p.location}</span></div>
                <ChevronRight size={18} />
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  </div>;
}
