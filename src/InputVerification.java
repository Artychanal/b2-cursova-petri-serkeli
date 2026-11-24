import java.nio.file.*;
import java.util.*;

/** Варіювання вхідних змінних. */
public final class InputVerification {
    private static final int RUNS = 20;
    private static final double HORIZON = 200_000;
    private static final long SEED_BASE = 920_000_000L;
    private static final String[] METRICS = {
        "pRefusal", "L_full", "L_secondary", "L_in", "U_primary", "U_secondary",
        "U_full", "throughput", "Q_full_end", "Q_full_max", "N_full_stage",
        "Q_in_max", "Q_secondary_max", "L_mid"
    };

    private static final Map<String, Map<String, Object>> REFERENCES = new HashMap<>();

    private InputVerification() { }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("output/input-verification");
        Files.createDirectories(output);
        REFERENCES.clear();
        var csv = new StringBuilder("variant,factor,value,replication,seed");
        for (String metric : METRICS) csv.append(',').append(metric);
        csv.append('\n');
        var means = new StringBuilder("variant,factor,value");
        for (String metric : METRICS) means.append(',').append(metric);
        means.append(",analytic_refusal,passed\n");
        int cases = 0;
        for (boolean buffered : new boolean[]{false, true}) {
            for (int factor = 0; factor < 4; factor++) {
                double[] levels = factor == 3 ? new double[]{80, 100, 120}
                        : new double[]{20, 30, 40};
                for (double level : levels) {
                    checkCase(csv, means, buffered, factor, level, 18);
                    cases++;
                }
            }
        }
        for (int capacity : new int[]{0, 5, 18, 38}) {
            checkCase(csv, means, true, 4, capacity, capacity);
            cases++;
        }
        Files.writeString(output.resolve("replications.csv"), csv);
        Files.writeString(output.resolve("summary.csv"), means);
        System.out.println("Input variation: " + cases + " cases, " + cases * RUNS + " runs passed");
    }

    private static void checkCase(StringBuilder csv, StringBuilder means, boolean buffered,
            int factor, double level, int capacity) throws Exception {
        String[] factors = {"arrivalMean", "primaryMean", "secondaryMean", "fullMean", "K"};
        double[] times = {30, 30, 30, 100};
        if (factor < 4) times[factor] = level;
        String variant = buffered ? "buffered" : "baseline";
        double[] sums = new double[METRICS.length];
        for (int run = 0; run < RUNS; run++) {
            long seed = SEED_BASE + run;
            var config = new ModelConfig(times[0], times[1], times[2], times[3], HORIZON, 0, seed);
            var delays = DelaySource.seeded(seed);
            var net = buffered ? B2NetFactory.buffered(config, delays, capacity)
                    : B2NetFactory.baseline(config, delays);
            var result = new SimulationRunner(net, config).run(null);
            var row = result.summary();
            String key = variant + run;
            if (factor == 0 && level == 30) REFERENCES.put(key, row);
            if (factor == 2 || factor == 3) {
                var reference = REFERENCES.get(key);
                var unchanged = new ArrayList<>(List.of("pRefusal", "U_primary", "L_in"));
                unchanged.addAll(factor == 2 ? List.of("L_full", "U_full")
                        : List.of("L_secondary", "U_secondary"));
                for (String metric : unchanged) {
                    double expected = ((Number) reference.get(metric)).doubleValue();
                    double actual = ((Number) row.get(metric)).doubleValue();
                    if (Math.abs(actual - expected) > 1e-9 * Math.max(1, expected)) {
                        throw new IllegalStateException("Unexpected upstream influence: " + metric);
                    }
                }
            }
            csv.append(variant).append(',').append(factors[factor]).append(',').append(level)
                    .append(',').append(run).append(',').append(seed);
            for (int i = 0; i < METRICS.length; i++) {
                double value = ((Number) row.get(METRICS[i])).doubleValue();
                if (!Double.isFinite(value) || value < 0) throw new IllegalStateException(METRICS[i]);
                sums[i] += value;
                csv.append(',').append(value);
            }
            csv.append('\n');
        }
        double rho = times[1] / times[0];
        int totalCapacity = buffered ? capacity + 1 : 1;
        double denominator = 0;
        for (int i = 0; i <= totalCapacity; i++) denominator += Math.pow(rho, i);
        double analytic = Math.pow(rho, totalCapacity) / denominator;
        boolean passed = Math.abs(sums[0] / RUNS - analytic) < .02;
        means.append(variant).append(',').append(factors[factor]).append(',').append(level);
        for (double sum : sums) means.append(',').append(sum / RUNS);
        means.append(',').append(analytic).append(',').append(passed).append('\n');
        if (!passed) throw new IllegalStateException("Refusal differs from analytical reference");
    }
}
