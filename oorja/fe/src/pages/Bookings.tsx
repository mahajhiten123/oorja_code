import { AlertTriangle, Calendar, Clock, Download, Eye, FileText, MapPin, Paperclip, RotateCcw, ShieldQuestion, Trash2, UploadCloud, Wallet2, Wrench, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ApiError, BookingsApi, PlantsApi, openCustomerFile, type ApiBooking, type ApiBookingStatus, type Plant, type SlotAvailability } from "../lib/api";
import { RefundsApi, REFUND_STATUS_CLASS, inr, type Refund } from "../lib/refunds";
import { addNotification } from "../data/notificationsStore";
import { RefundTimelineModal } from "../components/RefundTimelineModal";
import { CancelOrRebookModal } from "../components/CancelOrRebookModal";
import { RebookFromRefundModal } from "../components/RebookFromRefundModal";

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
function formatBytes(n: number | null) {
  if (!n) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function Bookings() {
  const [tab, setTab] = useState<Tab>("upcoming");
  const [bookings, setBookings] = useState<ApiBooking[] | null>(null);
  const [plants, setPlants] = useState<Record<number, Plant>>({});
  const [refunds, setRefunds] = useState<Record<number, Refund>>({});
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rescheduling, setRescheduling] = useState<ApiBooking | null>(null);
  const [cancelling, setCancelling] = useState<ApiBooking | null>(null);
  const [viewingRefund, setViewingRefund] = useState<Refund | null>(null);
  const [rebookingRefund, setRebookingRefund] = useState<Refund | null>(null);
  const [uploadingId, setUploadingId] = useState<number | null>(null);
  const [uploadError, setUploadError] = useState<{ id: number; message: string } | null>(null);
  const [busyDocId, setBusyDocId] = useState<number | null>(null);

  const load = () => {
    setError(null);
    Promise.all([BookingsApi.mine(), PlantsApi.mine(), RefundsApi.mine().catch(() => [] as Refund[])])
      .then(([b, p, r]) => {
        setBookings(b.sort((x, y) => (x.scheduledDate < y.scheduledDate ? 1 : -1)));
        setPlants(Object.fromEntries(p.map(pl => [pl.id, pl])));
        setRefunds(Object.fromEntries(r.map(rf => [rf.bookingId, rf])));
      })
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your bookings."));
  };

  useEffect(load, []);

  const list = (bookings ?? []).filter(b => TAB_STATUSES[tab].includes(b.status));

  // The cancel sheet can end in two completely different places: the booking
  // is gone and a refund is owed, or the booking simply moved and no money
  // changed hands. Both come back from the same call.
  const handleCancelOutcome = (outcome: { booking: ApiBooking; refundId: number | null; rebooked: boolean }) => {
    if (outcome.rebooked) {
      addNotification("booking", "Booking moved",
        `${outcome.booking.serviceType} ${outcome.booking.token} is now ${formatDate(outcome.booking.scheduledDate)}, ${formatTime(outcome.booking.scheduledTime)}. Your payment moved with it.`);
    } else {
      addNotification("booking", "Booking cancelled",
        outcome.refundId
          ? `${outcome.booking.serviceType} ${outcome.booking.token} is cancelled and your refund is on its way.`
          : `${outcome.booking.serviceType} ${outcome.booking.token} has been cancelled.`);
    }
    setCancelling(null);
    load();
  };

  const handleRescheduled = (updated: ApiBooking) => {
    addNotification("booking", "Booking rescheduled", `${updated.serviceType} booking ${updated.token} moved to ${formatDate(updated.scheduledDate)}, ${formatTime(updated.scheduledTime)}.`);
    setRescheduling(null);
    load();
  };

  const handleUploadFiles = async (b: ApiBooking, files: FileList | null) => {
    if (!files || files.length === 0) return;
    setUploadError(null);
    setUploadingId(b.id);
    try {
      const updated = await BookingsApi.addAttachments(b.id, Array.from(files));
      setBookings(prev => (prev ?? []).map(x => (x.id === updated.id ? updated : x)));
    } catch (e) {
      setUploadError({ id: b.id, message: e instanceof ApiError ? e.message : "Couldn't upload this file." });
    } finally {
      setUploadingId(null);
    }
  };

  const handleDeleteDoc = async (b: ApiBooking, attachmentId: number, fileName: string) => {
    if (!window.confirm(`Delete "${fileName}"? This removes it from your booking.`)) return;
    setBusyDocId(attachmentId);
    setUploadError(null);
    try {
      await BookingsApi.deleteAttachment(attachmentId);
      // Drop it locally rather than refetching every booking for one removal.
      setBookings(prev => (prev ?? []).map(x =>
        x.id === b.id ? { ...x, attachments: (x.attachments ?? []).filter(a => a.id !== attachmentId) } : x
      ));
    } catch (e) {
      setUploadError({ id: b.id, message: e instanceof ApiError ? e.message : "Couldn't delete this file." });
    } finally {
      setBusyDocId(null);
    }
  };

  const handleOpenDoc = async (attachmentId: number, fileName: string, customerUrl: string, mode: "view" | "download") => {
    setBusyDocId(attachmentId);
    try {
      await openCustomerFile(customerUrl, fileName, mode);
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't open this file.");
    } finally {
      setBusyDocId(null);
    }
  };

  return <div className="page bookings-page">
    <span className="kicker">MY BOOKINGS</span><h1>Track your service history</h1>
    <div style={{ display: "flex", gap: 18, flexWrap: "wrap", marginBottom: 14 }}>
      <Link to="/quotations" className="text-link" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
        <FileText size={14} /> Requested a quote? Track it here
      </Link>
      <Link to="/help" className="text-link" style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
        <ShieldQuestion size={14} /> Cancellation & refund policy
      </Link>
    </div>
    <div className="tabs">
      {(["upcoming", "completed", "cancelled"] as Tab[]).map(t => (
        <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>
          {t.charAt(0).toUpperCase() + t.slice(1)}<span>{(bookings ?? []).filter(b => TAB_STATUSES[t].includes(b.status)).length}</span>
        </button>
      ))}
    </div>

    {error && <div className="empty-state"><AlertTriangle size={18} color="#c54c44" /><b>Couldn't load bookings</b><p>{error}</p><button className="btn btn-primary" onClick={load}>Retry</button></div>}

    {!error && bookings === null && <p style={{ color: "var(--muted)", padding: "30px 0" }}>Loading your bookings…</p>}

    {!error && bookings !== null && list.length === 0 && (
      <div className="empty-state">
        <Wrench size={28} />
        <b>No {tab} bookings</b>
        <p>{tab === "upcoming" ? "Book a service to see it here." : `You don't have any ${tab} bookings yet.`}</p>
        {tab === "upcoming" && <Link className="btn btn-primary" to="/services">Browse Services</Link>}
      </div>
    )}

    <div className="bookings-list">
      {list.map(b => {
        const plant = plants[b.plantId];
        const refund = refunds[b.id];
        const canReschedule = b.status === "PENDING" || b.status === "ASSIGNED";
        const canCancel = b.status !== "COMPLETED" && b.status !== "CANCELLED";
        return (
          <div key={b.id} className="booking-card">
            <div className="booking-card-head">
              <div><b>{b.serviceType}</b><span className="booking-token">{b.token}</span></div>
              <span className={`status ${b.status.toLowerCase()}`}>{STATUS_LABEL[b.status]}</span>
            </div>
            <div className="booking-meta">
              <span><Calendar size={14} /> {formatDate(b.scheduledDate)}</span>
              <span><Clock size={14} /> {formatTime(b.scheduledTime)}</span>
              <span><MapPin size={14} /> {plant ? `${plant.name} · ${plant.location}` : b.plantName}</span>
            </div>
            {b.technicianName && b.technicianName !== "Unassigned" && (
              <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "6px 0 0" }}>Technician: {b.technicianName}</p>
            )}
            {b.notes && <p style={{ fontSize: 12.5, color: "var(--muted)", margin: "6px 0 0" }}>Note: {b.notes}</p>}

            {refund && (
              <div className="refund-line" onClick={() => setViewingRefund(refund)}>
                <Wallet2 size={14} />
                <span className={`status ${REFUND_STATUS_CLASS[refund.status]}`}>{refund.statusLabel}</span>
                <span className="muted">{inr(refund.amountInr)}{refund.deductionsInr > 0 ? ` of ${inr(refund.grossInr)}` : ""}</span>
                <span className="refund-line-hint">Track refund <FileText size={11} /></span>
              </div>
            )}

            <BookingDocuments
              booking={b}
              uploading={uploadingId === b.id}
              busyDocId={busyDocId}
              error={uploadError && uploadError.id === b.id ? uploadError.message : null}
              onUpload={files => handleUploadFiles(b, files)}
              onOpenDoc={handleOpenDoc}
              onDeleteDoc={(attachmentId, fileName) => handleDeleteDoc(b, attachmentId, fileName)}
            />

            {(canCancel || canReschedule) && (
              <div className="booking-actions">
                {canReschedule && <button onClick={() => setRescheduling(b)} disabled={busyId === b.id}><RotateCcw size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> Reschedule</button>}
                {canCancel && <button className="danger" onClick={() => setCancelling(b)} disabled={busyId === b.id}><X size={13} style={{ verticalAlign: -2, marginRight: 4 }} /> {busyId === b.id ? "Cancelling…" : "Cancel"}</button>}
              </div>
            )}
          </div>
        );
      })}
    </div>

    {rescheduling && <RescheduleModal booking={rescheduling} onClose={() => setRescheduling(null)} onDone={handleRescheduled} />}
    {cancelling && <CancelOrRebookModal booking={cancelling} onClose={() => setCancelling(null)} onDone={handleCancelOutcome} />}
    {viewingRefund && (
      <RefundTimelineModal
        refund={viewingRefund}
        onClose={() => setViewingRefund(null)}
        onRebook={r => { setViewingRefund(null); setRebookingRefund(r); }}
      />
    )}
    {rebookingRefund && (
      <RebookFromRefundModal
        refund={rebookingRefund}
        onClose={() => setRebookingRefund(null)}
        onDone={b => {
          addNotification("booking", "Booking restored",
            `${b.serviceType} ${b.token} is back on for ${formatDate(b.scheduledDate)}, ${formatTime(b.scheduledTime)} — your original payment covers it.`);
          setRebookingRefund(null);
          load();
        }}
      />
    )}
  </div>;
}

