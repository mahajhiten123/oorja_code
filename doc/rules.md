# System & Business Rules — Oorja on Move

## 1. Security & Architecture Invariants

### 1.1 Startup Validation Rules (`SecurityStartupValidator`)
The backend refuses to boot in any environment if security parameters are missing or weak:
- `JWT_SECRET`: Must be $\ge 32$ characters long and must not match common placeholder patterns (`123456`, `secret`, `changeme`).
- `ADMIN_PASSWORD`: Must be $\ge 12$ characters long and must not match dictionary passwords.
- **Fail-Fast Policy:** Misconfigurations crash immediately at startup rather than throwing runtime errors upon first login.

### 1.2 Cross-Origin Resource Sharing (CORS) Invariants
- `Access-Control-Allow-Credentials` is set to `true` across all authenticated routes.
- **Strict Rule:** `CORS_ALLOWED_ORIGINS` must **never** contain the bare wildcard `*`.
- When dynamic subdomains are needed (e.g. Vercel deployment previews), `config.setAllowedOriginPatterns` is used with explicit prefix/domain patterns such as `https://*.vercel.app`.

### 1.3 Tenant & Data Isolation Rules
- Customers can only query, modify, pay, or cancel resources where `customer_id` matches the authenticated JWT principal.
- Cross-tenant ID lookups throw `404 Not Found` (rather than `403 Forbidden`) to prevent resource enumeration.
- Admin endpoints (`/api/admin/*`) strictly require `ROLE_ADMIN` authority and are rejected for customer bearer tokens.

---

## 2. Authentication & Credential Rules

### 2.1 OTP Lifecycle Rules
- **Length & Entropy:** Exactly 6 numeric digits generated via `java.security.SecureRandom`.
- **Storage:** Plaintext OTP is **never** persisted in the database; only salted BCrypt hashes (`BCrypt.hashpw(otp, BCrypt.gensalt(10))`) are stored.
- **Lifespan:** Active OTP tokens expire strictly after 5 minutes (`expiresAt`).
- **Throttling & Rate Limiting:**
  - Max 5 OTP generation requests per identifier within a rolling 15-minute window.
  - Max 5 failed verification attempts per OTP token before permanent lockout.
- **Concurrency Locking:** OTP verification must execute under pessimistic row locks (`SELECT ... FOR UPDATE`) to prevent concurrent race verification.

### 2.2 Token Lifecycle & Rotation Rules
- **Access Tokens:** Signed via HMAC-SHA512 with a strict 15-minute expiration (`900,000 ms`).
- **Refresh Tokens:** High-entropy random UUIDs valid for 30 days.
  - **Single-Use Rotation:** Presenting a valid refresh token consumes it and returns a newly minted access/refresh token pair.
  - **Revocation:** Explicit logout instantly blacklists the access token and marks the refresh token revoked in the database.

---

## 3. Business & Operational Rules

### 3.1 Booking & Scheduling Invariants
- **Slot Hold Expiration:** A slot hold placed during checkout is valid for exactly 10 minutes. If payment is not completed within 10 minutes, the hold is released for other customers.
- **Technician Overbooking:** A technician cannot be assigned to overlapping service windows on the same date.
- **Booking Transitions:** Bookings can only advance forward in the state machine (`DRAFT` $\rightarrow$ `CONFIRMED` $\rightarrow$ `ASSIGNED` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `COMPLETED`). Reverse transitions are prohibited.

### 3.2 Cancellation & Refund Policy Rules
Refund eligibility is governed by time delta relative to scheduled appointment start time:
- **$> 48$ Hours Prior:** 100% refund, 0% penalty.
- **$24$ to $48$ Hours Prior:** 80% refund, 20% logistics deduction.
- **$< 24$ Hours Prior:** 50% refund, 50% late-cancellation fee.
- **Completed or In-Progress Bookings:** Non-refundable through automated channels (requires manual admin dispute resolution).

### 3.3 Quotation Rules
- **Small Installations ($\le 20$ kW):** Standard automated catalog pricing applied at checkout.
- **Commercial Installations ($> 20$ kW):** Requires manual on-site engineering review and an admin-issued quotation.
- **Quotation Validity:** Quotations are valid for 14 calendar days from issue date. Expired quotations cannot be paid without admin re-estimation.

---

## 4. Database & Code Standards

- **Migration Immutability:** Flyway migration scripts (`V1`..`V21`) are immutable once committed. Schema alterations require a new incremental script (`V22+`).
- **Soft Deletion:** Solar plant assets use soft deletion (`status = 'DELETED'`) to preserve booking audit trails and warranty histories.
- **DTO Immutability:** All incoming request payloads and outgoing API responses use Java `record` types or TypeScript `readonly` interfaces.
