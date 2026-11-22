import { http, adminHttp } from "./api";

/** Support ticketing — the customer's thread with the admin team. */

export type TicketStatus = "OPEN" | "IN_PROGRESS" | "WAITING_CUSTOMER" | "RESOLVED" | "CLOSED";
export type TicketPriority = "LOW" | "NORMAL" | "HIGH" | "URGENT";

export type TicketCategory =
  | "BOOKING" | "PAYMENT" | "REFUND" | "SERVICE_QUALITY"
  | "TECHNICIAN" | "PLANT" | "ACCOUNT" | "AMC" | "OTHER";

export type CategoryOption = { id: TicketCategory; label: string; firstResponseHours: number };

export type TicketMessage = {
  id: number;
  authorType: "CUSTOMER" | "ADMIN";
  authorLabel: string | null;
  body: string;
  internal: boolean;
  at: string;
};

export type SupportTicket = {
  id: number;
  ticketNumber: string;
  subject: string;
  category: TicketCategory;
  categoryLabel: string;
  priority: TicketPriority;
  status: TicketStatus;
  statusLabel: string;
  open: boolean;
  customerId: number;
  customerName: string;
  customerEmail: string | null;
  customerPhone: string | null;
  bookingId: number | null;
  bookingToken: string | null;
  invoiceId: number | null;
  refundId: number | null;
  assignedTo: string | null;
  firstResponseDue: string | null;
  firstResponseAt: string | null;
  slaBreached: boolean;
  resolvedAt: string | null;
  resolutionNote: string | null;
  unreadForAdmin: boolean;
  unreadForCustomer: boolean;
  lastMessageAt: string;
  createdAt: string;
  messages: TicketMessage[];
};

export type NewTicket = {
  category: TicketCategory;
  subject: string;
  message: string;
  bookingId?: number;
  invoiceId?: number;
  refundId?: number;
};

export const SupportApi = {
  categories: () => http.get<CategoryOption[]>("/api/customer/support/categories"),
  tickets: () => http.get<SupportTicket[]>("/api/customer/support/tickets"),
  one: (id: number) => http.get<SupportTicket>(`/api/customer/support/tickets/${id}`),
  create: (body: NewTicket) => http.post<SupportTicket>("/api/customer/support/tickets", body),
  reply: (id: number, message: string) =>
    http.post<SupportTicket>(`/api/customer/support/tickets/${id}/reply`, { message }),
  close: (id: number) => http.post<SupportTicket>(`/api/customer/support/tickets/${id}/close`)
};

export const AdminSupportApi = {
  all: () => adminHttp.get<SupportTicket[]>("/api/admin/support/tickets"),
  queue: () => adminHttp.get<SupportTicket[]>("/api/admin/support/tickets/queue"),
  breached: () => adminHttp.get<SupportTicket[]>("/api/admin/support/tickets/breached"),
  one: (id: number) => adminHttp.get<SupportTicket>(`/api/admin/support/tickets/${id}`),
  reply: (id: number, message: string, internal = false) =>
    adminHttp.post<SupportTicket>(`/api/admin/support/tickets/${id}/reply`, { message, internal }),
  update: (id: number, body: { status?: TicketStatus; priority?: TicketPriority; assignedTo?: string; resolutionNote?: string }) =>
    adminHttp.post<SupportTicket>(`/api/admin/support/tickets/${id}/triage`, body)
};

export const TICKET_STATUS_CLASS: Record<TicketStatus, string> = {
  OPEN: "pending",
  IN_PROGRESS: "assigned",
  WAITING_CUSTOMER: "assigned",
  RESOLVED: "completed",
  CLOSED: "cancelled"
};

/** "in 3h" / "4h overdue" — turns the SLA column into something readable. */
export function untilDue(iso: string | null): string | null {
  if (!iso) return null;
  const diffMs = new Date(iso).getTime() - Date.now();
  const hours = Math.round(Math.abs(diffMs) / 3_600_000);
  const text = hours >= 24 ? `${Math.round(hours / 24)}d` : `${hours}h`;
  return diffMs >= 0 ? `in ${text}` : `${text} overdue`;
}
