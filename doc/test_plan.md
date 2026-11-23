# Test Plan & Quality Assurance Strategy — Oorja on Move

## 1. Overview & Quality Objectives

The Oorja on Move test suite safeguards critical business workflows: multi-party payments, concurrency in appointment booking, customer data isolation, and state machine integrity.

### Test Hierarchy
1. **Unit Tests (Fast Execution):** Pure business logic, state machines, pricing formulas, and refund eligibility matrix.
2. **Integration Tests (Spring Context + DB):** API endpoint contracts, Spring Security role authorization, and Flyway migration validations.
3. **Concurrency Tests:** Multi-threaded simulation of simultaneous slot booking, double payments, and OTP validation races.
4. **Static Analysis & Type Checking:** TypeScript strict compiler verification (`tsc -b --noEmit`) and Maven build verification.

---

## 2. Backend Automated Test Suite (`oorja/be/src/test`)

### 2.1 Security & Access Control Tests
* **`CustomerIsolationApiIT`:** Verifies that Customer A cannot read, mutate, or cancel bookings, quotations, or invoices belonging to Customer B.
* **`AuthorizationApiIT`:** Verifies role boundaries. Ensures public users cannot access protected endpoints, customers cannot access `/api/admin/*`, and admins cannot trigger customer-specific payment webhooks.
* **`OtpExposureApiIT`:** Inspects all API response payloads to ensure plaintext OTP codes, hashes, and internal token attributes are never serialized in responses.

### 2.2 Concurrency & Race Condition Tests
* **`BookingConcurrencyIT`:** Spawns concurrent threads attempting to reserve the exact same technician slot at the same millisecond. Asserts that exactly one thread succeeds while remaining threads receive deterministic slot conflict exceptions (`409 Conflict`).
* **`PaymentConcurrencyIT`:** Dispatches concurrent payment creation and verification requests for identical booking references to ensure double-orders and duplicate payment records cannot be written.
* **`BookingLifecycleConcurrencyIT`:** Validates concurrent transitions (e.g., simultaneous cancel request and technician completion) resolving via database lock ordering.

### 2.3 Business Domain & State Machine Tests
* **`StateMachineTest`:** Exhaustively tests every legal and illegal transition across `BookingStatus`, `PaymentStatus`, and `RefundStatus`.
* **`RefundCalculationServiceTest`:** Validates tier-based fee calculations based on scheduled start timestamp delta (100% vs 80% vs 50%).
* **`PricingServiceTest`:** Validates base pricing, capacity tiers (kW), tax calculations, and coupon application logic.

---

## 3. Integration Environment & Test Execution

### 3.1 CI Pipeline Execution (`.github/workflows/backend.yml`)
The CI runner spins up a live containerized PostgreSQL service container:
```yaml
services:
  postgres:
    image: postgres:16-alpine
    env:
      POSTGRES_DB: oorja_test
      POSTGRES_USER: oorja_user
      POSTGRES_PASSWORD: oorja_test_pass
    ports:
      - 5432:5432
```
Command executed:
```bash
mvn verify -B
```
Every pull request and merge to `main` executes the full suite before Docker images are pushed or deployments triggered.

---

## 4. Frontend Validation Strategy (`oorja/fe`)

1. **Static Type Checking:**
   ```bash
   npm run build # (tsc -b && vite build)
   ```
   Ensures complete type safety across all API DTO interfaces, component props, and navigation route parameters.
2. **Pre-Deployment Contract Checks:**
   - Verify that all endpoints in `src/lib/api.ts` match backend controller route definitions.
   - Verify that environment variable fallbacks exist for local development (`VITE_API_BASE_URL`, `VITE_RAZORPAY_KEY_ID`, `VITE_GOOGLE_CLIENT_ID`).

---

## 5. Pre-Release Verification Checklist

- [ ] All 21 Flyway migrations apply successfully to an empty PostgreSQL database.
- [ ] Concurrency test suite passes with zero deadlocks or duplicate bookings.
- [ ] Razorpay webhook HMAC verification rejects invalid signatures with `401/403`.
- [ ] CORS policies properly permit all authorized origins and reject unauthorized domains.
- [ ] Frontend builds without TypeScript or Vite bundle warnings.
