import { ArrowRight, CheckCircle2, Gauge, ShieldCheck, SunMedium, Wrench, Droplets, Zap, Clock3, MapPin, Check } from "lucide-react";
import { Link } from "react-router-dom";
import { ServiceCard } from "../components/ServiceCard";
import { services } from "../data/services";
import { useAuthModal } from "../auth/AuthModalContext";

export function Home() {
  const { openLogin } = useAuthModal();
  return <div>
    <section className="hero">
      <div className="hero-copy">
        <span className="status-chip"><span/> Solar services, simplified</span>
        <h1>Powering a <em>greener</em><br/>tomorrow.</h1>
        <p>Book trusted solar installation, maintenance and professional cleaning — all in one place.</p>
        <div className="hero-actions"><Link className="btn btn-primary" to="/services">Explore Services <ArrowRight size={18}/></Link><button className="btn btn-ghost" onClick={openLogin}>Login / Sign In</button></div>
        <div className="trust-row"><span><ShieldCheck size={17}/> Verified technicians</span><span><CheckCircle2 size={17}/> Service reports</span><span><Gauge size={17}/> Plant insights</span></div>
      </div>
      <div className="hero-visual">
        <div className="sun"/><div className="hero-panel-grid">{Array.from({length: 24}).map((_,i)=><span key={i}/>)}</div>
        <div className="hero-house"><div className="roof"/><div className="house-body"/><div className="tree t1"/><div className="tree t2"/></div>
        <div className="floating-card"><div className="mini-icon"><SunMedium size={17}/></div><div><b>Plant performance</b><small>92% of expected output</small></div><span className="live-dot"/></div>
      </div>
    </section>

    <section className="stats-strip">
      <div><b>6</b><span>Core services</span></div><div><b>24×7</b><span>Plant monitoring</span></div><div><b>100%</b><span>Digital service reports</span></div><div><b>4 Cities</b><span>Service network</span></div>
    </section>

    
    <section className="amc-promo">
      <div className="amc-promo-copy">
        <span className="kicker">AMC · COMING SOON</span>
        <h2>Keep your solar plant performing at its best.</h2>
        <p>Save on routine maintenance with a yearly service plan that bundles preventive maintenance, cleaning and breakdown support.</p>
      </div>
      <Link className="btn btn-light" to="/amc">View AMC plans <ArrowRight size={18}/></Link>
    </section>

    <section className="section">
      <div className="section-heading"><div><span className="kicker">WHAT WE DO</span><h2>Solar care, from start to scale.</h2></div><Link to="/services" className="text-link">View all services <ArrowRight size={17}/></Link></div>
      <div className="service-grid">{services.map((s,i)=><ServiceCard key={s.id} service={s} featured={i===0}/>)}</div>
    </section>



    <section className="service-areas section" id="service-areas">
      <div className="service-areas-head">
        <div>
          <span className="kicker">SERVICE AREAS</span>
          <h2>Solar care, where you need it.</h2>
          <p>Oorja on Move currently serves customers across Jaipur and the Delhi NCR region.</p>
        </div>
        <div className="service-areas-note"><MapPin size={17}/><span>Expanding across more cities</span></div>
      </div>
      <div className="service-area-grid">
        {["Jaipur", "Gurgaon", "Delhi", "Noida"].map((city, i) => (
          <div className="service-area-card" key={city}>
            <div className="service-area-icon"><MapPin size={18}/></div>
            <div><strong>{city}</strong><span><Check size={13}/> Service available</span></div>
            <span className="area-index">0{i + 1}</span>
          </div>
        ))}
      </div>
    </section>

    <section className="section amc-section" id="amc">
      <div className="section-heading">
        <div><span className="kicker">AMC · COMING SOON</span><h2>Subscription Plans</h2><p className="amc-intro">Choose a service plan that keeps your solar plant maintained throughout the year and beyond. Register interest now and get a priced quotation the moment plans go live.</p></div>
        <Link to="/amc" className="text-link">View full details <ArrowRight size={17}/></Link>
      </div>
      <div className="amc-grid">
        {[
          { duration: "6 Months", maintenance: "2", cleaning: "1", breakdown: null, featured: false },
          { duration: "1 Year", maintenance: "4", cleaning: "3", breakdown: "1", featured: true },
          { duration: "1.5 Years", maintenance: "7", cleaning: "5", breakdown: "1", featured: false }
        ].map(plan => (
          <article className={`amc-card ${plan.featured ? "featured" : ""}`} key={plan.duration}>
            <span className="amc-badge" style={{ background: "#fff1c7", color: "#92660a" }}>COMING SOON</span>
            <div className="amc-card-top">
              <div className="amc-plan-icon"><Clock3 size={21} /></div>
              <span className="amc-duration">{plan.duration}</span>
            </div>
            <p className="amc-label">Plan includes</p>
            <ul className="amc-features">
              <li><Wrench size={17}/><span><b>{plan.maintenance}</b> Preventive Maintenance</span></li>
              <li><Droplets size={17}/><span><b>{plan.cleaning}</b> Cleaning Service</span></li>
              {plan.breakdown && <li><Zap size={17}/><span><b>{plan.breakdown}</b> Breakdown Repair</span></li>}
            </ul>
            <Link className="btn btn-primary full" to="/amc">Request a quote <ArrowRight size={17} /></Link>
          </article>
        ))}
      </div>
    </section>

    <section className="monitor-card">
      <div><span className="kicker">SMART PLANT INSIGHTS</span><h2>Your plant tells us when it needs help.</h2><p>Connect your PV monitoring data to receive actionable suggestions — from maintenance requests when performance drops to cleaning recommendations when soiling is detected.</p><Link to="/services/maintenance" className="btn btn-light">See recommended service <ArrowRight size={18}/></Link></div>
      <div className="monitor-screen"><div className="screen-top"><span>Jaipur Commercial Plant</span><span className="live">● LIVE</span></div><div className="chart"><i/><i/><i/><i/><i/><i/><i/><i/><i/><i/></div><div className="monitor-metrics"><div><small>Today's output</small><b>3,490 kWh</b></div><div><small>Performance</small><b>92%</b></div></div></div>
    </section>

    <section className="section workflow">
      <div className="section-heading"><div><span className="kicker">HOW IT WORKS</span><h2>Book in a few simple steps.</h2></div></div>
      <div className="steps">{["Choose a service","Tell us about your plant","Pick a date & pay","Track the technician"].map((x,i)=><div className="step" key={x}><span>0{i+1}</span><h3>{x}</h3><p>{["Select installation, maintenance or cleaning.","Add site details, issue photos or requirements.","Choose an available slot and confirm securely.","Get updates, reports and completion photos."][i]}</p></div>)}</div>
    </section>
  </div>;
}
