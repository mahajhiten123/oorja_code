import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  ArrowRight, CalendarCheck2, FileText, Plus, ShieldCheck, SunMedium,
  Wallet2, Wrench
} from "lucide-react";
import { useAuth } from "../auth/AuthContext";
import { BookingsApi, PlantsApi, QuotationsApi, type ApiBooking, type Plant, type Quotation } from "../lib/api";
import { RefundsApi, type Refund } from "../lib/refunds";
import { ContinueCard } from "../components/ContinueCard";
import { getContinuity, type ContinuityEntry } from "../data/continuityStore";

function formatDate(iso: string) {
  return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}

const STATUS_LABEL: Record<string, string> = { PENDING: "Pending", ASSIGNED: "Assigned", ON_SITE: "On site", COMPLETED: "Completed", CANCELLED: "Cancelled" };
const STATUS_CLASS: Record<string, string> = { PENDING: "pending", ASSIGNED: "assigned", ON_SITE: "on_site", COMPLETED: "completed", CANCELLED: "cancelled" };

export function Dashboard() {
  const { user } = useAuth();
  const [bookings, setBookings] = useState<ApiBooking[] | null>(null);
  const [plants, setPlants] = useState<Plant[] | null>(null);
  const [quotations, setQuotations] = useState<Quotation[] | null>(null);
  const [refunds, setRefunds] = useState<Refund[] | null>(null);
  const [continuity, setContinuity] = useState<ContinuityEntry | null>(null);

  useEffect(() => {
    BookingsApi.mine().then(setBookings).catch(() => setBookings([]));
    PlantsApi.mine().then(setPlants).catch(() => setPlants([]));
    QuotationsApi.mine().then(setQuotations).catch(() => setQuotations([]));
    RefundsApi.mine().then(setRefunds).catch(() => setRefunds([]));
    setContinuity(getContinuity());
  }, []);

  const upcoming = useMemo(
    () => (bookings || []).filter(b => b.status === "PENDING" || b.status === "ASSIGNED" || b.status === "ON_SITE")
      .sort((a, b) => a.scheduledDate.localeCompare(b.scheduledDate)),
    [bookings]
  );
  const pendingQuotes = useMemo(() => (quotations || []).filter(q => q.status === "SENT"), [quotations]);
  // .open covers REQUESTED/UNDER_REVIEW/APPROVED/PROCESSING/FAILED — anything
  // still owed and not yet settled or withdrawn.
  const pendingRefunds = useMemo(() => (refunds || []).filter(r => r.open), [refunds]);

  const firstName = (user?.name || "there").split(" ")[0];

  return (
    <div className="page dashboard-page">
      <div className="page-heading">
        <span className="kicker">MY DASHBOARD</span>
        <h1>Welcome back, {firstName}.</h1>
        <p>Here's what's happening with your solar plants and services.</p>
      </div>

      {continuity && <ContinueCard entry={continuity} onDismiss={() => setContinuity(null)} />}

      <div className="admin-stat-grid dash-stat-grid">
        <Link to="/plants" className="admin-stat-card">
          <div className="admin-stat-icon mint"><SunMedium size={19} /></div>
          <div>
            <div className="admin-stat-value">{plants ? plants.length : "—"}</div>
            <div className="admin-stat-label">My plants</div>
          </div>
        </Link>
        <Link to="/bookings" className="admin-stat-card">
          <div className="admin-stat-icon blue"><CalendarCheck2 size={19} /></div>
          <div>
            <div className="admin-stat-value">{bookings ? upcoming.length : "—"}</div>
            <div className="admin-stat-label">Upcoming bookings</div>
          </div>
        </Link>
        <Link to="/quotations" className="admin-stat-card">
          <div className="admin-stat-icon amber"><FileText size={19} /></div>
          <div>
            <div className="admin-stat-value">{quotations ? pendingQuotes.length : "—"}</div>
            <div className="admin-stat-label">Quotes to review</div>
          </div>
        </Link>
        <Link to="/bookings" className="admin-stat-card">
          <div className="admin-stat-icon rose"><Wallet2 size={19} /></div>
          <div>
            <div className="admin-stat-value">{refunds ? pendingRefunds.length : "—"}</div>
            <div className="admin-stat-label">Refunds in progress</div>
          </div>
        </Link>
      </div>

      <div className="dash-quick-actions">
        <Link to="/services" className="dash-action"><Wrench size={16} /> Book a service</Link>
        <Link to="/plants" className="dash-action"><Plus size={16} /> Add a plant</Link>
        <Link to="/amc" className="dash-action"><ShieldCheck size={16} /> Explore AMC plans</Link>
      </div>

      <div className="dashboard-columns">
        <div className="admin-panel">
          <div className="admin-panel-head">
            <h3>Upcoming bookings</h3>
            <Link to="/bookings" className="text-link" style={{ fontSize: 12.5 }}>View all <ArrowRight size={14} /></Link>
          </div>
          {bookings === null && <p className="muted" style={{ fontSize: 13 }}>Loading…</p>}
          {bookings !== null && upcoming.length === 0 && (
            <div className="empty-state" style={{ padding: "30px 10px" }}>
              <p style={{ margin: 0 }}>No upcoming bookings. <Link to="/services" className="text-link" style={{ display: "inline-flex" }}>Book a service</Link></p>
            </div>
          )}
          {upcoming.length > 0 && (
            <div className="admin-card-list">
              {upcoming.slice(0, 5).map(b => (
                <div className="admin-row" key={b.id}>
                  <div className="admin-row-main">
                    <div className="admin-row-icon"><CalendarCheck2 size={16} /></div>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 700, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{b.serviceType}</div>
                      <div className="muted" style={{ fontSize: 12 }}>{b.plantName} · {formatDate(b.scheduledDate)}</div>
                    </div>
                  </div>
                  <span className={`status ${STATUS_CLASS[b.status]}`}>{STATUS_LABEL[b.status]}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="admin-panel">
          <div className="admin-panel-head">
            <h3>Quotations needing you</h3>
            <Link to="/quotations" className="text-link" style={{ fontSize: 12.5 }}>View all <ArrowRight size={14} /></Link>
          </div>
          {quotations === null && <p className="muted" style={{ fontSize: 13 }}>Loading…</p>}
          {quotations !== null && pendingQuotes.length === 0 && (
            <div className="empty-state" style={{ padding: "30px 10px" }}>
              <p style={{ margin: 0 }}>Nothing waiting on you right now.</p>
            </div>
          )}
          {pendingQuotes.length > 0 && (
            <div className="admin-card-list">
              {pendingQuotes.slice(0, 5).map(q => (
                <div className="admin-row" key={q.id}>
                  <div className="admin-row-main">
                    <div className="admin-row-icon"><FileText size={16} /></div>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontWeight: 700 }}>{q.serviceType || "Installation quote"}</div>
                      <div className="muted" style={{ fontSize: 12 }}>
                        {q.amountInr != null ? `₹${q.amountInr.toLocaleString("en-IN")}` : "Awaiting price"}
                      </div>
                    </div>
                  </div>
                  <Link to="/quotations" className="btn-ghost sm">Review</Link>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
