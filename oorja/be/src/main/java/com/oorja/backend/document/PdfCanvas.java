package com.oorja.backend.document;

import com.lowagie.text.*;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A thin, opinionated layer over OpenPDF so the invoice and the service
 * report look like they came from the same company.
 *
 * Every visual decision — the palette, the two type sizes, the table
 * rhythm — lives here rather than being re-chosen in each renderer. If the
 * brand changes, one file changes. Renderers deal only in content.
 */
public final class PdfCanvas implements AutoCloseable {

    // Restrained palette: one brand colour, two greys. A document that a
    // customer might print in black and white has to survive losing all of it.
    public static final Color BRAND = new Color(0x1F, 0x6F, 0x54);
    public static final Color INK = new Color(0x1A, 0x1A, 0x1A);
    public static final Color MUTED = new Color(0x6B, 0x6B, 0x6B);
    public static final Color RULE = new Color(0xE2, 0xE0, 0xDA);
    public static final Color WASH = new Color(0xF7, 0xF6, 0xF3);

    public static final Font H1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK);
    public static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, INK);
    public static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
    public static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK);
    public static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8f, MUTED);
    public static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, MUTED);
    public static final Font TOTAL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a");

    private final Document document;
    private final ByteArrayOutputStream out;

    public PdfCanvas() {
        this.out = new ByteArrayOutputStream();
        this.document = new Document(PageSize.A4, 42, 42, 44, 52);
        PdfWriter.getInstance(document, out);
        document.open();
    }

    // ---------- Building blocks ----------

    /** Company block on the left, document title and reference on the right. */
    public PdfCanvas header(String companyName, String companyLines, String docTitle, String reference, String issuedOn) {
        // Slim brand-colour bar across the very top of the page, so a document
        // is recognisably Oorja's even glanced at from across a desk.
        PdfPTable bar = new PdfPTable(1);
        bar.setWidthPercentage(100);
        bar.setSpacingAfter(14);
        PdfPCell barCell = new PdfPCell();
        barCell.setBorder(Rectangle.NO_BORDER);
        barCell.setBackgroundColor(BRAND);
        barCell.setFixedHeight(5f);
        bar.addCell(barCell);
        add(bar);

        PdfPTable table = grid(new float[]{ 3f, 2f });

        PdfPTable leftStack = new PdfPTable(1);
        leftStack.setWidthPercentage(100);
        Image logo = loadLogo();
        if (logo != null) {
            logo.scaleToFit(80, 80);
            PdfPCell logoCell = new PdfPCell(logo, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setPadding(0);
            logoCell.setPaddingBottom(6);
            leftStack.addCell(logoCell);
        }
        Paragraph left = new Paragraph();
        left.add(new Chunk(companyName + "\n", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, BRAND)));
        left.add(new Chunk(companyLines, SMALL));
        PdfPCell leftTextCell = new PdfPCell(left);
        leftTextCell.setBorder(Rectangle.NO_BORDER);
        leftTextCell.setPadding(0);
        leftStack.addCell(leftTextCell);

        PdfPCell leftOuter = new PdfPCell(leftStack);
        leftOuter.setBorder(Rectangle.NO_BORDER);
        leftOuter.setPadding(0);
        leftOuter.setPaddingRight(12);
        table.addCell(leftOuter);

        Paragraph right = new Paragraph();
        right.setAlignment(Element.ALIGN_RIGHT);
        right.add(new Chunk(docTitle.toUpperCase() + "\n", LABEL));
        right.add(new Chunk(reference + "\n", H1));
        right.add(new Chunk(issuedOn, SMALL));
        table.addCell(bare(right));

        add(table);
        return rule();
    }

    /** The Oorja logo, read once from the classpath and cached for reuse across every render. */
    private static final byte[] LOGO_BYTES = loadLogoBytes();

    private static byte[] loadLogoBytes() {
        try (InputStream in = PdfCanvas.class.getResourceAsStream("/branding/oorja-logo.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** A fresh {@link Image} instance per call — OpenPDF Images carry layout state and can't be reused across pages/cells. */
    private static Image loadLogo() {
        if (LOGO_BYTES == null) return null;
        try {
            return Image.getInstance(LOGO_BYTES);
        } catch (Exception e) {
            return null;
        }
    }

    /** A horizontal hairline with breathing room, used to separate sections. */
    public PdfCanvas rule() {
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        line.setSpacingBefore(10);
        line.setSpacingAfter(12);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(RULE);
        cell.setFixedHeight(1f);
        line.addCell(cell);
        add(line);
        return this;
    }

    public PdfCanvas heading(String text) {
        Paragraph p = new Paragraph(text, H2);
        p.setSpacingBefore(12);
        p.setSpacingAfter(6);
        add(p);
        return this;
    }

    public PdfCanvas paragraph(String text) {
        if (text == null || text.isBlank()) return this;
        Paragraph p = new Paragraph(text, BODY);
        p.setSpacingAfter(6);
        p.setLeading(13f);
        add(p);
        return this;
    }

    /** Two side-by-side label/value blocks — "billed to" against "payment details". */
    public PdfCanvas twoColumns(String leftLabel, String leftBody, String rightLabel, String rightBody) {
        PdfPTable table = grid(new float[]{ 1f, 1f });
        table.addCell(bare(labelled(leftLabel, leftBody)));
        table.addCell(bare(labelled(rightLabel, rightBody)));
        add(table);
        return this;
    }

    /** Label/value rows in a tidy two-column block. Nulls are skipped, not printed as "null". */
    public PdfCanvas facts(String... labelValuePairs) {
        PdfPTable table = grid(new float[]{ 1f, 2f });
        for (int i = 0; i + 1 < labelValuePairs.length; i += 2) {
            String value = labelValuePairs[i + 1];
            if (value == null || value.isBlank()) continue;
            table.addCell(pad(new Phrase(labelValuePairs[i], SMALL)));
            table.addCell(pad(new Phrase(value, BODY)));
        }
        add(table);
        return this;
    }

    /** Line-item table with a shaded header row. */
    public PdfPTable itemsTable(float[] widths, String... headers) {
        PdfPTable table = grid(widths);
        for (String h : headers) {
            PdfPCell cell = pad(new Phrase(h.toUpperCase(), LABEL));
            cell.setBackgroundColor(WASH);
            cell.setPaddingTop(6);
            cell.setPaddingBottom(6);
            table.addCell(cell);
        }
        table.setHeaderRows(1);
        return table;
    }

    public void row(PdfPTable table, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            PdfPCell cell = pad(new Phrase(cells[i] == null ? "" : cells[i], BODY));
            cell.setBorder(Rectangle.BOTTOM);
            cell.setBorderColorBottom(RULE);
            if (i > 0) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
    }

    /** Right-aligned totals stack; the final row is emphasised. */
    public PdfCanvas totals(String[][] rows, String totalLabel, String totalValue) {
        PdfPTable outer = grid(new float[]{ 2f, 1.4f });
        outer.addCell(bare(new Paragraph("")));

        PdfPTable inner = new PdfPTable(new float[]{ 1.4f, 1f });
        inner.setWidthPercentage(100);
        for (String[] r : rows) {
            if (r[1] == null) continue;
            inner.addCell(right(new Phrase(r[0], SMALL)));
            inner.addCell(right(new Phrase(r[1], BODY)));
        }
        PdfPCell l = right(new Phrase(totalLabel, BODY_BOLD));
        PdfPCell v = right(new Phrase(totalValue, TOTAL));
        l.setBorder(Rectangle.TOP);
        v.setBorder(Rectangle.TOP);
        l.setBorderColorTop(RULE);
        v.setBorderColorTop(RULE);
        l.setPaddingTop(7);
        v.setPaddingTop(7);
        inner.addCell(l);
        inner.addCell(v);

        outer.addCell(bare(inner));
        add(outer);
        return this;
    }

    /** Closing note in small print. */
    public PdfCanvas footnote(String text) {
        Paragraph p = new Paragraph(text, SMALL);
        p.setSpacingBefore(18);
        p.setLeading(11f);
        add(p);
        return this;
    }

    public PdfCanvas add(Element element) {
        document.add(element);
        return this;
    }

    public byte[] finish() {
        document.close();
        return out.toByteArray();
    }

    @Override
    public void close() {
        if (document.isOpen()) document.close();
    }

    // ---------- Formatting helpers ----------

    /** Indian-format rupees from paise: 250000 → "₹2,500.00". */
    public static String rupees(long paise) {
        boolean negative = paise < 0;
        long abs = Math.abs(paise);
        String whole = groupIndian(abs / 100);
        String frac = String.format("%02d", abs % 100);
        return (negative ? "-" : "") + "\u20B9" + whole + "." + frac;
    }

    /** 12,34,567 rather than 1,234,567 — the only grouping an Indian customer reads as correct. */
    static String groupIndian(long value) {
        String s = Long.toString(value);
        if (s.length() <= 3) return s;
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder sb = new StringBuilder();
        while (rest.length() > 2) {
            sb.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        return rest + sb + "," + last3;
    }

    public static String date(LocalDate d) {
        return d == null ? null : d.format(DATE);
    }

    public static String dateTime(LocalDateTime d) {
        return d == null ? null : d.format(DATETIME);
    }

    public static String percent(int bps) {
        return (bps % 100 == 0) ? (bps / 100) + "%" : String.format("%.2f%%", bps / 100.0);
    }

    // ---------- Low-level cell plumbing ----------

    private PdfPTable grid(float[] widths) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        return table;
    }

    private Paragraph labelled(String label, String body) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label.toUpperCase() + "\n", LABEL));
        p.add(new Chunk(body == null ? "\u2014" : body, BODY));
        p.setLeading(12.5f);
        return p;
    }

    private static PdfPCell bare(Element content) {
        PdfPCell cell = content instanceof PdfPTable t ? new PdfPCell(t) : new PdfPCell((Paragraph) content);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingRight(12);
        return cell;
    }

    private static PdfPCell pad(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(4.5f);
        return cell;
    }

    private static PdfPCell right(Phrase phrase) {
        PdfPCell cell = pad(phrase);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }
}
