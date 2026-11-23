# Workflows & Operational Procedures — Oorja on Move

## 1. Development & Local Setup Workflow

### Prerequisites
* JDK 21 (Temurin recommended) & Maven 3.9+
* Node.js 20 LTS & npm
* PostgreSQL 16+ running locally on port 5432 (or Docker container)

### Step-by-Step Local Launch
1. **Database Setup:**
   ```sql
   CREATE DATABASE oorja_db;
   CREATE USER oorja_user WITH PASSWORD 'oorja_pass';
   GRANT ALL PRIVILEGES ON DATABASE oorja_db TO oorja_user;
   ```
2. **Backend Startup (`oorja/be`):**
   ```bash
   # Set minimum required development environment variables
   export JWT_SECRET="91339A960D1E827B7E11E167F96237D5B7772A6A434345DAA070876DE7BB7F03F1BCAB4B543AE2BC3565970B65D60859"
   export ADMIN_PASSWORD="OorjaLocalDev@2026!"
   export CORS_ALLOWED_ORIGINS="http://localhost:5173"
   export RAZORPAY_KEY_ID="rzp_test_U3IO89Q2YmpVjo"
   export RAZORPAY_KEY_SECRET="W01ZMkbY9OSWsqxy8fCXur2D"

   # Run with local profile (applies Flyway migrations automatically)
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```
   *Backend listens on:* `http://localhost:8080`

3. **Frontend Startup (`oorja/fe`):**
   ```bash
   npm install
   npm run dev
   ```
   *Frontend dev server listens on:* `http://localhost:5173`

---

## 2. CI/CD & Deployment Pipeline Workflow

```
[Developer Push / Pull Request to main]
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
 [frontend.yml]      [backend.yml]
  - Node 20 Setup     - Java 21 Setup
  - npm ci            - Postgres Service Container
  - tsc -b            - mvn verify (Unit & Concurrency IT)
  - Vercel CLI        - Docker Multi-Stage Build
  - Deploy to Vercel  - Push image to GHCR
        │             - Trigger Render Deploy Hook
        ▼                   ▼
  Vercel Edge CDN    Render Web Service (Live)
```

### Deployment Automation
* **Frontend:** Managed by `.github/workflows/frontend.yml` and Vercel CLI.
  * PR commits produce isolated preview deployments.
  * Pushes to `main` deploy directly to production edge endpoints (`https://oorja-on-move.vercel.app`).
* **Backend:** Managed by `.github/workflows/backend.yml` and Render Blueprint (`render.yaml`).
  * Pulls base images, runs database tests, packages the JAR, builds the hardened Alpine JRE container, and signals Render via webhook.

---

## 3. Business & Operational Workflows

### 3.1 Customer Booking & Payment Workflow
1. Customer registers/logs in via Email OTP or Google One-Tap.
2. Selects service type (Cleaning, Inspection, Maintenance, Inverter Diagnostic).
3. Chooses plant profile and preferred date/time slot.
4. System places a temporary `slot_hold` for 10 minutes.
5. Customer reviews pricing summary, applies optional promo coupons, and clicks **Pay & Book**.
6. Razorpay Checkout modal handles card/UPI/Netbanking transaction.
7. Verification signature confirmed by backend; booking status advances to `CONFIRMED`.
8. Invoice PDF generated and accessible from Customer & Admin dashboards.

### 3.2 Quotation & Custom Engineering Workflow
1. For plants $> 20$ kW, customer requests an on-site quotation.
2. Admin receives notification in Admin Console (`/admin/quotations`).
3. Operations engineer reviews satellite imagery/site data, sets customized subtotal, discounts, and line items, then issues quotation.
4. Customer receives quotation in web portal, clicks **Accept & Pay**, and triggers quotation-based Razorpay order.

### 3.3 Cancellation & Automated Refund Workflow
1. Customer or admin initiates cancellation from booking details modal.
2. `RefundCalculationService` determines refund tier based on slot lead time.
3. If approved, backend calls Razorpay Refund API (`POST /v1/payments/{payment_id}/refund`).
4. Double-entry ledger records debit; customer receives tracking timeline modal.
