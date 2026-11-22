import { AlertTriangle, Pencil, Plus, UserCog, X } from "lucide-react";
import { useEffect, useState } from "react";
import { AdminTechniciansApi, ApiError, type Technician, type TechnicianStatus } from "../../lib/api";

const STATUS_LABEL: Record<TechnicianStatus, string> = {
  AVAILABLE: "Available",
  ON_JOB: "On a job",
  OFF_DUTY: "Off duty"
};

export function AdminTechnicians() {
  const [technicians, setTechnicians] = useState<Technician[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Technician | "new" | null>(null);

  const load = () => {
    setError(null);
    AdminTechniciansApi.list().then(setTechnicians).catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load technicians."));
  };

  useEffect(load, []);

  const handleStatus = async (t: Technician, status: TechnicianStatus) => {
    try {
      await AdminTechniciansApi.setStatus(t.id, status);
      load();
    } catch (e) {
      alert(e instanceof ApiError ? e.message : "Couldn't update status.");
    }
  };

  return (
    <div>
      <div className="admin-page-head">
        <div>
          <span className="kicker">TECHNICIANS</span>
          <h1>Technician roster</h1>
          <p className="muted">Manage your field team and their duty status.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setEditing("new")}><Plus size={16} /> Add technician</button>
      </div>

      {error && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><AlertTriangle size={20} /><h3 style={{ margin: "14px 0 6px" }}>Couldn't load technicians</h3><p className="muted">{error}</p><button className="btn btn-primary sm" style={{ marginTop: 14 }} onClick={load}>Retry</button></div>}
      {!error && technicians === null && <><div className="skeleton-row" /><div className="skeleton-row" /></>}
      {!error && technicians !== null && technicians.length === 0 && <div className="empty-state" style={{ textAlign: "center", padding: "60px 20px" }}><UserCog size={20} /><h3 style={{ margin: "14px 0 6px" }}>No technicians yet</h3><p className="muted">Add your first technician to start assigning bookings.</p></div>}

      {!error && technicians && technicians.length > 0 && (
        <div className="admin-table-wrap" style={{ marginTop: 16 }}>
          <table className="admin-table">
            <thead><tr><th>Name</th><th>Contact</th><th>Specialization</th><th>Active bookings</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {technicians.map(t => (
                <tr key={t.id}>
                  <td><b>{t.name}</b></td>
                  <td>{t.phone}<br /><small className="muted">{t.email}</small></td>
                  <td>{t.specialization}</td>
                  <td>{t.activeBookings}</td>
                  <td>
                    <select value={t.status} onChange={e => handleStatus(t, e.target.value as TechnicianStatus)} style={{ padding: "4px 8px", fontSize: 12.5 }}>
                      {t.status === "ON_JOB" && <option value="ON_JOB" disabled>On a job (auto)</option>}
                      <option value="AVAILABLE">Available</option>
                      <option value="OFF_DUTY">Off duty</option>
                    </select>
                  </td>
                  <td><button className="btn btn-ghost sm" onClick={() => setEditing(t)}><Pencil size={13} /> Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && <TechnicianModal technician={editing === "new" ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); load(); }} />}
    </div>
  );
}

function TechnicianModal({ technician, onClose, onDone }: { technician: Technician | null; onClose: () => void; onDone: () => void }) {
  const [name, setName] = useState(technician?.name ?? "");
  const [phone, setPhone] = useState(technician?.phone ?? "");
  const [email, setEmail] = useState(technician?.email ?? "");
  const [specialization, setSpecialization] = useState(technician?.specialization ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSave = name.trim().length > 1 && phone.trim().length >= 8 && specialization.trim().length > 1;

  const handleSubmit = async () => {
    setBusy(true); setError(null);
    try {
      const data = { name: name.trim(), phone: phone.trim(), email: email.trim(), specialization: specialization.trim() };
      if (technician) await AdminTechniciansApi.update(technician.id, data);
      else await AdminTechniciansApi.create(data);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save this technician.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head"><h3>{technician ? "Edit technician" : "Add technician"}</h3><button className="icon-button" onClick={onClose}><X size={18} /></button></div>
        <label>Full name<input value={name} onChange={e => setName(e.target.value)} placeholder="Technician's name" /></label>
        <label>Mobile number<input value={phone} onChange={e => setPhone(e.target.value.replace(/[^\d+ ]/g, ""))} placeholder="10-digit mobile number" /></label>
        <label>Email<input type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="technician@oorjaonmove.com" /></label>
        <label>Specialization<input value={specialization} onChange={e => setSpecialization(e.target.value)} placeholder="e.g. Installation, Cleaning, Maintenance" /></label>
        {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={!canSave || busy} onClick={handleSubmit}>{busy ? "Saving…" : technician ? "Save changes" : "Add technician"}</button>
      </div>
    </div>
  );
}
