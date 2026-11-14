package ro.ppc.filegrid.connection;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;

/** Checksum validation for Romanian personal (CNP) and company (CUI/CIF) identifiers. */
public final class IdentityNumbers {

    private static final int[] CNP_KEY = {2, 7, 9, 1, 4, 6, 3, 5, 8, 2, 7, 9};
    private static final int[] CUI_KEY = {7, 5, 3, 2, 1, 7, 5, 3, 2};

    private IdentityNumbers() {
    }

    /** Normalizes user input: strips spaces and, for CUI, the optional "RO" VAT prefix. */
    public static String normalize(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return compact.startsWith("RO") ? compact.substring(2) : compact;
    }

    /** 13 digits: S YY MM DD JJ NNN C, with a valid date and control digit. */
    public static boolean isValidCnp(String raw) {
        String cnp = normalize(raw);
        if (!cnp.matches("[1-9]\\d{12}")) {
            return false;
        }
        int sex = cnp.charAt(0) - '0';
        int century = switch (sex) {
            case 1, 2 -> 1900;
            case 3, 4 -> 1800;
            case 5, 6 -> 2000;
            default -> -1; // 7, 8, 9: residents; century cannot be inferred
        };
        int yy = Integer.parseInt(cnp.substring(1, 3));
        int mm = Integer.parseInt(cnp.substring(3, 5));
        int dd = Integer.parseInt(cnp.substring(5, 7));
        try {
            LocalDate.of(century > 0 ? century + yy : 2000, mm, dd);
        } catch (DateTimeException e) {
            return false;
        }
        int county = Integer.parseInt(cnp.substring(7, 9));
        if (county < 1 || (county > 52 && county != 70)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (cnp.charAt(i) - '0') * CNP_KEY[i];
        }
        int control = sum % 11 == 10 ? 1 : sum % 11;
        return control == cnp.charAt(12) - '0';
    }

    /** 2-10 digits, the last being a control digit over the others (key 753217532). */
    public static boolean isValidCui(String raw) {
        String cui = normalize(raw);
        if (!cui.matches("[1-9]\\d{1,9}")) {
            return false;
        }
        String body = cui.substring(0, cui.length() - 1);
        int offset = CUI_KEY.length - body.length();
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += (body.charAt(i) - '0') * CUI_KEY[offset + i];
        }
        int control = (sum * 10) % 11;
        if (control == 10) {
            control = 0;
        }
        return control == cui.charAt(cui.length() - 1) - '0';
    }
}
