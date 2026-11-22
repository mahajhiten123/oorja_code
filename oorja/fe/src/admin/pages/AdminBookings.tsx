import { AlertTriangle, Banknote, Calendar, CheckCircle2, Clock, CreditCard, Eye, MapPin, Paperclip, Receipt, Trash2, UserCog, Wrench, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AdminBookingsApi, AdminPaymentsApi, AdminTechniciansApi, ApiError, openAdminFile, type AdminPayment, type ApiBooking, type ApiBookingStatus, type Technician } from "../../lib/api";

function money(paise: number) {
  return `₹${(paise / 100).toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

function moneyInr(inr: number) {
  return `₹${inr.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

function formatDateTime(iso: string) {
  return new Date(iso).toLocaleString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

type Tab = "upcoming" | "completed" | "cancelled";

const TAB_STATUSES: Record<Tab, ApiBookingStatus[]> = {
  upcoming: ["PENDING", "ASSIGNED", "ON_SITE"],
  completed: ["COMPLETED"],
  cancelled: ["CANCELLED"]
};

const STATUS_LABEL: Record<ApiBookingStatus, string> = {
  PENDING: "Pending",
  ASSIGNED: "Technician assigned",
  ON_SITE: "Technician on site",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled"
};

export function AdminBookings() {
  const [tab, setTab] = useState<Tab>("upcoming");
  const [bookings, setBookings] = useState<ApiBooking[] | null>(null);
  const [technicians, setTechnicians] = useState<Technician[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [assigning, setAssigning] = useState<ApiBooking | null>(null);
  const [completing, setCompleting] = useState<ApiBooking | null>(null);
  const [viewing, setViewing] = useState<ApiBooking | null>(null);
  const [busyDocId, setBusyDocId] = useState<number | null>(null);

  const load = () => {
    setError(null);
    Promise.all([AdminBookingsApi.list(), AdminTechniciansApi.list()])
      .then(([b, t]) => { setBookings(b.sort((x, y) => (x.scheduledDate < y.scheduledDate ? 1 : -1))); setTechnicians(t); })
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load bookings."));
  };

  useEffect(load, []);

  const list = useMemo(() => (bookings ?? []).filter(b => TAB_STATUSES[tab].includes(b.status)), [bookings, tab]);

  /**
   * Admins may delete any attachment. The reason is recorded on the audit
   * trail; the file is soft-deleted and its bytes are purged later by the
   * nightly job, so a mistake here is recoverable.
   */
  const handleDeleteAttachment = async (bookingId: number, attachmentId: number, fileName: string) => {
    const reason = prompt(`Delete "${fileName}"? Optionally record a reason:`);
    if (reason === null) return;
    setBusyDocId(attachmentId);
    try {
      await AdminBookingsApi.deleteAttachment(attachmentId, reason.trim() || undefined);
      setBookings(prev => (prev ?? []).map(x =>
        x.id === bookingId ? { ...x, attachments: (x.attachments ?? []).filter(a => a.id !== attachmentId) } : x
      ));
      setViewing(v => (v && v.id === bookingId
        ? { ...v, attachments: (v.attachments ?? []).filter(a => a.id !== attachmentId) }
        : v));
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't delete this file.");
    } finally {
      setBusyDocId(null);
    }
  };

  const handleCancel = async (b: ApiBooking) => {
    const reason = prompt(`Reason for cancelling booking ${b.token}?`) || undefined;
    setBusyId(b.id);
    try {
      await AdminBookingsApi.cancel(b.id, reason);
      load();
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't cancel this booking.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">BOOKINGS</span>
          <h1>Manage service bookings</h1>
          <p className="muted">Assign technicians, mark jobs complete, and handle cancellations.</p>
        </div>
      </div>

      <div className="tabs">
        {(["upcoming", "completed", "cancelled"] as Tab[]).map(t => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
            {t.charAt(0).toUpperCase() + t.slice(1)}
            <span>{(bookings ?? []).filter(b => TAB_STATUSES[t].includes(b.status)).length}</span>
          </button>
        ))}
      </div>

      {error && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><AlertTriangle size={20} /><h3 style={{ margin: "14px 0 6px" }}>Couldn't load bookings</h3><p className="muted">{error}</p><button className="btn btn-primary sm" style={{ marginTop: 14 }} onClick={load}>Retry</button></div>}
      {!error && bookings === null && <><div className="skeleton-row" /><div className="skeleton-row" /><div className="skeleton-row" /></>}
      {!error && bookings !== null && list.length === 0 && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><Wrench size={20} /><h3 style={{ margin: "14px 0 6px" }}>No {tab} bookings</h3><p className="muted">Nothing to show here right now.</p></div>}

      <div className="bookings-list">
        {list.map(b => {
          const canAssign = b.status === "PENDING" || b.status === "ASSIGNED";
          const canComplete = b.status === "ASSIGNED" || b.status === "ON_SITE";
          const canCancel = b.status !== "COMPLETED" && b.status !== "CANCELLED";
          return (
            <div key={b.id} className="booking-card">
              <div className="booking-card-head">
                <div><b>{b.serviceType}</b><span className="booking-token">{b.token}</span></div>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <span className={`status ${b.status.toLowerCase()}`}>{STATUS_LABEL[b.status]}</span>
                  <button className="btn-ghost sm" onClick={() => setViewing(b)} title="View details">
                    <Eye size={14} />
                  </button>
                </div>
              </div>
              <div className="booking-meta">
                <span><Calendar size={14} /> {b.scheduledDate}</span>
                <span><Clock size={14} /> {b.scheduledTime?.slice(0, 5)}</span>
                <span><MapPin size={14} /> {b.plantName}</span>
              </div>
              <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "6px 0 0" }}>
                Customer: {b.customerName} · {b.customerPhone}
              </p>
              <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "4px 0 0" }}>
                Technician: {b.technicianName && b.technicianName !== "Unassigned" ? b.technicianName : "Unassigned"}
              </p>
              {b.notes && <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "4px 0 0" }}>Note: {b.notes}</p>}
              {b.attachments.length > 0 && (
                <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 8 }}>
                  {b.attachments.map(a => (
                    <span key={a.id} style={{ display: "inline-flex", alignItems: "center", gap: 2 }}>
                      <button
                        className="btn btn-ghost sm"
                        style={{ fontSize: 11.5, padding: "5px 9px" }}
                        onClick={() => openAdminFile(a.url).catch(e => alert(e instanceof ApiError ? e.message : "Couldn't open this file."))}
                      >
                        <Paperclip size={11} style={{ verticalAlign: -1, marginRight: 4 }} />
                        {a.fileName}
                      </button>
                      <button
                        className="btn btn-ghost sm danger"
                        style={{ fontSize: 11.5, padding: "5px 7px" }}
                        title="Delete file"
                        disabled={busyDocId === a.id}
                        onClick={() => handleDeleteAttachment(b.id, a.id, a.fileName)}
                      >
                        <Trash2 size={11} />
                      </button>
                    </span>
                  ))}
                </div>
              )}
              {(canAssign || canComplete || canCancel) && (
                <div className="booking-actions">
                  {canAssign && <button onClick={() => setAssigning(b)} disabled={busyId === b.id}><UserCog size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Assign technician</button>}
                  {canComplete && <button onClick={() => setCompleting(b)} disabled={busyId === b.id}><CheckCircle2 size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Mark service complete</button>}
                  {canCancel && <button className="danger" onClick={() => handleCancel(b)} disabled={busyId === b.id}><X size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> {busyId === b.id ? "Cancelling…" : "Cancel"}</button>}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {assigning && (
        <AssignModal booking={assigning} technicians={technicians} onClose={() => setAssigning(null)} onDone={() => { setAssigning(null); load(); }} />
      )}
      {completing && (
        <CompleteModal booking={completing} onClose={() => setCompleting(null)} onDone={() => { setCompleting(null); load(); }} />
      )}
      {viewing && (
        <BookingDetailModal
          booking={viewing}
          onClose={() => setViewing(null)}
          onDeleteAttachment={(attachmentId, fileName) => handleDeleteAttachment(viewing.id, attachmentId, fileName)}
        />
      )}
    </div>
  );
}

function BookingDetailModal({ booking, onClose, onDeleteAttachment }: {
  booking: ApiBooking;
  onClose: () => void;
  onDeleteAttachment: (attachmentId: number, fileName: string) => void;
}) {
  const [payment, setPayment] = useState<AdminPayment | null>(null);
  const [paymentState, setPaymentState] = useState<"loading" | "found" | "none">("loading");

  useEffect(() => {
    let cancelled = false;
    setPaymentState("loading");
    AdminPaymentsApi.byBooking(booking.id)
      .then(p => { if (!cancelled) { setPayment(p); setPaymentState("found"); } })
      .catch(() => { if (!cancelled) setPaymentState("none"); });
    return () => { cancelled = true; };
  }, [booking.id]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" style={{ maxWidth: 480 }} onClick={e => e.stopPropagation()}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 6 }}>
          <h3 style={{ margin: 0 }}>{booking.token}</h3>
          <button className="btn-ghost sm" onClick={onClose}><X size={16} /></button>
        </div>
        <span className={`status ${booking.status.toLowerCase()}`} style={{ marginBottom: 14, display: "inline-flex" }}>
          {STATUS_LABEL[booking.status]}
        </span>

        <div className="detail-section-title">Service</div>
        <dl className="detail-kv">
          <dt>Service</dt><dd>{booking.serviceLabel || booking.serviceType}</dd>
          <dt><Calendar size={13} style={{ verticalAlign: -2 }} /> Scheduled</dt><dd>{booking.scheduledDate} · {booking.scheduledTime?.slice(0, 5)}</dd>
          <dt><MapPin size={13} style={{ verticalAlign: -2 }} /> Plant</dt><dd>{booking.plantName}</dd>
          {booking.location && <><dt>Location</dt><dd>{booking.location}</dd></>}
          <dt>Booked on</dt><dd>{formatDateTime(booking.createdAt)}</dd>
        </dl>

        <hr className="detail-divider" />
        <div className="detail-section-title">Customer & technician</div>
        <dl className="detail-kv">
          <dt>Customer</dt><dd>{booking.customerName}</dd>
          <dt>Phone</dt><dd>{booking.customerPhone}</dd>
          <dt>Email</dt><dd>{booking.customerEmail}</dd>
          <dt><UserCog size={13} style={{ verticalAlign: -2 }} /> Technician</dt>
          <dd>{booking.technicianName && booking.technicianName !== "Unassigned" ? booking.technicianName : "Unassigned"}</dd>
        </dl>

        {booking.notes && (
          <>
            <hr className="detail-divider" />
            <div className="detail-section-title">Notes</div>
            <p style={{ fontSize: 13.5, margin: 0 }}>{booking.notes}</p>
          </>
        )}

        {booking.attachments.length > 0 && (
          <>
            <hr className="detail-divider" />
            <div className="detail-section-title">Attachments</div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
              {booking.attachments.map(a => (
                <span key={a.id} style={{ display: "inline-flex", alignItems: "center", gap: 2 }}>
                  <button
                    className="btn btn-ghost sm"
                    style={{ fontSize: 11.5, padding: "5px 9px" }}
                    onClick={() => openAdminFile(a.url).catch(e => alert(e instanceof ApiError ? e.message : "Couldn't open this file."))}
                  >
                    <Paperclip size={11} style={{ verticalAlign: -1, marginRight: 4 }} />
                    {a.fileName}
                  </button>
                  <button
                    className="btn btn-ghost sm danger"
                    style={{ fontSize: 11.5, padding: "5px 7px" }}
                    title="Delete file"
                    onClick={() => onDeleteAttachment(a.id, a.fileName)}
                  >
                    <Trash2 size={11} />
                  </button>
                </span>
              ))}
            </div>
          </>
        )}

        <hr className="detail-divider" />
        <div className="detail-section-title">Payment</div>
        {paymentState === "loading" && <div className="skeleton-row" style={{ height: 36 }} />}
        {paymentState === "none" && (
          <p className="muted" style={{ fontSize: 13, margin: 0 }}>
            {booking.amountPaise
              ? <>{money(booking.amountPaise)} recorded on this booking{booking.paymentRef ? ` · ${booking.paymentRef}` : ""}, but no matching ledger entry was found.</>
              : "No payment is attached to this booking."}
          </p>
        )}
        {paymentState === "found" && payment && (
          <dl className="detail-kv">
            <dt><Banknote size={13} style={{ verticalAlign: -2 }} /> Amount</dt><dd>{moneyInr(payment.amountInr)}</dd>
            <dt><Receipt size={13} style={{ verticalAlign: -2 }} /> Payment #</dt><dd style={{ fontFamily: "monospace", fontSize: 12 }}>{payment.paymentNumber}</dd>
            <dt>Status</dt><dd>{payment.status}</dd>
            {payment.razorpayPaymentId && <><dt><CreditCard size={13} style={{ verticalAlign: -2 }} /> Payment id</dt><dd style={{ fontFamily: "monospace", fontSize: 12 }}>{payment.razorpayPaymentId}</dd></>}
            {payment.capturedAt && <><dt>Captured</dt><dd>{formatDateTime(payment.capturedAt)}</dd></>}
          </dl>
        )}
      </div>
    </div>
  );
}

function AssignModal({ booking, technicians, onClose, onDone }: { booking: ApiBooking; technicians: Technician[]; onClose: () => void; onDone: () => void }) {
  const [technicianId, setTechnicianId] = useState<number | "">("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    if (!technicianId) return;
    setBusy(true); setError(null);
    try {
      await AdminBookingsApi.assignTechnician(booking.id, Number(technicianId));
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't assign a technician.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>Assign technician · {booking.token}</h3><button className="icon-button" onClick={onClose}><X size={18} /></button></div>
        <label>Technician
          <select value={technicianId} onChange={e => setTechnicianId(e.target.value ? Number(e.target.value) : "")}>
            <option value="" disabled>Select a technician</option>
            {technicians.map(t => <option key={t.id} value={t.id} disabled={t.status === "OFF_DUTY"}>{t.name} · {t.specialization} ({t.status.replace("_", " ").toLowerCase()})</option>)}
          </select>
        </label>
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={!technicianId || busy} onClick={handleSubmit}>{busy ? "Assigning…" : "Assign"}</button>
      </div>
    </div>
  );
}

function CompleteModal({ booking, onClose, onDone }: { booking: ApiBooking; onClose: () => void; onDone: () => void }) {
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async () => {
    setBusy(true); setError(null);
    try {
      await AdminBookingsApi.complete(booking.id, notes.trim() || undefined);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't mark this booking complete.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>Mark service complete · {booking.token}</h3><button className="icon-button" onClick={onClose}><X size={18} /></button></div>
        <p className="field-hint" style={{ marginBottom: 12 }}>This flips the booking to <b>Completed</b> immediately in the customer's app.</p>
        <label>Completion notes (optional)<textarea rows={3} value={notes} onChange={e => setNotes(e.target.value)} placeholder="Work done, parts replaced, readings, etc." /></label>
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={busy} onClick={handleSubmit}>{busy ? "Saving…" : "Mark as completed"}</button>
      </div>
    </div>
  );
}
