import { ArrowUpRight, Brush, Home, Wrench } from "lucide-react";
import { Link } from "react-router-dom";
import type { Service } from "../data/services";

const iconMap = { solar: Home, wrench: Wrench, clean: Brush };

export function ServiceCard({ service, featured = false }: { service: Service; featured?: boolean }) {
  const Icon = iconMap[service.icon as keyof typeof iconMap];
  return (
    <Link to={`/services/${service.id}`} className={`service-card ${featured ? "featured" : ""}`}>
      <div className={`service-icon ${service.accent}`}><Icon size={25}/></div>
      <div className="service-card-body">
        <div className="eyebrow">{service.shortTitle}</div>
        <h3>{service.title}</h3>
        <p>{service.description}</p>
        <div className="card-bottom">
          <div>
            <span style={{ display: "block", fontSize: 10, color: "var(--muted)", marginBottom: 2 }}>Starting from</span>
            <strong>{service.price}</strong>
          </div>
          <span className="round-arrow"><ArrowUpRight size={18}/></span>
        </div>
      </div>
    </Link>
  );
}
