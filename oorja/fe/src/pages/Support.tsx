import { useEffect, useRef, useState } from "react";
import { ArrowLeft, Clock3, LifeBuoy, Plus, Send } from "lucide-react";
import { ApiError, BookingsApi, type ApiBooking } from "../lib/api";
import {
  SupportApi,
  TICKET_STATUS_CLASS,
  untilDue,
  type CategoryOption,
  type SupportTicket
} from "../lib/support";

function when(iso: string) {
  return new Date(iso).toLocaleString("en-IN", {
    day: "2-digit", month: "short", hour: "numeric", minute: "2-digit"
  });
}

/**
 * Help → My tickets.
 *
 * Two screens in one page: the list, and the thread. The thread is a chat,
 * not a form — a customer chasing a problem shouldn't have to compose a
 * formal message every time, and keeping the whole history in view is what
 * stops them re-explaining themselves to each new agent.
 */
export function Support() {
  const [tickets, setTickets] = useState<SupportTicket[] | null>(null);
  const [active, setActive] = useState<SupportTicket | null>(null);
  const [composing, setComposing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    SupportApi.tickets()
      .then(setTickets)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your tickets."));
  };

  useEffect(load, []);

  const open = async (t: SupportTicket) => {
    try {
      setActive(await SupportApi.one(t.id));
    } catch {
      setActive(t);
    }
  };

  if (composing) {
    return (
      <NewTicketForm
        onCancel={() => setComposing(false)}
        onCreated={t => {
          setComposing(false);
          setActive(t);
          load();
        }}
      />
    );
  }

  if (active) {
    return (
      <TicketThread
        ticket={active}
        onBack={() => {
          setActive(null);
          load();
        }}
        onUpdated={setActive}
      />
    );
  }

  return (
    <div className="page">
      <div className="page-heading">
        <span className="kicker">SUPPORT</span>
        <h1>My tickets</h1>
        <p className="muted">Raise an issue and our team picks it up — every reply lands here and in your email.</p>
      </div>

      <button className="btn btn-primary full" onClick={() => setComposing(true)}>
        <Plus size={15} style={{ verticalAlign: -3 }} /> Raise a new ticket
      </button>

      {error && <p className="form-error">{error}</p>}
      {tickets === null && <p className="muted">Loading…</p>}
      {tickets?.length === 0 && (
        <p className="muted" style={{ marginTop: 18 }}>
          No tickets yet. If something isn't right — a visit, a bill, a refund — raise one and we'll take it from there.
        </p>
      )}

      <div className="ticket-list">
        {(tickets ?? []).map(t => (
          <button key={t.id} className="ticket-row" onClick={() => open(t)}>
            <div className="ticket-row-head">
              <b>{t.subject}</b>
              <span className={`status ${TICKET_STATUS_CLASS[t.status]}`}>{t.statusLabel}</span>
            </div>
            <span className="muted small">
              {t.ticketNumber} · {t.categoryLabel}
              {t.bookingToken ? ` · ${t.bookingToken}` : ""} · {when(t.lastMessageAt)}
            </span>
            {t.unreadForCustomer && <span className="ticket-unread">New reply</span>}
          </button>
        ))}
      </div>
    </div>
  );
}

