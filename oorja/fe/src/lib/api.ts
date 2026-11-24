// Central API client for the Oorja on Move Java/Spring backend.
// Every call here hits a real endpoint — there is no mock/demo data path.
// If the backend is unreachable, callers get a clear ApiError instead of a
// silent fake success.

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || (import.meta.env.PROD ? "https://oorja-backend-kevf.onrender.com" : "http://localhost:8080");

const TOKEN_KEY = "oorja_jwt";
const ADMIN_TOKEN_KEY = "oorja_admin_jwt";
const REFRESH_KEY = "oorja_refresh";
const ADMIN_REFRESH_KEY = "oorja_admin_refresh";

function readStoredToken(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStoredToken(key: string, token: string | null) {
  try {
    if (token) localStorage.setItem(key, token);
    else localStorage.removeItem(key);
  } catch {
    // ignore storage errors (e.g. private browsing quota)
  }
}

export function getToken(): string | null {
  return readStoredToken(TOKEN_KEY);
}

export function setToken(token: string | null) {
  writeStoredToken(TOKEN_KEY, token);
}

export function getAdminToken(): string | null {
  return readStoredToken(ADMIN_TOKEN_KEY);
}

export function setAdminToken(token: string | null) {
  writeStoredToken(ADMIN_TOKEN_KEY, token);
}

export function getRefreshToken(): string | null {
  return readStoredToken(REFRESH_KEY);
}

export function setRefreshToken(token: string | null) {
  writeStoredToken(REFRESH_KEY, token);
}

export function getAdminRefreshToken(): string | null {
  return readStoredToken(ADMIN_REFRESH_KEY);
}

export function setAdminRefreshToken(token: string | null) {
  writeStoredToken(ADMIN_REFRESH_KEY, token);
}

/**
 * Stores both halves of a session after a successful login/refresh. Access
 * tokens are short-lived (~15 min) by design; the refresh token is what keeps
 * the user signed in, and it is rotated on every use.
 */
export function saveSession(auth: { token: string; refreshToken?: string | null }, which: "customer" | "admin" = "customer") {
  if (which === "admin") {
    setAdminToken(auth.token);
    if (auth.refreshToken !== undefined) setAdminRefreshToken(auth.refreshToken ?? null);
  } else {
    setToken(auth.token);
    if (auth.refreshToken !== undefined) setRefreshToken(auth.refreshToken ?? null);
  }
}

export function clearSession(which: "customer" | "admin" = "customer") {
  if (which === "admin") {
    setAdminToken(null);
    setAdminRefreshToken(null);
  } else {
    setToken(null);
    setRefreshToken(null);
  }
}

export class ApiError extends Error {
  status: number;
  fieldErrors?: Record<string, string>;
  constructor(message: string, status: number, fieldErrors?: Record<string, string>) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Attach the stored JWT as a Bearer token. Defaults to true. */
  auth?: boolean;
  /** Which session's token to attach — the customer session or the separate admin session. Defaults to "customer". */
  tokenKey?: "customer" | "admin";
  /** Extra request headers (e.g. Idempotency-Key). */
  headers?: Record<string, string>;
};

/**
 * Idempotency keys for create-style requests (bookings, payment orders).
 *
 * A key identifies one user *intent*: every retry of that intent must send the
 * same key so the server can return the original result instead of creating a
 * duplicate; a genuinely different intent must get a new key. The key is
 * therefore tied to a fingerprint of what is being requested - change the tier,
 * coupon, date etc. and a fresh key is issued, so a stale order/price is never
 * replayed. Call clearIdempotencyKey(scope) once the intent has completed.
 */
const idempotencyKeys = new Map<string, { fingerprint: string; key: string }>();

function randomKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
}

export function getIdempotencyKey(scope: string, fingerprint: string): string {
  const existing = idempotencyKeys.get(scope);
  if (existing && existing.fingerprint === fingerprint) return existing.key;
  const key = randomKey();
  idempotencyKeys.set(scope, { fingerprint, key });
  return key;
}

export function clearIdempotencyKey(scope: string): void {
  idempotencyKeys.delete(scope);
}

const idemHeaders = (key?: string): Record<string, string> | undefined =>
  key ? { "Idempotency-Key": key } : undefined;

