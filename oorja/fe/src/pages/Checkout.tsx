import { AlertTriangle, ArrowLeft, Check, CreditCard, Landmark, MapPin, ShieldCheck, Smartphone, Tag, Wallet } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams, useNavigate, useLocation, Link } from "react-router-dom";
import { services } from "../data/services";
import { useAuth } from "../auth/AuthContext";
import { addNotification } from "../data/notificationsStore";
import { ApiError, BookingsApi, getIdempotencyKey, clearIdempotencyKey, PlantsApi, type ApiBooking, type Plant, type PriceQuoteResponse } from "../lib/api";
import { createRazorpayOrder, openRazorpayCheckout, verifyRazorpayPayment, type RazorpayPaymentSuccess } from "../lib/razorpay";

function formatBookingDate(iso: string | null) {
  if (!iso) return "—";
  const d = new Date(iso + "T00:00:00");
  return d.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
}

function formatSlotLabel(hhmm: string | null) {
  if (!hhmm) return "—";
  const [h] = hhmm.split(":").map(Number);
  const end = h + 2;
  const label = (hour: number) => {
    const period = hour >= 12 ? "PM" : "AM";
    const h12 = hour % 12 === 0 ? 12 : hour % 12;
    return `${h12} ${period}`;
  };
  return `${label(h)} – ${label(end)}`;
}

