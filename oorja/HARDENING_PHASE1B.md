# Phase 1B — making existing functionality dependable

## What was verified, and how (read this first)

| Check | Status |
|---|---|
| All Flyway migrations V1–V21 apply cleanly on a fresh PostgreSQL 16 | **Ran it** |
| DB-level concurrency invariants (`be/verify/db_concurrency_check.py`, 8 scenarios, 5 consecutive runs) | **Ran it — all pass** |
| Java syntax across `src/main` + `src/test` (javac parse; only "missing dependency" errors remain) | **Ran it — no syntax errors** |
| `mvn compile / package` | **NOT run** — the build sandbox blocks Maven Central, so no dependency jar could be fetched |
| JUnit tests (`StateMachineTest`, `GlobalExceptionHandlerTest`, `ConcurrencyInvariantsTest`) | **Written, NOT run** (same reason; the concurrency test also needs Docker) |

Run first on your machine: `cd be && mvn -B verify`. If anything fails to compile it will be a small
type/import slip in the new Java — the logic was checked at the SQL level, the Java was not compiled.

## Defects found and fixed

1. **Last-seat oversell on the paid path (the headline one).** Capacity was only enforced for *unpaid* bookings; every paid
   booking used `reserveBestEffort()`, which never refuses. Two customers could both pay for the last seat and both get a booking.
   → The seat is now claimed atomically **when the Razorpay order is created** (a *hold*), before money moves. The booking
   takes the hold over (no double count). Unpaid holds expire (default 30 min, `booking.slots.hold-minutes`) via
   `SlotHoldExpiryJob`; a captured payment never loses its seat.
   The one remaining over-capacity case is deliberate and logged at ERROR: a payment whose hold lapsed *before* it was
   paid, finding the slot full — the money is taken so the booking must exist; ops must re-slot/refund.
2. **Pay-twice / retry-after-network-failure.** A retry (new idempotency key, or the webhook creating the booking first)
   got a 400 "already used". It now returns the *existing* booking. Same for re-calling cancel, complete, record-quotation-payment.
3. **A bad signature marked the payment FAILED**, poisoning an order the customer then genuinely paid. It now changes nothing.
   A late capture after FAILED/CANCELLED is recorded (gateway truth wins).
4. **Ownership errors returned 401**, which the frontend reads as "session expired" → refresh → logout. Now 403 (19 sites).
5. **Expired tokens returned 403, not 401** (no authentication entry point configured), so the frontend's silent token refresh never
   fired. Added `RestAuthHandlers` (401 vs 403, JSON).
6. **Razorpay HTTP call (with retries/backoff) ran inside a DB transaction**, pinning a pooled connection. Create-order is now
   validate → gateway call (no tx) → one short tx.
7. **Double-click on a quotation could open two payable orders.** One open order per quotation (unique partial index; stale-price
   orders are retired); webhook/record-payment verify the paid amount matches the quotation.
8. **Lock-order inversion** (record-payment: quotation→payment, webhook: payment→quotation; rebook: refund→booking, cancel:
   booking→refund) could deadlock. Orders made consistent.
9. **Admin status edit could skip slot release/refund** (any status → any status; `COMPLETED → PENDING`). State machine enforced.
10. **Hard-deleting a booking cascaded away its refunds** (`ON DELETE CASCADE`). Now refused when money/service records exist.
11. **Multipart booking JSON bypassed validation entirely** (parsed with ObjectMapper). Now validated; parser errors no longer echoed.
12. **Overlong / out-of-range input became DB errors (500).** DTO constraints now match column sizes/precision; slots limited to 180 days ahead.
13. Razorpay outage returned 400; now 503 (retryable).

## Error contract (all failures, one shape)
`{ success:false, status, code, message, errors?, path, timestamp }` — `code` is stable (`SLOT_FULL`, `VALIDATION_FAILED`,
`INVALID_STATE`, `ALREADY_PAID`, `CONCURRENT_UPDATE`, …); lock/deadlock/stale-version failures are 409 + `Retry-After`.
`success`/`message`/`errors` are unchanged, so the existing frontend keeps working. 5xx never echoes internals.

## State machines (enforced centrally)
`BookingStatus.canTransitionTo`, `PaymentStatus.canTransitionTo` (Refund/Quotation already had theirs). Illegal move → 409 `INVALID_STATE`.

## Database (V21)
Slot-hold columns + CHECKs; `uq_payments_quotation_open`; 11 missing FK indexes; webhook-status CHECK; captured-needs-gateway-id CHECK
(`NOT VALID` so legacy rows can't block the migration). Pre-flight queries are in the file header.
`scripts/slot_audit.sql` must return 0 rows: `booked_count = owning bookings + HELD checkouts`.

## Behaviour changes to be aware of
- Create-order now returns **409 `SLOT_FULL`** if the draft slot is full — the frontend should show the slot picker (it already handles server `message`).
- Order replay with the same Idempotency-Key now re-checks the slot hold and can return `SLOT_FULL` / `ALREADY_PAID`.
- Admin `PATCH` with status `CANCELLED`/`PENDING` is refused (use the cancel endpoint). The shipped admin UI doesn't do this.
- A customer's double-cancel returns the original result instead of 400.

## Not done / known limits
- Per-request idempotency *fingerprint* (same key, different body) is not checked; the frontend derives keys from the payload.
- Payment holds orphaned by a captured payment whose draft plant is gone stay held on purpose (reconcile manually).
- Spring-level concurrency tests need Docker (they skip otherwise).
