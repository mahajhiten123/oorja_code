import { useEffect, useMemo, useState } from "react";
import {
  AlertTriangle, Banknote, Calendar, CheckCircle2, Clock3, CreditCard,
  Eye, RefreshCw, Search, Tag, User, Wallet2, X, XCircle
} from "lucide-react";
import { AdminPaymentsApi, ApiError, type AdminPayment, type PaymentStatus } from "../../lib/api";

const STATUS_META: Record<PaymentStatus, { label: string; cls: string; icon: typeof Clock3 }> = {
  CREATED: { label: "Awaiting payment", cls: "pending", icon: Clock3 },
  PENDING: { label: "Pending", cls: "pending", icon: Clock3 },
  CAPTURED: { label: "Captured", cls: "completed", icon: CheckCircle2 },
  FAILED: { label: "Failed", cls: "cancelled", icon: XCircle },
  CANCELLED: { label: "Cancelled", cls: "cancelled", icon: XCircle },
  REFUNDED: { label: "Refunded", cls: "assigned", icon: RefreshCw },
  PARTIALLY_REFUNDED: { label: "Partially refunded", cls: "assigned", icon: RefreshCw }
};

const FILTERS: (PaymentStatus | "ALL")[] = ["ALL", "CAPTURED", "CREATED", "FAILED", "REFUNDED", "PARTIALLY_REFUNDED", "CANCELLED"];

function money(n: number, currency = "INR") {
  const symbol = currency === "INR" ? "₹" : currency + " ";
  return `${symbol}${n.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

function formatDate(iso: string | null) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

function whatFor(p: AdminPayment) {
  if (p.quotationId) return `Quotation #${p.quotationId}`;
  if (p.serviceId) return p.tierId ? `${p.serviceId} · ${p.tierId}` : p.serviceId;
  return "—";
}

