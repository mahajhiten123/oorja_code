// Thin wrapper around Razorpay's Standard Checkout, talking to the real
// backend the whole way through: the order is created server-side (so the
// amount can't be tampered with client-side) and the payment signature is
// verified server-side too. There is no demo/offline fallback here — if the
// backend or Razorpay can't be reached, the caller sees a real error.

import { PaymentsApi, getIdempotencyKey, clearIdempotencyKey, type CreateOrderResponse } from "./api";

declare global {
  interface Window {
    Razorpay: any;
  }
}

export function isRazorpayLoaded() {
  return typeof window !== "undefined" && !!window.Razorpay;
}

/**
 * Asks the backend to price and open a real Razorpay order for the given
 * purchase. Only describes *what* is being bought — the backend decides
 * how much it costs (see PricingService), so the amount can never be
 * tampered with client-side.
 * <p>
 * The optional draft* fields (plant/date/time/notes) let the backend
 * auto-create the booking via webhook if the customer's browser dies right
 * after paying and never reaches the "create booking" step — see the
 * Payment entity's draft columns on the backend.
 */
export async function createRazorpayOrder(
  purchase: {
    serviceId: string;
    tierId?: string;
    couponCode?: string;
    draftPlantId?: number;
    draftScheduledDate?: string;
    draftScheduledTime?: string;
    draftServiceType?: string;
    draftNotes?: string;
    draftLocation?: string;
  },
  receipt: string
): Promise<CreateOrderResponse> {
  // One key per distinct purchase: a retry (double-click, timeout) reuses the same Razorpay order,
  // while changing tier/coupon/slot yields a new key and therefore a freshly priced order.
  // `receipt` is deliberately excluded from the fingerprint - it varies per call.
  const key = getIdempotencyKey("order", JSON.stringify(purchase));
  return PaymentsApi.createOrder({ ...purchase, receipt }, key);
}

/**
 * Same idea, but for paying an already-priced Quotation (AMC/installation)
 * instead of a catalog service — the backend looks up the quotation's
 * admin-set total itself rather than trusting an amount from the client.
 */
export async function createRazorpayOrderForQuotation(quotationId: number, receipt: string): Promise<CreateOrderResponse> {
  const key = getIdempotencyKey("quotation-order", String(quotationId));
  return PaymentsApi.createOrderForQuotation({ quotationId, receipt }, key);
}

export type RazorpayPaymentSuccess = {
  razorpay_payment_id: string;
  razorpay_order_id: string;
  razorpay_signature: string;
};

type OpenCheckoutArgs = {
  order: CreateOrderResponse;
  description: string;
  customerName: string;
  customerEmail?: string;
  customerPhone?: string;
  onSuccess: (payment: RazorpayPaymentSuccess) => void;
  onFailure: (error: unknown) => void;
  onDismiss?: () => void;
};

export function openRazorpayCheckout({
  order, description, customerName, customerEmail, customerPhone, onSuccess, onFailure, onDismiss
}: OpenCheckoutArgs) {
  if (!isRazorpayLoaded()) {
    onFailure(new Error("Razorpay checkout script did not load. Check your internet connection and try again."));
    return;
  }

  const options = {
    // Always use the key the backend just issued the order under — never a
    // separately-configured client-side key, so they can never drift apart.
    key: order.keyId,
    amount: order.amount,
    currency: order.currency,
    name: "Oorja on Move",
    description,
    order_id: order.orderId,
    prefill: {
      name: customerName,
      email: customerEmail,
      contact: customerPhone
    },
    theme: { color: "#087a4d" },
    handler: (response: RazorpayPaymentSuccess) => onSuccess(response),
    modal: {
      ondismiss: () => onDismiss?.()
    }
  };

  const rzp = new window.Razorpay(options);
  rzp.on("payment.failed", (resp: any) => onFailure(resp?.error ?? new Error("Payment failed")));
  rzp.open();
}

/** Verifies the payment signature with the backend after a successful charge — required before treating it as real. */
export async function verifyRazorpayPayment(payment: RazorpayPaymentSuccess): Promise<boolean> {
  const result = await PaymentsApi.verify(payment);
  // Quotation payments have no follow-up booking step, so their intent is complete here.
  // (The catalog-order key is cleared by Checkout only once the booking exists, so a failed
  // booking step can never lead to a second, duplicate charge for the same purchase.)
  if (result.verified) clearIdempotencyKey("quotation-order");
  return result.verified;
}
