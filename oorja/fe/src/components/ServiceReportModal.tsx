import { useEffect, useState } from "react";
import { AlertTriangle, Calendar, Check, Download, Eye, FileText, TrendingUp, User, Wrench, X } from "lucide-react";
import { ApiError, openCustomerFile, type ApiBooking } from "../lib/api";
import { DocumentsApi, parseChecklist, type ServiceReport } from "../lib/documents";

function formatDate(iso: string) {
  return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}
function formatBytes(n: number | null) {
  if (!n) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * The completion record for a finished visit.
 *
 * It reads the published report from the server rather than inferring one
 * from the booking's fields — "what did they actually do?" deserves a real
 * answer, with the checks that were carried out and the measured difference,
 * not a restatement of the service name the customer already booked.
 */
export function ServiceReportModal({ booking, onClose }: { booking: ApiBooking; onClose: () => void }) {
  const [report, setReport] = useState<ServiceReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    DocumentsApi.reportFor(booking.id)
      .then(setReport)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load the report."))
      .finally(() => setLoading(false));
  }, [booking.id]);

  const download = async () => {
    if (!report) return;
    setBusy(true);
    try {
      await DocumentsApi.downloadReport(report);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't download the report.");
    } finally {
      setBusy(false);
    }
  };

  const checklist = parseChecklist(report?.checklistJson ?? null);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card refund-sheet" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>Service report</h3>
            <span className="booking-token">{report?.reportNumber ?? booking.token}</span>
          </div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>

        {loading && <p className="muted">Loading…</p>}
        {error && !report && <p className="muted"><AlertTriangle size={13} /> {error}</p>}

        {report && (
          <>
            <div className="report-meta-grid">
              <div className="report-meta-item">
                <span><Calendar size={11} /> Visit date</span>
                <b>{formatDate(report.serviceDate)}</b>
              </div>
              <div className="report-meta-item">
                <span><User size={11} /> Technician</span>
                <b>{report.technicianName ?? "—"}</b>
              </div>
              <div className="report-meta-item" style={{ gridColumn: "1 / -1" }}>
                <span><Wrench size={11} /> Work performed</span>
                <b style={{ fontWeight: 400, lineHeight: 1.6 }}>{report.workPerformed}</b>
              </div>
            </div>

            {report.improvementKw != null && (
              <div className="report-gain">
                <TrendingUp size={16} />
                <div>
                  <b>{report.outputBeforeKw} kW → {report.outputAfterKw} kW</b>
                  <span className="muted small">
                    {report.improvementKw > 0 ? `+${report.improvementKw} kW after this visit` : "No measurable change"}
                    {report.panelsCleaned ? ` · ${report.panelsCleaned} panels cleaned` : ""}
                  </span>
                </div>
              </div>
            )}

            {checklist.length > 0 && (
              <>
                <p className="amc-label">Checks carried out</p>
                <ul className="report-checklist">
                  {checklist.map((c, i) => (
                    <li key={i} className={c.ok ? "ok" : "flag"}>
                      {c.ok ? <Check size={13} /> : <AlertTriangle size={13} />}
                      <span>{c.label}{c.note ? ` — ${c.note}` : ""}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}

            {report.observations && <p className="report-notes"><b>What we found:</b> {report.observations}</p>}
            {report.partsReplaced && <p className="report-notes"><b>Parts replaced:</b> {report.partsReplaced}</p>}
            {report.recommendations && <p className="report-notes"><b>We recommend:</b> {report.recommendations}</p>}
            {report.nextServiceDue && (
              <p className="report-notes">Next service due {formatDate(report.nextServiceDue)}.</p>
            )}

            {report.attachments.length > 0 && (
              <>
                <p className="amc-label" style={{ marginTop: 4 }}>Photos &amp; documents</p>
                <div className="doc-list">
                  {report.attachments.map(a => (
                    <div key={a.id} className="doc-row">
                      <FileText size={14} />
                      <div>
                        <b>{a.fileName}</b>
                        <span className="muted small">{formatBytes(a.sizeBytes)}</span>
                      </div>
                      <button className="icon-button" title="View"
                              onClick={() => openCustomerFile(a.customerUrl, a.fileName, "view")}>
                        <Eye size={15} />
                      </button>
                      <button className="icon-button" title="Download"
                              onClick={() => openCustomerFile(a.customerUrl, a.fileName, "download")}>
                        <Download size={15} />
                      </button>
                    </div>
                  ))}
                </div>
              </>
            )}

            {error && <p className="form-error">{error}</p>}

            <button className="btn btn-primary full" onClick={download} disabled={busy}>
              <Download size={15} style={{ verticalAlign: -3 }} /> {busy ? "Preparing…" : "Download report (PDF)"}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
