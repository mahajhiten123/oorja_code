import { Mail, MessageCircle, Phone, ChevronDown, XCircle, Wallet2, ShieldCheck, Clock3, Check } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";

type Category = "all" | "booking" | "cancellation" | "amc" | "installation" | "reports";

const CATEGORIES: { id: Category; label: string }[] = [
  { id: "all", label: "All" },
  { id: "booking", label: "Booking & payments" },
  { id: "cancellation", label: "Cancellations & refunds" },
  { id: "amc", label: "AMC plans" },
  { id: "installation", label: "Installation" },
  { id: "reports", label: "Reports & technicians" }
];

const FAQS: { category: Category; q: string; a: string }[] = [
  { category: "booking", q: "How do I book a cleaning service?", a: "Choose Solar Plant Cleaning, select your plant and preferred date, then confirm and pay securely through Razorpay." },
  { category: "booking", q: "What payment methods are accepted?", a: "UPI, debit/credit cards, net banking and wallets — all processed securely through Razorpay. We never store your card details." },
  { category: "booking", q: "Can I reschedule a booking?", a: "Yes. Open the booking under My Bookings and choose Reschedule, as long as it's still pending or hasn't been assigned a technician's visit yet." },
  { category: "booking", q: "I paid but don't see my booking. What do I do?", a: "This is rare — if a payment succeeds but the booking doesn't appear, contact support with your payment reference and we'll resolve it immediately; you will not be charged twice." },

  { category: "cancellation", q: "What is your cancellation policy?", a: "You can cancel any upcoming booking (not yet completed) from My Bookings. We ask for a brief reason so we can improve, but it doesn't block the cancellation." },
  { category: "cancellation", q: "Will I get a refund if I cancel a paid booking?", a: "Yes. If you'd already paid for the booking, cancelling it automatically creates a refund request that our team reviews. Once approved, the refund is issued back to your original payment method via Razorpay." },
  { category: "cancellation", q: "How long does a refund take?", a: "Once our team initiates it, Razorpay typically settles refunds in 5–7 business days depending on your bank. You can track the live status under My Bookings → Refund status." },
  { category: "cancellation", q: "Can a booking be cancelled after the technician has visited?", a: "No — once a service is marked completed, it can no longer be cancelled or refunded through the app. If something went wrong during the visit, please contact support directly." },
  { category: "cancellation", q: "Does removing a plant/site affect my past bookings?", a: "No. Removing a plant from My Plants only hides it from new bookings — every booking you've ever made against it stays exactly as it was in your booking history." },

  { category: "amc", q: "What is an AMC (Annual Maintenance Contract)?", a: "An AMC is a yearly care plan for your solar plant that bundles preventive maintenance visits, panel cleaning, and breakdown support at a fixed price, so you don't pay per visit." },
  { category: "amc", q: "Are AMC plans live yet?", a: "AMC subscriptions are launching soon. You can already register interest against one of your plants — our team will price it and send you a formal quotation to review and pay, exactly like an installation quote." },
  { category: "amc", q: "How do I request an AMC plan?", a: "Go to the AMC section, pick a plan duration and the plant it applies to, and submit — you'll see the priced quotation appear under My Quotations once our team has reviewed it." },
  { category: "amc", q: "Can I cancel an AMC subscription after paying?", a: "Yes, the same cancellation and refund policy applies to a paid AMC subscription as any other paid booking." },

  { category: "installation", q: "How does installation pricing work?", a: "Installation requests begin with site details and photos. Our team reviews them and prepares a formal quotation — you only pay once you've reviewed and accepted it." },
  { category: "installation", q: "Can I upload photos for a repair or installation request?", a: "Yes. The maintenance and installation workflows support issue descriptions and photo/document uploads so our technicians can prepare before the visit." },
  { category: "installation", q: "How long is a quotation valid for?", a: "Each quotation shows a valid-until date. If it expires before you accept it, just let us know and we'll issue a fresh one." },

  { category: "reports", q: "Will I receive a service report?", a: "Yes. Completed maintenance and cleaning visits include a digital service/completion report, with before/after photos where applicable." },
  { category: "reports", q: "Who will show up for my service visit?", a: "A verified Oorja on Move technician assigned to your booking — their name and status are visible under My Bookings once assigned." }
];

