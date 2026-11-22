import { useEffect, useState } from "react";
import { AlertTriangle, ArrowRight, CalendarClock, Check, Loader2, X } from "lucide-react";
import { ApiError, BookingsApi, type ApiBooking, type SlotAvailability } from "../lib/api";
import {
  RefundsApi,
  formatSlot,
  inr,
  type CancellationPreview,
  type CancellationOutcome
} from "../lib/refunds";

/**
 * The cancellation sheet.
 *
 * The ordering here is the whole point. A customer who taps Cancel is told,
 * before anything is decided:
 *
 *   1. exactly what they'd get back, itemised, with the policy reason in
 *      plain words — not a surprise a week later;
 *   2. what moving the booking instead would be worth, when that's more;
 *   3. real open slots, so choosing the better option is one tap.
 *
 * Every number comes from GET /cancellation-preview, which runs the same
 * eligibility and calculation code the real refund will run. Nothing is
 * estimated client-side, so the figure shown is the figure paid.
 */
export function CancelOrRebookModal({
  booking,
  onClose,
  onDone
}: {
  booking: ApiBooking;
  onClose: () => void;
  onDone: (outcome: CancellationOutcome) => void;
}) {
  const [preview, setPreview] = useState<CancellationPreview | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<"choose" | "rebook" | "confirm">("choose");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Rebooking picker state
  const [date, setDate] = useState("");
  const [slots, setSlots] = useState<SlotAvailability[] | null>(null);
  const [time, setTime] = useState("");

  useEffect(() => {
    RefundsApi.preview(booking.id)
      .then(setPreview)
      .catch(e => setLoadError(e instanceof ApiError ? e.message : "Couldn't load your cancellation options."));
  }, [booking.id]);

  useEffect(() => {
    if (!date) return;
    setSlots(null);
    setTime("");
    BookingsApi.slots(date).then(setSlots).catch(() => setSlots([]));
  }, [date]);

  const pickSuggested = (d: string, t: string) => {
    setDate(d);
    setTime(t);
    setMode("rebook");
  };

  const doRebook = async () => {
    if (!date || !time) return;
    setBusy(true);
    setActionError(null);
    try {
      onDone(await RefundsApi.rebookInstead(booking.id, date, time, reason.trim() || undefined));
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : "Couldn't move this booking. Try another slot.");
    } finally {
      setBusy(false);
    }
  };

  const doCancel = async () => {
    setBusy(true);
    setActionError(null);
    try {
      onDone(await RefundsApi.cancel(booking.id, { reason: reason.trim() || undefined }));
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : "Couldn't cancel this booking.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card refund-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{mode === "rebook" ? "Move your booking" : `Cancel ${booking.token}?`}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        {loadError && <p className="form-error">{loadError}</p>}

        {!preview && !loadError && (
          <p className="refund-loading"><Loader2 size={15} className="spin" /> Checking your refund…</p>
        )}

        {preview && mode !== "rebook" && (
          <>
            {/* ---- What you'd get back ---- */}
            {preview.paidInr > 0 ? (
              <div className={`refund-quote ${preview.refundable ? "" : "refund-quote-empty"}`}>
                <span className="refund-quote-label">You'd get back</span>
                <strong className="refund-quote-amount">{inr(preview.refundAmountInr)}</strong>
                {preview.deductionsInr > 0 && (
                  <span className="refund-quote-sub">of the {inr(preview.paidInr)} you paid</span>
                )}
                <div className="refund-breakdown">
                  <div><span>Amount paid</span><span>{inr(preview.paidInr)}</span></div>
                  {preview.refundPercent < 100 && (
                    <div><span>Cancellation policy ({preview.refundPercent}%)</span>
                      <span>−{inr(preview.paidInr - preview.paidInr * (preview.refundPercent / 100))}</span></div>
                  )}
                  {preview.cancellationFeeInr > 0 && (
                    <div><span>Visit charge</span><span>−{inr(preview.cancellationFeeInr)}</span></div>
                  )}
                  {preview.gatewayFeeInr > 0 && (
                    <div><span>Payment processing</span><span>−{inr(preview.gatewayFeeInr)}</span></div>
                  )}
                  <div className="refund-breakdown-total">
                    <span>Refund to your original payment method</span><strong>{inr(preview.refundAmountInr)}</strong>
                  </div>
                </div>
                <p className="refund-policy-note">{preview.message}</p>
                {preview.refundable && (
                  <p className="refund-policy-note muted">
                    {preview.autoApproved
                      ? `Approved instantly — with your bank within ${preview.settlementDays} working days.`
                      : `Reviewed by our team, then with your bank within ${preview.settlementDays} working days of approval.`}
                  </p>
                )}
              </div>
            ) : (
              <p className="refund-policy-note">{preview.message}</p>
            )}

            {/* ---- The better option, when there is one ---- */}
            {preview.rebookAvailable && preview.rebookMessage && (
              <div className={`rebook-offer ${preview.rebookRecommended ? "rebook-offer-strong" : ""}`}>
                <div className="rebook-offer-head">
                  <CalendarClock size={16} />
                  <div>
                    <strong>Move it instead — keep {inr(preview.rebookValueInr)}</strong>
                    <p>{preview.rebookMessage}</p>
                  </div>
                </div>

                {preview.suggestedSlots.length > 0 && (
                  <div className="rebook-slots">
                    {preview.suggestedSlots.map(s => (
                      <button key={`${s.date}-${s.time}`} className="rebook-slot" onClick={() => pickSuggested(s.date, s.time)}>
                        <span>{formatSlot(s.date, s.time)}</span>
                        <small>{s.seatsLeft} left</small>
                      </button>
                    ))}
                  </div>
                )}

                <button className="btn btn-primary full" onClick={() => setMode("rebook")}>
                  Pick another day <ArrowRight size={14} style={{ verticalAlign: -2 }} />
                </button>
              </div>
            )}

            <label>Reason (optional)
              <textarea
                value={reason}
                onChange={e => setReason(e.target.value)}
                placeholder="e.g. Change of plans, site not ready, booked by mistake…"
              />
            </label>

            {actionError && <p className="form-error">{actionError}</p>}

            <div className="modal-actions">
              <button className="btn btn-ghost full" onClick={onClose} disabled={busy}>Keep booking</button>
              <button className="btn btn-danger full" onClick={doCancel} disabled={busy}>
                {busy ? "Cancelling…" : preview.refundable ? `Cancel & refund ${inr(preview.refundAmountInr)}` : "Cancel booking"}
              </button>
            </div>
          </>
        )}

        {/* ---- Rebooking picker ---- */}
        {preview && mode === "rebook" && (
          <>
            <p className="refund-policy-note">
              Your payment of {inr(preview.paidInr)} moves with the booking. Nothing is refunded and nothing is charged again.
            </p>

            <label>New date
              <input type="date" value={date} min={new Date().toISOString().slice(0, 10)} onChange={e => setDate(e.target.value)} />
            </label>

            {date && (
              <div className="slot-grid">
                {slots === null && <p className="refund-loading"><Loader2 size={14} className="spin" /> Checking availability…</p>}
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
                {slots?.length === 0 && <p className="muted">No slots published for this day.</p>}
              </div>
            )}

            {actionError && <p className="form-error"><AlertTriangle size={13} /> {actionError}</p>}

            <div className="modal-actions">
              <button className="btn btn-ghost full" onClick={() => setMode("choose")} disabled={busy}>Back</button>
              <button className="btn btn-primary full" onClick={doRebook} disabled={busy || !date || !time}>
                {busy ? "Moving…" : <><Check size={14} style={{ verticalAlign: -2 }} /> Confirm new slot</>}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