/** Fired whenever a customer request comes back 401, so the app can log the customer out globally. */
export const onUnauthorized = { handler: null as null | (() => void) };
/** Fired whenever an admin request comes back 401, so the app can log the admin out globally. */
export const onAdminUnauthorized = { handler: null as null | (() => void) };

const REFRESH_PATHS = {
  customer: "/api/customer/auth/refresh",
  admin: "/api/admin/auth/refresh"
} as const;

/**
 * In-flight refresh per session, so a burst of parallel 401s triggers exactly
 * one refresh call. This matters because refresh tokens are single-use: two
 * concurrent refreshes would make the second look like a replayed (stolen)
 * token to the backend, which revokes every session for that user.
 */
const refreshInFlight: Record<"customer" | "admin", Promise<boolean> | null> = {
  customer: null,
  admin: null
};

async function refreshSession(which: "customer" | "admin"): Promise<boolean> {
  if (refreshInFlight[which]) return refreshInFlight[which]!;

  const run = (async () => {
    const refreshToken = which === "admin" ? getAdminRefreshToken() : getRefreshToken();
    if (!refreshToken) return false;
    try {
      const res = await fetch(`${API_BASE_URL}${REFRESH_PATHS[which]}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken })
      });
      if (!res.ok) return false;
      const data = await res.json();
      if (!data?.token) return false;
      saveSession(data, which);
      return true;
    } catch {
      return false;
    }
  })();

  refreshInFlight[which] = run;
  try {
    return await run;
  } finally {
    refreshInFlight[which] = null;
  }
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return requestOnce<T>(path, options, true);
}

async function requestOnce<T>(path: string, options: RequestOptions = {}, allowRefresh = true): Promise<T> {
  const { method = "GET", body, auth = true, tokenKey = "customer", headers: extraHeaders } = options;
  const isFormData = typeof FormData !== "undefined" && body instanceof FormData;
  const headers: Record<string, string> = isFormData ? {} : { "Content-Type": "application/json" };
  if (extraHeaders) Object.assign(headers, extraHeaders);
  if (auth) {
    const token = tokenKey === "admin" ? getAdminToken() : getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  let res: Response;
  try {
    res = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : isFormData ? (body as FormData) : JSON.stringify(body)
    });
  } catch {
    throw new ApiError(
      `Could not reach the server at ${API_BASE_URL}. Make sure the backend is running and reachable.`,
      0
    );
  }

  const text = await res.text();
  let data: any = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    if (res.status === 401) {
      // Access tokens are short-lived, so a 401 usually just means "expired".
      // Try a single silent refresh and replay the request before giving up
      // and logging the user out.
      if (auth && allowRefresh && path !== REFRESH_PATHS[tokenKey]) {
        const refreshed = await refreshSession(tokenKey);
        if (refreshed) return requestOnce<T>(path, options, false);
      }
      clearSession(tokenKey);
      if (tokenKey === "admin") onAdminUnauthorized.handler?.();
      else onUnauthorized.handler?.();
    }
    const message =
      (data && typeof data === "object" && (data.message || data.error)) ||
      (typeof data === "string" && data) ||
      `Request failed (${res.status})`;
    throw new ApiError(message, res.status, data?.errors);
  }

  if (data && typeof data === "object" && Array.isArray((data as any).content) && ("totalElements" in (data as any) || "totalPages" in (data as any))) {
    return (data as any).content as T;
  }

  return data as T;
}

/** Fetches an admin-protected file (e.g. a booking attachment) with the admin token and opens it in a new tab. */
export async function openAdminFile(path: string): Promise<void> {
  const fetchOnce = async () => {
    const token = getAdminToken();
    try {
      return await fetch(`${API_BASE_URL}${path}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
    } catch {
      throw new ApiError(`Could not reach the server at ${API_BASE_URL}.`, 0);
    }
  };

  let res = await fetchOnce();
  if (res.status === 401 && (await refreshSession("admin"))) {
    res = await fetchOnce();
  }
  if (!res.ok) {
    if (res.status === 401) {
      clearSession("admin");
      onAdminUnauthorized.handler?.();
    }
    throw new ApiError(`Couldn't load this file (${res.status}).`, res.status);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  window.open(url, "_blank", "noopener,noreferrer");
}

/**
 * Fetches a customer-protected file (a booking/service-report attachment)
 * with the customer token. mode "view" opens it in a new tab; "download"
 * forces a save-as with the file's original name.
 */
export async function openCustomerFile(path: string, fileName?: string, mode: "view" | "download" = "view"): Promise<void> {
  const fetchOnce = async () => {
    const token = getToken();
    try {
      return await fetch(`${API_BASE_URL}${path}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
    } catch {
      throw new ApiError(`Could not reach the server at ${API_BASE_URL}.`, 0);
    }
  };

  let res = await fetchOnce();
  if (res.status === 401 && (await refreshSession("customer"))) {
    res = await fetchOnce();
  }
  if (!res.ok) {
    if (res.status === 401) {
      clearSession("customer");
      onUnauthorized.handler?.();
    }
    throw new ApiError(`Couldn't load this file (${res.status}).`, res.status);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  if (mode === "download") {
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName || "download";
    document.body.appendChild(a);
    a.click();
    a.remove();
  } else {
    window.open(url, "_blank", "noopener,noreferrer");
  }
}

export const http = {
  get: <T,>(path: string) => request<T>(path),
  post: <T,>(path: string, body?: unknown, auth = true, headers?: Record<string, string>) =>
    request<T>(path, { method: "POST", body, auth, headers }),
  put: <T,>(path: string, body?: unknown, auth = true) => request<T>(path, { method: "PUT", body, auth }),
  delete: <T,>(path: string, auth = true) => request<T>(path, { method: "DELETE", auth })
};

/** Same as `http`, but attaches the separate admin-session token instead of the customer one. */
export const adminHttp = {
  get: <T,>(path: string) => request<T>(path, { tokenKey: "admin" }),
  post: <T,>(path: string, body?: unknown, auth = true) => request<T>(path, { method: "POST", body, auth, tokenKey: "admin" }),
  put: <T,>(path: string, body?: unknown, auth = true) => request<T>(path, { method: "PUT", body, auth, tokenKey: "admin" })
};

// ---------------------------------------------------------------------------
// Domain types (mirroring the backend's response DTOs exactly)
// ---------------------------------------------------------------------------

export type PlantType = "RESIDENTIAL" | "COMMERCIAL" | "INDUSTRIAL";
export type ApiBookingStatus = "PENDING" | "ASSIGNED" | "ON_SITE" | "COMPLETED" | "CANCELLED";

export type BookingAttachment = {
  id: number;
  fileName: string;
  contentType: string | null;
  sizeBytes: number | null;
  url: string;
  customerUrl: string;
  /** Ops-generated service report / completion document — customers can never delete these. */
  officialDocument: boolean;
  /** Whether the owning customer may delete this file (own upload, booking not completed). */
  customerDeletable: boolean;
};

export type CustomerProfile = {
  id: number;
  customerCode: string;
  name: string;
  phone: string;
  email: string;
  address: string | null;
  status: string;
  plantsCount: number;
  bookingsCount: number;
};

export type Plant = {
  id: number;
  plantCode: string;
  name: string;
  type: PlantType;
  capacityKw: number;
  capacityDisplay: string;
  location: string;
  status: string;
  customerId: number | null;
  customerName: string | null;
  deleted: boolean;
  deletedAt: string | null;
};

export type ApiBooking = {
  id: number;
  token: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  customerEmail: string;
  plantId: number;
  plantName: string;
  plantCapacity: string;
  serviceType: string;
  serviceLabel: string;
  scheduledDate: string; // yyyy-MM-dd
  scheduledTime: string; // HH:mm[:ss]
  status: ApiBookingStatus;
  technicianId: number | null;
  technicianName: string;
  notes: string | null;
  location: string | null;
  amountPaise: number | null;
  paymentRef: string | null;
  createdAt: string;
  attachments: BookingAttachment[];
};

export type AuthResult = {
  token: string;
  tokenType: string;
  /** Access-token lifetime in seconds (short — the refresh token carries the session). */
  expiresIn: number;
  /** Single-use, rotated on every refresh. Store the new one each time. */
  refreshToken: string;
  role: string;
  userId: number;
  name: string;
  email: string;
  phone?: string | null;
  address?: string | null;
  /** True when phone/address are missing (typical for a fresh Google sign-up) and we should collect them. */
  needsProfile?: boolean;
};

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export const AuthApi = {
  sendLoginOtp: (email: string) =>
    http.post<{ success: boolean; message: string }>("/api/customer/auth/login/send-otp", { email }, false),

  verifyLoginOtp: (email: string, otp: string) =>
    http.post<AuthResult>("/api/customer/auth/login/verify-otp", { email, otp }, false),

  sendRegisterOtp: (data: { name: string; email: string; phone: string; address?: string }) =>
    http.post<{ success: boolean; message: string }>("/api/customer/auth/register/send-otp", data, false),

  verifyRegisterOtp: (data: { name: string; email: string; phone: string; address?: string; otp: string }) =>
    http.post<AuthResult>("/api/customer/auth/register/verify-otp", data, false),

  /**
   * Exchanges a Google ID token (from Google Identity Services in the browser)
   * for an Oorja session. The backend re-verifies the token with Google, so a
   * forged one is rejected server-side.
   */
  google: (idToken: string) =>
    http.post<AuthResult>("/api/customer/auth/google", { idToken }, false),

  /** Exchanges the stored refresh token for a fresh pair. Normally handled automatically on 401. */
  refresh: (refreshToken: string) =>
    http.post<AuthResult>("/api/customer/auth/refresh", { refreshToken }, false),

  /** Revokes the refresh token server-side and blacklists the current access token, then clears local storage. */
  logout: async () => {
    const refreshToken = getRefreshToken();
    try {
      await http.post<{ success: boolean; message: string }>("/api/customer/auth/logout", { refreshToken });
    } catch {
      // Even if the server call fails, drop the local session.
    }
    clearSession("customer");
  }
};

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

export const ProfileApi = {
  me: () => http.get<CustomerProfile>("/api/customer/me"),
  update: (data: { name: string; phone: string; address?: string }) =>
    http.put<CustomerProfile>("/api/customer/me", data)
};

// ---------------------------------------------------------------------------
// Plants
// ---------------------------------------------------------------------------

export const PlantsApi = {
  mine: (includeArchived = false) => http.get<Plant[]>(`/api/customer/plants${includeArchived ? "?includeArchived=true" : ""}`),
  add: (data: { name: string; type: PlantType; capacityKw: number; location: string }) =>
    http.post<Plant>("/api/customer/plants", data),
  update: (id: number, data: { name: string; type: PlantType; capacityKw: number; location: string }) =>
    http.put<Plant>(`/api/customer/plants/${id}`, data),
  remove: (id: number) => http.delete<{ success: boolean; message: string }>(`/api/customer/plants/${id}`)
};

// ---------------------------------------------------------------------------
// Service catalog
// ---------------------------------------------------------------------------

export const ServiceCatalogApi = {
  list: () => http.get<string[]>("/api/customer/services")
};

// ---------------------------------------------------------------------------
// Bookings
// ---------------------------------------------------------------------------

export type SlotAvailability = {
  time: string; // "HH:mm:ss"
  label: string; // e.g. "9 AM – 11 AM", formatted server-side
  capacity: number;
  booked: number;
  remaining: number;
  full: boolean;
  tooSoon: boolean;
  available: boolean;
};

export const BookingsApi = {
  mine: () => http.get<ApiBooking[]>("/api/customer/bookings"),
  /** The server-authoritative slot grid for a date, with live remaining capacity per slot. */
  slots: (date: string) => http.get<SlotAvailability[]>(`/api/customer/bookings/slots?date=${date}`),
  create: (data: {
    plantId: number;
    serviceType: string;
    scheduledDate: string;
    scheduledTime: string;
    notes?: string;
    /** Razorpay order id from a completed, verified payment — the backend copies the amount/payment ref from that CAPTURED payment. Omit for quote requests. */
    razorpayOrderId?: string;
    /** True for "request a quote" submissions - skips payment, creates a draft quotation instead. */
    requestQuote?: boolean;
  }, idempotencyKey?: string) => http.post<ApiBooking>("/api/customer/bookings", data, true, idemHeaders(idempotencyKey)),
  /** Same as create, but also uploads site photos/documents alongside the booking (e.g. installation quote requests). */
  createWithFiles: (
    data: { plantId?: number | null; serviceType: string; scheduledDate: string; scheduledTime: string; notes?: string; location?: string; razorpayOrderId?: string; requestQuote?: boolean },
    files: File[],
    idempotencyKey?: string
  ) => {
    const form = new FormData();
    form.append("data", new Blob([JSON.stringify(data)], { type: "application/json" }));
    files.forEach(f => form.append("files", f));
    return http.post<ApiBooking>("/api/customer/bookings", form, true, idemHeaders(idempotencyKey));
  },
  /** Adds site photos/documents to a booking the customer already has — the "+ Upload File" action inside booking details. */
  addAttachments: (id: number, files: File[]) => {
    const form = new FormData();
    files.forEach(f => form.append("files", f));
    return http.post<ApiBooking>(`/api/customer/bookings/${id}/attachments`, form);
  },
  /** Deletes a file the customer uploaded. Soft delete server-side; allowed only while the booking is open. */
  deleteAttachment: (attachmentId: number) =>
    http.delete<{ success: boolean; message: string }>(`/api/customer/bookings/attachments/${attachmentId}`),
  // Cancelling now goes through RefundsApi.cancel / RefundsApi.rebookInstead
  // in ./refunds.ts — the endpoint's response shape changed to
  // CancellationOutcome ({ booking, refundId, rebooked }), so the old
  // { success, message } signature here would silently be wrong. Removed
  // rather than left as a landmine for a future caller.
  reschedule: (id: number, data: { scheduledDate: string; scheduledTime: string }) =>
    http.post<ApiBooking>(`/api/customer/bookings/${id}/reschedule`, data),
  /** Backend-authoritative price for a service (+ optional cleaning tier / coupon) — never computed in the browser. */
  price: (data: { serviceId: string; tierId?: string; couponCode?: string; quoteRequest?: boolean }) =>
    http.post<PriceQuoteResponse>("/api/customer/bookings/price", data)
};

// ---------------------------------------------------------------------------
// Payments (Razorpay)
// ---------------------------------------------------------------------------

export type CreateOrderResponse = { orderId: string; amount: number; currency: string; keyId: string };
export type VerifyPaymentResponse = { verified: boolean; message: string };
export type PriceQuoteResponse = {
  subtotal: number;
  discount: number;
  tax: number;
  total: number;
  currency: string;
  couponApplied: boolean;
  couponError: string | null;
};

export const PaymentsApi = {
  /** Describes *what* is being bought (service/tier/coupon) — the backend looks up the price, never the amount directly. */
  createOrder: (data: {
    serviceId: string;
    tierId?: string;
    couponCode?: string;
    receipt: string;
    draftPlantId?: number;
    draftScheduledDate?: string;
    draftScheduledTime?: string;
    draftServiceType?: string;
    draftNotes?: string;
    draftLocation?: string;
  }, idempotencyKey?: string) =>
    http.post<CreateOrderResponse>("/api/customer/payments/create-order", data, true, idemHeaders(idempotencyKey)),
  /** Same idea, but for paying an already-priced Quotation (AMC/installation) — the amount comes from the quotation, not the client. */
  createOrderForQuotation: (data: { quotationId: number; receipt: string }, idempotencyKey?: string) =>
    http.post<CreateOrderResponse>("/api/customer/payments/create-order-for-quotation", data, true, idemHeaders(idempotencyKey)),
  verify: (payment: { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string }) =>
    http.post<VerifyPaymentResponse>("/api/customer/payments/verify", payment)
};

// ---------------------------------------------------------------------------
// Admin — auth
// ---------------------------------------------------------------------------
// The admin console is a separate session from the customer session (its own
// token, its own login). The backend exposes a shared-password login endpoint
// whose password is supplied only via the ADMIN_PASSWORD environment variable
// (there is no default — the server refuses to start without a strong one),
// plus an email+OTP login for individual admin accounts.

export type AdminAuthResult = {
  token: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  role: "ADMIN" | "SUPER_ADMIN";
  name: string;
};

export const AdminAuthApi = {
  login: (password: string) =>
    http.post<AdminAuthResult>("/api/admin/auth/login", { password }, false),

  /** Sends a one-time code to the admin's registered email address. */
  sendOtp: (email: string) =>
    http.post<{ success: boolean; message: string }>("/api/admin/auth/send-otp", { email }, false),

  verifyOtp: (email: string, otp: string) =>
    http.post<AdminAuthResult>("/api/admin/auth/verify-otp", { email, otp }, false),

  refresh: (refreshToken: string) =>
    http.post<AdminAuthResult>("/api/admin/auth/refresh", { refreshToken }, false),

  logout: async () => {
    const refreshToken = getAdminRefreshToken();
    try {
      await request<{ success: boolean; message: string }>("/api/admin/auth/logout", {
        method: "POST",
        body: { refreshToken },
        tokenKey: "admin"
      });
    } catch {
      // Even if the server call fails, drop the local session.
    }
    clearSession("admin");
  }
};

// ---------------------------------------------------------------------------
// Admin — members (registered users, their sites/plants, and their bookings
// split into upcoming / completed / cancelled). AMC subscription management
// will be wired up once real AMC plan data is provided.
// ---------------------------------------------------------------------------

export type MemberBookings = {
  upcomingCount: number;
  completedCount: number;
  cancelledCount: number;
  upcoming: ApiBooking[];
  completed: ApiBooking[];
  cancelled: ApiBooking[];
};

export type AdminMember = {
  id: number;
  customerCode: string;
  name: string;
  phone: string;
  email: string;
  address: string | null;
  status: string;
  sites: Plant[];
  bookings: MemberBookings;
};

export const AdminMembersApi = {
  list: () => adminHttp.get<AdminMember[]>("/api/admin/members"),
  one: (id: number) => adminHttp.get<AdminMember>(`/api/admin/members/${id}`)
};

// ---------------------------------------------------------------------------
// Admin — bookings (upcoming / completed / cancelled, technician assignment,
// marking a job done on-site so it flips to "Completed" for the customer)
// ---------------------------------------------------------------------------

export const AdminBookingsApi = {
  list: () => adminHttp.get<ApiBooking[]>("/api/admin/bookings"),
  assignTechnician: (id: number, technicianId: number) =>
    adminHttp.post<ApiBooking>(`/api/admin/bookings/${id}/assign-technician`, { technicianId }),
  complete: (id: number, notes?: string) =>
    adminHttp.post<ApiBooking>(`/api/admin/bookings/${id}/complete`, { notes }),
  cancel: (id: number, reason?: string) =>
    adminHttp.post<ApiBooking>(`/api/admin/bookings/${id}/cancel`, { reason }),
  /** Admins can delete any attachment. Soft delete server-side, with the reason kept on the audit record. */
  deleteAttachment: (attachmentId: number, reason?: string) =>
    request<{ success: boolean; message: string }>(`/api/admin/bookings/attachments/${attachmentId}`, {
      method: "DELETE",
      body: reason ? { reason } : undefined,
      tokenKey: "admin"
    })
};

// ---------------------------------------------------------------------------
// Admin — payments (read-only ledger: every Razorpay order ever created,
// whether or not it was ever paid, plus its full transaction audit trail)
// ---------------------------------------------------------------------------

export type PaymentStatus = "CREATED" | "PENDING" | "CAPTURED" | "FAILED" | "CANCELLED" | "REFUNDED" | "PARTIALLY_REFUNDED";

export type PaymentTransaction = {
  id: number;
  type: string;
  amountInr: number;
  status: string;
  provider: string;
  providerTransactionId: string | null;
  metadata: string | null;
  createdAt: string;
};

export type AdminPayment = {
  id: number;
  paymentNumber: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  bookingId: number | null;
  bookingToken: string | null;
  amountInr: number;
  currency: string;
  status: PaymentStatus;
  serviceId: string | null;
  tierId: string | null;
  quotationId: number | null;
  couponCode: string | null;
  razorpayOrderId: string;
  razorpayPaymentId: string | null;
  createdAt: string;
  capturedAt: string | null;
  failedAt: string | null;
  transactions: PaymentTransaction[];
};

export const AdminPaymentsApi = {
  list: () => adminHttp.get<AdminPayment[]>("/api/admin/payments"),
  get: (id: number) => adminHttp.get<AdminPayment>(`/api/admin/payments/${id}`),
  byBooking: (bookingId: number) => adminHttp.get<AdminPayment>(`/api/admin/payments/by-booking/${bookingId}`)
};

// ---------------------------------------------------------------------------
// Admin — installation quotations
// ---------------------------------------------------------------------------

export type QuotationStatus = "DRAFT" | "PENDING" | "SENT" | "VIEWED" | "ACCEPTED" | "REJECTED" | "EXPIRED";

export type Quotation = {
  id: number;
  quotationNumber?: string;
  bookingId?: number;
  bookingToken?: string;
  customerId: number;
  customerName: string;
  customerPhone: string;
  plantType: PlantType;
  capacityKw: number;
  siteAddress: string;
  serviceType?: string;
  requestedAt: string; // ISO datetime
  status: QuotationStatus;
  subtotal?: number | null;
  discountAmount?: number | null;
  taxAmount?: number | null;
  amountInr: number | null;
  validUntil: string | null; // yyyy-MM-dd
  notes: string | null;
  paid?: boolean;
  /** Which version these fields reflect — shown to the customer as "Revision N". */
  currentVersionNumber?: number;
  attachments?: BookingAttachment[];
};

// ---------------------------------------------------------------------------
// Quotation versioning (Phase 7) — every price change is a new immutable
// version; the fields above on Quotation always mirror whichever version is
// current. Once a version is sent it can never be edited, only superseded.
// ---------------------------------------------------------------------------

export type QuotationVersionStatus = "DRAFT" | "SENT" | "VIEWED" | "ACCEPTED" | "REJECTED" | "EXPIRED" | "SUPERSEDED";

export type QuotationVersion = {
  id: number;
  versionNumber: number;
  subtotal: number | null;
  discountAmount: number | null;
  taxAmount: number | null;
  amountInr: number | null;
  notes: string | null;
  validUntil: string | null;
  status: QuotationVersionStatus;
  /** True if this is the quotation's current (live) version. */
  current: boolean;
  paid: boolean;
  createdByType: "ADMIN" | "CUSTOMER" | null;
  sentAt: string | null;
  viewedAt: string | null;
  respondedAt: string | null;
  createdAt: string;
};

export type QuotationVersionFieldChange = { field: string; label: string; from: string | null; to: string | null };
export type QuotationVersionDiff = { fromVersion: number; toVersion: number; changes: QuotationVersionFieldChange[] };

export type RevisionReason = "PRICE" | "SERVICE" | "QUANTITY" | "AMC" | "OTHER";
export type RevisionRequestStatus = "OPEN" | "IN_REVIEW" | "RESOLVED" | "REJECTED";

export type QuotationRevisionRequest = {
  id: number;
  quotationId: number;
  quotationVersionNumber: number;
  reason: RevisionReason;
  message: string;
  status: RevisionRequestStatus;
  resolutionNote: string | null;
  createdAt: string;
  resolvedAt: string | null;
};

/** Fields shared by "Save Draft" and "Send to Customer" — the pricing form itself. */
export type QuotationPricingInput = {
  amountInr: number;
  validUntil: string;
  notes?: string;
  subtotal?: number;
  taxAmount?: number;
  discountAmount?: number;
};

export const AdminQuotationsApi = {
  list: () => adminHttp.get<Quotation[]>("/api/admin/quotations"),
  get: (id: number) => adminHttp.get<Quotation>(`/api/admin/quotations/${id}`),
  /** Saves the current editable draft version. Doesn't affect what the customer sees. */
  saveDraft: (id: number, data: QuotationPricingInput) =>
    adminHttp.put<Quotation>(`/api/admin/quotations/${id}/draft`, data),
  /** Prices (or re-prices, creating a new version) and emails it to the customer. */
  send: (id: number, data: QuotationPricingInput & { revisionRequestId?: number }) =>
    adminHttp.post<Quotation>(`/api/admin/quotations/${id}/send`, data),
  updateStatus: (id: number, status: Extract<QuotationStatus, "ACCEPTED" | "REJECTED">) =>
    adminHttp.post<Quotation>(`/api/admin/quotations/${id}/status`, { status }),
  /** Full revision history, including the in-progress draft if there is one. */
  versions: (id: number) => adminHttp.get<QuotationVersion[]>(`/api/admin/quotations/${id}/versions`),
  diffVersions: (id: number, from: number, to: number) =>
    adminHttp.get<QuotationVersionDiff>(`/api/admin/quotations/${id}/versions/${from}/diff/${to}`),
  revisionRequests: (id: number) => adminHttp.get<QuotationRevisionRequest[]>(`/api/admin/quotations/${id}/revision-requests`),
  startReview: (requestId: number) =>
    adminHttp.post<QuotationRevisionRequest>(`/api/admin/quotations/revision-requests/${requestId}/start-review`),
  resolveRevisionRequest: (requestId: number, note?: string) =>
    adminHttp.post<QuotationRevisionRequest>(`/api/admin/quotations/revision-requests/${requestId}/resolve`, note ? { note } : undefined),
  rejectRevisionRequest: (requestId: number, note?: string) =>
    adminHttp.post<QuotationRevisionRequest>(`/api/admin/quotations/revision-requests/${requestId}/reject`, note ? { note } : undefined)
};

// ---------------------------------------------------------------------------
// Customer — my quotations (view, accept/reject, pay, request a revision)
// ---------------------------------------------------------------------------

export const QuotationsApi = {
  mine: () => http.get<Quotation[]>("/api/customer/quotations"),
  get: (id: number) => http.get<Quotation>(`/api/customer/quotations/${id}`),
  accept: (id: number) => http.post<Quotation>(`/api/customer/quotations/${id}/accept`),
  reject: (id: number) => http.post<Quotation>(`/api/customer/quotations/${id}/reject`),
  recordPayment: (id: number, razorpayOrderId: string) =>
    http.post<Quotation>(`/api/customer/quotations/${id}/record-payment`, { razorpayOrderId }),
  /** Revision history for this quotation. Never includes an in-progress admin draft. */
  versions: (id: number) => http.get<QuotationVersion[]>(`/api/customer/quotations/${id}/versions`),
  diffVersions: (id: number, from: number, to: number) =>
    http.get<QuotationVersionDiff>(`/api/customer/quotations/${id}/versions/${from}/diff/${to}`),
  /** "Request Revision" — flags the sent quote for the admin; doesn't change anything itself. */
  requestRevision: (id: number, reason: RevisionReason, message: string) =>
    http.post<QuotationRevisionRequest>(`/api/customer/quotations/${id}/revision-requests`, { reason, message }),
  myRevisionRequests: (id: number) => http.get<QuotationRevisionRequest[]>(`/api/customer/quotations/${id}/revision-requests`)
};

// ---------------------------------------------------------------------------
// Admin — technicians
// ---------------------------------------------------------------------------

export type TechnicianStatus = "AVAILABLE" | "ON_JOB" | "OFF_DUTY";

export type Technician = {
  id: number;
  name: string;
  phone: string;
  email: string;
  specialization: string;
  activeBookings: number;
  status: TechnicianStatus;
};

export const AdminTechniciansApi = {
  list: () => adminHttp.get<Technician[]>("/api/admin/technicians"),
  create: (data: { name: string; phone: string; email: string; specialization: string }) =>
    adminHttp.post<Technician>("/api/admin/technicians", data),
  update: (id: number, data: { name: string; phone: string; email: string; specialization: string }) =>
    adminHttp.put<Technician>(`/api/admin/technicians/${id}`, data),
  setStatus: (id: number, status: TechnicianStatus) =>
    adminHttp.post<Technician>(`/api/admin/technicians/${id}/status`, { status })
};

// ---------------------------------------------------------------------------
// Refunds have moved to ./refunds.ts (RefundsApi / AdminRefundsApi / Refund /
// RefundStatus) — the refund engine's richer state machine, breakdown and
// audit trail replaced the old PENDING/INITIATED/COMPLETED/REJECTED model.
// Import refund-related types and endpoints from "./refunds", not here.
// ---------------------------------------------------------------------------
// AMC (Annual Maintenance Contract) — "Coming soon": register interest
// against a plant, which is priced and turned into a real quotation through
// the same Admin → Quotations pipeline as an installation quote.
// ---------------------------------------------------------------------------

export const AmcApi = {
  requestQuote: (data: { plantId: number; planLabel: string; notes?: string }) =>
    http.post<ApiBooking>("/api/customer/amc/request-quote", data)
};