export function Help() {
  const [open, setOpen] = useState<string | null>(null);
  const [category, setCategory] = useState<Category>("all");

  const visible = category === "all" ? FAQS : FAQS.filter(f => f.category === category);

  return (
    <div className="page help-page">
      <div className="page-heading">
        <span className="kicker">HELP CENTER</span>
        <h1>How can we help?</h1>
        <p>Answers and support for your Oorja on Move services — including our cancellation, refund and AMC policies.</p>
      </div>

      <div className="support-grid">
        <a href="mailto:info@oorjaonmove.com"><Mail /><b>Email support</b><span>info@oorjaonmove.com</span></a>
        <a href="tel:+911800000000"><Phone /><b>Call support</b><span>Mon–Sat · 9 AM–7 PM</span></a>
        <Link to="/support"><MessageCircle /><b>Raise a ticket</b><span>Track replies in one thread</span></Link>
      </div>

      {/* ---- Policy summary cards ---- */}
      <section className="section" style={{ padding: "0 0 50px" }}>
        <span className="kicker">POLICIES</span>
        <h2 style={{ marginBottom: 22 }}>Cancellation & refund policy</h2>
        <div className="profile-grid">
          <div className="profile-card">
            <h3 style={{ display: "flex", alignItems: "center", gap: 9 }}><XCircle size={19} color="var(--green)" /> Cancellations</h3>
            <ul className="policy-list">
              <li><Check size={14} /> Cancel any booking that hasn't been marked completed yet, right from My Bookings.</li>
              <li><Check size={14} /> You'll be asked for a short reason — this helps us and is visible to our support team.</li>
              <li><Check size={14} /> Once a technician has completed the visit, the booking can no longer be cancelled through the app.</li>
            </ul>
          </div>
          <div className="profile-card">
            <h3 style={{ display: "flex", alignItems: "center", gap: 9 }}><Wallet2 size={19} color="var(--green)" /> Refunds</h3>
            <ul className="policy-list">
              <li><Check size={14} /> Cancelling a paid booking automatically raises a refund request — no separate form needed.</li>
              <li><Check size={14} /> Our team reviews it, then initiates the refund back to your original payment method via Razorpay.</li>
              <li><Check size={14} /> Typical turnaround is 5–7 business days after initiation; track live status under My Bookings.</li>
              <li><Check size={14} /> If a request doesn't qualify for a refund, we'll show the reason directly against that booking.</li>
            </ul>
          </div>
        </div>
      </section>

      {/* ---- AMC teaser ---- */}
      <section className="section" style={{ padding: "0 0 50px" }}>
        <div className="amc-promo" style={{ margin: 0 }}>
          <div className="amc-promo-copy">
            <span className="kicker">AMC · COMING SOON</span>
            <h2 style={{ fontSize: 26 }}>Annual Maintenance Contracts</h2>
            <p>Bundle preventive maintenance, cleaning and breakdown support into one yearly plan. Registering interest today gets you a priced quotation as soon as plans go live.</p>
          </div>
          <Link className="btn btn-light" to="/amc"><ShieldCheck size={17} /> View AMC plans</Link>
        </div>
      </section>

      {/* ---- FAQ ---- */}
      <div className="faq">
        <span className="kicker">FAQ</span>
        <h2>Frequently asked questions</h2>
        <div className="category-tabs" style={{ marginTop: 18 }}>
          {CATEGORIES.map(c => (
            <button key={c.id} className={category === c.id ? "active" : ""} onClick={() => setCategory(c.id)}>{c.label}</button>
          ))}
        </div>
        {visible.map((f) => {
          const key = f.category + f.q;
          return (
            <button className="faq-row" key={key} onClick={() => setOpen(open === key ? null : key)}>
              <span><b>{f.q}</b>{open === key && <small>{f.a}</small>}</span>
              <ChevronDown className={open === key ? "rotate" : ""} />
            </button>
          );
        })}
      </div>

      <section className="section" style={{ paddingBottom: 20 }}>
        <div className="service-note">
          <div className="service-note-icon"><Clock3 size={19} /></div>
          <div>
            <b>Still have a question?</b>
            <p>Our support team usually replies within a few hours on working days.</p>
          </div>
          <a href="mailto:info@oorjaonmove.com">Contact support</a>
        </div>
      </section>
    </div>
  );
}
