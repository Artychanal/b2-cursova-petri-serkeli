import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.apache.commons.math3.distribution.TDistribution;

/** Порівняння варіантів ділянки. */
public final class Chapter5Comparison {
    private static final String[] METRICS = {
        "L_full", "L_secondary", "L_in", "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end"
    };

    public static void main(String[] args) throws Exception {
        Path output = Path.of("output/comparison/chapter5/H1000000_R40");
        if (args.length == 1 && args[0].equals("--effects")) {
            printEffects(Path.of("data/comparison/chapter5/H1000000_R40/replications.csv"));
            return;
        }
        Files.createDirectories(output);
        double horizon = 1_000_000;
        int n = 40;
        var records = new ArrayList<Object>();
        var reports = new ArrayList<Object>();
        var csv = new StringBuilder("variant,K,replication,seed,T_min,warmup_min,arrived_pairs,refused_pairs,L_full,L_secondary,L_in,U_primary,U_secondary,U_full,throughput,Q_full_end\n");
        for (int k:new int[]{
                -1, 18
        }) {
            var values = new LinkedHashMap<String, double[]>();
            for (String metric:METRICS){
                values.put(metric, new double[n]);
            }
            double[] arrivals = new double[n], refusals = new double[n];
            long seedBase = k<0?600000000L:600100000L;
            for (int r = 0;r<n;r++) {
                long seed = seedBase+r;
                var config = ModelConfig.baseline(horizon, seed, 0);
                var delays = DelaySource.seeded(seed);
                var net = k<0?B2NetFactory.baseline(config, delays):B2NetFactory.buffered(config, delays, k);
                var result = new SimulationRunner(net, config).run(null);
                var row = result.summary();
                records.add(row);
                arrivals[r] = result.arrivedPairsInWindow();
                refusals[r] = result.refusedPairsInWindow();
                csv.append(k<0?"baseline":"buffered").append(',').append(Math.max(0, k)).append(',').append(r).append(',').append(seed)
                    .append(',').append(horizon).append(',').append(0).append(',').append((long)arrivals[r]).append(',').append((long)refusals[r]);
                for (String metric:METRICS) {
                    double value = ((Number)row.get(metric)).doubleValue();
                    values.get(metric)[r] = value;
                    csv.append(',').append(value);
                }
                csv.append('\n');
            }
            var estimates = new ArrayList<Object>();
            estimates.add(ExperimentStatistics.refusal(arrivals, refusals).json());
            for (String metric:METRICS){
                estimates.add(ExperimentStatistics.meanEstimate(metric, values.get(metric)).json());
            }
            reports.add(Map.of("variant", k<0?"baseline":"buffered", "K", Math.max(0, k), "n", n, "T_min", horizon,
                "warmup_min", 0, "seedBase", seedBase, "lastSeed", seedBase+n-1, "estimates", estimates));
            System.out.println("Chapter 5 comparison "+(k<0?"N0":"K="+k)+": "+n+" runs complete");
        }
        Files.writeString(output.resolve("replications.csv"), csv.toString(), StandardCharsets.UTF_8);
        Json.write(output.resolve("runs.json"), records);
        Json.write(output.resolve("summary.json"), Map.of("kind", "independent_finite_horizon_comparison", "cases", reports,
            "provenance", RunProvenance.collect(), "notes", List.of("Both variants start empty, T=1000000 min, no burn-in",
            "Means are finite-horizon responses, not stationary means for N0",
            "This trial comparison does not replace the precision plan or main series",
            "Variants use disjoint seed ranges, no common-random-number pairing")));
    }

    private static void printEffects(Path csv) throws Exception {
        var rows = Files.readAllLines(csv, StandardCharsets.UTF_8);
        var columns = Arrays.asList(rows.getFirst().split(","));
        var groups = new HashMap<String, List<String[]>>();
        var seeds = new HashSet<String>();
        for (String line : rows.subList(1, rows.size())) {
            String[] row = line.split(",");
            String variant = row[columns.indexOf("variant")];
            if (!seeds.add(row[columns.indexOf("seed")])
                    || Double.parseDouble(row[columns.indexOf("T_min")]) != 1_000_000
                    || Double.parseDouble(row[columns.indexOf("warmup_min")]) != 0) {
                throw new IllegalArgumentException("Expected independent runs on the common horizon");
            }
            groups.computeIfAbsent(variant, key -> new ArrayList<>()).add(row);
        }
        var baseline = groups.getOrDefault("baseline", List.of());
        var buffered = groups.getOrDefault("buffered", List.of());
        if (baseline.size() != 40 || buffered.size() != 40 || groups.size() != 2) {
            throw new IllegalArgumentException("Expected 40 runs per variant");
        }
        System.out.println("metric,buffered_minus_baseline,lower95,upper95,df");
        printDifference(refusal(columns, baseline), refusal(columns, buffered), 100);
        for (String metric : List.of("L_full", "L_secondary")) {
            printDifference(
                ExperimentStatistics.meanEstimate(metric, values(columns, baseline, metric)),
                ExperimentStatistics.meanEstimate(metric, values(columns, buffered, metric)),
                1);
        }
    }

    private static double[] values(List<String> columns, List<String[]> rows, String metric) {
        int index = columns.indexOf(metric);
        return rows.stream().mapToDouble(row -> Double.parseDouble(row[index])).toArray();
    }

    private static ExperimentStatistics.Estimate refusal(List<String> columns, List<String[]> rows) {
        return ExperimentStatistics.refusal(values(columns, rows, "arrived_pairs"),
            values(columns, rows, "refused_pairs"));
    }

    private static void printDifference(ExperimentStatistics.Estimate baseline,
        ExperimentStatistics.Estimate buffered, double scale) {
        double delta = scale * (buffered.estimate() - baseline.estimate());
        double v0 = baseline.sd() * baseline.sd() / baseline.n();
        double v18 = buffered.sd() * buffered.sd() / buffered.n();
        double variance = v0 + v18;
        double df = variance * variance
        / (v0 * v0 / (baseline.n() - 1) + v18 * v18 / (buffered.n() - 1));
        double halfWidth = scale * Math.sqrt(variance) * new TDistribution(df)
            .inverseCumulativeProbability((1 + ExperimentStatistics.BETA) / 2);
        System.out.printf(Locale.ROOT, "%s,%.9f,%.9f,%.9f,%.5f%n",
            baseline.metric(), delta, delta - halfWidth, delta + halfWidth, df);
    }
}
