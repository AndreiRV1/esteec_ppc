package ro.ppc.filegrid.common;

/**
 * Counties served by Rețele Electrice, keyed by their official two-letter code.
 * The region is derived from the county, so a request can never be filed
 * under the wrong distribution area.
 */
public enum County {
    B("București", Region.MUNTENIA),
    IF("Ilfov", Region.MUNTENIA),
    GR("Giurgiu", Region.MUNTENIA),

    TM("Timiș", Region.BANAT),
    AR("Arad", Region.BANAT),
    CS("Caraș-Severin", Region.BANAT),
    HD("Hunedoara", Region.BANAT),

    CT("Constanța", Region.DOBROGEA),
    TL("Tulcea", Region.DOBROGEA),
    CL("Călărași", Region.DOBROGEA),
    IL("Ialomița", Region.DOBROGEA);

    private final String displayName;
    private final Region region;

    County(String displayName, Region region) {
        this.displayName = displayName;
        this.region = region;
    }

    /** Accepts the two-letter code ("TM") or the display name ("Timiș"), ignoring case. */
    public static County parse(String value) {
        if (value != null) {
            String wanted = value.strip();
            for (County county : values()) {
                if (county.name().equalsIgnoreCase(wanted) || county.displayName.equalsIgnoreCase(wanted)) {
                    return county;
                }
            }
        }
        throw ApiException.badRequest("invalid_county", "Unknown county: " + value);
    }

    /** Parses a county and checks that it belongs to {@code region}. */
    public static County parse(String value, Region region) {
        County county = parse(value);
        if (county.region != region) {
            throw ApiException.badRequest("county_region_mismatch",
                    county.displayName + " is in " + county.region + ", not " + region);
        }
        return county;
    }

    public String displayName() {
        return displayName;
    }

    public Region region() {
        return region;
    }
}
