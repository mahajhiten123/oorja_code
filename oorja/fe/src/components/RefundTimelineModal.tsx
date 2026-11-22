import { useEffect, useState } from "react";
import { AlertTriangle, CalendarClock, Check, Clock3, Loader2, X, XCircle } from "lucide-react";
import { ApiError } from "../lib/api";
import { RefundsApi, inr, type Refund } from "../lib/refunds";

function when(iso?: string | null) {
  if (!iso) return null;
  return new Date(iso).toLocaleString("en-IN", {
    day: "2-digit", month: "short", year: "numeric", hour: "numeric", minute: "2-digit"
  });
}

/**
 * Where's my money? — answered from the refund's own audit trail rather
 * than inferred from a status badge.
 *
 * Every row here is a real recorded event: who did what, when, and for how
 * much, including the gateway retries and the provider's own confirmation.
 * Customers chasing a refund are usually anxious, and specificity is what
 * actually calms that down — "sent to your bank on 14 Mar, expected by
 * 21 Mar" beats "processing" every time.
 */
export function RefundTimelineModal({
  refund: initial,
  onClose,
  onRebook
}: {
  refund: Refund;
  onClose: () => void;
  onRebook?: (refund: Refund) => void;
}) {
  const [refund, setRefund] = useState<Refund>(initial);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // The list view carries a summary; the full event trail is fetched here so
  // the customer always sees the latest state rather than a cached badge.
  useEffect(() => {
    RefundsApi.one(initial.id)
      .then(setRefund)
      .catch(e => setError(e instanceof ApiError ? e.message : null))
      .finally(() => setLoading(false));
  }, [initial.id]);

  const icon = (status: string | null) => {
    if (status === "COMPLETED") return <Check size={14} />;
    if (status === "REJECTED" || status === "FAILED" || status === "CANCELLED") return <XCircle size={14} />;
    return <Clock3 size={14} />;
  };

  const canRebook = refund.status === "REQUESTED" || refund.status === "UNDER_REVIEW";

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card refund-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>{inr(refund.amountInr)} refund</h3>
            <span className="booking-token">{refund.refundNumber} · {refund.bookingToken}</span>
          </div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        <div className={`refund-status-banner status-${refund.status.toLowerCase()}`}>
          <strong>{refund.statusLabel}</strong>
          {refund.status === "PROCESSING" && refund.expectedSettlementAt && (
            <span>Expected by {when(refund.expectedSettlementAt)}</span>
          )}
          {refund.status === "COMPLETED" && refund.completedAt && (
            <span>Completed {when(refund.completedAt)} — it can take a few more days to show on your statement.</span>
          )}
          {refund.status === "FAILED" && (
            <span>We're retrying this automatically. Your money is not lost.</span>
          )}
          {refund.status === "REJECTED" && refund.adminNote && <span>{refund.adminNote}</span>}
        </div>

        {refund.deductionsInr > 0 && (
          <div className="refund-breakdown">
            <div><span>Amount paid</span><span>{inr(refund.grossInr)}</span></div>
            <div><span>Policy ({refund.refundPercentBps / 100}%)</span><span>−{inr(refund.deductionsInr - refund.cancellationFeeInr - refund.gatewayFeeInr)}</span></div>
            {refund.cancellationFeeInr > 0 && <div><span>Visit charge</span><span>−{inr(refund.cancellationFeeInr)}</span></div>}
            {refund.gatewayFeeInr > 0 && <div><span>Payment processing</span><span>−{inr(refund.gatewayFeeInr)}</span></div>}
            <div className="refund-breakdown-total"><span>Refund</span><strong>{inr(refund.amountInr)}</strong></div>
          </div>
        )}

        {loading && <p className="refund-loading"><Loader2 size={14} className="spin" /> Loading the latest updates…</p>}
        {error && <p className="form-error"><AlertTriangle size={13} /> {error}</p>}

        <ol className="refund-timeline">
          {refund.events.map(e => (
            <li key={e.id} className={`refund-step ${e.status ? e.status.toLowerCase() : "note"}`}>
              <span className="refund-step-dot">{icon(e.status)}</span>
              <div>
                <b>{e.message}</b>
                <small>
                  {when(e.at)}
                  {e.actorLabel ? ` · ${e.actorLabel}` : ""}
                  {e.amountInr != null && e.amountInr > 0 ? ` · ${inr(e.amountInr)}` : ""}
                </small>
              </div>
            </li>
          ))}
        </ol>

        {refund.razorpayRefundId && (
          <p className="refund-ref muted">Bank reference {refund.razorpayRefundId} — quote this to your bank if you need to trace it.</p>
        )}

        {canRebook && onRebook && (
          <div className="rebook-offer">
            <div className="rebook-offer-head">
              <CalendarClock size={16} />
              <div>
                <strong>Changed your mind?</strong>
                <p>Book this service again instead and we'll put the full {inr(refund.grossInr)} back towards it — no waiting for the refund to land.</p>
              </div>
            </div>
            <button className="btn btn-primary full" onClick={() => onRebook(refund)}>Rebook this service</button>
          </div>
        )}
      </div>
    </div>
  );
}
