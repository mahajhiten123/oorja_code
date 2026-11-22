import { useEffect, useState } from "react";
import { AlertTriangle, X } from "lucide-react";
import { ApiError, BookingsApi, type ApiBooking, type SlotAvailability } from "../lib/api";
import { RefundsApi, inr, type Refund } from "../lib/refunds";

/**
 * "Actually, book me in instead."
 *
 * The cheapest refund is the one that never has to happen — for the
 * customer, who otherwise waits a week and pays again, and for us. So as
 * long as the money hasn't left yet, a pending refund can be turned back
 * into a booking: the refund is withdrawn and the original payment covers
 * the new slot, in one transaction on the server.
 */
export function RebookFromRefundModal({
  refund,
  onClose,
  onDone
}: {
  refund: Refund;
  onClose: () => void;
  onDone: (booking: ApiBooking) => void;
}) {
  const [date, setDate] = useState("");
  const [slots, setSlots] = useState<SlotAvailability[] | null>(null);
  const [time, setTime] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!date) return;
    setSlots(null);
    setTime("");
    BookingsApi.slots(date).then(setSlots).catch(() => setSlots([]));
  }, [date]);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onDone(await RefundsApi.rebookFromRefund(refund.id, date, time));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't rebook this service. Try another slot.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>Rebook {refund.bookingToken}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        <p className="refund-policy-note">
          We'll cancel the {inr(refund.amountInr)} refund and put your original payment of {inr(refund.grossInr)}
          {" "}towards this booking instead — nothing to pay now.
        </p>

        <label>Date
          <input type="date" value={date} min={new Date().toISOString().slice(0, 10)} onChange={e => setDate(e.target.value)} />
        </label>

        {date && (
          <div className="slot-grid">
            {slots === null && <p className="muted">Checking availability…</p>}
            {slots?.map(s => (
              <button
                key={s.time}
                className={`slot-chip ${time === s.time ? "selected" : ""}`}
                disabled={!s.available}
                onClick={() => setTime(s.time)}
              >
                {s.label}
                <small>{s.full ? "Full" : s.tooSoon ? "Too soon" : `${s.remaining} left`}</small>
              </button>
            ))}
          </div>
        )}

        {error && <p className="form-error"><AlertTriangle size={13} /> {error}</p>}

        <div className="modal-actions">
          <button className="btn btn-ghost full" onClick={onClose} disabled={busy}>Keep the refund</button>
          <button className="btn btn-primary full" disabled={busy || !date || !time} onClick={submit}>
            {busy ? "Booking…" : "Confirm booking"}
          </button>
        </div>
      </div>
    </div>
  );
}
