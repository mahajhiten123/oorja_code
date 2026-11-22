import { http, adminHttp, type ApiBooking } from "./api";

/**
 * Refund engine — client types and endpoints.
 *
 * Deliberately free of arithmetic. Every rupee figure here was computed by
 * the backend's policy engine and arrives ready to render; the app never
 * derives a refund amount, a percentage or a deduction of its own. If the
 * UI ever needs a new number, it gets added to the response, not calculated
 * in TypeScript — one source of truth for money.
 */

export type RefundStatus =
  | "REQUESTED"
  | "UNDER_REVIEW"
  | "APPROVED"
  | "PROCESSING"
  | "COMPLETED"
  | "REJECTED"
  | "FAILED"
  | "CANCELLED";

export type RefundReasonCode =
  | "CUSTOMER_REQUEST"
  | "RESCHEDULE_UNAVAILABLE"
  | "CUSTOMER_NO_SHOW"
  | "DUPLICATE_PAYMENT"
  | "PROVIDER_CANCELLED"
  | "SERVICE_FAILURE"
  | "PAYMENT_ERROR"
  | "GOODWILL";

export type RefundEvent = {
  id: number;
  type: string;
  status: RefundStatus | null;
  actorType: "CUSTOMER" | "ADMIN" | "SYSTEM" | "PROVIDER";
  actorLabel: string | null;
  message: string | null;
  amountInr: number | null;
  at: string;
};

export type Refund = {
  id: number;
  refundNumber: string;
  bookingId: number;
  bookingToken: string;
  serviceLabel: string | null;
  customerId: number;
  customerName: string;
  customerPhone: string | null;

  grossInr: number;
  refundPercentBps: number;
  cancellationFeeInr: number;
  gatewayFeeInr: number;
  deductionsInr: number;
  amountInr: number;

  reasonCode: RefundReasonCode;
  reason: string | null;
  policyTier: string | null;
  eligibilityNote: string | null;
  requestedByType: "CUSTOMER" | "ADMIN";

  status: RefundStatus;
  statusLabel: string;
  open: boolean;
  autoApproved: boolean;
  approvedBy: string | null;
  adminNote: string | null;
  lastError: string | null;
  attemptCount: number | null;

  razorpayPaymentId: string | null;
  razorpayRefundId: string | null;
  gatewayStatus: string | null;

  requestedAt: string;
  approvedAt: string | null;
  initiatedAt: string | null;
  completedAt: string | null;
  rejectedAt: string | null;
  expectedSettlementAt: string | null;
  reconciledAt: string | null;
  settlementDays: number;

  events: RefundEvent[];
};

export type SlotSuggestion = {
  date: string;
  time: string;
  label: string;
  seatsLeft: number;
};

/** Everything the cancel sheet needs, computed server-side before anything happens. */
export type CancellationPreview = {
  bookingId: number;
  bookingToken: string;
  serviceLabel: string | null;
  scheduledDate: string;
  scheduledTime: string;

  refundable: boolean;
  policyTier: string;
  message: string;

  paidInr: number;
  refundPercent: number;
  cancellationFeeInr: number;
  gatewayFeeInr: number;
  deductionsInr: number;
  refundAmountInr: number;

  autoApproved: boolean;
  settlementDays: number;

  rebookAvailable: boolean;
  rebookRecommended: boolean;
  rebookValueInr: number;
  rebookMessage: string | null;
  suggestedSlots: SlotSuggestion[];
};

export type CancellationOutcome = {
  booking: ApiBooking;
  refundId: number | null;
  rebooked: boolean;
};

export type ReconciliationReport = {
  generatedAt: string;
  paymentsChecked: number;
  totalCapturedInr: number;
  totalRefundedInr: number;
  netHeldInr: number;
  pendingApproval: number;
  inFlight: number;
  needingAttention: number;
  mismatchCount: number;
  mismatches: {
    paymentNumber: string;
    razorpayPaymentId: string | null;
    capturedInr: number;
    ledgerRefundedInr: number;
    refundRecordsInr: number;
    detail: string;
  }[];
};

export const RefundsApi = {
  /** What happens if I cancel? Nothing is changed by calling this. */
  preview: (bookingId: number, reasonCode?: RefundReasonCode) =>
    http.get<CancellationPreview>(
      `/api/customer/bookings/${bookingId}/cancellation-preview${reasonCode ? `?reasonCode=${reasonCode}` : ""}`
    ),

  /** Cancel and take the refund. */
  cancel: (bookingId: number, body: { reason?: string; reasonCode?: RefundReasonCode }) =>
    http.post<CancellationOutcome>(`/api/customer/bookings/${bookingId}/cancel`, {
      ...body,
      intent: "REFUND"
    }),

  /** Move the booking instead — no refund, no deduction, payment stays put. */
  rebookInstead: (bookingId: number, newDate: string, newTime: string, reason?: string) =>
    http.post<CancellationOutcome>(`/api/customer/bookings/${bookingId}/cancel`, {
      reason,
      intent: "REBOOK",
      newDate,
      newTime
    }),

  mine: () => http.get<Refund[]>("/api/customer/refunds"),

  one: (id: number) => http.get<Refund>(`/api/customer/refunds/${id}`),

  /** Withdraw a pending refund and revive the booking at a new slot. */
  rebookFromRefund: (refundId: number, scheduledDate: string, scheduledTime: string) =>
    http.post<ApiBooking>(`/api/customer/refunds/${refundId}/rebook`, { scheduledDate, scheduledTime })
};

export const AdminRefundsApi = {
  list: () => adminHttp.get<Refund[]>("/api/admin/refunds"),
  queue: () => adminHttp.get<Refund[]>("/api/admin/refunds/queue"),
  startReview: (id: number) => adminHttp.post<Refund>(`/api/admin/refunds/${id}/review`),
  approve: (id: number, body?: { amountInr?: number; note?: string }) =>
    adminHttp.post<Refund>(`/api/admin/refunds/${id}/approve`, body ?? {}),
  reject: (id: number, reason?: string) =>
    adminHttp.post<Refund>(`/api/admin/refunds/${id}/reject`, { reason }),
  retry: (id: number) => adminHttp.post<Refund>(`/api/admin/refunds/${id}/retry`),
  create: (body: { bookingId: number; reasonCode: RefundReasonCode; reason?: string; amountInr?: number }) =>
    adminHttp.post<Refund>("/api/admin/refunds", body),
  reconciliation: () => adminHttp.get<ReconciliationReport>("/api/admin/refunds/reconciliation")
};

/** Badge colour classes, shared by the customer app and the console. */
export const REFUND_STATUS_CLASS: Record<RefundStatus, string> = {
  REQUESTED: "pending",
  UNDER_REVIEW: "pending",
  APPROVED: "assigned",
  PROCESSING: "assigned",
  COMPLETED: "completed",
  REJECTED: "cancelled",
  FAILED: "cancelled",
  CANCELLED: "cancelled"
};

export function inr(value: number) {
  return `₹${value.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

export function formatSlot(date: string, time: string) {
  const d = new Date(`${date}T00:00:00`);
  const [h, m] = time.split(":").map(Number);
  const period = h >= 12 ? "PM" : "AM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${d.toLocaleDateString("en-IN", { weekday: "short", day: "2-digit", month: "short" })}, ${h12}:${String(m).padStart(2, "0")} ${period}`;
}
