import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, RefreshCw, RotateCw, Scale, X } from "lucide-react";
import { ApiError } from "../../lib/api";
import {
  AdminRefundsApi,
  REFUND_STATUS_CLASS,
  inr,
  type Refund,
  type ReconciliationReport
} from "../../lib/refunds";

type Filter = "queue" | "inflight" | "done" | "all";

const FILTERS: { key: Filter; label: string; match: (r: Refund) => boolean }[] = [
  { key: "queue", label: "Needs action", match: r => ["REQUESTED", "UNDER_REVIEW", "FAILED"].includes(r.status) },
  { key: "inflight", label: "In flight", match: r => ["APPROVED", "PROCESSING"].includes(r.status) },
  { key: "done", label: "Settled", match: r => ["COMPLETED", "REJECTED", "CANCELLED"].includes(r.status) },
  { key: "all", label: "All", match: () => true }
];

/**
 * Admin → Refunds: the approval desk (stage 4) and the ops view of stages
 * 5–8.
 *
 * The design assumption is that an admin approving a refund wants to see
 * the policy's reasoning, not re-derive it: the tier, the itemised
 * deductions and the customer's words are all on the card, so the decision
 * is "do I agree?" rather than "what is this?". Overriding the amount is
 * possible but deliberately a second step.
 */