function NewTicketForm({ onCancel, onCreated }: { onCancel: () => void; onCreated: (t: SupportTicket) => void }) {
  const [categories, setCategories] = useState<CategoryOption[]>([]);
  const [category, setCategory] = useState<CategoryOption | null>(null);
  const [bookings, setBookings] = useState<ApiBooking[]>([]);
  const [bookingId, setBookingId] = useState<string>("");
  const [subject, setSubject] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    SupportApi.categories().then(c => {
      setCategories(c);
      setCategory(c[0] ?? null);
    });
    BookingsApi.mine().then(setBookings).catch(() => setBookings([]));
  }, []);

  const submit = async () => {
    if (!category) return;
    setBusy(true);
    setError(null);
    try {
      onCreated(await SupportApi.create({
        category: category.id,
        subject: subject.trim(),
        message: message.trim(),
        bookingId: bookingId ? Number(bookingId) : undefined
      }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't raise this ticket. Try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <button className="link-back" onClick={onCancel}><ArrowLeft size={15} /> Back</button>
      <div className="page-heading">
        <span className="kicker">SUPPORT</span>
        <h1>Raise a ticket</h1>
      </div>

      <label>What's it about?
        <select value={category?.id ?? ""} onChange={e => setCategory(categories.find(c => c.id === e.target.value) ?? null)}>
          {categories.map(c => <option key={c.id} value={c.id}>{c.label}</option>)}
        </select>
      </label>

      {/* The promise is made here, at the point the customer chooses — not
          buried in a policy page they'll never open. */}
      {category && (
        <p className="muted small">
          <Clock3 size={12} style={{ verticalAlign: -2 }} /> We aim to reply within {category.firstResponseHours} hours for this.
        </p>
      )}

      <label>Related booking (optional)
        <select value={bookingId} onChange={e => setBookingId(e.target.value)}>
          <option value="">Not about a specific booking</option>
          {bookings.map(b => (
            <option key={b.id} value={b.id}>{b.token} — {b.serviceType} ({b.scheduledDate})</option>
          ))}
        </select>
      </label>

      <label>Subject
        <input value={subject} onChange={e => setSubject(e.target.value)} maxLength={200}
               placeholder="e.g. Panels still dusty after the visit" />
      </label>

      <label>Tell us what happened
        <textarea value={message} onChange={e => setMessage(e.target.value)} rows={7}
                  placeholder="The more detail you give us — dates, what you saw, what you expected — the fewer questions we'll have to come back with." />
      </label>

      {error && <p className="form-error">{error}</p>}

      <button className="btn btn-primary full" disabled={busy || !subject.trim() || !message.trim()} onClick={submit}>
        {busy ? "Sending…" : <><LifeBuoy size={15} style={{ verticalAlign: -3 }} /> Send to support</>}
      </button>
    </div>
  );
}

function TicketThread({
  ticket, onBack, onUpdated
}: {
  ticket: SupportTicket;
  onBack: () => void;
  onUpdated: (t: SupportTicket) => void;
}) {
  const [reply, setReply] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [ticket.messages.length]);

  const send = async () => {
    if (!reply.trim()) return;
    setBusy(true);
    setError(null);
    try {
      onUpdated(await SupportApi.reply(ticket.id, reply.trim()));
      setReply("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't send that. Try again.");
    } finally {
      setBusy(false);
    }
  };

  const close = async () => {
    try {
      onUpdated(await SupportApi.close(ticket.id));
    } catch {
      /* leave the thread as it is; the customer can try again */
    }
  };

  const due = untilDue(ticket.firstResponseDue);

  return (
    <div className="page ticket-thread-page">
      <button className="link-back" onClick={onBack}><ArrowLeft size={15} /> All tickets</button>

      <div className="ticket-thread-head">
        <div>
          <h2>{ticket.subject}</h2>
          <span className="muted small">{ticket.ticketNumber} · {ticket.categoryLabel}
            {ticket.bookingToken ? ` · ${ticket.bookingToken}` : ""}</span>
        </div>
        <span className={`status ${TICKET_STATUS_CLASS[ticket.status]}`}>{ticket.statusLabel}</span>
      </div>

      {ticket.status === "OPEN" && !ticket.firstResponseAt && due && (
        <p className="muted small"><Clock3 size={12} style={{ verticalAlign: -2 }} /> First reply expected {due}.</p>
      )}
      {ticket.resolutionNote && <div className="ticket-resolution">{ticket.resolutionNote}</div>}

      <div className="ticket-messages">
        {ticket.messages.map(m => (
          <div key={m.id} className={`ticket-bubble ${m.authorType === "CUSTOMER" ? "mine" : "theirs"}`}>
            <p>{m.body}</p>
            <small>{m.authorType === "CUSTOMER" ? "You" : (m.authorLabel || "Oorja support")} · {when(m.at)}</small>
          </div>
        ))}
        <div ref={endRef} />
      </div>

      {error && <p className="form-error">{error}</p>}

      {ticket.status !== "CLOSED" ? (
        <div className="ticket-composer">
          <textarea value={reply} onChange={e => setReply(e.target.value)} rows={3} placeholder="Write a reply…" />
          <div className="modal-actions">
            <button className="btn btn-ghost full" onClick={close} disabled={busy}>Close ticket</button>
            <button className="btn btn-primary full" onClick={send} disabled={busy || !reply.trim()}>
              {busy ? "Sending…" : <><Send size={14} style={{ verticalAlign: -2 }} /> Send</>}
            </button>
          </div>
        </div>
      ) : (
        <p className="muted small">This ticket is closed. Raise a new one if you need anything else.</p>
      )}
    </div>
  );
}

export default Support;
