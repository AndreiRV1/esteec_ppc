package ro.ppc.filegrid.extraction;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ro.ppc.filegrid.common.County;
import ro.ppc.filegrid.common.Region;

/**
 * Reads form fields from document text (PDF text layer or OCR). Forms print one "label  value" pair per
 * line, so a field is the rest of the first line that starts with one of its labels. Labels are matched
 * without diacritics or case, because OCR often drops them. Keys are the web form's field paths; a field
 * that is missing or unreadable is left out, never guessed.
 */
final class DocumentFields {

    private static final Pattern DATE = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})");
    private static final Pattern TIME = Pattern.compile("\\b(\\d{1,2})[:.](\\d{2})\\b");
    private static final Pattern PERMIT_NO = Pattern.compile(
            "(?i)\\bnr\\.?\\s*([A-Z0-9][A-Z0-9./-]*)\\s+din\\s+(\\d{1,2}[./]\\d{1,2}[./]\\d{4})");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    // OCR reads "@" as Q, © or &: "ion.demoQexample.ro".
    private static final Pattern OCR_EMAIL = Pattern.compile("^([\\w.+-]+)[Q©®&]([\\w-]+(\\.[\\w-]+)+)$");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{5,19}");
    private static final int WRAPPED_LINE_CHARS = 40;
    // Sign-off lines that follow the last table row of a report.
    private static final Pattern SIGN_OFF = Pattern.compile("^(dispecer|sef |semnatur|intocmit|aprobat|specimen|data:).*");
    private static final Pattern HEADING = Pattern.compile("^\\d+\\.\\s.*");

    private static final String[] FAULT_LABELS = {"regiune", "judet", "localitate", "cod statie", "momentul avariei",
            "data si ora avariei", "alimentare restabilita", "restabilita la", "severitate", "clienti afectati", "cauza",
            "descriere"};

    private DocumentFields() {
    }

    /** Connection request (cerere) and permit (aviz / certificat de urbanism). */
    static Map<String, String> connection(String text) {
        List<Line> lines = lines(text);
        String all = String.join("\n", lines.stream().map(Line::text).toList());
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "applicantType", applicantType(value(lines, "tip solicitant")));
        put(out, "applicant.fullName", name(value(lines, "nume si prenume", "denumire")));
        put(out, "applicant.identityNumber", identity(value(lines, "cnp", "cui", "cod unic de inregistrare", "cod fiscal")));
        put(out, "applicant.email", email(value(lines, "e-mail", "email")));
        put(out, "applicant.phone", first(PHONE, value(lines, "telefon")));
        put(out, "applicant.address", value(lines, "domiciliu", "sediu social", "sediu"));
        put(out, "requestType", requestType(value(lines, "tipul cererii", "tip solicitare")));
        put(out, "consumptionPlace.county", county(value(lines, "judet")));
        put(out, "consumptionPlace.locality", value(lines, "localitate"));
        put(out, "consumptionPlace.address", value(lines, "adresa locului de consum", "adresa"));
        put(out, "requestedPowerKw", number(value(lines, "putere solicitata", "putere ceruta", "putere aprobata")));
        put(out, "permit.type", permitType(fold(all)));
        Matcher permit = PERMIT_NO.matcher(all);
        boolean hasPermitNo = permit.find();
        put(out, "permit.number", hasPermitNo ? permit.group(1) : null);
        String issued = date(value(lines, "data emiterii"));
        put(out, "permit.issuedOn", issued != null ? issued : hasPermitNo ? date(permit.group(2)) : null);
        put(out, "permit.issuer", value(lines, "emitent", "emis de"));
        put(out, "permit.validUntil", date(value(lines, "valabil pana la", "valabilitate")));
        return out;
    }

    /** Fault report (raport de avarie). */
    static Map<String, String> fault(String text) {
        List<Line> lines = lines(text);
        Map<String, String> out = new LinkedHashMap<>();
        County county = countyOf(value(lines, "judet"));
        Region region = region(value(lines, "regiune", "regiunea"));
        // The county decides the region (as on upload), so the two can never disagree.
        put(out, "region", county != null ? county.region().name() : region == null ? null : region.name());
        put(out, "county", county == null ? null : county.displayName());
        put(out, "locality", value(lines, "localitate"));
        String code = value(lines, "cod statie", "statia");
        put(out, "substationCode", code == null ? null : code.replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
        put(out, "occurredAt", dateTime(value(lines, "momentul avariei", "data si ora avariei", "data avariei")));
        put(out, "restoredAt", dateTime(value(lines, "alimentare restabilita", "restabilita la")));
        put(out, "severity", severity(value(lines, "severitate", "gravitate")));
        put(out, "affectedCustomers", integer(value(lines, "clienti afectati", "consumatori afectati")));
        put(out, "cause", value(lines, "cauza"));
        put(out, "description", description(lines));
        return out;
    }

    // ---- lines and labels

    /** One line of text, and the same line folded (no diacritics, lower case) for label matching. */
    private record Line(String text, String folded) {
    }

    private static List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        for (String raw : Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC).split("\\R")) {
            // Table borders come out of OCR as "|"; folding keeps the length, so offsets match the original.
            String line = raw.replace('|', ' ').replaceAll("\\s+", " ").strip();
            lines.add(new Line(line, fold(line)));
        }
        return lines;
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** The rest of the first line that starts with one of the labels (tried in order). */
    private static String value(List<Line> lines, String... labels) {
        for (String label : labels) {
            for (Line line : lines) {
                if (startsWithLabel(line.folded(), label)) {
                    return clean(line.text().substring(label.length()));
                }
            }
        }
        return null;
    }

    private static boolean startsWithLabel(String folded, String label) {
        return folded.startsWith(label) && (folded.length() == label.length() || !Character.isLetter(folded.charAt(label.length())));
    }

    /** Strips separators around a value; a dash alone ("–") means the field is empty. */
    private static String clean(String value) {
        String v = value.replaceAll("^[\\s:.\\-–—]+", "").strip();
        return v.isEmpty() || v.matches("[\\p{Punct}–—\\s]+") ? null : v;
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value.length() > 2000 ? value.substring(0, 2000) : value);
        }
    }

    // ---- value parsers

    private static String first(Pattern pattern, String value) {
        if (value == null) {
            return null;
        }
        Matcher m = pattern.matcher(value);
        return m.find() ? m.group().strip() : null;
    }

    private static String applicantType(String value) {
        if (value == null) {
            return null;
        }
        String f = fold(value);
        return f.contains("juridic") ? "PERSOANA_JURIDICA" : f.contains("fizic") ? "PERSOANA_FIZICA" : null;
    }

    /** Names are capitalized, so a word starting with a lower-case "l" is OCR's reading of "I" ("lon" = "Ion"). */
    private static String name(String value) {
        return value == null ? null : value.replaceAll("\\bl(?=\\p{Ll})", "I");
    }

    private static String identity(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)(RO)?\\d{2,13}").matcher(value.replaceAll("\\s+", ""));
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
    }

    private static String email(String value) {
        if (value == null) {
            return null;
        }
        String found = first(EMAIL, value);
        if (found != null) {
            return found;
        }
        Matcher m = OCR_EMAIL.matcher(value.replaceAll("\\s+", ""));
        return m.matches() ? m.group(1) + "@" + m.group(2) : null;
    }

    private static String requestType(String value) {
        if (value == null) {
            return null;
        }
        String f = fold(value);
        if (f.contains("spor")) return "SPOR_PUTERE";
        if (f.contains("separ")) return "SEPARARE";
        if (f.contains("actualiz")) return "ACTUALIZARE_DATE";
        if (f.contains("nou")) return "LOC_CONSUM_NOU";
        return null;
    }

    private static String permitType(String foldedText) {
        int aviz = foldedText.indexOf("aviz de amplasament");
        int cu = foldedText.indexOf("certificat de urbanism");
        if (aviz < 0 && cu < 0) return null;
        return cu < 0 || (aviz >= 0 && aviz < cu) ? "AVIZ_AMPLASAMENT" : "CERTIFICAT_URBANISM";
    }

    private static County countyOf(String value) {
        if (value == null) {
            return null;
        }
        String f = fold(value);
        // Longest name first, and a word boundary after it, so trailing OCR noise does not matter.
        return Arrays.stream(County.values())
                .sorted(Comparator.comparingInt((County c) -> c.displayName().length()).reversed())
                .filter(c -> startsWithLabel(f, fold(c.displayName())) || f.equals(c.name().toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
    }

    private static String county(String value) {
        County county = countyOf(value);
        return county == null ? null : county.displayName();
    }

    private static Region region(String value) {
        if (value == null) {
            return null;
        }
        String f = fold(value);
        return Stream.of(Region.values()).filter(r -> f.startsWith(r.name().toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private static String severity(String value) {
        if (value == null) {
            return null;
        }
        String f = fold(value);
        if (f.startsWith("critic")) return "CRITICAL";
        if (f.startsWith("ridicat") || f.startsWith("mare") || f.startsWith("high")) return "HIGH";
        if (f.startsWith("medi") || f.startsWith("moderat")) return "MEDIUM";
        if (f.startsWith("scazut") || f.startsWith("redus") || f.startsWith("low")) return "LOW";
        return null;
    }

    /** "9,5 kW (monofazat)" -> "9.5". */
    private static String number(String value) {
        String n = first(Pattern.compile("\\d+(?:[.,]\\d+)?"), value);
        return n == null ? null : n.replace(',', '.');
    }

    /** "1.240" or "1 240" -> "1240". */
    private static String integer(String value) {
        String n = first(Pattern.compile("\\d[\\d. ]*"), value);
        return n == null ? null : n.replaceAll("\\D", "");
    }

    /** "01.11.2026" -> "2026-11-01" (the date input's format). */
    static String date(String value) {
        LocalDate d = localDate(value);
        return d == null ? null : d.toString();
    }

    /** "14.11.2026 17:30" -> "2026-11-14T17:30" (local time, as typed in a datetime-local input). Needs a time. */
    static String dateTime(String value) {
        LocalDate d = localDate(value);
        if (d == null) {
            return null;
        }
        Matcher dm = DATE.matcher(value);
        dm.find();
        Matcher t = TIME.matcher(value.substring(dm.end()));
        if (!t.find()) {
            return null;
        }
        try {
            return d + "T" + LocalTime.of(Integer.parseInt(t.group(1)), Integer.parseInt(t.group(2)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDate localDate(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = DATE.matcher(value);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** The "Descriere" value plus the lines that continue it (until a blank line, a heading or another label). */
    private static String description(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!startsWithLabel(lines.get(i).folded(), "descriere")) {
                continue;
            }
            StringBuilder text = new StringBuilder(lines.get(i).text().substring("descriere".length()));
            String previous = text.toString();
            for (int j = i + 1; j < lines.size(); j++) {
                Line next = lines.get(j);
                // Text wraps only when the line above filled the cell; after a short line the cell has ended.
                if (previous.strip().length() < WRAPPED_LINE_CHARS || next.text().isEmpty() || HEADING.matcher(next.text()).matches()
                        || SIGN_OFF.matcher(next.folded()).matches()
                        || Arrays.stream(FAULT_LABELS).anyMatch(l -> startsWithLabel(next.folded(), l))) {
                    break;
                }
                text.append(' ').append(next.text());
                previous = next.text();
            }
            return clean(text.toString());
        }
        return null;
    }
}