export function Checkout() {
  const [params] = useSearchParams();
  const location = useLocation();
  const uploadedFiles = (location.state as { files?: File[] } | null)?.files;
  const navigate = useNavigate();
  const { user } = useAuth();

  const service = services.find(s => s.id === params.get("service")) ?? services[4];
  const quote = params.get("quote") === "1";
  const tierId = params.get("tier");
  const tier = service.cleaningTiers?.find(t => t.id === tierId) ?? service.cleaningTiers?.[0];
  const preferredDate = params.get("date");
  const preferredTime = params.get("time");
  const plantId = Number(params.get("plantId") || 0) || null;
  const serviceType = params.get("serviceType") || service.title;
  const notesFromDetail = params.get("notes") || "";

  const [plant, setPlant] = useState<Plant | null>(null);
  const [plantLoadError, setPlantLoadError] = useState<string | null>(null);

  const [method, setMethod] = useState("upi");
  const [paid, setPaid] = useState(false);
  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState<string | null>(null);
  const [paymentRef, setPaymentRef] = useState<string | null>(null);
  const [booking, setBooking] = useState<ApiBooking | null>(null);

  const [promoInput, setPromoInput] = useState("");
  const [promoApplied, setPromoApplied] = useState(false);
  const [promoError, setPromoError] = useState<string | null>(null);

  // Pricing is always fetched from the backend — PricingService is the only
  // thing allowed to decide what this actually costs. We never compute a
  // total in the browser and hand it to Razorpay.
  const [priceQuote, setPriceQuote] = useState<PriceQuoteResponse | null>(null);
  const [priceLoading, setPriceLoading] = useState(true);
  const [priceError, setPriceError] = useState<string | null>(null);

  const fetchPrice = async (couponCode?: string) => {
    setPriceLoading(true);
    setPriceError(null);
    try {
      const q = await BookingsApi.price({ serviceId: service.id, tierId: tier?.id, couponCode, quoteRequest: quote });
      setPriceQuote(q);
      if (couponCode) {
        if (q.couponApplied) {
          setPromoApplied(true);
          setPromoError(null);
        } else {
          setPromoApplied(false);
          setPromoError(q.couponError || "Invalid or expired coupon code.");
        }
      }
      return q;
    } catch (e) {
      setPriceError(e instanceof ApiError ? e.message : "Couldn't load pricing. Please try again.");
      return null;
    } finally {
      setPriceLoading(false);
    }
  };

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { fetchPrice(); }, [service.id, tier?.id, quote]);

  useEffect(() => {
    if (!plantId) {
      setPlantLoadError("No plant selected. Please go back and choose a plant before checking out.");
      return;
    }
    PlantsApi.mine()
      .then(list => {
        const found = list.find(p => p.id === plantId);
        if (!found) {
          setPlantLoadError("We couldn't find the selected plant on your account. Please go back and pick again.");
        } else {
          setPlant(found);
        }
      })
      .catch(e => setPlantLoadError(e instanceof ApiError ? e.message : "Couldn't load plant details."));
  }, [plantId]);

  const subtotal = priceQuote?.subtotal ?? 0;
  const discount = priceQuote?.discount ?? 0;
  const taxes = priceQuote?.tax ?? 0;
  const total = priceQuote?.total ?? 0;
  // Compute the actual discount % from the backend's numbers so the label is
  // always accurate — never hardcoded (the coupon table may grant any %).
  const discountPct = subtotal > 0 && discount > 0 ? Math.round((discount / subtotal) * 100) : 0;

  const handleApplyPromo = async () => {
    const code = promoInput.trim();
    if (!code) {
      setPromoError("Enter a coupon code first.");
      setPromoApplied(false);
      return;
    }
    await fetchPrice(code);
  };

  const removePromo = () => {
    setPromoApplied(false);
    setPromoInput("");
    setPromoError(null);
    fetchPrice();
  };

  const fmt = (n: number) => `₹${n.toLocaleString("en-IN")}`;

  const createBookingOnBackend = async (razorpayOrderId?: string) => {
    if (!plantId || !preferredDate || !preferredTime) {
      throw new Error("Missing plant, date or time — please go back and complete the booking form.");
    }
    // Same key for every retry of this exact booking (double-click, timeout, flaky network), so the
    // server returns the original booking instead of creating a second one and taking a second slot.
    const idemKey = getIdempotencyKey(
      "booking",
      JSON.stringify([plantId, serviceType, preferredDate, preferredTime, razorpayOrderId ?? null, !!quote])
    );
    const created = (quote && uploadedFiles && uploadedFiles.length > 0)
      ? await BookingsApi.createWithFiles(
          {
            plantId,
            serviceType,
            scheduledDate: preferredDate,
            scheduledTime: preferredTime,
            notes: notesFromDetail || undefined,
            razorpayOrderId,
            requestQuote: quote
          },
          uploadedFiles,
          idemKey
        )
      : await BookingsApi.create({
          plantId,
          serviceType,
          scheduledDate: preferredDate,
          scheduledTime: preferredTime,
          notes: notesFromDetail || undefined,
          razorpayOrderId,
          requestQuote: quote
        }, idemKey);
    // Intent complete: the next purchase must get fresh keys.
    clearIdempotencyKey("booking");
    clearIdempotencyKey("order");
    return created;
  };

  const handlePay = async () => {
    setPayError(null);

    if (!plantId || plantLoadError) {
      setPayError("Please go back and select a valid plant before continuing.");
      return;
    }

    if (quote) {
      // Quote requests (e.g. new installation) skip payment entirely — they
      // still create a real booking/site-visit record on the backend.
      setPaying(true);
      try {
        const created = await createBookingOnBackend();
        setBooking(created);
        addNotification("booking", "Quote request submitted", `${service.shortTitle} — request ${created.token}, site visit scheduled ${formatBookingDate(preferredDate)}.`);
        setPaid(true);
      } catch (e) {
        setPayError(e instanceof ApiError ? e.message : e instanceof Error ? e.message : "Couldn't submit your request. Please try again.");
      } finally {
        setPaying(false);
      }
      return;
    }

    setPaying(true);
    try {
      const order = await createRazorpayOrder(
        {
          serviceId: service.id,
          tierId: tier?.id,
          couponCode: promoApplied ? promoInput.trim() : undefined,
          draftPlantId: plantId ?? undefined,
          draftScheduledDate: preferredDate ?? undefined,
          draftScheduledTime: preferredTime ?? undefined,
          draftServiceType: serviceType,
          draftNotes: notesFromDetail || undefined,
          draftLocation: plant?.location
        },
        `rcpt_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`
      );

      openRazorpayCheckout({
        order,
        description: `${service.title}${tier ? ` — ${tier.label}` : ""}`,
        customerName: user?.name ?? "Customer",
        customerEmail: user?.email,
        customerPhone: user?.phone,
        onSuccess: async (payment: RazorpayPaymentSuccess) => {
          try {
            const verified = await verifyRazorpayPayment(payment);
            if (!verified) {
              setPaying(false);
              setPayError("We couldn't verify this payment. If money was deducted, it will be refunded automatically — please contact support.");
              return;
            }
            setPaymentRef(payment.razorpay_payment_id);
            const created = await createBookingOnBackend(order.orderId);
            setBooking(created);
            addNotification("booking", "Booking confirmed", `${service.shortTitle}${tier ? ` (${tier.label})` : ""} — Booking ${created.token}, scheduled ${formatBookingDate(preferredDate)}.`);
            setPaying(false);
            setPaid(true);
          } catch (e) {
            setPaying(false);
            setPayError(
              "Payment succeeded but we couldn't create the booking record. Please contact support with payment reference " +
              payment.razorpay_payment_id + "."
            );
          }
        },
        onFailure: (err) => {
          setPaying(false);
          setPayError(err instanceof Error ? err.message : "Payment failed. Please try again.");
        },
        onDismiss: () => {
          setPaying(false);
        }
      });
    } catch (e) {
      setPaying(false);
      setPayError(e instanceof ApiError ? e.message : "Couldn't start the payment. Please check your connection and try again.");
    }
  };

  if (paid && booking) return (
    <div className="checkout-page">
      <div className="payment-success">
        <div className="success-icon"><Check size={40} /></div>
        <span className="kicker">{quote ? "REQUEST SUBMITTED" : "PAYMENT SUCCESSFUL"}</span>
        <h1>{quote ? "Your quote request has been submitted." : "Your booking has been confirmed."}</h1>
        <p>{"We've sent the details to your email and phone number."}</p>
        <div className="receipt">
          <div><span>Booking ID</span><b style={{ display: "block", marginTop: 4 }}>{booking?.token}</b></div>
          <div style={{ textAlign: "right" }}><span>Amount Paid</span><b style={{ display: "block", marginTop: 4 }}>{quote ? "Quote pending" : fmt(total)}</b></div>
        </div>
        {paymentRef && <p style={{ fontSize: 11, color: "var(--muted)", marginTop: -14 }}>Payment reference: {paymentRef}</p>}
        <button className="btn btn-primary full" onClick={() => navigate(quote ? "/quotations" : "/bookings")}>{quote ? "Track My Quotation" : "View My Bookings"}</button>
        <div style={{ height: 10 }} />
        <Link className="btn btn-ghost full" to="/services">Continue Shopping</Link>
      </div>
    </div>
  );

  return <div className="checkout-page">
    <div className="checkout-head"><button className="back" onClick={() => navigate(-1)}><ArrowLeft size={17} /> Back</button><div><span className="kicker">CHECKOUT</span><h1>Confirm your service</h1></div><span className="secure-label"><ShieldCheck size={16} /> Secure</span></div>
    <div className="checkout-grid">
      <div className="checkout-main">
        <section className="checkout-card">
          <div className="card-title"><span className="number">1</span><div><h3>Service</h3><p>Selected service</p></div></div>
          <div className="checkout-service">
            <div className={`service-icon ${service.accent}`}>☀</div>
            <div className="checkout-service-inner">
              <b>{service.title}{tier ? ` — ${tier.label}` : ""}</b>
              <span>{quote ? "Quote request — final price after site survey" : "Standard service booking"}</span>
              {preferredDate && <span>Preferred slot: {formatBookingDate(preferredDate)}, {formatSlotLabel(preferredTime)}</span>}
            </div>
            <strong>{quote ? "Quote" : priceLoading ? "…" : fmt(subtotal)}</strong>
          </div>
        </section>

        <section className="checkout-card">
          <div className="card-title"><span className="number">2</span><div><h3>Service location</h3><p>Tied to the plant/site you selected</p></div></div>
          {plantLoadError && <p className="field-error" style={{ marginTop: 0 }}><AlertTriangle size={14} /> {plantLoadError}</p>}
          {!plantLoadError && !plant && <p style={{ fontSize: 12.5, color: "var(--muted)" }}>Loading plant details…</p>}
          {plant && (
            <div className="address-input" style={{ padding: "13px 11px", color: "var(--muted)", fontSize: 13 }}>
              <MapPin size={18} /><span style={{ marginLeft: 8 }}><b style={{ color: "var(--ink)" }}>{plant.name}</b> · {plant.location} · {plant.capacityDisplay}</span>
            </div>
          )}
        </section>

        {!quote && <section className="checkout-card">
          <div className="card-title"><span className="number">3</span><div><h3>Payment method</h3><p>Secured by Razorpay</p></div></div>
          <div className="payment-methods four">
            {[["upi", "UPI", Smartphone], ["card", "Card / Debit", CreditCard], ["netbanking", "Net Banking", Landmark], ["wallet", "Wallets", Wallet]].map(([mid, label, Icon]: any) =>
              <button className={method === mid ? "selected" : ""} key={mid} onClick={() => setMethod(mid)}><Icon size={20} /><span>{label}</span><i>{method === mid ? "✓" : ""}</i></button>
            )}
          </div>
          <p style={{ fontSize: 11, color: "var(--muted)", marginTop: 12 }}>You'll choose your exact UPI app, card or bank on the Razorpay screen that opens next.</p>
        </section>}

        {payError && (
          <div className="checkout-card" style={{ borderColor: "#f3c6c1", background: "#fdf3f2", display: "flex", gap: 12, alignItems: "flex-start" }}>
            <AlertTriangle size={18} color="#c54c44" style={{ flex: "none", marginTop: 2 }} />
            <p style={{ margin: 0, fontSize: 12.5, color: "#8a3a34" }}>{payError}</p>
          </div>
        )}
      </div>

      <aside className="order-summary">
        <span className="kicker">ORDER SUMMARY</span>
        <h3>{service.shortTitle}{tier ? ` (${tier.label})` : ""}</h3>
        <div className="summary-line"><span>Service amount</span><b>{quote ? "To be quoted" : priceLoading ? "…" : fmt(subtotal)}</b></div>
        {!quote && promoApplied && <div className="summary-line"><span className="promo-applied-line"><Tag size={13} style={{ verticalAlign: -2 }} /> Coupon applied{discountPct > 0 ? ` (${discountPct}% off)` : ""}</span><b className="promo-applied-line">−{fmt(discount)}</b></div>}
        {!quote && <div className="summary-line"><span>Taxes & charges</span><b>{priceLoading ? "…" : fmt(taxes)}</b></div>}
        <div className="summary-total"><span>Total Amount</span><strong>{quote ? "Quote after survey" : priceLoading ? "…" : fmt(total)}</strong></div>
        {priceError && <p className="field-error" style={{ marginTop: 8 }}><AlertTriangle size={14} /> {priceError}</p>}

        {!quote && (
          <>
            <div className="promo-row">
              <input placeholder="Have a promo code? Try OOM20" value={promoInput} onChange={e => { setPromoInput(e.target.value); setPromoError(null); }} disabled={promoApplied} />
              {promoApplied
                ? <button type="button" className="applied" onClick={removePromo}>Remove</button>
                : <button type="button" onClick={handleApplyPromo} disabled={priceLoading}>Apply</button>}
            </div>
            {promoError && <p className="promo-error">{promoError}</p>}
            {promoApplied && <p className="field-hint">Coupon {promoInput.trim().toUpperCase()} applied{discountPct > 0 ? ` — ${discountPct}% off your service amount` : ""}.</p>}
          </>
        )}

        <button className={`btn btn-primary full ${paying ? "paying" : ""}`} onClick={handlePay} disabled={paying || !!plantLoadError || (!quote && (priceLoading || !priceQuote))}>
          {paying
            ? <><span className="pay-spinner" /> {quote ? "Submitting…" : "Opening Razorpay…"}</>
            : quote ? "Submit Request" : priceLoading ? "Loading price…" : `Pay ${fmt(total)} Securely`}
          {!paying && <ShieldCheck size={17} />}
        </button>
        <p>By confirming, you agree to our service terms and cancellation policy. Payments are processed securely by Razorpay.</p>
      </aside>
    </div>
  </div>
}
