# Oorja on Move — Backend

Java 17 + Spring Boot 3 + PostgreSQL backend for the Oorja on Move solar field-operations dashboard.
Supports **admin login via email OTP**, **customer registration/login via email OTP**, plant & technician
management, and a customer-facing booking flow for cleaning/maintenance services.

## Stack
- Java 17, Spring Boot 3.3
- PostgreSQL + Flyway (schema + seed data auto-applied on boot)
- Spring Security + JWT (stateless, role-based: `SUPER_ADMIN` / `CUSTOMER`)
- Spring Mail (SMTP) for OTP delivery
- BCrypt-hashed OTPs (never stored in plain text), rate-limited, 5-minute expiry

## 1. Prerequisites
- JDK 17+
- Maven 3.9+
- PostgreSQL 14+ running locally (or a connection string to a hosted instance)
- An SMTP account for sending OTP emails (Gmail app password, SendGrid, etc.) — optional for local dev

## 2. Create the database

```sql
CREATE DATABASE oorja_db;
CREATE USER oorja_user WITH PASSWORD 'oorja_pass';
GRANT ALL PRIVILEGES ON DATABASE oorja_db TO oorja_user;
```

(Or set `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` env vars to point at your own instance.)

Flyway will automatically create all tables and seed:
- 1 admin (`admin@oorjaonmove.com`)
- 8 customers, 5 plants, 5 technicians, 8 bookings — matching the dashboard's original mock data

## 3. Configure environment variables

| Variable | Purpose | Local default |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Postgres connection | `localhost:5432/oorja_db` |
| `JWT_SECRET` | HMAC signing key (32+ chars) | dev placeholder — **change in prod** |
| `JWT_EXPIRATION_MS` | Token lifetime | `86400000` (24h) |
| `MAIL_ENABLED` | `true` to send real emails, `false` to log OTPs to console | `true` |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP credentials | Gmail SMTP placeholders |
| `OTP_LOG_TO_CONSOLE` | Print the OTP in the app console. Works only with `MAIL_ENABLED=false` **and** `SPRING_PROFILES_ACTIVE=local`; ignored under prod | `false` |
| `MAIL_FROM` | From address shown in OTP emails | `no-reply@oorjaonmove.com` |
| `MSG91_ENABLED` | `true` to send real SMS OTPs via MSG91, `false` to log them to console | `false` |
| `MSG91_AUTH_KEY` | MSG91 account auth key | — |
| `MSG91_OTP_TEMPLATE_ID` | DLT-approved MSG91 Flow template id (must contain exactly one numeric variable) | — |
| `MSG91_SENDER_ID` | Approved SMS sender id | `OORJA` |
| `MSG91_OTP_VAR_NAME` | Name of the variable in your template that holds the OTP | `OTP` |
| `GOOGLE_CLIENT_ID` | Google OAuth 2.0 Web Client ID — must match the frontend's `VITE_GOOGLE_CLIENT_ID` | — |

For quick local testing without SMTP/MSG91, leave `MAIL_ENABLED=false` and `MSG91_ENABLED=false` — OTPs
are printed to the application log instead of being emailed/texted.

## 4. Run

```bash
mvn spring-boot:run
```

The API starts on `http://localhost:8080`.

## 5. Authentication flow

Both admin and customer login are **passwordless, OTP-over-email**, matching the dashboard's login screen
(email step → 6-digit OTP step).

### Admin
1. `POST /api/admin/auth/send-otp` `{ "email": "admin@oorjaonmove.com" }`
2. `POST /api/admin/auth/verify-otp` `{ "email": "...", "otp": "123456" }` → returns `{ token, role: "SUPER_ADMIN", ... }`
3. Send `Authorization: Bearer <token>` on all `/api/admin/**` requests.

### Customer — existing user login
1. `POST /api/customer/auth/login/send-otp` `{ "email": "rahul@gmail.com" }`
2. `POST /api/customer/auth/login/verify-otp` `{ "email": "...", "otp": "..." }` → JWT with role `CUSTOMER`

### Customer — new registration
1. `POST /api/customer/auth/register/send-otp` `{ "name", "email", "phone", "address" }`
2. `POST /api/customer/auth/register/verify-otp` `{ "name", "email", "phone", "address", "otp" }`
   → creates the account and returns a JWT

