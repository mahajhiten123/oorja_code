import { AlertTriangle, Calendar, CheckCircle2, Clock, MapPin, Search, Sun, X, XCircle } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AdminMembersApi, ApiError, type AdminMember, type ApiBooking } from "../../lib/api";

type BookingTab = "upcoming" | "completed" | "cancelled";

export function AdminMembers() {
  const [members, setMembers] = useState<AdminMember[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [viewing, setViewing] = useState<AdminMember | null>(null);

  const load = () => {
    setError(null);
    AdminMembersApi.list()
      .then(setMembers)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load members."));
  };

  useEffect(load, []);

  const filtered = useMemo(() => {
    if (!members) return [];
    const q = query.trim().toLowerCase();
    if (!q) return members;
    return members.filter(m =>
      m.name.toLowerCase().includes(q) || m.email.toLowerCase().includes(q) ||
      m.phone.includes(q) || m.customerCode.toLowerCase().includes(q)
    );
  }, [members, query]);

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">MEMBERS</span>
          <h1>Registered members</h1>
          <p className="muted">
            Every customer who has signed up, the site(s) they've registered, and their bookings by status.
            AMC plan management will show up here once plan data is provided.
          </p>
        </div>
      </div>

      <div className="admin-toolbar">
        <div className="admin-search"><Search size={16} /><input placeholder="Search by name, email, phone or customer code" value={query} onChange={e => setQuery(e.target.value)} /></div>
      </div>

      {error && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><AlertTriangle size={20} /><h3 style={{ margin: "14px 0 6px" }}>Couldn't load members</h3><p className="muted">{error}</p><button className="btn btn-primary sm" style={{ marginTop: 14 }} onClick={load}>Retry</button></div>}
      {!error && members === null && <><div className="skeleton-row" /><div className="skeleton-row" /><div className="skeleton-row" /></>}
      {!error && members !== null && filtered.length === 0 && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><Sun size={20} /><h3 style={{ margin: "14px 0 6px" }}>No members found</h3><p className="muted">Try a different search.</p></div>}

      {!error && filtered.length > 0 && (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>Member</th><th>Contact</th><th>Sites</th><th>Bookings</th><th>Status</th><th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(m => (
                <tr key={m.id}>
                  <td><b>{m.name}</b><br /><small className="muted">{m.customerCode}</small></td>
                  <td>{m.phone}<br /><small className="muted">{m.email}</small></td>
                  <td>{m.sites.length === 0 ? <span className="muted">No sites yet</span> : `${m.sites.length} site${m.sites.length === 1 ? "" : "s"}`}</td>
                  <td>
                    <span className="status pending" style={{ marginRight: 6 }}>{m.bookings.upcomingCount} upcoming</span>
                    <span className="status completed" style={{ marginRight: 6 }}>{m.bookings.completedCount} done</span>
                    <span className="status cancelled">{m.bookings.cancelledCount} cancelled</span>
                  </td>
                  <td><span className={`amc-badge ${m.status === "ACTIVE" ? "active" : "none"}`}>{m.status}</span></td>
                  <td><button className="btn btn-ghost sm" onClick={() => setViewing(m)}>View details</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {viewing && <MemberDetailModal member={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}

function MemberDetailModal({ member, onClose }: { member: AdminMember; onClose: () => void }) {
  const [tab, setTab] = useState<BookingTab>("upcoming");
  const bookingsByTab: Record<BookingTab, ApiBooking[]> = {
    upcoming: member.bookings.upcoming,
    completed: member.bookings.completed,
    cancelled: member.bookings.cancelled
  };
  const counts: Record<BookingTab, number> = {
    upcoming: member.bookings.upcomingCount,
    completed: member.bookings.completedCount,
    cancelled: member.bookings.cancelledCount
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" style={{ maxWidth: 560 }} onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{member.name} · {member.customerCode}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        <p className="field-hint" style={{ marginBottom: 4 }}>{member.phone} · {member.email}</p>
        {member.address && <p className="field-hint" style={{ marginTop: 0, marginBottom: 14 }}>{member.address}</p>}

        <h4 style={{ margin: "6px 0 8px" }}>Sites</h4>
        {member.sites.length === 0 && <p className="muted" style={{ fontSize: 13 }}>No sites registered yet.</p>}
        {member.sites.map(s => (
          <div key={s.id} className="plant-pick" style={{ marginBottom: 6, cursor: "default" }}>
            <div><b>{s.name}</b><span><MapPin size={11} style={{ verticalAlign: -1 }} /> {s.location} · {s.capacityDisplay}</span></div>
            <span className="muted" style={{ fontSize: 11 }}>{s.deleted ? <span className="status cancelled">Archived</span> : s.type}</span>
          </div>
        ))}

        <h4 style={{ margin: "16px 0 8px" }}>Bookings</h4>
        <div className="tabs">
          {(["upcoming", "completed", "cancelled"] as BookingTab[]).map(t => (
            <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
              {t.charAt(0).toUpperCase() + t.slice(1)} ({counts[t]})
            </button>
          ))}
        </div>

        {bookingsByTab[tab].length === 0 && <p className="muted" style={{ fontSize: 13, marginTop: 10 }}>No {tab} bookings.</p>}
        <div style={{ maxHeight: 260, overflowY: "auto", marginTop: 8 }}>
          {bookingsByTab[tab].map(b => (
            <div key={b.id} className="booking-card" style={{ marginBottom: 8 }}>
              <div className="booking-card-head">
                <div><b>{b.serviceType}</b><span className="booking-token">{b.token}</span></div>
                {tab === "completed" && <CheckCircle2 size={16} color="var(--green)" />}
                {tab === "cancelled" && <XCircle size={16} color="#c54c44" />}
              </div>
              <div className="booking-meta">
                <span><Calendar size={14} /> {b.scheduledDate}</span>
                <span><Clock size={14} /> {b.scheduledTime?.slice(0, 5)}</span>
                <span><MapPin size={14} /> {b.plantName}</span>
              </div>
              {b.attachments.length > 0 && (
                <p style={{ fontSize: 12, color: "var(--muted)", margin: "6px 0 0" }}>
                  {b.attachments.length} file{b.attachments.length > 1 ? "s" : ""} attached
                </p>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
