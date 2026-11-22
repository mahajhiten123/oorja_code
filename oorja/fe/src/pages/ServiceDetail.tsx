import { AlertCircle, ArrowRight, CheckCircle2, ChevronRight, CreditCard, MapPin, Plus, Upload } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useParams, useNavigate } from "react-router-dom";
import { services } from "../data/services";
import { useAuth } from "../auth/AuthContext";
import { useAuthModal } from "../auth/AuthModalContext";
import { ApiError, BookingsApi, PlantsApi, ServiceCatalogApi, type Plant, type SlotAvailability } from "../lib/api";
import { AddPlantModal } from "../components/AddPlantModal";

function todayIso() {
  return new Date().toISOString().slice(0, 10);
}

export function ServiceDetail() {
  const { id } = useParams();
  const service = services.find(s => s.id === id) ?? services[0];
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const { openLogin } = useAuthModal();

  const [date, setDate] = useState("");
  const [time, setTime] = useState("");
  const [notes, setNotes] = useState("");
  const [tier, setTier] = useState(service.cleaningTiers?.[0]?.id ?? "");
  const [files, setFiles] = useState<FileList | null>(null);
  const [touched, setTouched] = useState(false);

  const [slots, setSlots] = useState<SlotAvailability[] | null>(null);
  const [slotsError, setSlotsError] = useState<string | null>(null);
  const [slotsLoading, setSlotsLoading] = useState(false);

  const [plants, setPlants] = useState<Plant[] | null>(null);
  const [plantsError, setPlantsError] = useState<string | null>(null);
  const [plantId, setPlantId] = useState<number | null>(null);
  const [showAddPlant, setShowAddPlant] = useState(false);

  const [catalog, setCatalog] = useState<string[]>([]);

  const immediate = service.category === "cleaning" || service.category === "maintenance";
  const iconGlyph = service.category === "cleaning" ? "✦" : service.category === "maintenance" ? "⚒" : "☀";
  const needsImage = service.category === "installation";

  useEffect(() => {
    if (!isAuthenticated) return;
    PlantsApi.mine()
      .then(list => { setPlants(list); if (list.length > 0) setPlantId(list[0].id); })
      .catch(e => setPlantsError(e instanceof ApiError ? e.message : "Couldn't load your plants."));
    ServiceCatalogApi.list().then(setCatalog).catch(() => {});
  }, [isAuthenticated]);

  // Fetches the live slot grid whenever the chosen date changes, and clears
  // a previously-picked time if it's no longer available on the new date.
  useEffect(() => {
    if (!date) { setSlots(null); return; }
    setSlotsLoading(true);
    setSlotsError(null);
    BookingsApi.slots(date)
      .then(list => {
        setSlots(list);
        setTime(prev => (prev && list.some(s => s.time.slice(0, 5) === prev && s.available) ? prev : ""));
      })
      .catch(e => setSlotsError(e instanceof ApiError ? e.message : "Couldn't load available time slots."))
      .finally(() => setSlotsLoading(false));
  }, [date]);

  const plantMissing = !plantId;
  const dateMissing = !date;
  const timeMissing = !time;
  const imageMissing = needsImage && (!files || files.length === 0);
  const canSubmit = !plantMissing && !dateMissing && !timeMissing && !imageMissing;

  const handleAddedPlant = (plant: Plant) => {
    setPlants(prev => (prev ? [plant, ...prev] : [plant]));
    setPlantId(plant.id);
    setShowAddPlant(false);
  };

  const handleSubmit = () => {
    if (!isAuthenticated) {
      openLogin();
      return;
    }
    setTouched(true);
    if (!canSubmit) return;

    // Prefer the exact service-type string the backend's catalog recognizes,
    // falling back to our local display title if there's no exact match.
    const serviceType = catalog.find(c => c.toLowerCase() === service.title.toLowerCase()) ?? service.title;

    const params = new URLSearchParams({
      service: service.id,
      serviceType,
      plantId: String(plantId),
      date,
      time
    });
    if (!immediate) params.set("quote", "1");
    if (service.cleaningTiers) params.set("tier", tier);
    if (notes.trim()) params.set("notes", notes.trim());

    // File objects can't survive a query string, so any selected site
    // photos/documents ride along as router state instead.
    const filesArray = files && files.length > 0 ? Array.from(files) : undefined;
    navigate(`/checkout?${params.toString()}`, filesArray ? { state: { files: filesArray } } : undefined);
  };

  return <div className="page detail-page">
    <div className="breadcrumbs"><Link to="/services">Services</Link><ChevronRight size={15} /><span>{service.shortTitle}</span></div>
    <div className="detail-hero">
      <div><span className={`service-icon large ${service.accent}`}>{iconGlyph}</span><span className="kicker">SERVICE</span><h1>{service.title}</h1><p>{service.description}</p><div className="price-big">Starting from <b>{service.price}</b></div></div>
      <div className="detail-art"><div className="art-sun" /><div className="art-panel" /><div className="art-glow" /></div>
    </div>
    <div className="detail-layout">
      <div>
        <section className="detail-section"><h2>What's included</h2><div className="included-grid">{service.bullets.map(x => <div key={x}><CheckCircle2 size={18} /><span>{x}</span></div>)}</div></section>
        <section className="detail-section"><h2>How the service works</h2><div className="workflow-list">{service.steps.map((x, i) => <div key={x}><span>{String(i + 1).padStart(2, "0")}</span><b>{x}</b></div>)}</div></section>
      </div>
      <aside className="booking-box">
        <span className="kicker">{immediate ? "BOOK SERVICE" : "REQUEST QUOTE"}</span><h3>{immediate ? "Choose your plant, date & time" : "Tell us about your site"}</h3>

        {!isAuthenticated && (
          <p className="field-hint" style={{ background: "#fff8e6", color: "#92660a", padding: "10px 12px", borderRadius: 10 }}>
            You'll be asked to log in before this request is submitted.
          </p>
        )}

        {service.cleaningTiers && (
          <>
            <label>Cleaning type</label>
            <div className="cleaning-types">
              {service.cleaningTiers.map(t => (
                <button key={t.id} type="button" className={`cleaning-type ${tier === t.id ? "selected" : ""}`} onClick={() => setTier(t.id)}>
                  <b>{t.label}</b>
                  <span>{t.description}</span>
                  <strong>{t.price}</strong>
                </button>
              ))}
            </div>
          </>
        )}

        <label>Plant / site</label>
        {plantsError && <p className="field-error"><AlertCircle size={14} /> {plantsError}</p>}
        {isAuthenticated && plants === null && !plantsError && <p style={{ fontSize: 12, color: "var(--muted)", marginBottom: 8 }}>Loading your plants…</p>}
        {isAuthenticated && plants && plants.length === 0 && (
          <div className="empty-state" style={{ padding: "18px 10px", marginBottom: 8 }}>
            <b>No plants registered yet</b>
            <p>Add your site to book this service against it.</p>
            <button className="btn btn-primary" style={{ marginTop: 10 }} onClick={() => setShowAddPlant(true)}><Plus size={16} /> Add a plant</button>
          </div>
        )}
        {isAuthenticated && plants && plants.length > 0 && (
          <>
            {plants.map(p => (
              <div key={p.id} className={`plant-pick ${plantId === p.id ? "selected" : ""}`} onClick={() => setPlantId(p.id)}>
                <div><b>{p.name}</b><span><MapPin size={11} style={{ verticalAlign: -1 }} /> {p.location} · {p.capacityDisplay}</span></div>
                {plantId === p.id && <CheckCircle2 size={17} color="var(--green)" />}
              </div>
            ))}
            <button className="text-link" style={{ marginBottom: 10 }} onClick={() => setShowAddPlant(true)}><Plus size={14} /> Add another plant</button>
          </>
        )}
        {!isAuthenticated && (
          <div className="plant-pick" style={{ color: "var(--muted)", fontSize: 12.5 }}>Log in to choose or add a plant.</div>
        )}
        {touched && isAuthenticated && plantMissing && <p className="field-error"><AlertCircle size={14} /> Please select or add a plant to continue.</p>}

        <label>Preferred date<input type="date" value={date} onChange={e => setDate(e.target.value)} min={todayIso()} /></label>
        {touched && dateMissing && <p className="field-error"><AlertCircle size={14} /> Please choose a preferred date to continue.</p>}

        <label>Preferred time slot</label>
        {date && slotsLoading && <p className="field-hint" style={{ marginBottom: 8 }}>Checking availability…</p>}
        {slotsError && <p className="field-error"><AlertCircle size={14} /> {slotsError}</p>}
        {date && !slotsLoading && !slotsError && (
          <div className="time-slots">
            {(slots ?? []).map(slot => {
              const hhmm = slot.time.slice(0, 5);
              return (
                <button
                  key={hhmm}
                  type="button"
                  className={`time-slot ${time === hhmm ? "selected" : ""}`}
                  disabled={!slot.available}
                  title={slot.full ? "This slot is fully booked" : slot.tooSoon ? "Too soon to book — please pick a later slot" : undefined}
                  onClick={() => setTime(hhmm)}
                >
                  {slot.label}{slot.full ? " · Full" : ""}
                </button>
              );
            })}
          </div>
        )}
        {!date && <p className="field-hint">Choose a date to see available time slots.</p>}
        {touched && timeMissing && <p className="field-error"><AlertCircle size={14} /> Please choose a preferred time slot.</p>}

        {needsImage && <>
          <label>Site / project details<textarea value={notes} onChange={e => setNotes(e.target.value)} placeholder="Tell us your capacity, site type and requirements..." /></label>
          <label className="upload"><Upload size={18} /><span>Upload site photos / documents</span><input type="file" multiple accept="image/*,.pdf" onChange={e => setFiles(e.target.files)} /></label>
          {files && files.length > 0 && <p className="field-hint">{files.length} file{files.length > 1 ? "s" : ""} selected</p>}
          {touched && imageMissing && <p className="field-error"><AlertCircle size={14} /> Please upload at least one site photo or document to request a quotation.</p>}
        </>}

        <button className="btn btn-primary full" onClick={handleSubmit} disabled={touched && isAuthenticated && !canSubmit}>{service.cta}<ArrowRight size={18} /></button>
        <p className="secure"><CreditCard size={15} /> Secure checkout · GST invoice available</p>
      </aside>
    </div>

    {showAddPlant && <AddPlantModal onClose={() => setShowAddPlant(false)} onAdded={handleAddedPlant} />}
  </div>
}
