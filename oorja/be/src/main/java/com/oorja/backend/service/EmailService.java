package com.oorja.backend.service;

import com.oorja.backend.config.CompanyProperties;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * All outbound mail.
 *
 * Two families of message, deliberately kept apart:
 *
 * <p><b>Customer-facing</b> mail (OTP, quotation ready, quotation-request
 * receipt, payment receipt, service report, support replies) is rendered as
 * branded HTML via {@link #htmlDocument} — the Oorja logo, a clear subject,
 * the customer's name where one is available, the relevant reference number,
 * a call-to-action button where one makes sense, and a footer with the
 * support phone number and email.
 *
 * <p><b>Internal ops</b> mail (a new support ticket, a new quotation request
 * landing in the admin inbox) stays plain text — it's read by the team, not
 * the customer, and a terse operational message is faster to triage than a
 * styled one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final CompanyProperties company;
    private final Environment environment;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.mail.enabled:true}")
    private boolean mailEnabled;

    /**
     * Local-development aid: when mail is disabled, print the OTP to the application console. Defaults to off and is
     * honoured only under the {@code local} profile (see {@link #shouldLogOtpToConsole()}).
     */
    @Value("${app.otp.log-to-console:false}")
    private boolean otpLogToConsole;

    /** Where customer support tickets are delivered. Defaults to the from-address. */
    @Value("${app.mail.support-inbox:}")
    private String supportInbox;

    /** Where CTA buttons in customer emails point people to log in. */
    @Value("${app.frontend.base-url:https://oorjaonmove.com}")
    private String frontendBaseUrl;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    // =====================================================================
    // Login
    // =====================================================================

    public void sendOtpEmail(String toEmail, String otp, String purposeLabel) {
        String bodyHtml = p("Hello,")
                + p("Your one-time password (OTP) to " + escape(purposeLabel) + " is:")
                + otpBlock(otp)
                + p("This code is valid for " + "5 minutes. Do not share it with anyone.")
                + p("If you did not request this, you can safely ignore this email.");

        // Unlike the other notifications in this class, an OTP has no other
        // delivery path for the customer to fall back on — so unlike the
        // rest of this class, a failure here must propagate as an error
        // rather than be logged and swallowed.
        if (!mailEnabled) {
            logOtpForLocalDevelopment(toEmail, otp, purposeLabel);
        }
        sendHtmlInternal(toEmail, "Oorja on Move \u2014 Your login code", "Your login code", bodyHtml,
                null, null, null, null, "OTP email", true);
    }

    /**
     * True only for a developer machine that has opted in: mail is disabled, the {@code local} profile is active,
     * OTP_LOG_TO_CONSOLE=true, and no production profile is active. Anything else - including a missing profile -
     * means "never print".
     */
    boolean shouldLogOtpToConsole() {
        return !mailEnabled
                && otpLogToConsole
                && environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    private void logOtpForLocalDevelopment(String toEmail, String otp, String purposeLabel) {
        if (shouldLogOtpToConsole()) {
            log.info("[LOCAL DEV OTP] code={} for '{}' -> {}", otp, purposeLabel, maskEmail(toEmail));
        }
    }

    /** "rahul.sharma@example.com" -> "r***a@example.com". */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(no recipient)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String masked = local.length() <= 2 ? local.charAt(0) + "***" : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return masked + email.substring(at);
    }

    // =====================================================================
    // Quotations
    // =====================================================================

    /** Confirms to the customer that their request for a quote has been received. */
    public void sendQuotationRequestReceivedEmail(String toEmail, String customerName, String quotationNumber,
                                                   String serviceLabel, String bookingToken) {
        String subject = "We've received your request \u2014 " + quotationNumber;
        String bodyHtml = p("Hi " + escape(nameOrFallback(customerName)) + ",")
                + p("Thanks for reaching out to " + escape(company.getName())
                        + (serviceLabel != null && !serviceLabel.isBlank() ? " for " + escape(serviceLabel) : "")
                        + ". Our team is putting together a quotation for you and will email it as soon as it's ready.")
                + detailsTable(
                        "Reference number", quotationNumber,
                        "Booking", bookingToken)
                + p("You can check its status any time from your account.");

        sendHtml(toEmail, subject, "Request received", bodyHtml,
                "Track your request", frontendBaseUrl + "/login", "quotation request received " + quotationNumber);
    }

    /** Lets the admin/support inbox know a new quotation request needs pricing. Internal — plain text. */
    public void sendQuotationRequestAlertToAdmin(String quotationNumber, String customerName, String customerEmail,
                                                  String customerPhone, String serviceLabel, String location,
                                                  String bookingToken) {
        String to = (supportInbox == null || supportInbox.isBlank()) ? fromAddress : supportInbox;
        String subject = "New quotation request \u2014 " + quotationNumber;
        String body = """
                A new quotation request needs pricing.

                Reference : %s
                Booking   : %s
                Service   : %s
                Location  : %s

                Customer  : %s
                Email     : %s
                Phone     : %s

                Open Admin -> Quotations in the console to price and send it, and to
                review any site photos the customer attached.

                - %s
                """.formatted(
                quotationNumber,
                bookingToken == null ? "-" : bookingToken,
                serviceLabel == null || serviceLabel.isBlank() ? "-" : serviceLabel,
                location == null || location.isBlank() ? "-" : location,
                customerName,
                customerEmail == null ? "-" : customerEmail,
                customerPhone == null ? "-" : customerPhone,
                company.getName());

        sendPlain(to, subject, body, "quotation request alert " + quotationNumber);
    }

    /** Notifies a customer that a quotation has been priced and sent to them. */
    public void sendQuotationEmail(String toEmail, String customerName, String quotationNumber,
                                    BigDecimal amount, LocalDate validUntil, String notes) {
        String subject = "Your quotation " + quotationNumber + " from " + company.getName();
        String bodyHtml = p("Hi " + escape(nameOrFallback(customerName)) + ",")
                + p("Your quotation is ready.")
                + detailsTable(
                        "Quotation number", quotationNumber,
                        "Quoted amount", rupees(amount),
                        "Valid until", validUntil != null ? validUntil.format(DATE) : null)
                + (notes != null && !notes.isBlank() ? p("Notes: " + notes) : "")
                + p("Log in to your account to view the full breakdown and accept the quote.");

        sendHtml(toEmail, subject, "Your quotation is ready", bodyHtml,
                "View quotation", frontendBaseUrl + "/login", "quotation email " + quotationNumber);
    }

    // =====================================================================
    // Payments & documents
    // =====================================================================

    /** Sends the tax invoice / payment receipt PDF to the customer once a payment is captured. */
    public void sendPaymentReceiptEmail(String toEmail, String customerName, String invoiceNumber,
                                         long totalPaise, String currency, LocalDateTime paidAt,
                                         byte[] pdfBytes, String pdfFileName) {
        String subject = "Oorja Payment Receipt #" + invoiceNumber;
        String bodyHtml = p("Hi " + escape(nameOrFallback(customerName)) + ",")
                + p("Your payment has been successfully received.")
                + detailsTable(
                        "Amount", formatPaise(totalPaise, currency),
                        "Receipt", invoiceNumber,
                        "Paid on", paidAt != null ? paidAt.format(DATETIME) : null)
                + p("The receipt is attached to this email as a PDF, and is also available any time under Payments in your account.");

        sendHtmlWithAttachment(toEmail, subject, "Payment received", bodyHtml,
                "Download receipt", frontendBaseUrl + "/login",
                pdfBytes, pdfFileName, "payment receipt " + invoiceNumber);
    }

    /** Sends the completed service report PDF to the customer once an admin publishes it. */
    public void sendServiceReportEmail(String toEmail, String customerName, String reportNumber,
                                        String bookingToken, byte[] pdfBytes, String pdfFileName) {
        String subject = "Your service report \u2014 " + reportNumber;
        String bodyHtml = p("Hi " + escape(nameOrFallback(customerName)) + ",")
                + p("The service report for your visit is ready.")
                + detailsTable(
                        "Report number", reportNumber,
                        "Booking", bookingToken)
                + p("It's attached to this email as a PDF, and is also available any time under Bookings in your account.");

        sendHtmlWithAttachment(toEmail, subject, "Your service report is ready", bodyHtml,
                "View report", frontendBaseUrl + "/login",
                pdfBytes, pdfFileName, "service report " + reportNumber);
    }

    private static String formatPaise(long paise, String currency) {
        BigDecimal amount = BigDecimal.valueOf(paise, 2);
        String symbol = currency == null || "INR".equalsIgnoreCase(currency) ? "\u20b9" : currency + " ";
        return symbol + amount.toPlainString();
    }

    private static String rupees(BigDecimal amount) {
        return amount == null ? null : "\u20b9" + amount.toPlainString();
    }

    // =====================================================================
    // Support ticketing
    // =====================================================================

    /**
     * Notifies the support inbox that a customer needs something. Internal —
     * plain text, so the first minute of a support response is spent
     * triaging, not scrolling past styling.
     */
    public void sendSupportTicketToAdmin(String ticketNumber, String category, String priority,
                                         String subject, String customerName, String customerEmail,
                                         String customerPhone, String bookingToken, String message) {
        String to = (supportInbox == null || supportInbox.isBlank()) ? fromAddress : supportInbox;
        String mailSubject = "[%s] %s \u2014 %s".formatted(priority, ticketNumber, subject);

        String body = """
                A new support ticket has been raised in the Oorja app.

                Ticket   : %s
                Category : %s
                Priority : %s
                Subject  : %s

                Customer : %s
                Email    : %s
                Phone    : %s
                Booking  : %s

                ----- Message -----
                %s
                -------------------

                Reply from Admin -> Support in the console so the customer sees it in their app.
                """.formatted(ticketNumber, category, priority, subject,
                        customerName,
                        customerEmail == null ? "-" : customerEmail,
                        customerPhone == null ? "-" : customerPhone,
                        bookingToken == null ? "-" : bookingToken,
                        message);

        sendPlain(to, mailSubject, body, "support ticket " + ticketNumber);
    }

    /** Tells a customer there's a reply waiting in the app. */
    public void sendSupportReplyToCustomer(String toEmail, String customerName, String ticketNumber,
                                           String subject, String message, String adminLabel) {
        String mailSubject = "Re: [%s] %s".formatted(ticketNumber, subject);
        String bodyHtml = p("Hello " + escape(nameOrFallback(customerName)) + ",")
                + p("We've replied to your support ticket " + escape(ticketNumber) + " (" + escape(subject) + "):")
                + quoteBlock(message)
                + p("You can read the full conversation and reply under Help \u2192 My tickets in the app.");

        sendHtml(toEmail, mailSubject, "Reply to your support ticket", bodyHtml,
                "View ticket", frontendBaseUrl + "/login", "support reply " + ticketNumber);
    }

    // =====================================================================
    // HTML template
    // =====================================================================

    private static String nameOrFallback(String name) {
        return (name == null || name.isBlank()) ? "there" : name;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String p(String text) {
        if (text == null || text.isBlank()) return "";
        return "<p style=\"margin:0 0 14px 0;\">" + escape(text) + "</p>";
    }

    /** A prominent, monospaced block for the OTP digits. */
    private static String otpBlock(String otp) {
        return "<div style=\"margin:6px 0 18px 0;padding:14px 0;text-align:center;"
                + "background:#F7F6F3;border-radius:6px;\">"
                + "<span style=\"font-family:'Courier New',monospace;font-size:28px;font-weight:bold;"
                + "letter-spacing:8px;color:#1F6F54;\">" + escape(otp) + "</span></div>";
    }

    /** An indented, left-ruled block for a quoted support message. */
    private static String quoteBlock(String message) {
        return "<div style=\"margin:6px 0 18px 0;padding:10px 16px;border-left:3px solid #1F6F54;"
                + "background:#F7F6F3;color:#1A1A1A;\">" + escape(message).replace("\n", "<br/>") + "</div>";
    }

    /** Label/value rows; a null or blank value is skipped rather than printed as empty. */
    private static String detailsTable(String... labelValuePairs) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                        + "style=\"width:100%;margin:4px 0 18px 0;border-collapse:collapse;\">");
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            String value = labelValuePairs[i + 1];
            if (value == null || value.isBlank()) continue;
            sb.append("<tr>")
              .append("<td style=\"padding:5px 14px 5px 0;color:#6B6B6B;font-size:12.5px;")
              .append("white-space:nowrap;vertical-align:top;\">").append(escape(labelValuePairs[i])).append("</td>")
              .append("<td style=\"padding:5px 0;color:#1A1A1A;font-size:13.5px;font-weight:bold;\">")
              .append(escape(value)).append("</td>")
              .append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    /**
     * Wraps the given body fragment in the shared Oorja letterhead: logo,
     * brand bar, title, the content, an optional CTA button, and a footer
     * with the support phone number and email. Table-based layout with
     * inline styles throughout, since that's what renders consistently
     * across email clients.
     */
    private String htmlDocument(String title, String bodyHtml, String ctaLabel, String ctaUrl) {
        String cta = (ctaLabel != null && !ctaLabel.isBlank() && ctaUrl != null && !ctaUrl.isBlank())
                ? ("<tr><td align=\"center\" style=\"padding:6px 32px 28px 32px;\">"
                        + "<a href=\"" + ctaUrl + "\" style=\"background:#1F6F54;color:#ffffff;text-decoration:none;"
                        + "font-family:Arial,Helvetica,sans-serif;font-size:14px;font-weight:bold;"
                        + "padding:12px 30px;border-radius:4px;display:inline-block;\">"
                        + escape(ctaLabel) + "</a></td></tr>")
                : "";

        String phone = company.getPhone() == null || company.getPhone().isBlank() ? null : company.getPhone();
        String email = company.getEmail();

        return """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:0;background:#F2F1ED;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#F2F1ED;padding:24px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="600" cellpadding="0" cellspacing="0"
                               style="background:#ffffff;border-radius:6px;overflow:hidden;max-width:600px;width:100%%;">
                          <tr><td style="background:#1F6F54;height:5px;line-height:5px;font-size:0;">&nbsp;</td></tr>
                          <tr>
                            <td style="padding:26px 32px 6px 32px;" align="left">
                              <img src="cid:logo" alt="%s" width="64" style="display:block;border:0;"/>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:10px 32px 4px 32px;">
                              <h1 style="margin:0 0 12px 0;font-size:19px;line-height:1.3;color:#1A1A1A;
                                         font-family:Arial,Helvetica,sans-serif;">%s</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 4px 32px;font-size:14px;line-height:1.55;color:#1A1A1A;
                                       font-family:Arial,Helvetica,sans-serif;">
                              %s
                            </td>
                          </tr>
                          %s
                          <tr><td style="padding:0 32px;"><div style="border-top:1px solid #E2E0DA;"></div></td></tr>
                          <tr>
                            <td style="padding:18px 32px 26px 32px;font-size:12px;line-height:1.7;color:#6B6B6B;
                                       font-family:Arial,Helvetica,sans-serif;">
                              <strong style="color:#1A1A1A;">%s</strong><br/>
                              Need help? Call %s or email
                              <a href="mailto:%s" style="color:#1F6F54;text-decoration:none;">%s</a> \u2014 just reply to this email.<br/>
                              This is an automated message from %s.
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                escape(company.getName()),
                escape(title),
                bodyHtml,
                cta,
                escape(company.getName()),
                phone == null ? "us" : escape(phone),
                email, escape(email),
                escape(company.getName()));
    }

    // =====================================================================
    // Delivery
    // =====================================================================

    /** Plain-text delivery for internal ops mail. Failures are logged and swallowed. */
    private void sendPlain(String to, String subject, String body, String what) {
        if (to == null || to.isBlank()) {
            log.warn("No recipient configured for {} \u2014 skipping email.", what);
            return;
        }
        if (!mailEnabled) {
            log.info("[DEV MAIL DISABLED] {} -> {}\n{}", what, maskEmail(to), body);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send {} to {}: {}", what, to, e.getMessage());
        }
    }

    /** Branded HTML delivery for customer-facing mail, no attachment. */
    private void sendHtml(String to, String subject, String title, String bodyHtml,
                           String ctaLabel, String ctaUrl, String what) {
        sendHtmlInternal(to, subject, title, bodyHtml, ctaLabel, ctaUrl, null, null, what, false);
    }

    /** Branded HTML delivery for customer-facing mail, with a PDF attached. */
    private void sendHtmlWithAttachment(String to, String subject, String title, String bodyHtml,
                                         String ctaLabel, String ctaUrl,
                                         byte[] pdfBytes, String pdfFileName, String what) {
        sendHtmlInternal(to, subject, title, bodyHtml, ctaLabel, ctaUrl, pdfBytes, pdfFileName, what, false);
    }

    private void sendHtmlInternal(String to, String subject, String title, String bodyHtml,
                                   String ctaLabel, String ctaUrl,
                                   byte[] pdfBytes, String pdfFileName, String what, boolean throwOnFailure) {
        if (to == null || to.isBlank()) {
            log.warn("No recipient configured for {} \u2014 skipping email.", what);
            if (throwOnFailure) {
                throw new RuntimeException("No recipient email address on file.");
            }
            return;
        }
        if (!mailEnabled) {
            log.info("[DEV MAIL DISABLED] {} -> {}{}", what, maskEmail(to),
                    pdfFileName != null ? " (with attachment " + pdfFileName + ")" : "");
            return;
        }
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            // MIXED_RELATED supports an inline logo (cid:logo) and a PDF
            // attachment on the same message; plain HTML-only sends use the
            // same mode harmlessly.
            MimeMessageHelper helper = new MimeMessageHelper(mime, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            if (company.getEmail() != null && !company.getEmail().isBlank()) {
                helper.setReplyTo(company.getEmail());
            }
            helper.setSubject(subject);
            helper.setText(htmlDocument(title, bodyHtml, ctaLabel, ctaUrl), true);

            try {
                ClassPathResource logo = new ClassPathResource("branding/oorja-logo.png");
                if (logo.exists()) {
                    helper.addInline("logo", logo, "image/png");
                }
            } catch (Exception e) {
                log.warn("Couldn't attach logo to email: {}", e.getMessage());
            }

            if (pdfBytes != null && pdfBytes.length > 0) {
                helper.addAttachment(pdfFileName, new ByteArrayResource(pdfBytes));
            }

            mailSender.send(mime);
        } catch (Exception e) {
            // Same trade-off throughout this class: the underlying record
            // (quotation, receipt, report) already exists regardless of
            // whether the email made it out, so failures are logged for ops
            // rather than thrown back at the caller — except the OTP path
            // (throwOnFailure), which the customer has no other way to receive.
            log.error("Failed to send {} to {}: {}", what, to, e.getMessage());
            if (throwOnFailure) {
                throw new RuntimeException("Unable to send OTP email. Please try again later.");
            }
        }
    }
}