### Customer — mobile number + real SMS OTP (MSG91)
1. `POST /api/customer/auth/otp/phone/send` `{ "phone": "9876543210" }` → sends a real SMS via MSG91 (or logs it if `MSG91_ENABLED=false`)
2. `POST /api/customer/auth/otp/phone/verify` `{ "phone": "...", "otp": "..." }` →
   - existing number: `{ "status": "LOGGED_IN", "auth": { token, ... } }`
   - brand-new number: `{ "status": "NEEDS_PROFILE", "profileTicket": "..." }`
3. For a brand-new number: `POST /api/customer/auth/register/complete-phone` `{ "profileTicket", "name", "email", "address" }` → creates the account and returns a JWT

### Customer — Google OAuth
1. Frontend uses Google Identity Services to get a real Google ID token (the person picks/enters their
   Google account inside Google's own UI - this app never sees their password).
2. `POST /api/customer/auth/google` `{ "idToken": "..." }` → verified server-side against Google's
   `tokeninfo` endpoint; finds or creates the customer and returns a JWT. `needsProfile: true` in the
   response means phone/address are still missing (fresh sign-up) - the frontend should collect those and
   call `PUT /api/customer/me` before continuing.

## 6. API reference (summary)

### Admin (`/api/admin/**`, requires `SUPER_ADMIN` JWT)
- `GET /dashboard/summary` — counts + status breakdown + recent bookings
- `GET|POST /plants`, `GET|PUT|DELETE /plants/{id}`
- `GET|POST /technicians`, `GET|PUT|DELETE /technicians/{id}` (availability is derived live from active bookings)
- `GET /bookings`, `GET /bookings/{id}`, `PATCH /bookings/{id}` (assign technician / change status), `DELETE /bookings/{id}`
- `GET /customers?q=`, `GET /customers/{id}`, `PATCH /customers/{id}/status?status=ACTIVE|INACTIVE`

### Customer (`/api/customer/**`, requires `CUSTOMER` JWT)
- `GET /me` — own profile
- `PUT /me` `{ name, phone, address }` — update own profile (used by the "complete your profile" step and profile editing)
- `GET /plants` — own registered plants
- `GET /services` — bookable service catalog (Solar Plant Cleaning, Preventive Maintenance, etc.)
- `GET /bookings` — own bookings
- `POST /bookings` `{ plantId?, serviceType, scheduledDate, scheduledTime, notes?, location?, amountPaise?, paymentRef?, serviceLabel? }` — book a service. `plantId` is optional: if omitted, the customer's first registered plant is reused, or a starter plant is auto-created from `location`.
- `POST /bookings/{id}/cancel`

## 7. Booking lifecycle

`PENDING → ASSIGNED (technician set) → ON_SITE → COMPLETED`, or `CANCELLED` at any point before completion.
Assigning a technician via `PATCH /admin/bookings/{id}` automatically marks them `BOOKED`; completing,
cancelling, or reassigning frees them back to `AVAILABLE` once they have no other active job.

## 8. Razorpay payments

- `POST /api/customer/payments/create-order` `{ amount, receipt }` — creates a server-side Razorpay order (amount in rupees). Requires `RAZORPAY_KEY_SECRET`.
- `POST /api/customer/payments/verify` `{ razorpay_order_id, razorpay_payment_id, razorpay_signature }` — verifies the HMAC signature Razorpay returns after checkout, so a payment can't be spoofed client-side.

Set these env vars:

| Variable | Purpose |
|---|---|
| `RAZORPAY_KEY_ID` | Public key ID (also sent to the frontend to open Checkout) |
| `RAZORPAY_KEY_SECRET` | Secret key — **never** expose this in frontend code |

The frontend (`oorja-on-move-web`) creates the order via `create-order`, opens Razorpay Standard Checkout with the returned `orderId`, then calls `verify` once the user completes payment. If the backend isn't running, the frontend falls back to a local "demo order" so the UI can still be previewed — but no real payment verification happens in that mode.

## 9. Security notes for production
- Replace `JWT_SECRET` with a long random value (e.g. `openssl rand -base64 48`).
- Tighten CORS in `SecurityConfig` (`corsConfigurationSource`) to your actual frontend origin instead of `*`.
- Put the app behind HTTPS; JWTs are bearer tokens with no additional binding.
- Consider adding refresh tokens if 24h access tokens are too long for your threat model.
