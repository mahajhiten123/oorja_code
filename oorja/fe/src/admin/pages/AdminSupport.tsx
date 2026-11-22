import { useEffect, useMemo, useState } from "react";
import { AlertTriangle, Clock3, Lock, RefreshCw, Send } from "lucide-react";
import { ApiError } from "../../lib/api";
import {
  AdminSupportApi,
  TICKET_STATUS_CLASS,
  untilDue,
  type SupportTicket,
  type TicketPriority,
  type TicketStatus
} from "../../lib/support";

const STATUSES: TicketStatus[] = ["OPEN", "IN_PROGRESS", "WAITING_CUSTOMER", "RESOLVED", "CLOSED"];
const PRIORITIES: TicketPriority[] = ["LOW", "NORMAL", "HIGH", "URGENT"];

function when(iso: string) {
  return new Date(iso).toLocaleString("en-IN", { day: "2-digit", month: "short", hour: "numeric", minute: "2-digit" });
}

/**
 * Admin → Support.
 *
 * A two-pane queue: tickets on the left, the conversation on the right.
 * Sorting puts breached SLAs first and unread tickets next, because the
 * only ordering that matters in a support queue is "who have we let down,
 * and who are we about to".
 */
export function AdminSupport() {
  const [tickets, setTickets] = useState<SupportTicket[] | null>(null);
  const [active, setActive] = useState<SupportTicket | null>(null);
  const [tab, setTab] = useState<"queue" | "all">("queue");
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    (tab === "queue" ? AdminSupportApi.queue() : AdminSupportApi.all())
      .then(setTickets)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load tickets."));
  };

  useEffect(load, [tab]);

  const sorted = useMemo(() => {
    const score = (t: SupportTicket) => (t.slaBreached ? 0 : t.unreadForAdmin ? 1 : 2);
    return [...(tickets ?? [])].sort((a, b) => score(a) - score(b)
      || new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime());
  }, [tickets]);

  const open = async (t: SupportTicket) => {
    try {
      setActive(await AdminSupportApi.one(t.id));
      setTickets(prev => (prev ?? []).map(x => (x.id === t.id ? { ...x, unreadForAdmin: false } : x)));
    } catch {
      setActive(t);
    }
  };

  const onUpdated = (t: SupportTicket) => {
    setActive(t);
    setTickets(prev => (prev ?? []).map(x => (x.id === t.id ? t : x)));
  };

  const breached = (tickets ?? []).filter(t => t.slaBreached).length;

  return (
    <div className="admin-page">
      <div className="admin-page-head">
        <h2>Support</h2>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>

      {breached > 0 && (
        <div className="recon-strip recon-alert">
          <div>
            <span><AlertTriangle size={12} /> Past promised reply</span>
            <strong>{breached} ticket{breached === 1 ? "" : "s"}</strong>
          </div>
        </div>
      )}

      <div className="admin-tabs">
        <button className={tab === "queue" ? "active" : ""} onClick={() => setTab("queue")}>Needs action</button>
        <button className={tab === "all" ? "active" : ""} onClick={() => setTab("all")}>All</button>
      </div>

      {error && <p className="form-error">{error}</p>}

      <div className="support-split">
        <div className="support-queue">
          {tickets === null && <p className="muted">Loading…</p>}
          {tickets?.length === 0 && <p className="muted">Nothing waiting. Good.</p>}
          {sorted.map(t => (
            <button key={t.id}
                    className={`ticket-row ${active?.id === t.id ? "active" : ""} ${t.slaBreached ? "breached" : ""}`}
                    onClick={() => open(t)}>
              <div className="ticket-row-head">
                <b>{t.subject}</b>
                <span className={`status ${TICKET_STATUS_CLASS[t.status]}`}>{t.statusLabel}</span>
              </div>
              <span className="muted small">
                {t.ticketNumber} · {t.customerName} · {t.categoryLabel} · {t.priority}
              </span>
              <span className="muted small">
                {when(t.lastMessageAt)}
                {t.firstResponseAt == null && t.firstResponseDue
                  ? ` · first reply ${untilDue(t.firstResponseDue)}` : ""}
              </span>
              {t.unreadForAdmin && <span className="ticket-unread">Unread</span>}
            </button>
          ))}
        </div>

        <div className="support-detail">
          {!active && <p className="muted">Pick a ticket to read it.</p>}
          {active && <TicketDetail ticket={active} onUpdated={onUpdated} />}
        </div>
      </div>
    </div>
  );
}

