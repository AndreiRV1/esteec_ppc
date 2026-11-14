package ro.filegrid.simulator.fleet;

import java.util.List;

/**
 * Distribution areas (matches the {@code region} table). Localities carry
 * approximate coordinates and a rough population weight used when placing meters.
 */
public enum Region {

    MUNTENIA('M', List.of(
            new Locality("București", "București", 44.4268, 26.1025, 60),
            new Locality("Ilfov", "Voluntari", 44.4925, 26.1860, 4),
            new Locality("Ilfov", "Otopeni", 44.5500, 26.0700, 2),
            new Locality("Ilfov", "Buftea", 44.5610, 25.9480, 2),
            new Locality("Ilfov", "Popești-Leordeni", 44.3800, 26.1700, 3),
            new Locality("Giurgiu", "Giurgiu", 43.9037, 25.9699, 4),
            new Locality("Giurgiu", "Bolintin-Vale", 44.4470, 25.7570, 1),
            new Locality("Giurgiu", "Mihăilești", 44.3220, 25.9070, 1))),

    BANAT('B', List.of(
            new Locality("Timiș", "Timișoara", 45.7489, 21.2087, 25),
            new Locality("Timiș", "Lugoj", 45.6886, 21.9031, 3),
            new Locality("Timiș", "Sânnicolau Mare", 46.0720, 20.6290, 1),
            new Locality("Timiș", "Dumbrăvița", 45.7970, 21.2440, 2),
            new Locality("Arad", "Arad", 46.1866, 21.3123, 12),
            new Locality("Arad", "Lipova", 46.0890, 21.6920, 1),
            new Locality("Arad", "Ineu", 46.4260, 21.8400, 1),
            new Locality("Caraș-Severin", "Reșița", 45.3008, 21.8892, 5),
            new Locality("Caraș-Severin", "Caransebeș", 45.4214, 22.2219, 2),
            new Locality("Caraș-Severin", "Oravița", 45.0390, 21.6850, 1),
            new Locality("Hunedoara", "Deva", 45.8667, 22.9000, 4),
            new Locality("Hunedoara", "Hunedoara", 45.7500, 22.9000, 4),
            new Locality("Hunedoara", "Petroșani", 45.4122, 23.3733, 3))),

    DOBROGEA('D', List.of(
            new Locality("Constanța", "Constanța", 44.1598, 28.6348, 20),
            new Locality("Constanța", "Mangalia", 43.8170, 28.5830, 3),
            new Locality("Constanța", "Medgidia", 44.2500, 28.2610, 3),
            new Locality("Constanța", "Năvodari", 44.3210, 28.6130, 2),
            new Locality("Tulcea", "Tulcea", 45.1716, 28.7914, 5),
            new Locality("Tulcea", "Babadag", 44.8970, 28.7120, 1),
            new Locality("Tulcea", "Măcin", 45.2440, 28.1350, 1),
            new Locality("Călărași", "Călărași", 44.2000, 27.3333, 4),
            new Locality("Călărași", "Oltenița", 44.0870, 26.6370, 2),
            new Locality("Ialomița", "Slobozia", 44.5640, 27.3660, 3),
            new Locality("Ialomița", "Fetești", 44.4150, 27.8230, 2),
            new Locality("Ialomița", "Urziceni", 44.7180, 26.6410, 1)));

    private final char letter;
    private final List<Locality> localities;
    private final int totalWeight;

    Region(char letter, List<Locality> localities) {
        this.letter = letter;
        this.localities = localities;
        this.totalWeight = localities.stream().mapToInt(Locality::weight).sum();
    }

    /** Letter used in meter ids: MTR-&lt;letter&gt;-0000000. */
    public char letter() {
        return letter;
    }

    public List<Locality> localities() {
        return localities;
    }

    Locality pickLocality(int roll) {
        int r = Math.floorMod(roll, totalWeight);
        for (Locality l : localities) {
            r -= l.weight();
            if (r < 0) return l;
        }
        throw new IllegalStateException("unreachable");
    }

    public record Locality(String county, String name, double latitude, double longitude, int weight) {}
}
