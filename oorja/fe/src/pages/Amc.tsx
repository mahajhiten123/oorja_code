import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AlertTriangle, ArrowRight, Check, CheckCircle2, Clock3, Droplets,
  ShieldCheck, Sparkles, Wrench, X, Zap
} from "lucide-react";
import { useAuth } from "../auth/AuthContext";
import { useAuthModal } from "../auth/AuthModalContext";
import { ApiError, AmcApi, PlantsApi, type Plant } from "../lib/api";
import { saveContinuity, clearContinuity } from "../data/continuityStore";

const PLANS = [
  {
    duration: "6 Months", maintenance: "2", cleaning: "1", breakdown: null as string | null,
    featured: false, blurb: "A light-touch plan for newer plants that just need periodic upkeep."
  },
  {
    duration: "1 Year", maintenance: "4", cleaning: "3", breakdown: "1",
    featured: true, blurb: "Our most popular plan — quarterly maintenance plus breakdown cover."
  },
  {
    duration: "1.5 Years", maintenance: "7", cleaning: "5", breakdown: "1",
    featured: false, blurb: "Best value for larger installations that need frequent servicing."
  }
];

const BENEFITS = [
  { icon: Wrench, title: "Preventive maintenance", copy: "Scheduled visits to check wiring, inverters and panel health before small issues become breakdowns." },
  { icon: Droplets, title: "Panel cleaning", copy: "Regular cleaning keeps soiling losses down and output close to rated capacity." },
  { icon: Zap, title: "Breakdown support", copy: "Priority response and repair cover included in the 1 Year and 1.5 Year plans." },
  { icon: ShieldCheck, title: "One fixed price", copy: "No per-visit billing surprises — everything for the plan period is bundled upfront." }
];

