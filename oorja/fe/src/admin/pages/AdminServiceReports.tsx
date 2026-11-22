import { useEffect, useState } from "react";
import { Check, Plus, Save, Send, Trash2 } from "lucide-react";
import { ApiError, AdminBookingsApi, type ApiBooking } from "../../lib/api";
import { AdminDocumentsApi, parseChecklist, type ChecklistItem, type ServiceReport } from "../../lib/documents";

/** Sensible starting checks for a cleaning/maintenance visit. Ops can edit freely. */
const DEFAULT_CHECKLIST: ChecklistItem[] = [
  { label: "Panels cleaned and rinsed", ok: true },
  { label: "Inverter status checked", ok: true },
  { label: "Wiring and connectors inspected", ok: true },
  { label: "Mounting structure secure", ok: true },
  { label: "Shading / debris cleared", ok: true }
];

/**
 * Admin → Service reports.
 *
 * Drafts autosave-on-demand and only reach the customer when published.
 * Publishing also closes the booking out, because a completed visit and
 * the record of it belong to the same moment — a booking marked done with
 * no report is exactly what generates "what did they actually do?" tickets.
 */
export function AdminServiceReports() {
  const [bookings, setBookings] = useState<ApiBooking[]>([]);
  const [selected, setSelected] = useState<ApiBooking | null>(null);
  const [report, setReport] = useState<Partial<ServiceReport> | null>(null);
  const [checklist, setChecklist] = useState<ChecklistItem[]>(DEFAULT_CHECKLIST);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    AdminBookingsApi.list()
      .then(bs => setBookings(bs.filter(b => b.status !== "CANCELLED")))
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load bookings."));
  }, []);

  const select = async (booking: ApiBooking) => {
    setSelected(booking);
    setError(null);
    setSaved(null);
    try {
      const existing = await AdminDocumentsApi.reportFor(booking.id);
      setReport(existing);
      const parsed = parseChecklist(existing.checklistJson);
      setChecklist(parsed.length ? parsed : DEFAULT_CHECKLIST);
    } catch {
      // No draft yet — start a blank one seeded from the booking.
      setReport({ serviceDate: booking.scheduledDate, workPerformed: "" });
      setChecklist(DEFAULT_CHECKLIST);
    }
  };

  const field = (key: keyof ServiceReport, value: unknown) =>
    setReport(prev => ({ ...(prev ?? {}), [key]: value }));

  const payload = () => ({
    serviceDate: report?.serviceDate,
    startedAt: report?.startedAt,
    finishedAt: report?.finishedAt,
    workPerformed: report?.workPerformed,
    observations: report?.observations,
    partsReplaced: report?.partsReplaced,
    recommendations: report?.recommendations,
    checklistJson: JSON.stringify(checklist),
    panelsCleaned: report?.panelsCleaned,
    outputBeforeKw: report?.outputBeforeKw,
    outputAfterKw: report?.outputAfterKw,
    nextServiceDue: report?.nextServiceDue,
    customerSignatureName: report?.customerSignatureName,
    customerRemarks: report?.customerRemarks
  });

  const save = async (publish: boolean) => {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      const draft = await AdminDocumentsApi.saveReport(selected.id, payload());
      setReport(draft);
      if (publish) {
        const published = await AdminDocumentsApi.publishReport(selected.id, true);
        setReport(published);
        setSaved(`Published as ${published.reportNumber}. The customer can download it now.`);
      } else {
        setSaved(`Draft saved as ${draft.reportNumber}. Not visible to the customer yet.`);
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save this report.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-head"><h2>Service reports</h2></div>
      {error && <p className="form-error">{error}</p>}

      <div className="support-split">
        <div className="support-queue">
          {bookings.map(b => (
            <button key={b.id} className={`ticket-row ${selected?.id === b.id ? "active" : ""}`} onClick={() => select(b)}>
              <div className="ticket-row-head">
                <b>{b.serviceType}</b>
                <span className={`status ${b.status.toLowerCase()}`}>{b.status}</span>
              </div>
              <span className="muted small">{b.token} · {b.customerName} · {b.scheduledDate}</span>
            </button>
          ))}
        </div>

        <div className="support-detail">
          {!selected && <p className="muted">Pick a booking to write up its visit.</p>}

          {selected && report && (
            <>
              <div className="ticket-thread-head">
                <div>
                  <h3>{selected.token}</h3>
                  <span className="muted small">{selected.customerName} · {selected.serviceType}</span>
                </div>
                {report.published && <span className="status completed">Published</span>}
              </div>

              <label>Work performed *
                <textarea rows={4} value={report.workPerformed ?? ""}
                          onChange={e => field("workPerformed", e.target.value)}
                          placeholder="What was actually done on site, in the customer's language." />
              </label>

              <p className="amc-label">Checks carried out</p>
              <div className="checklist-editor">
                {checklist.map((item, i) => (
                  <div key={i} className="checklist-row">
                    <button className={`check-toggle ${item.ok ? "ok" : "flag"}`}
                            onClick={() => setChecklist(c => c.map((x, j) => j === i ? { ...x, ok: !x.ok } : x))}>
                      <Check size={12} />
                    </button>
                    <input value={item.label}
                           onChange={e => setChecklist(c => c.map((x, j) => j === i ? { ...x, label: e.target.value } : x))} />
                    <input placeholder="Note (optional)" value={item.note ?? ""}
                           onChange={e => setChecklist(c => c.map((x, j) => j === i ? { ...x, note: e.target.value } : x))} />
                    <button className="icon-button" onClick={() => setChecklist(c => c.filter((_, j) => j !== i))}>
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
                <button className="btn btn-ghost" onClick={() => setChecklist(c => [...c, { label: "", ok: true }])}>
                  <Plus size={13} /> Add check
                </button>
              </div>

              <div className="field-grid">
                <label>Panels cleaned
                  <input type="number" value={report.panelsCleaned ?? ""}
                         onChange={e => field("panelsCleaned", e.target.value ? Number(e.target.value) : null)} />
                </label>
                <label>Output before (kW)
                  <input type="number" step="0.01" value={report.outputBeforeKw ?? ""}
                         onChange={e => field("outputBeforeKw", e.target.value ? Number(e.target.value) : null)} />
                </label>
                <label>Output after (kW)
                  <input type="number" step="0.01" value={report.outputAfterKw ?? ""}
                         onChange={e => field("outputAfterKw", e.target.value ? Number(e.target.value) : null)} />
                </label>
                <label>Next service due
                  <input type="date" value={report.nextServiceDue ?? ""}
                         onChange={e => field("nextServiceDue", e.target.value || null)} />
                </label>
              </div>

              <label>What we found
                <textarea rows={3} value={report.observations ?? ""} onChange={e => field("observations", e.target.value)} />
              </label>
              <label>Parts replaced
                <input value={report.partsReplaced ?? ""} onChange={e => field("partsReplaced", e.target.value)} />
              </label>
              <label>Recommendations
                <textarea rows={3} value={report.recommendations ?? ""}
                          onChange={e => field("recommendations", e.target.value)} />
              </label>
              <label>Acknowledged on site by
                <input value={report.customerSignatureName ?? ""}
                       onChange={e => field("customerSignatureName", e.target.value)} />
              </label>

              {saved && <p className="muted small">{saved}</p>}

              <div className="modal-actions">
                <button className="btn btn-ghost full" disabled={busy} onClick={() => save(false)}>
                  <Save size={14} /> Save draft
                </button>
                <button className="btn btn-primary full" disabled={busy || !report.workPerformed} onClick={() => save(true)}>
                  <Send size={14} /> {busy ? "Working…" : "Publish & complete booking"}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default AdminServiceReports;
