# OORJA_web_final — merge notes

This bundle is the base Oorja platform (`files__12_.zip`: Phase 7/8 quotation
versioning) merged with the full refund engine, invoicing, service-report and
support-ticketing feature set (`oorja-platform-bundle.zip`), on top of the
partial frontend merge that was already started (`oorja-frontend-merged.zip`).
`oorja-refund-engine.zip` was an earlier/partial snapshot of the same patch
and was superseded by the platform bundle — nothing from it was needed once
the bundle was applied.

## What's in here

- `be/` — Spring Boot backend. Base backend (`entity`, `controller`, `service`,
  `repository`, `dto`, migrations V1–V16) with the refund-engine backend
  overlaid on top: refunds (state machine, eligibility, calculation, gateway
  dispatch, reconciliation job), invoices + credit notes (PDF rendering),
  service reports (draft → publish), and support ticketing — migrations V17
  and V18, all new/updated entities, repositories, services and controllers.
  - `pom.xml` — added the OpenPDF dependency used by the PDF renderers.
  - `application.properties` — appended the refund-policy, company/invoice
    and support-inbox configuration block. **Set `app.company.*` (legal name,
    address, GSTIN) before going live** — they print on every invoice.
- `fe/` — React/Vite frontend. Base frontend with:
  - Part 1 (refunds): `CancelOrRebookModal`, `RebookFromRefundModal`,
    `RefundTimelineModal`, `lib/refunds.ts`, admin `AdminRefunds`, and the
    patched `Bookings.tsx` — the old inline refund types/API block was
    removed from `lib/api.ts` per the bundle's install notes.
  - Part 2 (documents & support), which the earlier merge attempt was
    missing and is now wired in end to end:
    - `pages/Invoices.tsx` (customer) → routed at `/invoices`, linked from
      Profile → quick access.
    - `pages/Support.tsx` (customer) → routed at `/support`, linked from
      Help → "Raise a ticket".
    - `admin/pages/AdminServiceReports.tsx` → routed at
      `/admin/service-reports`, in the admin sidebar.
    - `admin/pages/AdminSupport.tsx` → routed at `/admin/support`, in the
      admin sidebar.
    - `components/ServiceReportModal.tsx` (customer-facing report view),
      `lib/documents.ts`, `lib/support.ts`.
  - `styles.css` — rebuilt from the base stylesheet plus the newer, grown
    `styles.refunds.css` (refunds + invoices + service reports + support),
    replacing the smaller, stale version an earlier pass had appended.
  - Admin branding: the sidebar's "Oorja Admin" text has been replaced with
    the actual Oorja on Move logo (`public/assets/logo.png`, via the
    existing `Logo` component), and the sidebar nav now includes
    **Service Reports** and **Support**.

## Install / run, in short

1. Backend: `cd be && mvn -q compile` (Maven Central wasn't reachable in
   this environment, so this hasn't been compiled here — the source has
   passed brace/import-level static review, but treat `mvn compile` as the
   first real verification step). Flyway runs `V17`/`V18` automatically on
   boot.
2. Set `app.company.*`, `app.mail.support-inbox`, and the `REFUND_*` policy
   overrides as needed in your environment (all have sane defaults).
3. In the Razorpay dashboard, subscribe the webhook to `refund.created`,
   `refund.processed`, `refund.failed` in addition to the payment events.
4. Frontend: `cd fe && npm install && npm run dev` (no network access in
   this environment, so `npm install`/`tsc -b` weren't run here either —
   every relative import and named import/export pair across the touched
   files was checked by hand and lines up).

## Still open (carried over from the refund-engine README)

- No automated tests yet for the eligibility/calculation services or the
  refund state machine.
- Ticket attachments, in-app unread badges, and a "raise a ticket about
  this booking/refund/invoice" deep-link button aren't built yet.
- `start-review` (quotation revision IN_REVIEW transition) still has no
  admin UI trigger.
- Version-diff ("v2 → v3") buttons aren't wired into the quotation history
  modals yet, though the endpoints exist.
