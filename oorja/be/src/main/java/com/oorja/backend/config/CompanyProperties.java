package com.oorja.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Who the documents say they're from. Externalised because a legal name,
 * a registered address and a GSTIN are exactly the things that change
 * without any code changing — and the wrong ones on a tax invoice are a
 * compliance problem, not a typo.
 */
@Component
@ConfigurationProperties(prefix = "app.company")
@Data
public class CompanyProperties {

    private String name = "Oorja on Move";
    private String legalName = "Oorja on Move Pvt. Ltd.";
    private String addressLine1 = "";
    private String addressLine2 = "";
    private String city = "";
    private String state = "";
    private String pincode = "";
    private String gstin = "";
    private String phone = "+91 97884 95584";
    private String email = "info@oorjaonmove.com";
    private String website = "oorjaonmove.com";

    /** GST rate applied to storefront services, in basis points. Mirrors PricingService. */
    private int taxRateBps = 1800;

    /** Printed at the bottom of every invoice. */
    private String invoiceFooter =
            "This is a computer-generated document and is valid without a signature. "
            + "For any billing query, raise a support ticket in the app quoting the invoice number above.";

    /** The multi-line block under the company name on documents. */
    public String addressBlock() {
        StringBuilder sb = new StringBuilder();
        if (notBlank(legalName)) sb.append(legalName).append('\n');
        if (notBlank(addressLine1)) sb.append(addressLine1).append('\n');
        if (notBlank(addressLine2)) sb.append(addressLine2).append('\n');
        String cityLine = join(", ", city, state, pincode);
        if (notBlank(cityLine)) sb.append(cityLine).append('\n');
        if (notBlank(gstin)) sb.append("GSTIN: ").append(gstin).append('\n');
        String contact = join("  ·  ", phone, email);
        if (notBlank(contact)) sb.append(contact);
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!notBlank(p)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