export function AdminRefunds() {
  const [refunds, setRefunds] = useState<Refund[] | null>(null);
  const [filter, setFilter] = useState<Filter>("queue");
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejecting, setRejecting] = useState<Refund | null>(null);
  const [overriding, setOverriding] = useState<Refund | null>(null);
  const [report, setReport] = useState<ReconciliationReport | null>(null);

  const load = () => {
    AdminRefundsApi.list()
      .then(setRefunds)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load refunds."));
    AdminRefundsApi.reconciliation().then(setReport).catch(() => setReport(null));
  };

  useEffect(load, []);

  const visible = useMemo(
    () => (refunds ?? []).filter(FILTERS.find(f => f.key === filter)!.match),
    [refunds, filter]
  );

  const act = async (id: number, fn: () => Promise<Refund>) => {
    setBusyId(id);
    setError(null);
    try {
      const updated = await fn();
      setRefunds(prev => (prev ?? []).map(r => (r.id === updated.id ? updated : r)));
      setRejecting(null);
      setOverriding(null);
      AdminRefundsApi.reconciliation().then(setReport).catch(() => undefined);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "That didn't work.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-head">
        <h2>Refunds</h2>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>

      {/* ---- Stage 8: does the money add up? ---- */}
      {report && (
        <div className={`recon-strip ${report.mismatchCount > 0 ? "recon-alert" : ""}`}>
          <div><span>Captured</span><strong>{inr(report.totalCapturedInr)}</strong></div>
          <div><span>Refunded</span><strong>{inr(report.totalRefundedInr)}</strong></div>
          <div><span>Net held</span><strong>{inr(report.netHeldInr)}</strong></div>
          <div><span>Awaiting approval</span><strong>{report.pendingApproval}</strong></div>
          <div><span>In flight</span><strong>{report.inFlight}</strong></div>
          <div>
            <span><Scale size={12} /> Ledger</span>
            <strong>{report.mismatchCount === 0 ? "Balanced" : `${report.mismatchCount} mismatch`}</strong>
          </div>
        </div>
      )}

      {report && report.mismatchCount > 0 && (
        <div className="recon-mismatches">
          <b><AlertTriangle size={14} /> Payments whose refund ledger disagrees with their refund records</b>
          {report.mismatches.map(m => (
            <div key={m.paymentNumber}>
              <span>{m.paymentNumber}</span>
              <span>captured {inr(m.capturedInr)} · ledger {inr(m.ledgerRefundedInr)} · records {inr(m.refundRecordsInr)}</span>
              <small>{m.detail}</small>
            </div>
          ))}
        </div>
      )}

      <div className="admin-tabs">
        {FILTERS.map(f => (
          <button key={f.key} className={filter === f.key ? "active" : ""} onClick={() => setFilter(f.key)}>
            {f.label}
            <span className="count">{(refunds ?? []).filter(f.match).length}</span>
          </button>
        ))}
      </div>

      {error && <p className="form-error">{error}</p>}
      {refunds === null && <p className="muted">Loading…</p>}
      {refunds && visible.length === 0 && <p className="muted">Nothing here.</p>}

      <div className="admin-list">
        {visible.map(r => (
          <div key={r.id} className="admin-card">
            <div className="admin-card-head">
              <div>
                <b>{inr(r.amountInr)}</b>
                <span className="booking-token">{r.refundNumber} · {r.bookingToken}</span>
              </div>
              <span className={`status ${REFUND_STATUS_CLASS[r.status]}`}>{r.statusLabel}</span>
            </div>

            <p className="muted small">
              {r.customerName}{r.customerPhone ? ` · ${r.customerPhone}` : ""} · {r.serviceLabel}
            </p>

            <div className="refund-breakdown compact">
              <div><span>Paid</span><span>{inr(r.grossInr)}</span></div>
              <div><span>Policy {r.refundPercentBps / 100}% · {r.policyTier}</span><span>−{inr(r.deductionsInr)}</span></div>
              <div className="refund-breakdown-total"><span>Refund</span><strong>{inr(r.amountInr)}</strong></div>
            </div>

            {r.eligibilityNote && <p className="muted small">{r.eligibilityNote}</p>}
            {r.reason && <p className="muted small">Customer: “{r.reason}”</p>}
            {r.lastError && <p className="form-error small">Gateway: {r.lastError} (attempt {r.attemptCount})</p>}
            {r.autoApproved && <p className="muted small">Auto-approved under policy.</p>}
            {r.approvedBy && !r.autoApproved && <p className="muted small">Approved by {r.approvedBy}.</p>}
            {r.razorpayRefundId && <p className="muted small">Gateway ref {r.razorpayRefundId} ({r.gatewayStatus})</p>}

            <div className="admin-card-actions">
              {(r.status === "REQUESTED" || r.status === "UNDER_REVIEW") && (
                <>
                  <button className="btn btn-primary" disabled={busyId === r.id}
                          onClick={() => act(r.id, () => AdminRefundsApi.approve(r.id))}>
                    <Check size={13} /> Approve {inr(r.amountInr)}
                  </button>
                  <button className="btn btn-ghost" disabled={busyId === r.id} onClick={() => setOverriding(r)}>
                    Change amount
                  </button>
                  <button className="btn btn-ghost danger" disabled={busyId === r.id} onClick={() => setRejecting(r)}>
                    <X size={13} /> Reject
                  </button>
                </>
              )}
              {(r.status === "FAILED" || r.status === "APPROVED") && (
                <button className="btn btn-primary" disabled={busyId === r.id}
                        onClick={() => act(r.id, () => AdminRefundsApi.retry(r.id))}>
                  <RotateCw size={13} /> Retry gateway
                </button>
              )}
            </div>

            <details className="refund-audit">
              <summary>Audit trail ({r.events.length})</summary>
              <ol>
                {r.events.map(e => (
                  <li key={e.id}>
                    <span>{new Date(e.at).toLocaleString("en-IN")}</span>
                    <b>{e.type}</b>
                    <span>{e.message}</span>
                    <small>{e.actorLabel}</small>
                  </li>
                ))}
              </ol>
            </details>
          </div>
        ))}
      </div>

      {rejecting && (
        <NoteModal
          title={`Reject ${rejecting.refundNumber}?`}
          hint="The customer sees this reason on their refund timeline, so write it for them."
          confirmLabel="Reject refund"
          busy={busyId === rejecting.id}
          onClose={() => setRejecting(null)}
          onConfirm={note => act(rejecting.id, () => AdminRefundsApi.reject(rejecting.id, note))}
        />
      )}

      {overriding && (
        <NoteModal
          title={`Change the amount on ${overriding.refundNumber}`}
          hint={`Policy calculated ${inr(overriding.amountInr)} of ${inr(overriding.grossInr)} paid. The override is capped at what's still refundable on the original payment.`}
          confirmLabel="Approve this amount"
          amountDefault={overriding.amountInr}
          busy={busyId === overriding.id}
          onClose={() => setOverriding(null)}
          onConfirm={(note, amount) =>
            act(overriding.id, () => AdminRefundsApi.approve(overriding.id, { amountInr: amount, note }))
          }
        />
      )}
    </div>
  );
}

function NoteModal({
  title, hint, confirmLabel, amountDefault, busy, onClose, onConfirm
}: {
  title: string;
  hint: string;
  confirmLabel: string;
  amountDefault?: number;
  busy: boolean;
  onClose: () => void;
  onConfirm: (note: string, amount?: number) => void;
}) {
  const [note, setNote] = useState("");
  const [amount, setAmount] = useState(amountDefault ?? 0);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <p className="muted small">{hint}</p>
        {amountDefault !== undefined && (
          <label>Refund amount (₹)
            <input type="number" min={0} step="0.01" value={amount} onChange={e => setAmount(Number(e.target.value))} />
          </label>
        )}
        <label>Note
          <textarea value={note} onChange={e => setNote(e.target.value)} />
        </label>
        <div className="modal-actions">
          <button className="btn btn-ghost full" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="btn btn-primary full" disabled={busy}
                  onClick={() => onConfirm(note.trim(), amountDefault !== undefined ? amount : undefined)}>
            {busy ? "Working…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export default AdminRefunds;