export function Amc() {
  const { isAuthenticated } = useAuth();
  const { openLogin } = useAuthModal();
  const navigate = useNavigate();

  const [pickerPlan, setPickerPlan] = useState<string | null>(null);
  const [plants, setPlants] = useState<Plant[] | null>(null);
  const [selectedPlantId, setSelectedPlantId] = useState<number | null>(null);
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!pickerPlan || !isAuthenticated) return;
    setPlants(null);
    PlantsApi.mine()
      .then(list => {
        setPlants(list);
        if (list.length === 1) setSelectedPlantId(list[0].id);
      })
      .catch(() => setPlants([]));
  }, [pickerPlan, isAuthenticated]);

  const openPicker = (duration: string) => {
    if (!isAuthenticated) {
      openLogin();
      return;
    }
    setError(null);
    setDone(false);
    setSelectedPlantId(null);
    setNotes("");
    setPickerPlan(duration);
  };

  const closePicker = () => setPickerPlan(null);

  const pickPlant = (plant: Plant) => {
    setSelectedPlantId(plant.id);
    if (pickerPlan) {
      saveContinuity({
        kind: "amc",
        title: `${pickerPlan} AMC`,
        subtitle: `for ${plant.name}`,
        href: "/amc"
      });
    }
  };

  const submit = async () => {
    if (!pickerPlan || !selectedPlantId) {
      setError("Please choose which plant this plan is for.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await AmcApi.requestQuote({ plantId: selectedPlantId, planLabel: pickerPlan, notes: notes || undefined });
      clearContinuity();
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't submit your request. Please try again.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="page">
      <div className="page-heading">
        <span className="kicker">AMC · COMING SOON</span>
        <h1>Annual Maintenance Contracts</h1>
        <p>
          Keep your solar plant performing at its best with a yearly care plan that bundles preventive
          maintenance, cleaning and breakdown support. AMC subscriptions are launching soon — register
          interest today and we'll send you a priced quotation the moment it's ready.
        </p>
      </div>

      <div className="support-grid support-grid-4" style={{ marginBottom: 46 }}>
        {BENEFITS.map(b => (
          <div key={b.title}><b.icon /><b>{b.title}</b><span>{b.copy}</span></div>
        ))}
      </div>

      <div className="section-heading">
        <div><span className="kicker">PLANS</span><h2>Choose a plan to register interest</h2></div>
      </div>
      <div className="amc-grid">
        {PLANS.map(plan => (
          <article className={`amc-card ${plan.featured ? "featured" : ""}`} key={plan.duration}>
            <span className="amc-badge" style={{ background: "#fff1c7", color: "#92660a" }}>COMING SOON</span>
            <div className="amc-card-top">
              <div className="amc-plan-icon"><Clock3 size={21} /></div>
              <span className="amc-duration">{plan.duration}</span>
            </div>
            <p className="amc-label">Plan includes</p>
            <ul className="amc-features">
              <li><Wrench size={17} /><span><b>{plan.maintenance}</b> Preventive Maintenance</span></li>
              <li><Droplets size={17} /><span><b>{plan.cleaning}</b> Cleaning Service</span></li>
              {plan.breakdown && <li><Zap size={17} /><span><b>{plan.breakdown}</b> Breakdown Repair</span></li>}
            </ul>
            <p style={{ fontSize: 12, color: "var(--muted)", margin: "0 0 16px", lineHeight: 1.6 }}>{plan.blurb}</p>
            <button className="btn btn-primary full" onClick={() => openPicker(plan.duration)}>
              Request a quote <ArrowRight size={17} />
            </button>
          </article>
        ))}
      </div>

      <section className="service-note" style={{ marginTop: 10 }}>
        <div className="service-note-icon"><Sparkles size={18} /></div>
        <div>
          <b>How AMC quote requests work</b>
          <p>We'll price your request and send a formal quotation to My Quotations — review it and pay only once you accept, just like an installation quote.</p>
        </div>
      </section>

      {pickerPlan && (
        <div className="modal-overlay" onClick={closePicker}>
          <div className="modal-card" onClick={e => e.stopPropagation()}>
            {!done ? (
              <>
                <div className="modal-head"><h3>{pickerPlan} AMC — request a quote</h3><button className="btn-ghost sm" onClick={closePicker}><X size={16} /></button></div>
                <p className="muted" style={{ fontSize: 13, marginTop: -6 }}>Choose which of your plants this plan is for. We'll price it and send you a quotation.</p>

                {plants === null && <p className="muted" style={{ fontSize: 13 }}>Loading your plants…</p>}
                {plants && plants.length === 0 && (
                  <div className="empty-state" style={{ padding: "30px 10px" }}>
                    <p style={{ margin: "0 0 12px" }}>You don't have any plants registered yet.</p>
                    <button className="btn btn-primary sm" onClick={() => navigate("/plants")}>Add a plant first</button>
                  </div>
                )}
                {plants && plants.length > 0 && (
                  <div style={{ margin: "14px 0" }}>
                    {plants.filter(p => !p.deleted).map(p => (
                      <div key={p.id} className={`plant-pick ${selectedPlantId === p.id ? "selected" : ""}`} onClick={() => pickPlant(p)}>
                        <div><b>{p.name}</b><span>{p.location} · {p.capacityDisplay}</span></div>
                        {selectedPlantId === p.id && <Check size={16} color="var(--green)" />}
                      </div>
                    ))}
                  </div>
                )}

                <label>
                  Anything we should know? (optional)
                  <textarea value={notes} onChange={e => setNotes(e.target.value)} placeholder="e.g. plant is on a sloped rooftop, prior AMC with another vendor, etc." />
                </label>

                {error && <p className="field-error"><AlertTriangle size={14} /> {error}</p>}

                <button className="btn btn-primary full" onClick={submit} disabled={submitting || !plants || plants.length === 0}>
                  {submitting ? "Submitting…" : "Submit request"}
                </button>
              </>
            ) : (
              <div className="success">
                <div className="success-icon"><CheckCircle2 size={34} /></div>
                <h3 style={{ margin: "0 0 8px" }}>Request submitted</h3>
                <p className="muted" style={{ fontSize: 13.5 }}>
                  We've received your interest in the {pickerPlan} AMC plan. Our team will price it and send
                  a quotation to My Quotations shortly.
                </p>
                <button className="btn btn-primary full" style={{ marginTop: 16 }} onClick={() => navigate("/quotations")}>View my quotations</button>
                <button className="btn btn-ghost full" style={{ marginTop: 8 }} onClick={closePicker}>Close</button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
