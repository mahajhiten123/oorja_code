import { useEffect, useState } from "react";
import { ClipboardCheck, ChevronRight, AlertTriangle, TrendingUp } from "lucide-react";
import { Link } from "react-router-dom";
import { ApiError, type ApiBooking } from "../lib/api";
import { DocumentsApi, type ServiceReport } from "../lib/documents";
import { ServiceReportModal } from "../components/ServiceReportModal";

function formatDate(iso: string) {
  return new Date(iso + "T00:00:00").toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

/**
 * Profile → Service Reports.
 *
 * Every published visit report in one list, independent of which plant or
 * booking it hung off originally — the same "everything, in one place"
 * pattern as Invoices. Opening one reuses the existing report viewer
 * (checklist, before/after output, photos, PDF download) rather than
 * duplicating that layout here.
 */
export function ServiceReports() {
  const [reports, setReports] = useState<ServiceReport[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [viewing, setViewing] = useState<ServiceReport | null>(null);

  useEffect(() => {
    DocumentsApi.reports()
      .then(list => setReports(list.sort((a, b) => (a.serviceDate < b.serviceDate ? 1 : -1))))
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your service reports."));
  }, []);

  // ServiceReportModal re-fetches the full report by booking id, so a
  // minimal stand-in with just the two fields it actually reads is enough —
  // no need for a second endpoint or a wider type here.
  const asBooking = (r: ServiceReport) => ({ id: r.bookingId, token: r.bookingToken } as ApiBooking);

  return (
    <div className="page">
      <div className="page-heading">
        <span className="kicker">SERVICE HISTORY</span>
        <h1>Service Reports</h1>
        <p className="muted">The completion record for every finished visit — checklist, before/after output and photos.</p>
      </div>

      {error && (
        <div className="empty-state">
          <AlertTriangle size={18} color="#c54c44" />
          <b>Couldn't load reports</b>
          <p>{error}</p>
        </div>
      )}

      {!error && reports === null && <p className="muted">Loading…</p>}

      {!error && reports?.length === 0 && (
        <div className="empty-state">
          <ClipboardCheck size={28} />
          <b>No service reports yet</b>
          <p>Once a technician completes a visit, the report appears here — usually within a day.</p>
          <Link className="btn btn-primary" to="/bookings">Go to My Bookings</Link>
        </div>
      )}

      <div className="invoice-list">
        {(reports ?? []).map(r => (
          <button
            key={r.id}
            className="invoice-row"
            style={{ width: "100%", textAlign: "left", background: "white" }}
            onClick={() => setViewing(r)}
          >
            <div className="invoice-row-main">
              <span className="invoice-icon"><ClipboardCheck size={16} /></span>
              <div>
                <b>{r.reportNumber}</b>
                <span className="muted small">
                  {r.serviceLabel} · {r.bookingToken} · {formatDate(r.serviceDate)}
                  {r.technicianName ? ` · ${r.technicianName}` : ""}
                </span>
                {r.improvementKw != null && (
                  <span className="muted small" style={{ display: "inline-flex", alignItems: "center", gap: 4 }}>
                    <TrendingUp size={12} /> {r.outputBeforeKw} kW → {r.outputAfterKw} kW
                  </span>
                )}
              </div>
            </div>
            <div className="invoice-row-actions">
              <span className="muted small">{r.published ? "Published" : "Draft"}</span>
              <span className="text-link" style={{ fontSize: 12 }}>View report <ChevronRight size={14} /></span>
            </div>
          </button>
        ))}
      </div>

      {viewing && <ServiceReportModal booking={asBooking(viewing)} onClose={() => setViewing(null)} />}
    </div>
  );
}

export default ServiceReports;
