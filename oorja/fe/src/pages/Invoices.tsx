import { useEffect, useState } from "react";
import { Download, Eye, FileText, Receipt } from "lucide-react";
import { ApiError } from "../lib/api";
import { DocumentsApi, type Invoice } from "../lib/documents";

function money(v: number) {
  return `₹${v.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
function when(iso: string) {
  return new Date(iso).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

/**
 * Profile → Invoices.
 *
 * Credit notes sit in the same list as invoices rather than in a separate
 * "refunds" tab, because from the customer's side they're one story: what
 * they were charged and what came back. They're shown as negative amounts
 * so the column reads correctly top to bottom.
 */
export function Invoices() {
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  useEffect(() => {
    DocumentsApi.invoices()
      .then(setInvoices)
      .catch(e => setError(e instanceof ApiError ? e.message : "Couldn't load your invoices."));
  }, []);

  const act = async (inv: Invoice, fn: () => Promise<void>) => {
    setBusyId(inv.id);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't open that document.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="page">
      <div className="page-heading">
        <span className="kicker">BILLING</span>
        <h1>Invoices</h1>
        <p className="muted">Every payment and refund, as a GST document you can download or forward to your accountant.</p>
      </div>

      {error && <p className="form-error">{error}</p>}
      {invoices === null && <p className="muted">Loading…</p>}
      {invoices?.length === 0 && <p className="muted">No invoices yet — they appear here as soon as a payment goes through.</p>}

      <div className="invoice-list">
        {(invoices ?? []).map(inv => {
          const credit = inv.type === "CREDIT_NOTE";
          return (
            <div key={inv.id} className={`invoice-row ${credit ? "credit" : ""}`}>
              <div className="invoice-row-main">
                <span className="invoice-icon">{credit ? <Receipt size={16} /> : <FileText size={16} />}</span>
                <div>
                  <b>{inv.invoiceNumber}</b>
                  <span className="muted small">
                    {credit ? "Credit note" : "Tax invoice"} · {when(inv.issuedAt)}
                    {inv.bookingToken ? ` · ${inv.bookingToken}` : ""}
                  </span>
                  <span className="muted small">{inv.description}</span>
                </div>
                <b className="invoice-amount">{credit ? `−${money(inv.totalInr)}` : money(inv.totalInr)}</b>
              </div>

              <div className="invoice-row-actions">
                <span className="muted small">incl. {inv.taxRatePercent}% GST ({money(inv.taxInr)})</span>
                <div>
                  <button className="btn btn-ghost" disabled={busyId === inv.id}
                          onClick={() => act(inv, () => DocumentsApi.viewInvoice(inv))}>
                    <Eye size={13} /> View
                  </button>
                  <button className="btn btn-ghost" disabled={busyId === inv.id}
                          onClick={() => act(inv, () => DocumentsApi.downloadInvoice(inv))}>
                    <Download size={13} /> {busyId === inv.id ? "…" : "PDF"}
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default Invoices;
