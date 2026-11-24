import java.nio.file.*;
import java.util.*;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.StatUtils;

/** Незалежна перевірка відмов. */
public final class RefusalAudit {
    private static final Path OUTPUT = Path.of("output/refusal-audit");
    private static final Path ORIGINAL = Path.of(
        "data/main/capacity-S-v2/K18_H10000000_A2000000_R2109_S400000000/replications.csv");
    private static final int K = 18;
    private static final int REPLICATIONS = 200;
    private static final long SEED_BASE = 1_900_000_000L;
    private static final double HORIZON = 10_000_000;
    private static final double WARMUP = 2_000_000;

    private record Counts(long arrivals, long refused) {
    }

    private RefusalAudit() {
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path)));
    }

    private static Counts primary(long seed) {
        DelaySource delays = DelaySource.seeded(seed);
        double arrival = delays.next(0, 30);
        double completion = Double.POSITIVE_INFINITY;
        int pairs = 0;
        long arrivals = 0, refused = 0;
        while (Math.min(arrival, completion) <= HORIZON) {
            if (completion <= arrival) {
                double time = completion;
                pairs--;
                completion = pairs == 0 ? Double.POSITIVE_INFINITY : time + delays.next(1, 30);
            } else {
                double time = arrival;
                if (time > WARMUP) {
                    arrivals++;
                }
                if (pairs < K + 1) {
                    pairs++;
                    if (pairs == 1) {
                        completion = time + delays.next(1, 30);
                    }
                } else if (time > WARMUP) {
                    refused++;
                }
                arrival = time + delays.next(0, 30);
            }
        }
        return new Counts(arrivals, refused);
    }

    private static Map<String, Object> interval(double[] arrivals, double[] refused) {
        double[] rates = new double[arrivals.length];
        for (int i = 0; i < rates.length; i++) rates[i] = refused[i] / arrivals[i];
        double estimate = StatUtils.mean(rates);
        double standardError = Math.sqrt(StatUtils.variance(rates) / rates.length);
        double halfWidth = new TDistribution(rates.length - 1)
                .inverseCumulativeProbability(.975) * standardError;
        return Map.of("n", arrivals.length, "estimate", estimate,
            "standardError", standardError, "halfWidth", halfWidth,
            "low", estimate - halfWidth, "high", estimate + halfWidth,
            "analytic", .05, "standardizedDeviation", (estimate - .05) / standardError);
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        if (Files.exists(OUTPUT.resolve("plan.json"))) {
            throw new IllegalStateException("Audit already planned; preserve its results");
        }
        Json.write(OUTPUT.resolve("plan.json"), Map.of(
            "K", K, "horizon", HORIZON, "warmup", WARMUP,
            "replications", REPLICATIONS, "seedBase", SEED_BASE,
            "historicalReplications", List.of(0, 1054, 2108),
            "algorithm", "independent primary queue event calendar; same delay streams",
            "purpose", "fixed diagnostic series; never pooled with original study",
            "sourceSha256", sha256(Path.of("src/RefusalAudit.java")),
            "originalCsvSha256", sha256(ORIGINAL)));
        List<String> rows = Files.readAllLines(ORIGINAL);
        int n = rows.size() - 1;
        double[] originalArrivals = new double[n], originalRefused = new double[n];
        for (int index = 0; index < n; index++) {
            String[] cells = rows.get(index + 1).split(",");
            originalArrivals[index] = Double.parseDouble(cells[2]);
            originalRefused[index] = Double.parseDouble(cells[3]);
        }
        var replay = new ArrayList<Object>();
        for (int index : new int[]{0, 1054, 2108}) {
            String[] cells = rows.get(index + 1).split(",");
            Counts counts = primary(Long.parseLong(cells[1]));
            boolean equal = counts.arrivals == originalArrivals[index]
                && counts.refused == originalRefused[index];
            replay.add(Map.of("replication", index, "arrivals", counts.arrivals,
                "refused", counts.refused, "matchesOriginal", equal));
            if (!equal) {
                throw new IllegalStateException("Independent counter mismatch: " + index);
            }
        }
        var independentInterval = interval(originalArrivals, originalRefused);
        var current = ExperimentStatistics.refusal(originalArrivals, originalRefused);
        double estimateDifference = Math.abs(current.estimate()
            - (double) independentInterval.get("estimate"));
        double halfWidthDifference = Math.abs(current.halfWidth()
            - (double) independentInterval.get("halfWidth"));
        if (estimateDifference > 1e-14 || halfWidthDifference > 1e-14) {
            throw new IllegalStateException("Independent interval mismatch");
        }
        double[] arrivals = new double[REPLICATIONS], refused = new double[REPLICATIONS];
        var csv = new StringBuilder("replication,seed,arrived_pairs,refused_pairs\n");
        for (int index = 0; index < REPLICATIONS; index++) {
            Counts counts = primary(SEED_BASE + index);
            arrivals[index] = counts.arrivals;
            refused[index] = counts.refused;
            csv.append(index).append(',').append(SEED_BASE + index).append(',')
                .append(counts.arrivals).append(',').append(counts.refused).append('\n');
        }
        Files.writeString(OUTPUT.resolve("replications.csv"), csv);
        Json.write(OUTPUT.resolve("summary.json"), Map.of(
            "originalRecomputed", independentInterval, "historicalReplay", replay,
            "estimateDifference", estimateDifference, "halfWidthDifference", halfWidthDifference,
            "diagnosticPrimaryOnly", interval(arrivals, refused),
            "limitations", "three matched historical paths; no proof of absence of every bias",
            "originalDataUnchanged", sha256(ORIGINAL)));
        System.out.println(Json.encode(independentInterval));
        System.out.println(Json.encode(interval(arrivals, refused)));
        System.out.println("Independent historical paths: 3/3 matched");
    }
}
