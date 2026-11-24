import { AlertTriangle, ChevronDown, Clock, FileText, History, Send, ThumbsDown, ThumbsUp, X } from "lucide-react";
import { useEffect, useState } from "react";
import {
  AdminQuotationsApi, ApiError,
  type Quotation, type QuotationPricingInput, type QuotationRevisionRequest, type QuotationVersion
} from "../../lib/api";

const STATUS_LABEL: Record<Quotation["status"], string> = {
  DRAFT: "Awaiting quote",
  PENDING: "Awaiting quote",
  SENT: "Quote sent",
  VIEWED: "Viewed by customer",
  ACCEPTED: "Accepted",
  REJECTED: "Rejected",
  EXPIRED: "Expired"
};

const REVISION_REASON_LABEL: Record<string, string> = {
  PRICE: "Price", SERVICE: "Service", QUANTITY: "Quantity", AMC: "AMC", OTHER: "Other"
};

function money(n: number | null | undefined) {
  return n == null ? "—" : `₹${n.toLocaleString("en-IN")}`;
}

export function AdminQuotations() {
  const [quotations, setQuotations] = useState<Quotation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Quotation | null>(null);
  const [historyFor, setHistoryFor] = useState<Quotation | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  /** Open revision-request counts per quotation, loaded lazily for the badge on each card. */
  const [openCounts, setOpenCounts] = useState<Record<number, number>>({});

  const load = () => {
    setError(null);
    AdminQuotationsApi.list()
      .then(list => {
        setQuotations(list);
        // Only quotations currently awaiting a response can have live revision requests worth flagging.
        list.filter(q => q.status === "SENT" || q.status === "VIEWED").forEach(q => {
          AdminQuotationsApi.revisionRequests(q.id)
            .then(reqs => {
              const open = reqs.filter(r => r.status === "OPEN" || r.status === "IN_REVIEW").length;
              if (open > 0) setOpenCounts(prev => ({ ...prev, [q.id]: open }));
            })
            .catch(() => {});
        });
      })
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load quotations."));
  };

  useEffect(load, []);

  const handleStatus = async (q: Quotation, status: "ACCEPTED" | "REJECTED") => {
    setBusyId(q.id);
    try {
      await AdminQuotationsApi.updateStatus(q.id, status);
      load();
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't update this quotation.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">QUOTATIONS</span>
          <h1>Installation quotation requests</h1>
          <p className="muted">Price incoming requests, handle revision requests, and track them through to acceptance.</p>
        </div>
      </div>

      {error && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><AlertTriangle size={20} /><h3 style={{ margin: "14px 0 6px" }}>Couldn't load quotations</h3><p className="muted">{error}</p><button className="btn btn-primary sm" style={{ marginTop: 14 }} onClick={load}>Retry</button></div>}
      {!error && quotations === null && <><div className="skeleton-row" /><div className="skeleton-row" /></>}
      {!error && quotations !== null && quotations.length === 0 && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><FileText size={20} /><h3 style={{ margin: "14px 0 6px" }}>No quotation requests</h3><p className="muted">New installation quote requests will show up here.</p></div>}

      <div className="admin-card-list">
        {(quotations ?? []).map(q => (
          <div className="admin-panel" key={q.id}>
            <div className="admin-row" style={{ borderBottom: "none", paddingBottom: 0 }}>
              <div>
                <b>{q.customerName}</b> · {q.plantType.charAt(0) + q.plantType.slice(1).toLowerCase()} · {q.capacityKw} kW
                {!!q.currentVersionNumber && q.status !== "DRAFT" && q.status !== "PENDING" && (
                  <span className="muted" style={{ fontSize: 12, marginLeft: 8 }}>Revision {q.currentVersionNumber}</span>
                )}
                <div className="muted" style={{ fontSize: 12.5, marginTop: 3 }}>{q.siteAddress}</div>
                <div className="muted" style={{ fontSize: 12.5 }}>{q.customerPhone} · requested {new Date(q.requestedAt).toLocaleDateString("en-IN")}</div>
                {q.notes && <div className="muted" style={{ fontSize: 12.5, marginTop: 3 }}>Note: {q.notes}</div>}
              </div>
              <span className={`status ${q.status.toLowerCase()}`}>{STATUS_LABEL[q.status]}</span>
            </div>

            {q.amountInr != null && (
              <p style={{ fontSize: 13, marginTop: 10 }}>Quoted: <b>{money(q.amountInr)}</b>{q.validUntil && <> · valid until {q.validUntil}</>}{q.paid && <> · <span style={{ color: "var(--green)" }}>Paid</span></>}</p>
            )}

            {openCounts[q.id] > 0 && (
              <div style={{ marginTop: 8, display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                <p style={{ fontSize: 12.5, margin: 0, color: "var(--amber, #92660a)", display: "flex", alignItems: "center", gap: 5 }}>
                  <Clock size={13} /> {openCounts[q.id]} open revision request{openCounts[q.id] > 1 ? "s" : ""} from the customer
                </p>
                <button className="btn btn-ghost sm" style={{ fontSize: 11.5, padding: "3px 8px" }} onClick={() => setEditing(q)}>
                  Review requests
                </button>
              </div>
            )}

            {q.attachments && q.attachments.length > 0 && (
              <div style={{ marginTop: 8, display: "flex", flexWrap: "wrap", gap: 6 }}>
                {q.attachments.map(a => (
                  <a key={a.id} href={a.url} target="_blank" rel="noreferrer" className="text-link" style={{ fontSize: 12 }}>
                    {a.fileName}
                  </a>
                ))}
              </div>
            )}

            <div className="booking-actions" style={{ marginTop: 12 }}>
              {(q.status === "DRAFT" || q.status === "PENDING" || q.status === "SENT" || q.status === "VIEWED") && (
                <button onClick={() => setEditing(q)} disabled={busyId === q.id}><Send size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> {(q.status === "DRAFT" || q.status === "PENDING") ? "Send quote" : "Create revision"}</button>
              )}
              {(q.status === "SENT" || q.status === "VIEWED") && (
                <>
                  <button onClick={() => handleStatus(q, "ACCEPTED")} disabled={busyId === q.id}><ThumbsUp size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Mark accepted</button>
                  <button className="danger" onClick={() => handleStatus(q, "REJECTED")} disabled={busyId === q.id}><ThumbsDown size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Mark rejected</button>
                </>
              )}
              {(q.currentVersionNumber ?? 1) > 1 && (
                <button onClick={() => setHistoryFor(q)}><History size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Revision history</button>
              )}
            </div>
          </div>
        ))}
      </div>

      {editing && (
        <RevisionWorkspaceModal
          quotation={editing}
          onClose={() => setEditing(null)}
          onDone={() => { setEditing(null); load(); }}
        />
      )}
      {historyFor && <AdminVersionHistoryModal quotation={historyFor} onClose={() => setHistoryFor(null)} />}
    </div>
  );
}

/**
 * The admin's "Create Revision" workspace: shows the current version (if
 * any) and any open customer revision requests, then lets the admin price a
 * new version and either save it as a draft (doesn't touch the customer's
 * view) or send it (creates the official new version).
 */
function RevisionWorkspaceModal({ quotation, onClose, onDone }: { quotation: Quotation; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState(quotation.amountInr?.toString() ?? "");
  const [validUntil, setValidUntil] = useState(quotation.validUntil ?? "");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState<"draft" | "send" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [requests, setRequests] = useState<QuotationRevisionRequest[] | null>(null);
  const [resolvingWith, setResolvingWith] = useState<number | null>(null);

  const isFirstQuote = quotation.status === "DRAFT" || quotation.status === "PENDING";

  useEffect(() => {
    AdminQuotationsApi.revisionRequests(quotation.id)
      .then(all => setRequests(all.filter(r => r.status === "OPEN" || r.status === "IN_REVIEW")))
      .catch(() => setRequests([]));
  }, [quotation.id]);

  const buildPayload = (): QuotationPricingInput | null => {
    const amountInr = Number(amount);
    if (!amountInr || !validUntil) return null;
    return { amountInr, validUntil, notes: notes.trim() || undefined };
  };

  const handleSaveDraft = async () => {
    const payload = buildPayload();
    if (!payload) return;
    setBusy("draft"); setError(null);
    try {
      await AdminQuotationsApi.saveDraft(quotation.id, payload);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save this draft.");
    } finally {
      setBusy(null);
    }
  };

  const handleSend = async () => {
    const payload = buildPayload();
    if (!payload) return;
    setBusy("send"); setError(null);
    try {
      await AdminQuotationsApi.send(quotation.id, { ...payload, revisionRequestId: resolvingWith ?? undefined });
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't send this quote.");
    } finally {
      setBusy(null);
    }
  };

  const handleStartReview = async (requestId: number) => {
    try {
      const updated = await AdminQuotationsApi.startReview(requestId);
      setRequests(prev => (prev ?? []).map(r => r.id === requestId ? updated : r));
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't mark this request in-review.");
    }
  };

  const handleResolveRequest = async (requestId: number) => {
    const note = prompt("Optional note explaining how this request was resolved:") ?? undefined;
    try {
      await AdminQuotationsApi.resolveRevisionRequest(requestId, note || undefined);
      setRequests(prev => (prev ?? []).filter(r => r.id !== requestId));
      if (resolvingWith === requestId) setResolvingWith(null);
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't resolve this request.");
    }
  };

  const handleRejectRequest = async (requestId: number) => {
    const note = prompt("Optional note for why you're declining this request:") ?? undefined;
    try {
      await AdminQuotationsApi.rejectRevisionRequest(requestId, note || undefined);
      setRequests(prev => (prev ?? []).filter(r => r.id !== requestId));
      if (resolvingWith === requestId) setResolvingWith(null);
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't reject this request.");
    }
  };

  const valid = !!Number(amount) && !!validUntil;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{isFirstQuote ? "Send quote" : "Create revision"} · {quotation.customerName}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        {!isFirstQuote && (
          <p className="muted" style={{ fontSize: 12.5, marginTop: -6, marginBottom: 12 }}>
            Current version: v{quotation.currentVersionNumber} — {money(quotation.amountInr)}
          </p>
        )}

        {requests && requests.length > 0 && (
          <div style={{ marginBottom: 14, display: "flex", flexDirection: "column", gap: 8 }}>
            <div className="detail-section-title" style={{ margin: 0 }}>Customer revision requests</div>
            {requests.map(r => (
              <div key={r.id} style={{ border: "1px solid var(--admin-border, #e3e3e3)", borderRadius: 10, padding: "8px 10px", fontSize: 12.5 }}>
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                  <label style={{ display: "flex", alignItems: "flex-start", gap: 8, margin: 0, fontWeight: 400 }}>
                    <input type="radio" name="resolve-with" style={{ width: "auto", marginTop: 3 }}
                      checked={resolvingWith === r.id} onChange={() => setResolvingWith(r.id)} />
                    <span>
                      <b>{REVISION_REASON_LABEL[r.reason] ?? r.reason}</b> · v{r.quotationVersionNumber}
                      <div className="muted" style={{ marginTop: 2 }}>"{r.message}"</div>
                    </span>
                  </label>
                  <span className={`status ${r.status === "IN_REVIEW" ? "assigned" : "pending"}`} style={{ fontSize: 11 }}>
                    {r.status === "IN_REVIEW" ? "Under Review" : "Open"}
                  </span>
                </div>
                <div style={{ display: "flex", gap: 12, marginTop: 6, paddingLeft: 24 }}>
                  {r.status === "OPEN" && (
                    <button className="text-link" style={{ fontSize: 11.5 }} onClick={() => handleStartReview(r.id)}>
                      Start review
                    </button>
                  )}
                  <button className="text-link" style={{ fontSize: 11.5, color: "var(--green)" }} onClick={() => handleResolveRequest(r.id)}>
                    Complete review
                  </button>
                  <button className="text-link danger" style={{ fontSize: 11.5 }} onClick={() => handleRejectRequest(r.id)}>
                    Reject
                  </button>
                </div>
              </div>
            ))}
            {resolvingWith != null && (
              <p className="muted" style={{ fontSize: 11.5, margin: 0 }}>Selected request will be marked resolved once this version is sent.</p>
            )}
          </div>
        )}

        <label>Quoted amount (₹)<input type="number" min={0} value={amount} onChange={e => setAmount(e.target.value)} placeholder="e.g. 185000" /></label>
        <label>Valid until<input type="date" value={validUntil} onChange={e => setValidUntil(e.target.value)} /></label>
        <label>Notes for customer (optional)<textarea rows={3} value={notes} onChange={e => setNotes(e.target.value)} placeholder="Scope, inclusions, warranty terms, etc." /></label>
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}

        <div style={{ display: "flex", gap: 10, marginTop: 4 }}>
          <button className="btn btn-ghost full" disabled={!valid || busy !== null} onClick={handleSaveDraft}>
            {busy === "draft" ? "Saving…" : "Save Draft"}
          </button>
          <button className="btn btn-primary full" disabled={!valid || busy !== null} onClick={handleSend}>
            {busy === "send" ? "Sending…" : "Send to Customer"}
          </button>
        </div>
        <p className="muted" style={{ fontSize: 11.5, marginTop: 8 }}>
          Save Draft keeps this private — the customer sees nothing until you send it.
        </p>
      </div>
    </div>
  );
}

function AdminVersionHistoryModal({ quotation, onClose }: { quotation: Quotation; onClose: () => void }) {
  const [versions, setVersions] = useState<QuotationVersion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<number | null>(null);

  useEffect(() => {
    AdminQuotationsApi.versions(quotation.id)
      .then(v => setVersions(v.filter(x => x.status !== "DRAFT").slice().reverse()))
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load revision history."));
  }, [quotation.id]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>Revision history · {quotation.customerName}</h3><button className="icon-button" onClick={onClose}><X size={18} /></button></div>

        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        {!error && versions === null && <p className="muted">Loading…</p>}

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
                    <span className="muted" style={{ fontSize: 11.5 }}>{v.status}</span>
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
