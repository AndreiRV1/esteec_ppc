package ro.filegrid.simulator.fleet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import ro.filegrid.simulator.config.SimulatorProperties;

/** Builds the meter fleet deterministically from a seed: same seed + settings, same meters. */
public final class FleetGenerator {

    private static final String[] FIRMWARE = {"2.3.7", "2.4.0", "2.4.1"};
    private static final double[] FIRMWARE_SHARE = {0.15, 0.25, 0.60};
    /** Max coordinate jitter around the locality centre, in degrees (~3 km). */
    private static final double COORD_JITTER = 0.03;

    private FleetGenerator() {}

    public static Fleet generate(long seed, SimulatorProperties.Meters config) {
        SplittableRandom rng = new SplittableRandom(seed);
        Region[] regions = Region.values();
        double[] regionWeights = new double[regions.length];
        for (int i = 0; i < regions.length; i++) {
            regionWeights[i] = config.regionWeights().getOrDefault(regions[i], 0.0);
        }

        Set<Integer> usedNumbers = new HashSet<>(config.count() * 2);
        List<Meter> meters = new ArrayList<>(config.count());
        for (int i = 0; i < config.count(); i++) {
            Region region = regions[pick(regionWeights, rng.nextDouble())];
            Region.Locality locality = region.pickLocality(rng.nextInt(Integer.MAX_VALUE));

            int number;
            do {
                number = 1 + rng.nextInt(SimulatorProperties.MAX_FLEET_NUMBER);
            } while (!usedNumbers.add(number));

            LoadProfile profile = pickProfile(rng.nextDouble());
            int baseWatts = profile.minBaseWatts() + rng.nextInt(profile.maxBaseWatts() - profile.minBaseWatts() + 1);
            long registerWh = (long) baseWatts * (2_000 + rng.nextInt(60_000)); // a few years of usage
            meters.add(new Meter(
                    meterId(region, number), region, locality,
                    round6(locality.latitude() + (rng.nextDouble() * 2 - 1) * COORD_JITTER),
                    round6(locality.longitude() + (rng.nextDouble() * 2 - 1) * COORD_JITTER),
                    profile, baseWatts,
                    (int) Math.round(rng.nextGaussian() * 25), // +-2.5 V per-site offset
                    FIRMWARE[pick(FIRMWARE_SHARE, rng.nextDouble())],
                    1 + rng.nextInt(60_000),
                    registerWh));
        }
        return new Fleet(List.copyOf(meters), seed, fingerprint(meters));
    }

    public static String meterId(Region region, int number) {
        return "MTR-%c-%07d".formatted(region.letter(), number);
    }

    private static LoadProfile pickProfile(double roll) {
        LoadProfile[] all = LoadProfile.values();
        double[] shares = new double[all.length];
        for (int i = 0; i < all.length; i++) shares[i] = all[i].share();
        return all[pick(shares, roll)];
    }

    private static int pick(double[] weights, double roll) {
        double total = 0;
        for (double w : weights) total += w;
        double r = roll * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0 && weights[i] > 0) return i;
        }
        for (int i = weights.length - 1; i >= 0; i--) if (weights[i] > 0) return i;
        throw new IllegalArgumentException("all weights are 0");
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static String fingerprint(List<Meter> meters) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (Meter m : meters) {
                sha.update(m.id().getBytes(StandardCharsets.US_ASCII));
                sha.update((byte) '\n');
            }
            return HexFormat.of().formatHex(sha.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