export function AdminPayments() {
  const [payments, setPayments] = useState<AdminPayment[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<PaymentStatus | "ALL">("ALL");
  const [query, setQuery] = useState("");
  const [detail, setDetail] = useState<AdminPayment | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setPayments(await AdminPaymentsApi.list());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Something went wrong.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const openDetail = async (p: AdminPayment) => {
    setDetail(p);
    setDetailLoading(true);
    try {
      const full = await AdminPaymentsApi.get(p.id);
      setDetail(full);
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't load this payment's transaction history.");
    } finally {
      setDetailLoading(false);
    }
  };

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (payments ?? []).filter(p => {
      if (filter !== "ALL" && p.status !== filter) return false;
      if (!q) return true;
      return (
        p.paymentNumber.toLowerCase().includes(q) ||
        p.customerName.toLowerCase().includes(q) ||
        p.customerPhone.toLowerCase().includes(q) ||
        (p.bookingToken || "").toLowerCase().includes(q) ||
        p.razorpayOrderId.toLowerCase().includes(q) ||
        (p.razorpayPaymentId || "").toLowerCase().includes(q)
      );
    });
  }, [payments, filter, query]);

  const capturedTotal = (payments ?? [])
    .filter(p => p.status === "CAPTURED")
    .reduce((sum, p) => sum + p.amountInr, 0);

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">PAYMENTS</span>
          <h1>Payment ledger</h1>
          <p className="muted">
            Every Razorpay order ever opened, whether or not it was ever paid — with the full transaction
            trail for each one.
          </p>
        </div>
        <button className="btn btn-ghost sm" onClick={load} disabled={loading}>
          <RefreshCw size={15} className={loading ? "spin-icon" : ""} /> Refresh
        </button>
      </div>

      {!loading && !error && payments && payments.length > 0 && (
        <div className="admin-callout" style={{ background: "linear-gradient(90deg,#eff9f2,#f6fdf8)", borderColor: "#d8eddf", color: "#0b6b45" }}>
          <Banknote size={16} />
          {money(capturedTotal)} captured across {payments.filter(p => p.status === "CAPTURED").length} payment{payments.filter(p => p.status === "CAPTURED").length === 1 ? "" : "s"}.
        </div>
      )}

      <div className="service-toolbar" style={{ marginTop: 16, marginBottom: 0 }}>
        <div className="searchbox">
          <Search size={16} />
          <input
            placeholder="Search by customer, phone, booking, payment or order id…"
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
        </div>
        <select
          className="filter-btn"
          value={filter}
          onChange={e => setFilter(e.target.value as PaymentStatus | "ALL")}
          style={{ minWidth: 170 }}
        >
          {FILTERS.map(f => (
            <option key={f} value={f}>{f === "ALL" ? "All statuses" : STATUS_META[f].label}</option>
          ))}
        </select>
      </div>

      {loading && !payments && (
        <div style={{ marginTop: 16 }}>
          {[1, 2, 3].map(i => <div key={i} className="skeleton-row" />)}
        </div>
      )}

      {error && !loading && (
        <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px", marginTop: 16 }}>
          <AlertTriangle size={20} />
          <h3 style={{ margin: "14px 0 6px" }}>Couldn't load payments</h3>
          <p className="muted">Something went wrong: {error}</p>
          <button className="btn btn-primary sm" style={{ marginTop: 14 }} onClick={load}>Retry</button>
        </div>
      )}

      {!loading && !error && payments && filtered.length === 0 && (
        <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px", marginTop: 16 }}>
          <Wallet2 size={20} />
          <h3 style={{ margin: "14px 0 6px" }}>{payments.length === 0 ? "No payments yet" : "No payments match"}</h3>
          <p className="muted">
            {payments.length === 0
              ? "Payments show up here the moment a customer opens a Razorpay order."
              : "Try a different search or status filter."}
          </p>
        </div>
      )}

      {!loading && !error && payments && filtered.length > 0 && (
        <div className="admin-table-wrap" style={{ marginTop: 16 }}>
          <table className="admin-table">
            <thead>
              <tr>
                <th>Payment</th>
                <th>Customer</th>
                <th>Booking</th>
                <th>For</th>
                <th>Amount</th>
                <th>Created</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(p => {
                const meta = STATUS_META[p.status];
                return (
                  <tr key={p.id}>
                    <td style={{ fontFamily: "monospace", fontSize: 12 }}>{p.paymentNumber}</td>
                    <td>
                      <div style={{ fontWeight: 700 }}>{p.customerName}</div>
                      <div className="muted" style={{ fontSize: 11.5 }}>{p.customerPhone}</div>
                    </td>
                    <td>{p.bookingToken || "—"}</td>
                    <td className="muted" style={{ fontSize: 12.5 }}>{whatFor(p)}</td>
                    <td style={{ fontWeight: 700 }}>{money(p.amountInr, p.currency)}</td>
                    <td className="muted" style={{ whiteSpace: "nowrap" }}>{formatDate(p.createdAt)}</td>
                    <td><span className={`status ${meta.cls}`}>{meta.label}</span></td>
                    <td>
                      <button className="btn-ghost sm" onClick={() => openDetail(p)} title="View details">
                        <Eye size={14} />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {detail && (
        <div className="modal-overlay" onClick={() => setDetail(null)}>
          <div className="modal-card" style={{ maxWidth: 480 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
              <h3 style={{ margin: 0 }}>{detail.paymentNumber}</h3>
              <button className="btn-ghost sm" onClick={() => setDetail(null)}><X size={16} /></button>
            </div>
            <span className={`status ${STATUS_META[detail.status].cls}`} style={{ marginBottom: 14, display: "inline-flex" }}>
              {STATUS_META[detail.status].label}
            </span>

            <div className="detail-section-title">Customer & booking</div>
            <dl className="detail-kv">
              <dt><User size={13} style={{ verticalAlign: -2 }} /> Customer</dt><dd>{detail.customerName}</dd>
              <dt>Phone</dt><dd>{detail.customerPhone}</dd>
              <dt><Calendar size={13} style={{ verticalAlign: -2 }} /> Booking</dt><dd>{detail.bookingToken || "Not yet created"}</dd>
              <dt><Tag size={13} style={{ verticalAlign: -2 }} /> For</dt><dd>{whatFor(detail)}</dd>
              {detail.couponCode && <><dt>Coupon</dt><dd>{detail.couponCode}</dd></>}
            </dl>

            <hr className="detail-divider" />
            <div className="detail-section-title">Amount & references</div>
            <dl className="detail-kv">
              <dt><Banknote size={13} style={{ verticalAlign: -2 }} /> Amount</dt><dd>{money(detail.amountInr, detail.currency)}</dd>
              <dt><CreditCard size={13} style={{ verticalAlign: -2 }} /> Order id</dt><dd style={{ fontFamily: "monospace", fontSize: 12 }}>{detail.razorpayOrderId}</dd>
              {detail.razorpayPaymentId && <><dt>Payment id</dt><dd style={{ fontFamily: "monospace", fontSize: 12 }}>{detail.razorpayPaymentId}</dd></>}
              <dt>Created</dt><dd>{formatDate(detail.createdAt)}</dd>
              {detail.capturedAt && <><dt>Captured</dt><dd>{formatDate(detail.capturedAt)}</dd></>}
              {detail.failedAt && <><dt>Failed</dt><dd>{formatDate(detail.failedAt)}</dd></>}
            </dl>

            <hr className="detail-divider" />
            <div className="detail-section-title">Transaction history</div>
            {detailLoading && <div className="skeleton-row" style={{ height: 36 }} />}
            {!detailLoading && detail.transactions.length === 0 && (
              <p className="muted" style={{ fontSize: 13, margin: 0 }}>No transaction events recorded.</p>
            )}
            {!detailLoading && detail.transactions.length > 0 && (
              <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: 10 }}>
                {detail.transactions.map(t => (
                  <li key={t.id} style={{ display: "flex", justifyContent: "space-between", gap: 12, fontSize: 12.5, borderLeft: "2px solid var(--admin-border, #e5e9e6)", paddingLeft: 10 }}>
                    <div>
                      <div style={{ fontWeight: 700 }}>{t.type.replaceAll("_", " ")}</div>
                      <div className="muted">{formatDate(t.createdAt)}{t.providerTransactionId ? ` · ${t.providerTransactionId}` : ""}</div>
                      {t.metadata && <div className="muted" style={{ marginTop: 2 }}>{t.metadata}</div>}
                    </div>
                    <div style={{ fontWeight: 700, whiteSpace: "nowrap" }}>{money(t.amountInr, detail.currency)}</div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
