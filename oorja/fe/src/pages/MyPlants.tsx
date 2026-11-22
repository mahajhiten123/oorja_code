import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AlertTriangle, Archive, MapPin, Pencil, Plus, SunMedium, Trash2, X
} from "lucide-react";
import { ApiError, PlantsApi, type Plant } from "../lib/api";
import { AddPlantModal } from "../components/AddPlantModal";

const TYPE_LABEL: Record<string, string> = { RESIDENTIAL: "Residential", COMMERCIAL: "Commercial", INDUSTRIAL: "Industrial" };

export function MyPlants() {
  const navigate = useNavigate();
  const [plants, setPlants] = useState<Plant[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<"active" | "archived">("active");
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<Plant | null>(null);
  const [removing, setRemoving] = useState<Plant | null>(null);
  const [removeBusy, setRemoveBusy] = useState(false);

  const load = async () => {
    setError(null);
    try {
      setPlants(await PlantsApi.mine(true));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't load your plants.");
    }
  };

  useEffect(() => { load(); }, []);

  const active = (plants || []).filter(p => !p.deleted);
  const archived = (plants || []).filter(p => p.deleted);
  const list = tab === "active" ? active : archived;

  const confirmRemove = async () => {
    if (!removing) return;
    setRemoveBusy(true);
    try {
      await PlantsApi.remove(removing.id);
      setPlants(prev => (prev || []).map(p => p.id === removing.id ? { ...p, deleted: true, deletedAt: new Date().toISOString() } : p));
      setRemoving(null);
      setTab("active");
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't remove this plant.");
    } finally {
      setRemoveBusy(false);
    }
  };

  return (
    <div className="page">
      <div className="page-heading compact">
        <div>
          <span className="kicker">MY PLANTS</span>
          <h1>My plants & sites</h1>
          <p>Manage every solar site registered to your account.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowAdd(true)}><Plus size={17} /> Add a plant</button>
      </div>

      <div className="tabs">
        <button className={tab === "active" ? "active" : ""} onClick={() => setTab("active")}>Active<span>{active.length}</span></button>
        <button className={tab === "archived" ? "active" : ""} onClick={() => setTab("archived")}>Archived<span>{archived.length}</span></button>
      </div>

      {tab === "archived" && archived.length > 0 && (
        <div className="admin-callout" style={{ marginTop: 0, marginBottom: 18 }}>
          <Archive size={16} />
          Archived plants are removed from new bookings, but every past booking against them is still visible in My Bookings.
        </div>
      )}

      {error && (
        <div className="empty-state">
          <AlertTriangle size={22} color="#c54c44" />
          <b>Couldn't load your plants</b>
          <p>{error}</p>
          <button className="btn btn-primary sm" style={{ marginTop: 12 }} onClick={load}>Retry</button>
        </div>
      )}

      {!error && plants === null && <p className="muted">Loading your plants…</p>}

      {!error && plants !== null && list.length === 0 && (
        <div className="empty-state">
          <SunMedium size={26} />
          <b>{tab === "active" ? "No plants registered yet" : "No archived plants"}</b>
          <p>{tab === "active" ? "Add your first solar plant or site to start booking services against it." : "Plants you remove will show up here, with their booking history intact."}</p>
          {tab === "active" && <button className="btn btn-primary" style={{ marginTop: 14 }} onClick={() => setShowAdd(true)}><Plus size={16} /> Add a plant</button>}
        </div>
      )}

      {!error && list.length > 0 && (
        <div className="plants-grid">
          {list.map(p => (
            <div className={`plant-card ${p.deleted ? "archived" : ""}`} key={p.id} onClick={() => navigate(`/plants/${p.id}`)}>
              <div className="plant-card-top">
                <div className="plant-card-icon"><SunMedium size={19} /></div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <b>{p.name}</b>
                  <span className="plant-card-code">{p.plantCode}</span>
                </div>
                {p.deleted && <span className="status cancelled">Archived</span>}
              </div>
              <div className="plant-card-meta">
                <span><MapPin size={13} /> {p.location}</span>
                <span>{TYPE_LABEL[p.type] || p.type} · {p.capacityDisplay}</span>
              </div>
              {!p.deleted ? (
                <div className="booking-actions" onClick={e => e.stopPropagation()}>
                  <button onClick={() => navigate(`/plants/${p.id}`)}>View details</button>
                  <button onClick={() => setEditing(p)}><Pencil size={13} /> Edit</button>
                  <button className="danger" onClick={() => setRemoving(p)}><Trash2 size={13} /> Remove</button>
                </div>
              ) : (
                <div className="booking-actions" onClick={e => e.stopPropagation()}>
                  <button onClick={() => navigate(`/plants/${p.id}`)}>View details</button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {showAdd && <AddPlantModal onClose={() => setShowAdd(false)} onAdded={() => { setShowAdd(false); load(); }} />}
      {editing && <AddPlantModal plant={editing} onClose={() => setEditing(null)} onAdded={() => { setEditing(null); load(); }} />}

      {removing && (
        <div className="modal-overlay" onClick={() => setRemoving(null)}>
          <div className="modal-card" style={{ maxWidth: 400 }} onClick={e => e.stopPropagation()}>
            <div className="modal-head"><h3>Remove {removing.name}?</h3><button className="icon-button" onClick={() => setRemoving(null)}><X size={18} /></button></div>
            <p style={{ fontSize: 13.5, color: "var(--muted)", lineHeight: 1.6 }}>
              This plant will be archived and hidden from new bookings. Every booking you've already made
              against it will stay exactly as it is in your booking history — nothing gets deleted.
            </p>
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
              <button className="btn btn-ghost full" onClick={() => setRemoving(null)}>Cancel</button>
              <button className="btn btn-primary full" style={{ background: "#c54c44" }} disabled={removeBusy} onClick={confirmRemove}>
                {removeBusy ? "Removing…" : "Remove plant"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
