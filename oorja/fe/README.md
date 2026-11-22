# Oorja on Move — React + TypeScript Customer Web App

A production-style responsive frontend based on the supplied Oorja on Move customer-app scope and visual references.

## Included
- Home / landing page
- Top navigation: Home, Services, Contact Us
- Mobile bottom navigation: Home, Services, Bookings, Help, Profile
- Three core services:
  - Installation & Site Improvement
  - Plant Maintenance & Repair
  - Solar Plant Cleaning
- Service detail pages with service-specific workflows
- OTP login/sign-in UI
- Customer profile setup/editing UI
- Bookings / service history
- Checkout flow
- UPI / card payment selection UI
- Quote-request flow for installation
- PV monitoring insight section that recommends maintenance/cleaning
- Responsive desktop/tablet/mobile layout
- Design language inspired by the supplied references: clean white cards, green solar palette, rounded UI, Zepto-like checkout/login simplicity
- **Admin console** (`/admin`) — separate password-gated portal for staff to manage members' AMC plans, bookings, quotations, technicians and refunds (see below)

## Run

```bash
npm install
npm run dev
```

Open the Vite URL shown in the terminal.

## Production integrations still required

The current implementation is frontend-ready but uses mock/local state for:
1. OTP delivery and verification
2. Customer authentication/session
3. Razorpay/other payment gateway
4. Booking persistence
5. File uploads
6. Technician assignment
7. PV monitoring API
8. Real service availability
9. Customer/profile database

For a real deployment, connect these flows to your backend APIs. Never put payment secrets or OTP-provider credentials in React.

## Recommended backend contract

POST /api/auth/send-otp
POST /api/auth/verify-otp
GET  /api/services
POST /api/quotes
POST /api/bookings
POST /api/bookings/:id/payment-order
POST /api/payments/verify
GET  /api/bookings
GET  /api/profile
PATCH /api/profile
POST /api/uploads
GET  /api/plants
GET  /api/plants/:id/monitoring

## Auth gating

Login is now real (session-based). `/bookings`, `/profile` and `/checkout` are wrapped in `<ProtectedRoute>` — visiting them while signed out redirects to `/login` and returns you to the page you wanted after OTP verification. Auth state lives in `AuthContext` (`src/auth/`) and persists in `sessionStorage` (clears when the tab closes) so a "Login / Sign Up" button shows in the top bar until you're signed in, after which it becomes an account dropdown (Profile / Bookings / Help / Logout).

Note: OTP verification currently just marks you as logged in locally — wire `Login.tsx`'s `handleVerify` to the Java backend's `/api/customer/auth/*` endpoints for real OTP delivery and JWTs.

## Razorpay payments

Checkout now opens real Razorpay Standard Checkout instead of instantly "succeeding":

1. Copy `.env.example` to `.env.local` and set `VITE_RAZORPAY_KEY_ID` (from your Razorpay dashboard, test key is fine for dev) and `VITE_API_BASE_URL` (your running Java backend).
2. `index.html` loads `https://checkout.razorpay.com/v1/checkout.js`.
3. `src/lib/razorpay.ts` creates an order via the backend, opens the Razorpay modal, and verifies the payment signature via the backend after checkout completes.
4. If the backend isn't reachable, it falls back to a local demo order so you can still preview the UI — but no real charge/verification happens in that mode.

See the backend README's "Razorpay payments" section for the matching server-side setup (`RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET`).

## Admin console

A separate, password-gated admin portal lives at `/admin`. It shares the same visual language as the customer app but runs its **own session** (own token, own login) — signing in or out of one side never affects the other.

**Getting in:** open the existing login popup (top-right "Login / Sign Up", or any protected page) and pick the **Admin** tab instead of Customer, then enter the admin password. There's also a direct page at `/admin/login` for bookmarking. Either path lands on the same `AdminAuthContext` session.

**Default admin password: `123456`.** This is validated server-side (see contract below), not hardcoded in the frontend — so you can change it by changing what the backend accepts, without touching any React code. For production, replace the shared-password login with per-admin accounts and stronger auth as soon as you have more than one admin user.

### What's in the admin console
- **Overview** — quick counts (active AMC members, upcoming bookings, quotations awaiting reply, refunds to action) and a recent-bookings feed.
- **Members & AMC** — every existing customer, searchable, with their AMC subscription plan (or lack of one). Assign a plan, change it, or cancel it.
- **Bookings** — Upcoming / Completed / Cancelled tabs. Assign a technician, cancel with a reason, and **mark a job as serviced on-site** — this immediately flips the booking to "Completed" in the customer's own Bookings tab.
- **Quotations** — installation quote requests from customers; send a priced quote (amount + validity + notes), or mark one accepted/rejected.
- **Technicians** — roster with contact info, specialization, live status (Available / On a job / Off duty) and active booking count; add or edit technicians.
- **Refunds** — refund requests tied to bookings/payments; initiate or reject each one.

### Admin backend contract

The admin UI is wired to real endpoints exactly like the customer side — there is no mock data path. Your Java backend needs to expose:

```
POST /api/admin/auth/login                         { password } -> { token, tokenType, role: "ADMIN", name }

GET  /api/admin/members                             -> AdminMember[]  (CustomerProfile + { amc: {...} | null })
GET  /api/admin/amc-plans                            -> AmcPlan[]
POST /api/admin/members/:customerId/amc              { planId, startDate } -> AdminMember
POST /api/admin/members/:customerId/amc/cancel       -> AdminMember

GET  /api/admin/bookings                             -> ApiBooking[]  (same shape as /api/customer/bookings)
POST /api/admin/bookings/:id/assign-technician       { technicianId } -> ApiBooking
POST /api/admin/bookings/:id/complete                { notes? } -> ApiBooking   (status -> COMPLETED)
POST /api/admin/bookings/:id/cancel                  { reason? } -> ApiBooking  (status -> CANCELLED)

GET  /api/admin/quotations                           -> Quotation[]
POST /api/admin/quotations/:id/send                  { amountInr, validUntil, notes? } -> Quotation (status -> SENT)
POST /api/admin/quotations/:id/status                { status: "ACCEPTED" | "REJECTED" } -> Quotation

GET  /api/admin/technicians                          -> Technician[]
POST /api/admin/technicians                          { name, phone, email, specialization } -> Technician
PUT  /api/admin/technicians/:id                       { name, phone, email, specialization } -> Technician
POST /api/admin/technicians/:id/status               { status: "AVAILABLE" | "ON_JOB" | "OFF_DUTY" } -> Technician

GET  /api/admin/refunds                              -> RefundRequest[]
POST /api/admin/refunds/:id/initiate                 -> RefundRequest  (status -> INITIATED, ideally triggers a real Razorpay refund)
POST /api/admin/refunds/:id/reject                   { reason? } -> RefundRequest
```

Exact TypeScript shapes for every request/response are in `src/lib/api.ts` (search for `AdminAuthApi`, `AdminMembersApi`, `AdminBookingsApi`, `AdminQuotationsApi`, `AdminTechniciansApi`, `AdminRefundsApi`). All admin endpoints except login should require the admin bearer token and return `401` on an invalid/missing one — the frontend already logs the admin out and redirects to `/admin/login` when that happens.

Until these endpoints exist, every admin page will show a "couldn't load" retry state — that's expected and mirrors how the rest of this app behaves without its backend running.
