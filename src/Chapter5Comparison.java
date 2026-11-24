import java.nio.file.*;
import java.util.*;

/** Порівняння варіантів ділянки. */
public final class Chapter5Comparison {
    private static final double HORIZON = 1_000_000;
    private static final int PILOT_RUNS = 20;
    private static final int MIN_MAIN_RUNS = 40;
    private static final Path OUTPUT = Path.of("output/comparison/chapter5");
    private static final Path DATA = Path.of("data/comparison/chapter5");
    private static final String[] VARIANTS = {"baseline", "buffered"};
    private static final String[] TARGETS = {"pRefusal", "L_full", "throughput"};
    private static final String[] METRICS = {
        "L_full", "L_secondary", "L_in", "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end"
    };
    private static final String HEADER = "variant,K,replication,seed,T_min,warmup_min,arrived_pairs,refused_pairs,"
            + String.join(",", METRICS);
    private static final List<String> COLUMNS = List.of(HEADER.split(","));

    private record Plan(int runs, Map<String, Map<String, Double>> epsilon, List<Object> targets) { }

    private Chapter5Comparison() { }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        if (args.length > 0 && args[0].equals("--effects")) {
            var pilot = read(DATA.resolve("pilot.csv"));
            var plan = plan(pilot);
            summarize(read(Path.of(args.length > 1 ? args[1] : DATA.resolve("replications.csv").toString())), plan);
            return;
        }
        var pilot = new ArrayList<String[]>();
        for (String variant : VARIANTS) {
            long seedBase = variant.equals("baseline") ? 1_800_000_000L : 1_800_100_000L;
            for (int r = 0; r < PILOT_RUNS; r++) pilot.add(simulate(variant, r, seedBase + r));
        }
        write(OUTPUT.resolve("pilot.csv"), pilot);
        var plan = plan(pilot);
        Json.write(OUTPUT.resolve("plan.json"), Map.of(
                "kind", "finite_horizon_comparison_with_pilot", "horizon", HORIZON,
                "warmup", 0, "beta", .95, "pilotRunsPerVariant", PILOT_RUNS,
                "mainRunsPerVariant", plan.runs(), "targets", plan.targets(),
                "notes", List.of("Precision tolerances follow the pilot-based rules",
                    "Pilot and comparison samples use disjoint seed ranges",
                    "Pilot observations are excluded from the main sample",
                    "Other reported metrics are descriptive; no precision target is claimed for them"),
                "provenance", RunProvenance.collect()));

