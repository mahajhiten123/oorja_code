import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  AlertTriangle, Archive, Calendar, ChevronRight, ClipboardList, Download, Eye,
  FileText, MapPin, Pencil, ShieldCheck, SunMedium, User, Wrench
} from "lucide-react";
import { ApiError, BookingsApi, PlantsApi, openCustomerFile, type ApiBooking, type Plant } from "../lib/api";
import { AddPlantModal } from "../components/AddPlantModal";
import { ServiceReportModal } from "../components/ServiceReportModal";

const TYPE_LABEL: Record<string, string> = { RESIDENTIAL: "Residential", COMMERCIAL: "Commercial", INDUSTRIAL: "Industrial" };

type Tab = "overview" | "history" | "documents";

function formatDate(iso: string) {
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

function formatBytes(n: number | null) {
  if (!n) return "";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export function PlantDetail() {
  const { id } = useParams();
  const plantId = Number(id);
  const navigate = useNavigate();

  const [plant, setPlant] = useState<Plant | null | undefined>(undefined); // undefined = loading, null = not found
  const [bookings, setBookings] = useState<ApiBooking[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("overview");
  const [editing, setEditing] = useState(false);
  const [viewingReport, setViewingReport] = useState<ApiBooking | null>(null);
  const [busyDoc, setBusyDoc] = useState<number | null>(null);
  const [docError, setDocError] = useState<string | null>(null);

  const load = () => {
    setError(null);
    Promise.all([PlantsApi.mine(true), BookingsApi.mine()])
      .then(([plants, allBookings]) => {
        setPlant(plants.find(p => p.id === plantId) ?? null);
        setBookings(allBookings.filter(b => b.plantId === plantId));
      })
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load this plant."));
  };

  useEffect(load, [plantId]);

  const history = useMemo(
    () => (bookings ?? [])
      .filter(b => b.status === "COMPLETED" || b.status === "CANCELLED")
      .sort((a, b) => (a.scheduledDate < b.scheduledDate ? 1 : -1)),
    [bookings]
  );

  const completed = useMemo(() => history.filter(b => b.status === "COMPLETED"), [history]);

  const documents = useMemo(
    () => completed.flatMap(b => (b.attachments || []).map(a => ({ booking: b, attachment: a }))),
    [completed]
  );

  const openDoc = async (attachmentId: number, fileName: string, customerUrl: string, mode: "view" | "download") => {
    setBusyDoc(attachmentId);
    setDocError(null);
    try {
      await openCustomerFile(customerUrl, fileName, mode);
    } catch (e) {
      setDocError(e instanceof ApiError ? e.message : "Couldn't open this file.");
    } finally {
      setBusyDoc(null);
    }
  };

  if (error) {
    return (
      <div className="page">
        <div className="empty-state">
          <AlertTriangle size={22} color="#c54c44" />
          <b>Couldn't load this plant</b>
          <p>{error}</p>
          <button className="btn btn-primary sm" style={{ marginTop: 12 }} onClick={load}>Retry</button>
        </div>
      </div>
    );
  }

  if (plant === undefined) {
    return <div className="page"><p className="muted">Loading…</p></div>;
  }

  if (plant === null) {
    return (
      <div className="page">
        <div className="empty-state">
          <SunMedium size={26} />
          <b>Plant not found</b>
          <p>This plant may have been removed, or doesn't belong to your account.</p>
          <button className="btn btn-primary sm" style={{ marginTop: 12 }} onClick={() => navigate("/plants")}>Back to My Plants</button>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <Link to="/plants" className="text-link" style={{ display: "inline-flex", alignItems: "center", gap: 5, marginBottom: 16, fontSize: 13 }}>
        &larr; Back to My Plants
      </Link>

      <div className="plant-detail-head">
        <div className="plant-detail-icon"><SunMedium size={24} /></div>
        <div>
          <h1>{plant.name}</h1>
          <p>
            <span><MapPin size={13} /> {plant.location}</span>
            <span>{TYPE_LABEL[plant.type] || plant.type} · {plant.capacityDisplay}</span>
            <span>{plant.plantCode}</span>
          </p>
        </div>
        {!plant.deleted && (
          <div className="plant-detail-actions">
            <button className="btn btn-light" onClick={() => navigate("/services")}><Wrench size={15} /> Book a service</button>
            <button className="btn btn-ghost" onClick={() => setEditing(true)}><Pencil size={15} /> Edit</button>
          </div>
        )}
      </div>

      {plant.deleted && (
        <div className="admin-callout" style={{ marginBottom: 18 }}>
          <Archive size={16} />
          This plant is archived. It won't appear when booking new services, but its history below stays intact.
        </div>
      )}

      <div className="tabs">
        <button className={tab === "overview" ? "active" : ""} onClick={() => setTab("overview")}>Overview</button>
        <button className={tab === "history" ? "active" : ""} onClick={() => setTab("history")}>Service history<span>{history.length}</span></button>
        <button className={tab === "documents" ? "active" : ""} onClick={() => setTab("documents")}>Documents<span>{documents.length}</span></button>
      </div>

      {tab === "overview" && (
        <div className="overview-grid">
          <div className="admin-panel">
            <div className="admin-panel-head"><h3>Plant details</h3></div>
            <div className="profile-row"><div className="profile-row-icon"><SunMedium size={17} /></div><div><b>Name</b><span>{plant.name}</span></div></div>
            <div className="profile-row"><div className="profile-row-icon"><MapPin size={17} /></div><div><b>Location</b><span>{plant.location}</span></div></div>
            <div className="profile-row"><div className="profile-row-icon"><ClipboardList size={17} /></div><div><b>Type &amp; capacity</b><span>{TYPE_LABEL[plant.type] || plant.type} · {plant.capacityDisplay}</span></div></div>
            <div className="profile-row" style={{ borderBottom: "none" }}><div className="profile-row-icon"><FileText size={17} /></div><div><b>Plant code</b><span>{plant.plantCode}</span></div></div>
          </div>
          <div className="admin-panel">
            <div className="admin-panel-head"><h3>Quick actions</h3></div>
            <div className="dash-quick-actions" style={{ margin: 0, flexDirection: "column" }}>
              <Link to="/services" className="dash-action" style={{ width: "100%" }}><Wrench size={16} /> Book a service</Link>
              <Link to="/amc" className="dash-action" style={{ width: "100%" }}><ShieldCheck size={16} /> Explore AMC for this plant</Link>
              <Link to="/bookings" className="dash-action" style={{ width: "100%" }}><Calendar size={16} /> View all bookings</Link>
            </div>
          </div>
        </div>
      )}

      {tab === "history" && (
        <>
          {bookings === null && <p className="muted">Loading service history…</p>}
          {bookings !== null && history.length === 0 && (
            <div className="empty-state">
              <ClipboardList size={26} />
              <b>No service history yet</b>
              <p>Completed and cancelled services against this plant will show up here.</p>
            </div>
          )}
          {history.length > 0 && (
            <ul className="service-history-list">
              {history.map((b, i) => (
                <li key={b.id} className={`service-history-item ${b.status === "CANCELLED" ? "cancelled" : ""}`} onClick={() => b.status === "COMPLETED" && setViewingReport(b)}>
                  <div className="service-history-rail">
                    <div className="service-history-dot">
                      {b.status === "COMPLETED" ? <Wrench size={15} /> : <AlertTriangle size={15} />}
                    </div>
                    {i < history.length - 1 && <div className="service-history-line" />}
                  </div>
                  <div className="service-history-body">
                    <div className="service-history-top">
                      <b>{b.serviceType}</b>
                      <span className={`status ${b.status.toLowerCase()}`}>{b.status === "COMPLETED" ? "Completed" : "Cancelled"}</span>
                    </div>
                    <div className="service-history-date">{formatDate(b.scheduledDate)}</div>
                    <div className="service-history-meta">
                      {b.technicianName && b.technicianName !== "Unassigned" && <span><User size={12} /> {b.technicianName}</span>}
                      {b.attachments && b.attachments.length > 0 && <span><FileText size={12} /> {b.attachments.length} file{b.attachments.length > 1 ? "s" : ""}</span>}
                    </div>
                  </div>
                  {b.status === "COMPLETED" && <ChevronRight size={18} className="service-history-chevron" />}
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {tab === "documents" && (
        <>
          <p className="muted" style={{ fontSize: 13, marginTop: -4, marginBottom: 16 }}>
            Service reports and photos from every completed visit at this plant, in one place.
          </p>
          {bookings === null && <p className="muted">Loading documents…</p>}
          {bookings !== null && documents.length === 0 && (
            <div className="empty-state">
              <FileText size={26} />
              <b>No documents yet</b>
              <p>Reports and photos from completed services will appear here.</p>
            </div>
          )}
          {docError && <p className="field-error" style={{ marginBottom: 12 }}>{docError}</p>}
          {documents.length > 0 && (
            <ul className="doc-list">
              {documents.map(({ booking, attachment }) => (
                <li className="doc-row" key={attachment.id}>
                  <div className="doc-row-icon"><FileText size={16} /></div>
                  <div className="doc-row-body">
                    <b>{attachment.fileName}</b>
                    <span>{booking.serviceType} · {formatDate(booking.scheduledDate)} · {formatBytes(attachment.sizeBytes)}</span>
                  </div>
                  <div className="doc-row-actions">
                    <button disabled={busyDoc === attachment.id} onClick={() => openDoc(attachment.id, attachment.fileName, attachment.customerUrl, "view")}>
                      <Eye size={13} /> View
                    </button>
                    <button disabled={busyDoc === attachment.id} onClick={() => openDoc(attachment.id, attachment.fileName, attachment.customerUrl, "download")}>
                      <Download size={13} /> Download
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      {editing && (
        <AddPlantModal
          plant={plant}
          onClose={() => setEditing(false)}
          onAdded={updated => { setEditing(false); setPlant(updated); }}
        />
      )}
      {viewingReport && <ServiceReportModal booking={viewingReport} onClose={() => setViewingReport(null)} />}
    </div>
  );
}
