import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  AlertTriangle, ArrowUpRight, CalendarCheck2, CheckCircle2, Clock3,
  FileText, RefreshCw, Users, Wallet2, XCircle
} from "lucide-react";
import {
  AdminMembersApi, AdminBookingsApi, AdminQuotationsApi,
  type AdminMember, type ApiBooking, type Quotation
} from "../../lib/api";
import { AdminRefundsApi, type Refund } from "../../lib/refunds";

export function AdminOverview() {
  const [members, setMembers] = useState<AdminMember[] | null>(null);
  const [bookings, setBookings] = useState<ApiBooking[] | null>(null);
  const [quotations, setQuotations] = useState<Quotation[] | null>(null);
  const [refunds, setRefunds] = useState<Refund[] | null>(null);
  const [failed, setFailed] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    const failures: string[] = [];
    const [m, b, q, r] = await Promise.allSettled([
      AdminMembersApi.list(),
      AdminBookingsApi.list(),
      AdminQuotationsApi.list(),
      AdminRefundsApi.list()
    ]);
    if (m.status === "fulfilled") setMembers(m.value); else failures.push("members");
    if (b.status === "fulfilled") setBookings(b.value); else failures.push("bookings");
    if (q.status === "fulfilled") setQuotations(q.value); else failures.push("quotations");
    if (r.status === "fulfilled") setRefunds(r.value); else failures.push("refunds");
    setFailed(failures);
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const stats = useMemo(() => {
    const totalSites = (members || []).reduce((sum, m) => sum + (m.sites?.length || 0), 0);
    const upcoming = (bookings || []).filter(b => b.status === "PENDING" || b.status === "ASSIGNED" || b.status === "ON_SITE").length;
    const completed = (bookings || []).filter(b => b.status === "COMPLETED").length;
    const cancelled = (bookings || []).filter(b => b.status === "CANCELLED").length;
    // "Needs action" mirrors the Admin → Refunds queue tab: requested,
    // under review, or a failed gateway attempt waiting on a human.
    const pendingRefunds = (refunds || [])
      .filter(r => r.status === "REQUESTED" || r.status === "UNDER_REVIEW" || r.status === "FAILED").length;
    const pendingQuotes = (quotations || []).filter(q => q.status === "PENDING" || q.status === "DRAFT").length;
    return { totalSites, upcoming, completed, cancelled, pendingRefunds, pendingQuotes };
  }, [members, bookings, quotations, refunds]);

  const recentBookings = useMemo(
    () => [...(bookings || [])].sort((a, b) => b.id - a.id).slice(0, 6),
    [bookings]
  );

  const statusMeta: Record<string, { label: string; cls: string }> = {
    PENDING: { label: "Pending", cls: "pending" },
    ASSIGNED: { label: "Assigned", cls: "assigned" },
    ON_SITE: { label: "On site", cls: "on_site" },
    COMPLETED: { label: "Completed", cls: "completed" },
    CANCELLED: { label: "Cancelled", cls: "cancelled" }
  };

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">OVERVIEW</span>
          <h1>Dashboard</h1>
          <p className="muted">Registered customers, sites, bookings and what needs your attention today.</p>
        </div>
        <button className="btn btn-ghost sm" onClick={load} disabled={loading}>
          <RefreshCw size={15} className={loading ? "spin-icon" : ""} /> Refresh
        </button>
      </div>

      {failed.length > 0 && (
        <div className="admin-callout">
          <AlertTriangle size={16} />
          Couldn't load: {failed.join(", ")}. Some numbers below may be incomplete.
          <a onClick={load} role="button">Retry</a>
        </div>
      )}

      <div className="admin-stat-grid">
        <Link to="/admin/members" className="admin-stat-card">
          <div className="admin-stat-icon mint"><Users size={19} /></div>
          <div>
            <div className="admin-stat-value">{members ? members.length : "—"}</div>
            <div className="admin-stat-label">Registered members</div>
            <div className="admin-stat-sub">{stats.totalSites} sites registered</div>
          </div>
        </Link>
        <Link to="/admin/bookings" className="admin-stat-card">
          <div className="admin-stat-icon blue"><CalendarCheck2 size={19} /></div>
          <div>
            <div className="admin-stat-value">{bookings ? bookings.length : "—"}</div>
            <div className="admin-stat-label">Total bookings</div>
            <div className="admin-stat-sub">{stats.upcoming} upcoming · {stats.completed} completed</div>
          </div>
        </Link>
        <Link to="/admin/quotations" className="admin-stat-card">
          <div className="admin-stat-icon amber"><FileText size={19} /></div>
          <div>
            <div className="admin-stat-value">{quotations ? quotations.length : "—"}</div>
            <div className="admin-stat-label">Quotations</div>
            <div className="admin-stat-sub">{stats.pendingQuotes} awaiting pricing</div>
          </div>
        </Link>
        <Link to="/admin/refunds" className="admin-stat-card">
          <div className="admin-stat-icon rose"><Wallet2 size={19} /></div>
          <div>
            <div className="admin-stat-value">{refunds ? refunds.length : "—"}</div>
            <div className="admin-stat-label">Refund requests</div>
            <div className="admin-stat-sub">{stats.pendingRefunds} pending action</div>
          </div>
        </Link>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1.6fr 1fr", gap: 16, marginTop: 22, alignItems: "start" }}>
        <div className="admin-panel">
          <div className="admin-panel-head">
            <h3>Recent bookings</h3>
            <Link to="/admin/bookings" className="btn-ghost sm" style={{ display: "flex", alignItems: "center", gap: 4, fontSize: 12.5, fontWeight: 700 }}>
              View all <ArrowUpRight size={13} />
            </Link>
          </div>
          {loading && !bookings ? (
            <>{[1, 2, 3].map(i => <div key={i} className="skeleton-row" />)}</>
          ) : recentBookings.length === 0 ? (
            <p className="muted" style={{ padding: "8px 2px" }}>No bookings yet.</p>
          ) : (
            <div className="admin-card-list">
              {recentBookings.map(b => {
                const meta = statusMeta[b.status] || { label: b.status, cls: "" };
                return (
                  <div className="admin-row" key={b.id}>
                    <div className="admin-row-main">
                      <div className="admin-row-icon"><CalendarCheck2 size={16} /></div>
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontWeight: 700, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                          {b.customerName} · {b.serviceType}
                        </div>
                        <div className="muted" style={{ fontSize: 12 }}>
                          {b.token} · {b.plantName} · {b.scheduledDate}
                        </div>
                      </div>
                    </div>
                    <span className={`status ${meta.cls}`} style={{ whiteSpace: "nowrap" }}>{meta.label}</span>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="admin-panel">
          <h3>Needs attention</h3>
          <div className="admin-card-list">
            <div className="admin-row">
              <div className="admin-row-main">
                <div className="admin-row-icon"><Clock3 size={16} /></div>
                <div>Bookings in progress</div>
              </div>
              <strong>{stats.upcoming}</strong>
            </div>
            <div className="admin-row">
              <div className="admin-row-main">
                <div className="admin-row-icon"><Wallet2 size={16} /></div>
                <div>Refunds pending</div>
              </div>
              <strong>{stats.pendingRefunds}</strong>
            </div>
            <div className="admin-row">
              <div className="admin-row-main">
                <div className="admin-row-icon"><FileText size={16} /></div>
                <div>Quotes to price</div>
              </div>
              <strong>{stats.pendingQuotes}</strong>
            </div>
            <div className="admin-row">
              <div className="admin-row-main">
                <div className="admin-row-icon"><CheckCircle2 size={16} /></div>
                <div>Completed bookings</div>
              </div>
              <strong>{stats.completed}</strong>
            </div>
            <div className="admin-row">
              <div className="admin-row-main">
                <div className="admin-row-icon"><XCircle size={16} /></div>
                <div>Cancelled bookings</div>
              </div>
              <strong>{stats.cancelled}</strong>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
