import { AlertCircle, X } from "lucide-react";
import { useState } from "react";
import { ApiError, PlantsApi, type Plant, type PlantType } from "../lib/api";

const PLANT_TYPES: { id: PlantType; label: string }[] = [
  { id: "RESIDENTIAL", label: "Residential" },
  { id: "COMMERCIAL", label: "Commercial" },
  { id: "INDUSTRIAL", label: "Industrial" }
];

/** Pass `plant` to edit an existing plant instead of creating a new one. */
export function AddPlantModal({ onClose, onAdded, plant }: { onClose: () => void; onAdded: (p: Plant) => void; plant?: Plant | null }) {
  const isEdit = !!plant;
  const [name, setName] = useState(plant?.name ?? "");
  const [type, setType] = useState<PlantType>(plant?.type ?? "RESIDENTIAL");
  const [capacity, setCapacity] = useState(plant ? String(plant.capacityKw) : "");
  const [location, setLocation] = useState(plant?.location ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const capacityNum = Number(capacity);
  const canSubmit = name.trim().length > 1 && location.trim().length > 2 && capacityNum > 0;

  const handleSubmit = async () => {
    setError(null);
    setSaving(true);
    try {
      const payload = { name: name.trim(), type, capacityKw: capacityNum, location: location.trim() };
      const saved = isEdit ? await PlantsApi.update(plant!.id, payload) : await PlantsApi.add(payload);
      onAdded(saved);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : `Couldn't ${isEdit ? "update" : "add"} this plant. Please try again.`);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-card" onClick={e => e.stopPropagation()}>
        <div className="modal-head">
          <h3>{isEdit ? "Edit plant / site" : "Add a plant / site"}</h3>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label>Plant / site name<input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Home Rooftop, Office Building" /></label>
        <label>Type
          <div className="cleaning-types" style={{ gridTemplateColumns: "repeat(3,1fr)" }}>
            {PLANT_TYPES.map(t => (
              <button key={t.id} type="button" className={`cleaning-type ${type === t.id ? "selected" : ""}`} onClick={() => setType(t.id)}>
                <b>{t.label}</b>
              </button>
            ))}
          </div>
        </label>
        <label>Capacity (kW)<input type="number" min="0.1" step="0.1" value={capacity} onChange={e => setCapacity(e.target.value)} placeholder="e.g. 5" /></label>
        <label>Location<input value={location} onChange={e => setLocation(e.target.value)} placeholder="Area, city" /></label>
        {error && <p className="field-error"><AlertCircle size={14} /> {error}</p>}
        <button className="btn btn-primary full" disabled={!canSubmit || saving} onClick={handleSubmit}>
          {saving ? (isEdit ? "Saving…" : "Adding…") : (isEdit ? "Save changes" : "Add plant")}
        </button>
      </div>
    </div>
  );
}