function BookingDocuments({
  booking, uploading, busyDocId, error, onUpload, onOpenDoc, onDeleteDoc
}: {
  booking: ApiBooking;
  uploading: boolean;
  busyDocId: number | null;
  error: string | null;
  onUpload: (files: FileList | null) => void;
  onOpenDoc: (attachmentId: number, fileName: string, customerUrl: string, mode: "view" | "download") => void;
  onDeleteDoc: (attachmentId: number, fileName: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const attachments = booking.attachments ?? [];

  return (
    <div className="booking-documents">
      <div className="detail-section-title" style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <Paperclip size={13} /> Documents &amp; Photos
      </div>

      {attachments.length > 0 && (
        <ul className="booking-doc-list">
          {attachments.map(a => (
            <li key={a.id} className="booking-doc-row">
              <FileText size={15} />
              <div className="booking-doc-info">
                <b>{a.fileName}</b>
                <span>{formatBytes(a.sizeBytes)}</span>
              </div>
              <button disabled={busyDocId === a.id} title="View" onClick={() => onOpenDoc(a.id, a.fileName, a.customerUrl, "view")}>
                <Eye size={14} />
              </button>
              <button disabled={busyDocId === a.id} title="Download" onClick={() => onOpenDoc(a.id, a.fileName, a.customerUrl, "download")}>
                <Download size={14} />
              </button>
              {a.customerDeletable && (
                <button
                  className="danger"
                  disabled={busyDocId === a.id}
                  title="Delete"
                  onClick={() => onDeleteDoc(a.id, a.fileName)}
                >
                  <Trash2 size={14} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {error && <p className="field-error"><AlertTriangle size={13} /> {error}</p>}

      <input
        ref={inputRef}
        type="file"
        multiple
        accept=".pdf,.jpg,.jpeg,.png,image/jpeg,image/png,application/pdf"
        style={{ display: "none" }}
        onChange={e => {
          onUpload(e.target.files);
          e.target.value = "";
        }}
      />
      <button
        type="button"
        className="text-link"
        style={{ display: "inline-flex", alignItems: "center", gap: 6, marginTop: 6 }}
        disabled={uploading}
        onClick={() => inputRef.current?.click()}
      >
        <UploadCloud size={14} /> {uploading ? "Uploading…" : "+ Upload File"}
      </button>
      <span className="field-hint" style={{ marginLeft: 10 }}>PDF, JPG, PNG • Max 15 MB each</span>
    </div>
  );
}

function RescheduleModal({ booking, onClose, onDone }: { booking: ApiBooking; onClose: () => void; onDone: (b: ApiBooking) => void }) {
  const [date, setDate] = useState(booking.scheduledDate);
  const [time, setTime] = useState(booking.scheduledTime.slice(0, 5));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [slots, setSlots] = useState<SlotAvailability[] | null>(null);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [slotsError, setSlotsError] = useState<string | null>(null);

  useEffect(() => {
    if (!date) { setSlots(null); return; }
    setSlotsLoading(true);
    setSlotsError(null);
    BookingsApi.slots(date)
      .then(list => {
        setSlots(list);
        // Keep the booking's current time selected even if it's since sold
        // out elsewhere - it's still this booking's own slot until they
        // actually pick something else. Otherwise, drop a pick that's no
        // longer available on the newly chosen date.
        const stillCurrent = date === booking.scheduledDate && time === booking.scheduledTime.slice(0, 5);
        setTime(prev => (stillCurrent || list.some(s => s.time.slice(0, 5) === prev && s.available) ? prev : ""));
      })
      .catch(e => setSlotsError(e instanceof ApiError ? e.message : "Couldn't load available time slots."))
      .finally(() => setSlotsLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [date]);

  const handleSubmit = async () => {
    setError(null);
    setSaving(true);
    try {
      const updated = await BookingsApi.reschedule(booking.id, { scheduledDate: date, scheduledTime: time });
      onDone(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't reschedule this booking.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Reschedule {booking.token}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label>New date<input type="date" value={date} min={new Date().toISOString().slice(0, 10)} onChange={e => setDate(e.target.value)} /></label>
        <label>New time slot</label>
        {slotsLoading && <p className="field-hint" style={{ marginBottom: 8 }}>Checking availability…</p>}
        {slotsError && <p className="field-error"><AlertTriangle size={14} /> {slotsError}</p>}
        {!slotsLoading && !slotsError && (
          <div className="time-slots" style={{ marginBottom: 14 }}>
            {(slots ?? []).map(slot => {
              const hhmm = slot.time.slice(0, 5);
              const isCurrentSlot = date === booking.scheduledDate && hhmm === booking.scheduledTime.slice(0, 5);
              const disabled = !slot.available && !isCurrentSlot;
              return (
                <button
                  key={hhmm}
                  type="button"
                  className={`time-slot ${time === hhmm ? "selected" : ""}`}
                  disabled={disabled}
                  title={disabled ? (slot.full ? "This slot is fully booked" : "Too soon to book") : undefined}
                  onClick={() => setTime(hhmm)}
                >
                  {slot.label}{slot.full && !isCurrentSlot ? " · Full" : ""}
                </button>
              );
            })}
          </div>
        )}
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={!date || !time || saving} onClick={handleSubmit}>{saving ? "Saving…" : "Confirm new slot"}</button>
      </div>
    </div>
  );
}

