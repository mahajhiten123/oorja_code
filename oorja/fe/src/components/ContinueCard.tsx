import { ArrowRight, History, X } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { clearContinuity, type ContinuityEntry } from "../data/continuityStore";

export function ContinueCard({ entry, onDismiss }: { entry: ContinuityEntry; onDismiss: () => void }) {
  const navigate = useNavigate();

  const dismiss = () => {
    clearContinuity();
    onDismiss();
  };

  return (
    <div className="continue-card">
      <div className="continue-card-icon"><History size={19} /></div>
      <div className="continue-card-body">
        <span className="continue-card-kicker">Continue where you left off</span>
        <b>{entry.title}</b>
        <span className="continue-card-sub">{entry.subtitle}</span>
      </div>
      <div className="continue-card-actions">
        <button className="btn btn-primary sm" onClick={() => navigate(entry.href)}>
          Continue <ArrowRight size={15} />
        </button>
        <button className="icon-button" aria-label="Dismiss" onClick={dismiss}><X size={15} /></button>
      </div>
    </div>
  );
}
