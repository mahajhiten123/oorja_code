export function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className="brand">
      <img src="/assets/logo.png" alt="Oorja on Move" className={compact ? "brand-img compact" : "brand-img"} />
    </div>
  );
}
