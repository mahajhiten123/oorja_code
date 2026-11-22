import { Search, SlidersHorizontal } from "lucide-react";
import { useMemo, useState } from "react";
import { ServiceCard } from "../components/ServiceCard";
import { services, categories } from "../data/services";

export function Services() {
  const [q, setQ] = useState("");
  const [category, setCategory] = useState<string>("all");

  const filtered = useMemo(() => {
    return services
      .filter(s => category === "all" || s.category === category)
      .filter(s => `${s.title} ${s.description}`.toLowerCase().includes(q.toLowerCase()));
  }, [q, category]);

  return <div className="page">
    <div className="page-heading">
      <div><span className="kicker">SERVICES</span><h1>Our Services</h1><p>End-to-end solar solutions for your home and business.</p></div>
    </div>

    <div className="category-tabs">
      {categories.map(c => (
        <button key={c.id} className={category === c.id ? "active" : ""} onClick={() => setCategory(c.id)}>{c.label}</button>
      ))}
    </div>

    <div className="service-toolbar">
      <div className="searchbox"><Search size={19}/><input value={q} onChange={e=>setQ(e.target.value)} placeholder="Search for a service..." /></div>
      <button className="filter-btn"><SlidersHorizontal size={18}/> Filters</button>
    </div>

    <div className="service-list">
      {filtered.map(s=><ServiceCard key={s.id} service={s}/>)}
      {filtered.length === 0 && (
        <div className="empty-state" style={{ gridColumn: "1 / -1" }}>
          <b>No services found</b>
          <p>Try a different search term or category.</p>
        </div>
      )}
    </div>

    <div className="service-note"><SparkleIcon/><div><b>Need help choosing?</b><p>Our team can recommend a service based on your plant and monitoring data.</p></div><a href="mailto:info@oorjaonmove.com">Talk to support</a></div>
  </div>
}
function SparkleIcon(){ return <div className="service-note-icon">✦</div> }
