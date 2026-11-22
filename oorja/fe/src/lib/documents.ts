import { http, adminHttp, openCustomerFile } from "./api";

/**
 * Invoices and service reports.
 *
 * Downloads go through `openCustomerFile`, which already handles the bearer
 * token, a 401 refresh-and-retry, and turning the response into a real
 * saved file. A plain <a href> would send no token and silently produce a
 * login page saved as "invoice.pdf".
 */

export type InvoiceType = "TAX_INVOICE" | "CREDIT_NOTE";

export type Invoice = {
  id: number;
  invoiceNumber: string;
  type: InvoiceType;
  status: string;
  statusLabel: string;
  bookingId: number | null;
  bookingToken: string | null;
  description: string;
  subtotalInr: number;
  discountInr: number;
  taxRatePercent: number;
  taxInr: number;
  totalInr: number;
  currency: string;
  paymentMethod: string | null;
  paymentReference: string | null;
  paidAt: string | null;
  issuedAt: string;
  originalInvoiceNumber: string | null;
  fileName: string;
};

export type ChecklistItem = { label: string; ok: boolean; note?: string };

export type ServiceReport = {
  id: number;
  reportNumber: string;
  bookingId: number;
  bookingToken: string;
  serviceLabel: string;
  customerName: string;
  technicianName: string | null;
  serviceDate: string;
  startedAt: string | null;
  finishedAt: string | null;
  workPerformed: string;
  observations: string | null;
  partsReplaced: string | null;
  recommendations: string | null;
  checklistJson: string | null;
  panelsCleaned: number | null;
  outputBeforeKw: number | null;
  outputAfterKw: number | null;
  improvementKw: number | null;
  nextServiceDue: string | null;
  customerSignatureName: string | null;
  customerRemarks: string | null;
  published: boolean;
  publishedAt: string | null;
  preparedBy: string | null;
  fileName: string;
  attachments: {
    id: number;
    fileName: string;
    contentType: string | null;
    sizeBytes: number | null;
    customerUrl: string;
  }[];
};

/** Tolerant parse: a malformed checklist should hide the section, not break the page. */
export function parseChecklist(json: string | null): ChecklistItem[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export const DocumentsApi = {
  invoices: () => http.get<Invoice[]>("/api/customer/invoices"),

  downloadInvoice: (invoice: Invoice) =>
    openCustomerFile(`/api/customer/invoices/${invoice.id}/pdf`, invoice.fileName, "download"),

  viewInvoice: (invoice: Invoice) =>
    openCustomerFile(`/api/customer/invoices/${invoice.id}/pdf?disposition=inline`, invoice.fileName, "view"),

  reports: () => http.get<ServiceReport[]>("/api/customer/service-reports"),

  reportFor: (bookingId: number) =>
    http.get<ServiceReport>(`/api/customer/bookings/${bookingId}/service-report`),

  downloadReport: (report: ServiceReport) =>
    openCustomerFile(
      `/api/customer/bookings/${report.bookingId}/service-report/pdf`,
      report.fileName,
      "download"
    )
};

export const AdminDocumentsApi = {
  invoices: () => adminHttp.get<Invoice[]>("/api/admin/invoices"),
  reports: () => adminHttp.get<ServiceReport[]>("/api/admin/service-reports"),
  reportFor: (bookingId: number) =>
    adminHttp.get<ServiceReport>(`/api/admin/bookings/${bookingId}/service-report`),
  saveReport: (bookingId: number, body: Record<string, unknown>) =>
    adminHttp.put<ServiceReport>(`/api/admin/bookings/${bookingId}/service-report`, body),
  publishReport: (bookingId: number, completeBooking = true) =>
    adminHttp.post<ServiceReport>(
      `/api/admin/bookings/${bookingId}/service-report/publish?completeBooking=${completeBooking}`
    )
};
