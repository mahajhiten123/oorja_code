import { AlertTriangle, Calendar, CheckCircle2, ChevronDown, FileText, History, MapPin, MessageSquareWarning, ThumbsDown, ThumbsUp, Wallet, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, QuotationsApi, type Quotation, type QuotationVersion, type RevisionReason } from "../lib/api";
import { useAuth } from "../auth/AuthContext";
import { addNotification } from "../data/notificationsStore";
import { createRazorpayOrderForQuotation, openRazorpayCheckout, verifyRazorpayPayment, type RazorpayPaymentSuccess } from "../lib/razorpay";

const STATUS_LABEL: Record<Quotation["status"], string> = {
  DRAFT: "Awaiting quote",
  PENDING: "Awaiting quote",
  SENT: "Quote ready — review below",
  VIEWED: "Quote ready — review below",
  ACCEPTED: "Accepted",
  REJECTED: "Declined",
  EXPIRED: "Expired"
};

function formatDate(iso: string | null) {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

function quotationTitle(q: Quotation) {
  if (q.serviceType?.toLowerCase().includes("amc")) return q.serviceType;
  const plantLabel = q.plantType.charAt(0) + q.plantType.slice(1).toLowerCase();
  return q.serviceType && !q.serviceType.toLowerCase().includes("installation")
    ? `${q.serviceType} — ${plantLabel}`
    : `${plantLabel} installation`;
}

export function Quotations() {
  const { user } = useAuth();
  const [quotations, setQuotations] = useState<Quotation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<{ id: number; message: string } | null>(null);
  const [requestingRevision, setRequestingRevision] = useState<Quotation | null>(null);
  const [historyFor, setHistoryFor] = useState<Quotation | null>(null);

  const load = () => {
    setError(null);
    QuotationsApi.mine()
      .then(list => setQuotations(list.sort((a, b) => (a.requestedAt < b.requestedAt ? 1 : -1))))
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your quotations."));
  };

  useEffect(load, []);

  const handleReject = async (q: Quotation) => {
    if (!confirm("Decline this quotation? You can always request a fresh one later.")) return;
    setBusyId(q.id);
    setActionError(null);
    try {
      await QuotationsApi.reject(q.id);
      addNotification("booking", "Quotation declined", `You declined quotation ${q.quotationNumber ?? "#" + q.id}.`);
      load();
    } catch (e) {
      setActionError({ id: q.id, message: e instanceof ApiError ? e.message : "Couldn't update this quotation." });
    } finally {
      setBusyId(null);
    }
  };

  const payForQuotation = async (q: Quotation) => {
    if (q.amountInr == null) return;
    const qLabel = q.quotationNumber ?? `#${q.id}`;
    try {
      const order = await createRazorpayOrderForQuotation(q.id, `qrct_${q.id}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`);
      openRazorpayCheckout({
        order,
        description: `Quotation ${qLabel}`,
        customerName: user?.name ?? "Customer",
        customerEmail: user?.email,
        customerPhone: user?.phone,
        onSuccess: async (payment: RazorpayPaymentSuccess) => {
          // Restore busy state: Razorpay closed its modal but we still have async
          // work to do (verify signature + record payment on the backend).
          setBusyId(q.id);
          try {
            const verified = await verifyRazorpayPayment(payment);
            if (!verified) throw new Error("Payment could not be verified. Please contact support.");
            await QuotationsApi.recordPayment(q.id, order.orderId);
            addNotification("booking", "Quotation paid", `Payment received for quotation ${qLabel}. Our team will be in touch to schedule installation.`);
            load();
          } catch (e) {
            setActionError({
              id: q.id,
              message: e instanceof Error ? e.message : "Payment succeeded but we couldn't record it — please contact support with your payment reference."
            });
          } finally {
            setBusyId(null);
          }
        },
        onFailure: (err) => {
          setActionError({ id: q.id, message: err instanceof Error ? err.message : "Payment failed. Please try again." });
          setBusyId(null);
        },
        onDismiss: () => setBusyId(null)
      });
    } catch (e) {
      setActionError({ id: q.id, message: e instanceof ApiError ? e.message : "Couldn't start the payment. Please check your connection and try again." });
      setBusyId(null);
    }
  };

  const handlePayOnly = (q: Quotation) => {
    setBusyId(q.id);
    setActionError(null);
    payForQuotation(q);
  };

  const handleAcceptAndPay = async (q: Quotation) => {
    if (q.amountInr == null) return;
    setBusyId(q.id);
    setActionError(null);
    try {
      await QuotationsApi.accept(q.id);
    } catch (e) {
      setActionError({ id: q.id, message: e instanceof ApiError ? e.message : "Couldn't accept this quotation." });
      setBusyId(null);
      return;
    }
    await payForQuotation(q);
  };

  return (
    <div className="page bookings-page">
      <span className="kicker">MY QUOTATIONS</span>
      <h1>Track your quote requests</h1>

      {error && (
        <div className="empty-state">
          <AlertTriangle size={18} color="#c54c44" />
          <b>Couldn't load quotations</b>
          <p>{error}</p>
          <button className="btn btn-primary" onClick={load}>Retry</button>
        </div>
      )}

      {!error && quotations === null && <p style={{ color: "var(--muted)", padding: "30px 0" }}>Loading your quotations…</p>}

      {!error && quotations !== null && quotations.length === 0 && (
        <div className="empty-state">
          <FileText size={26} />
          <b>No quote requests yet</b>
          <p>Request a quote for an installation or an AMC plan to see its status here.</p>
          <div style={{ display: "flex", gap: 10, justifyContent: "center", marginTop: 6 }}>
            <Link className="btn btn-primary" to="/services">Browse Services</Link>
            <Link className="btn btn-ghost" to="/amc">View AMC Plans</Link>
          </div>
        </div>
      )}

      <div className="bookings-list">
        {(quotations ?? []).map(q => {
          const respondable = q.status === "SENT" || q.status === "VIEWED";
          const showPayCta = q.status === "ACCEPTED" && !q.paid;
          return (
            <div key={q.id} className="booking-card">
              <div className="booking-card-head">
                <div>
                  <b>{quotationTitle(q)}</b>
                  <span className="booking-token">{q.quotationNumber ?? `Q-${q.id}`}</span>
                  {!!q.currentVersionNumber && q.status !== "DRAFT" && q.status !== "PENDING" && (
                    <span className="booking-token" style={{ marginLeft: 6 }}>Revision {q.currentVersionNumber}</span>
                  )}
                </div>
                <span className={`status ${q.status.toLowerCase()}`}>{STATUS_LABEL[q.status]}</span>
              </div>
              <div className="booking-meta">
                <span><Calendar size={14} /> Requested {formatDate(q.requestedAt)}</span>
                <span><MapPin size={14} /> {q.siteAddress}</span>
              </div>

              {q.amountInr != null && (
                <p style={{ fontSize: 13, marginTop: 10 }}>
                  Quoted amount: <b>₹{q.amountInr.toLocaleString("en-IN")}</b>
                  {q.validUntil && <> · valid until {formatDate(q.validUntil)}</>}
                  {q.paid && <> · <span style={{ color: "var(--green)" }}><CheckCircle2 size={13} style={{ verticalAlign: -2 }} /> Paid</span></>}
                </p>
              )}
              {q.notes && <p style={{ fontSize: 12.5, color: "var(--muted)", marginTop: 6 }}>Note from our team: {q.notes}</p>}

              {q.attachments && q.attachments.length > 0 && (
                <div style={{ marginTop: 8, display: "flex", flexWrap: "wrap", gap: 6 }}>
                  {q.attachments.map(a => (
                    <a key={a.id} href={a.url} target="_blank" rel="noreferrer" className="text-link" style={{ fontSize: 12 }}>{a.fileName}</a>
                  ))}
                </div>
              )}

              {(q.status === "DRAFT" || q.status === "PENDING") && (
                <p style={{ fontSize: 12.5, color: "var(--muted)", marginTop: 10 }}>Our team is preparing your quote — you'll be notified by email once it's ready.</p>
              )}

              {actionError?.id === q.id && <p className="field-error" style={{ marginTop: 10 }}><AlertTriangle size={14} /> {actionError.message}</p>}

              {(respondable || showPayCta || (q.currentVersionNumber ?? 1) > 1) && (
                <div className="booking-actions" style={{ marginTop: 12 }}>
                  {showPayCta && (
                    <button onClick={() => handlePayOnly(q)} disabled={busyId === q.id}>
                      <Wallet size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> {busyId === q.id ? "Processing…" : "Pay now"}
                    </button>
                  )}
                  {respondable && (
                    <>
                      <button onClick={() => handleAcceptAndPay(q)} disabled={busyId === q.id}>
                        <ThumbsUp size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> {busyId === q.id ? "Processing…" : "Accept & pay"}
                      </button>
                      <button onClick={() => setRequestingRevision(q)} disabled={busyId === q.id}>
                        <MessageSquareWarning size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Request revision
                      </button>
                      <button className="danger" onClick={() => handleReject(q)} disabled={busyId === q.id}>
                        <ThumbsDown size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Decline
                      </button>
                    </>
                  )}
                  {(q.currentVersionNumber ?? 1) > 1 && (
                    <button onClick={() => setHistoryFor(q)}>
                      <History size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Revision history
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {requestingRevision && (
        <RequestRevisionModal
          quotation={requestingRevision}
          onClose={() => setRequestingRevision(null)}
          onSubmitted={() => { setRequestingRevision(null); load(); }}
        />
      )}
      {historyFor && <VersionHistoryModal quotation={historyFor} onClose={() => setHistoryFor(null)} />}
    </div>
  );
}

const REVISION_REASONS: { value: RevisionReason; label: string }[] = [
  { value: "PRICE", label: "Price" },
  { value: "SERVICE", label: "Service" },
  { value: "QUANTITY", label: "Quantity" },
  { value: "AMC", label: "AMC" },
  { value: "OTHER", label: "Other" }
];

function RequestRevisionModal({ quotation, onClose, onSubmitted }: {
  quotation: Quotation;
  onClose: () => void;
  onSubmitted: () => void;
}) {
  const [reason, setReason] = useState<RevisionReason>("PRICE");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!message.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await QuotationsApi.requestRevision(quotation.id, reason, message.trim());
      addNotification("booking", "Revision requested", `We've sent your request for ${quotation.quotationNumber ?? "quotation #" + quotation.id} to our team.`);
      onSubmitted();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't submit your request.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>Request a revision</h3><button className="btn-ghost sm" onClick={onClose}><X size={16} /></button></div>
        <label>
          What would you like changed?
          <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 8 }}>
            {REVISION_REASONS.map(r => (
              <label key={r.value} style={{ display: "inline-flex", alignItems: "center", gap: 5, fontWeight: 400, fontSize: 13.5, margin: 0 }}>
                <input type="radio" name="revision-reason" checked={reason === r.value} onChange={() => setReason(r.value)} style={{ width: "auto" }} />
                {r.label}
              </label>
            ))}
          </div>
        </label>
        <label>Message<textarea rows={4} value={message} onChange={e => setMessage(e.target.value)} placeholder="e.g. Please remove the cleaning service and reprice." /></label>
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={!message.trim() || busy} onClick={handleSubmit}>
          {busy ? "Submitting…" : "Submit Request"}
        </button>
      </div>
    </div>
  );
}

function money(n: number | null) {
  return n == null ? "—" : `₹${n.toLocaleString("en-IN")}`;
}

function VersionHistoryModal({ quotation, onClose }: { quotation: Quotation; onClose: () => void }) {
  const [versions, setVersions] = useState<QuotationVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);

  useEffect(() => {
    QuotationsApi.versions(quotation.id)
      .then(v => setVersions(v.slice().reverse())) // newest first
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load revision history."));
  }, [quotation.id]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>Revision history</h3><button className="btn-ghost sm" onClick={onClose}><X size={16} /></button></div>

        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        {!error && versions === null && <p style={{ color: "var(--muted)" }}>Loading…</p>}

        {versions && (
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {versions.map(v => (
              <div key={v.id} style={{ border: "1px solid var(--admin-border, #e3e3e3)", borderRadius: 12, padding: "10px 12px" }}>
                <button
                  onClick={() => setExpanded(expanded === v.versionNumber ? null : v.versionNumber)}
                  style={{ width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center", background: "none", border: 0, padding: 0, cursor: "pointer" }}
                >
                  <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <b>v{v.versionNumber}</b>
                    <span>{money(v.amountInr)}</span>
                    {v.current && <span className="status accepted" style={{ fontSize: 11 }}>CURRENT</span>}
                  </span>
                  <span style={{ display: "flex", alignItems: "center", gap: 6, color: "var(--muted)", fontSize: 12 }}>
                    {new Date(v.createdAt).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}
                    <ChevronDown size={14} style={{ transform: expanded === v.versionNumber ? "rotate(180deg)" : undefined, transition: "transform .15s" }} />
                  </span>
                </button>
                {expanded === v.versionNumber && (
                  <div style={{ marginTop: 8, paddingTop: 8, borderTop: "1px solid var(--admin-border, #eee)", fontSize: 12.5, color: "var(--muted)" }}>
                    {v.subtotal != null && <div>Subtotal: {money(v.subtotal)}</div>}
                    {!!v.discountAmount && <div>Discount: -{money(v.discountAmount)}</div>}
                    {!!v.taxAmount && <div>Tax: {money(v.taxAmount)}</div>}
                    {v.validUntil && <div>Valid until: {new Date(v.validUntil).toLocaleDateString("en-IN")}</div>}
                    {v.notes && <div style={{ marginTop: 4 }}>Note: {v.notes}</div>}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
