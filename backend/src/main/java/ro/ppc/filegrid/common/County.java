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

    public String displayName() {
        return displayName;
    }

    public Region region() {
        return region;
    }
}