function TicketDetail({ ticket, onUpdated }: { ticket: SupportTicket; onUpdated: (t: SupportTicket) => void }) {
  const [reply, setReply] = useState("");
  const [internal, setInternal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resolution, setResolution] = useState("");

  const act = async (fn: () => Promise<SupportTicket>) => {
    setBusy(true);
    setError(null);
    try {
      onUpdated(await fn());
      setReply("");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "That didn't work.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="ticket-thread-head">
        <div>
          <h3>{ticket.subject}</h3>
          <span className="muted small">
            {ticket.ticketNumber} · {ticket.customerName}
            {ticket.customerPhone ? ` · ${ticket.customerPhone}` : ""}
            {ticket.customerEmail ? ` · ${ticket.customerEmail}` : ""}
          </span>
          {ticket.bookingToken && <span className="muted small">Booking {ticket.bookingToken}</span>}
        </div>
        <span className={`status ${TICKET_STATUS_CLASS[ticket.status]}`}>{ticket.statusLabel}</span>
      </div>

      {ticket.firstResponseAt == null && ticket.firstResponseDue && (
        <p className={ticket.slaBreached ? "form-error small" : "muted small"}>
          <Clock3 size={12} style={{ verticalAlign: -2 }} /> First reply due {untilDue(ticket.firstResponseDue)}
        </p>
      )}

      <div className="ticket-messages">
        {ticket.messages.map(m => (
          <div key={m.id}
               className={`ticket-bubble ${m.authorType === "ADMIN" ? "mine" : "theirs"} ${m.internal ? "internal" : ""}`}>
            {m.internal && <span className="internal-tag"><Lock size={10} /> Internal note</span>}
            <p>{m.body}</p>
            <small>{m.authorLabel || (m.authorType === "ADMIN" ? "Support" : ticket.customerName)} · {when(m.at)}</small>
          </div>
        ))}
      </div>

      {error && <p className="form-error">{error}</p>}

      <div className="ticket-composer">
        <textarea value={reply} onChange={e => setReply(e.target.value)} rows={4}
                  placeholder={internal ? "Internal note — the customer never sees this…" : "Reply to the customer…"} />
        <label className="checkbox-row">
          <input type="checkbox" checked={internal} onChange={e => setInternal(e.target.checked)} />
          Internal note only
        </label>
        <button className="btn btn-primary full" disabled={busy || !reply.trim()}
                onClick={() => act(() => AdminSupportApi.reply(ticket.id, reply.trim(), internal))}>
          <Send size={14} style={{ verticalAlign: -2 }} /> {busy ? "Sending…" : internal ? "Save note" : "Send reply"}
        </button>
      </div>

      <div className="ticket-triage">
        <label>Status
          <select value={ticket.status} disabled={busy}
                  onChange={e => act(() => AdminSupportApi.update(ticket.id, {
                    status: e.target.value as TicketStatus,
                    resolutionNote: resolution.trim() || undefined
                  }))}>
            {STATUSES.map(s => <option key={s} value={s}>{s.replace("_", " ")}</option>)}
          </select>
        </label>
        <label>Priority
          <select value={ticket.priority} disabled={busy}
                  onChange={e => act(() => AdminSupportApi.update(ticket.id, { priority: e.target.value as TicketPriority }))}>
            {PRIORITIES.map(p => <option key={p} value={p}>{p}</option>)}
          </select>
        </label>
        <label style={{ gridColumn: "1 / -1" }}>Resolution note (sent to the customer when you resolve)
          <input value={resolution} onChange={e => setResolution(e.target.value)}
                 placeholder="What we did about it" />
        </label>
      </div>
    </>
  );
}

export default AdminSupport;
