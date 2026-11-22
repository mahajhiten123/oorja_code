import { useEffect, useState } from "react";
import { Wallet2, AlertTriangle, ChevronRight } from "lucide-react";
import { Link } from "react-router-dom";
import { ApiError } from "../lib/api";
import { RefundsApi, REFUND_STATUS_CLASS, inr, type Refund } from "../lib/refunds";
import { RefundTimelineModal } from "../components/RefundTimelineModal";
import { RebookFromRefundModal } from "../components/RebookFromRefundModal";
import { addNotification } from "../data/notificationsStore";

function when(iso: string) {
  return new Date(iso).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

function formatDate(iso: string) {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}
function formatTime(hhmmss: string) {
  const [h, m] = hhmmss.split(":").map(Number);
  const period = h >= 12 ? "PM" : "AM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${String(m).padStart(2, "0")} ${period}`;
}

/**
 * Profile → Refunds.
 *
 * Every refund a customer has ever raised, in one ledger — not just the
 * ones still attached to a visible booking. Cancelling from My Bookings
 * still works exactly as before; this page is the "where's my money,
 * going back as far as I need" view, same story as Invoices is for what
 * they were charged.
 */
export function Refunds() {
  const [refunds, setRefunds] = useState<Refund[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [viewing, setViewing] = useState<Refund | null>(null);
  const [rebooking, setRebooking] = useState<Refund | null>(null);

  const load = () => {
    setError(null);
    RefundsApi.mine()
      .then(list => setRefunds(list.sort((a, b) => (a.requestedAt < b.requestedAt ? 1 : -1))))
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your refunds."));
  };

  useEffect(load, []);

  return (
    <div className="page">
      <div className="page-heading">
        <span className="kicker">BILLING</span>
        <h1>Refunds</h1>
        <p className="muted">Every refund you've requested and where it stands — from approval through to settlement.</p>
      </div>

      {error && (
        <div className="empty-state">
          <AlertTriangle size={18} color="#c54c44" />
          <b>Couldn't load refunds</b>
          <p>{error}</p>
          <button className="btn btn-primary" onClick={load}>Retry</button>
        </div>
      )}

      {!error && refunds === null && <p className="muted">Loading…</p>}

      {!error && refunds?.length === 0 && (
        <div className="empty-state">
          <Wallet2 size={28} />
          <b>No refunds yet</b>
          <p>Cancelling a paid booking automatically raises one — you'll see it here the moment it's requested.</p>
          <Link className="btn btn-primary" to="/bookings">Go to My Bookings</Link>
        </div>
      )}

      <div className="invoice-list">
        {(refunds ?? []).map(r => (
          <button
            key={r.id}
            className="invoice-row"
            style={{ width: "100%", textAlign: "left", background: "white" }}
            onClick={() => setViewing(r)}
          >
            <div className="invoice-row-main">
              <span className="invoice-icon"><Wallet2 size={16} /></span>
              <div>
                <b>{r.refundNumber}</b>
                <span className="muted small">
                  {r.serviceLabel ?? "Service"} · {r.bookingToken} · Requested {when(r.requestedAt)}
                </span>
                <span className={`status ${REFUND_STATUS_CLASS[r.status]}`} style={{ marginTop: 4, width: "max-content" }}>
                  {r.statusLabel}
                </span>
              </div>
              <b className="invoice-amount">{inr(r.amountInr)}</b>
            </div>
            <div className="invoice-row-actions">
              <span className="muted small">
                {r.deductionsInr > 0 ? `of ${inr(r.grossInr)} paid` : "Full amount"}
                {r.expectedSettlementAt ? ` · Expected by ${when(r.expectedSettlementAt)}` : ""}
              </span>
              <span className="text-link" style={{ fontSize: 12 }}>Track refund <ChevronRight size={14} /></span>
            </div>
          </button>
        ))}
      </div>

      {viewing && (
        <RefundTimelineModal
          refund={viewing}
          onClose={() => setViewing(null)}
          onRebook={r => { setViewing(null); setRebooking(r); }}
        />
      )}
      {rebooking && (
        <RebookFromRefundModal
          refund={rebooking}
          onClose={() => setRebooking(null)}
          onDone={b => {
            addNotification("booking", "Booking restored",
              `${b.serviceType} ${b.token} is back on for ${formatDate(b.scheduledDate)}, ${formatTime(b.scheduledTime)} — your original payment covers it.`);
            setRebooking(null);
            load();
          }}
        />
      )}
    </div>
  );
}

export default Refunds;