        // Збережені результати моделювання.
        var historical = read(DATA.resolve("H1000000_R40/replications.csv"));
        var main = new ArrayList<String[]>();
        int reused = 0;
        for (String variant : VARIANTS) {
            var saved = group(historical, variant);
            requireSequence(saved, variant, MIN_MAIN_RUNS, false);
            long seedBase = mainSeed(variant);
            for (int r = 0; r < plan.runs(); r++) {
                if (r < saved.size()) {
                    main.add(saved.get(r));
                    reused++;
                } else {
                    main.add(simulate(variant, r, seedBase + r));
                }
            }
        }
        write(OUTPUT.resolve("replications.csv"), main);
        summarize(main, plan);
        System.out.println("Comparison: " + plan.runs() + " runs per variant; " + reused
                + " saved observations; " + pilot.size() + " separate pilot runs");
    }

    private static Plan plan(List<String[]> pilot) {
        int runs = MIN_MAIN_RUNS;
        var tolerances = new LinkedHashMap<String, Map<String, Double>>();
        var targets = new ArrayList<Object>();
        for (String variant : VARIANTS) {
            var sample = group(pilot, variant);
            requireSequence(sample, variant, PILOT_RUNS, true);
            var epsilon = new LinkedHashMap<String, Double>();
            for (String metric : TARGETS) {
                double[] values = values(sample, metric);
                double tolerance = ExperimentStatistics.epsilon(metric, ExperimentStatistics.mean(values));
                var estimate = ExperimentStatistics.meanEstimate(metric, values, tolerance);
                runs = Math.max(runs, Math.toIntExact(estimate.chebyshevN()));
                epsilon.put(metric, tolerance);
                targets.add(Map.of("variant", variant, "metric", metric,
                        "pilotMean", estimate.estimate(), "pilotSd", estimate.sd(),
                        "epsilon", tolerance, "requiredN", estimate.chebyshevN()));
            }
            tolerances.put(variant, epsilon);
        }
        if (runs > 10_000) throw new IllegalStateException("Review the comparison precision plan");
        return new Plan(runs, tolerances, targets);
    }

    private static String[] simulate(String variant, int replication, long seed) throws Exception {
        var config = ModelConfig.baseline(HORIZON, seed, 0);
        var delays = DelaySource.seeded(seed);
        boolean buffered = variant.equals("buffered");
        var net = buffered ? B2NetFactory.buffered(config, delays, 18) : B2NetFactory.baseline(config, delays);
        var result = new SimulationRunner(net, config).run(null);
        var summary = result.summary();
        var row = new ArrayList<String>(List.of(variant, buffered ? "18" : "0",
                Integer.toString(replication), Long.toString(seed), Double.toString(HORIZON), "0",
                Long.toString(result.arrivedPairsInWindow()), Long.toString(result.refusedPairsInWindow())));
        for (String metric : METRICS) row.add(summary.get(metric).toString());
        return row.toArray(String[]::new);
    }

    private static void summarize(List<String[]> rows, Plan plan) throws Exception {
        var estimates = new LinkedHashMap<String, Object>();
        var byVariant = new LinkedHashMap<String, Map<String, ExperimentStatistics.Estimate>>();
        for (String variant : VARIANTS) {
            var sample = group(rows, variant);
            requireSequence(sample, variant, plan.runs(), false);
            var results = new LinkedHashMap<String, ExperimentStatistics.Estimate>();
            var json = new LinkedHashMap<String, Object>();
            for (String metric : allMetrics()) {
                double[] values = values(sample, metric);
                Double fixed = plan.epsilon().get(variant).get(metric);
                var estimate = fixed == null ? ExperimentStatistics.meanEstimate(metric, values)
                        : ExperimentStatistics.meanEstimate(metric, values, fixed);
                if (fixed != null && estimate.halfWidth() > fixed) {
                    throw new IllegalStateException("Comparison precision not achieved: " + variant + " " + metric);
                }
                results.put(metric, estimate);
                json.put(metric, estimate.json());
            }
            byVariant.put(variant, results);
            estimates.put(variant, json);
        }
        if (rows.size() != 2 * plan.runs()) throw new IllegalArgumentException("Unexpected comparison rows");
        Json.write(OUTPUT.resolve("summary-from-saved.json"), estimates);
        System.out.println("metric,buffered_minus_baseline");
        for (String metric : List.of("pRefusal", "L_full", "L_secondary", "throughput")) {
            double delta = byVariant.get("buffered").get(metric).estimate()
                    - byVariant.get("baseline").get(metric).estimate();
            double scale = metric.equals("pRefusal") ? 100 : metric.equals("throughput") ? 60 : 1;
            System.out.printf(Locale.ROOT, "%s,%.9f%n", metric, delta * scale);
        }
    }

    private static List<String> allMetrics() {
        var metrics = new ArrayList<String>(List.of("pRefusal"));
        metrics.addAll(List.of(METRICS));
        return metrics;
    }

    private static long mainSeed(String variant) {
        return variant.equals("baseline") ? 600_000_000L : 600_100_000L;
    }

    private static void requireSequence(List<String[]> rows, String variant, int count, boolean pilot) {
        if (rows.size() != count) throw new IllegalArgumentException("Unexpected number of runs: " + variant);
        long seedBase = pilot ? (variant.equals("baseline") ? 1_800_000_000L : 1_800_100_000L) : mainSeed(variant);
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            if (row.length != COLUMNS.size() || Integer.parseInt(row[2]) != r
                    || Long.parseLong(row[3]) != seedBase + r || Double.parseDouble(row[4]) != HORIZON
                    || Double.parseDouble(row[5]) != 0
                    || Integer.parseInt(row[1]) != (variant.equals("baseline") ? 0 : 18)) {
                throw new IllegalArgumentException("Unexpected comparison configuration");
            }
            for (int i = 6; i < row.length; i++) {
                double value = Double.parseDouble(row[i]);
                if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid observation");
            }
        }
    }

    private static List<String[]> group(List<String[]> rows, String variant) {
        return rows.stream().filter(row -> row[0].equals(variant)).toList();
    }

    private static double[] values(List<String[]> rows, String metric) {
        if (metric.equals("pRefusal")) {
            return rows.stream().mapToDouble(row -> {
                double arrivals = Double.parseDouble(row[6]), refused = Double.parseDouble(row[7]);
                if (arrivals <= 0 || refused > arrivals) throw new IllegalArgumentException("Invalid counters");
                return refused / arrivals;
            }).toArray();
        }
        int index = COLUMNS.indexOf(metric);
        return rows.stream().mapToDouble(row -> Double.parseDouble(row[index])).toArray();
    }

    private static List<String[]> read(Path path) throws Exception {
        var lines = Files.readAllLines(path);
        if (!lines.getFirst().equals(HEADER)) throw new IllegalArgumentException("Unexpected CSV header");
        return lines.subList(1, lines.size()).stream().map(line -> line.split(",")).toList();
    }

    private static void write(Path path, List<String[]> rows) throws Exception {
        var csv = new StringBuilder(HEADER).append('\n');
        for (String[] row : rows) csv.append(String.join(",", row)).append('\n');
        Files.writeString(path, csv);
    }
}
