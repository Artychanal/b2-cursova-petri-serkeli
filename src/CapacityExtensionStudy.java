import java.nio.file.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Додаткові рівні місткості. */
public final class CapacityExtensionStudy {
    private static final Path OUTPUT = Path.of("output/capacity-extension");
    private static final int[] CAPACITIES = {8, 12, 25, 30};
    private static final double HORIZON = 10_000_000;
    private static final double WARMUP = 2_000_000;
    private static final int PILOT_RUNS = 20;
    private static final int EXTENDED_RUNS = 80;
    private static final int MAIN_RUNS = 40;
    private static final String HEADER = "K,replication,seed,T_min,warmup_min,arrived_pairs,refused_pairs,pRefusal,L_in,p_warmup_change,p_late_change,L_in_warmup_change,L_in_late_change\n";

    private record Run(int capacity, int replication, long seed, long arrivals, long refusals,
                       double refusalRate, double inputQueue, double refusalWarmup,
                       double refusalLate, double queueWarmup, double queueLate) {
        String csv() {
            return String.format(Locale.ROOT,
                    "%d,%d,%d,%.0f,%.0f,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                    capacity, replication, seed, HORIZON, WARMUP, arrivals, refusals,
                    refusalRate, inputQueue, refusalWarmup, refusalLate, queueWarmup, queueLate);
        }

        static Run parse(String text) {
            String[] fields = text.strip().split(",");
            if (fields.length != 13) {
                throw new IllegalArgumentException("Expected 13 CSV fields");
            }
            return new Run(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]), Long.parseLong(fields[5]), Long.parseLong(fields[6]),
                    Double.parseDouble(fields[7]), Double.parseDouble(fields[8]),
                    Double.parseDouble(fields[9]), Double.parseDouble(fields[10]),
                    Double.parseDouble(fields[11]), Double.parseDouble(fields[12]));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("pilot", "extend", "main", "analyze").contains(args[0])) {
            throw new IllegalArgumentException("Use pilot, extend, main or analyze");
        }
        freezePlan();
        if (args[0].equals("analyze")) {
            Json.write(OUTPUT.resolve("analysis.json"), analyze(OUTPUT));
            return;
        }
        if (args[0].equals("main") && !pilotPassed(OUTPUT)) {
            throw new IllegalStateException("Pilot failed; run the planned extension first");
        }
        String stage = args[0];
        int repetitions = stage.equals("pilot") ? PILOT_RUNS
                : stage.equals("extend") ? EXTENDED_RUNS : MAIN_RUNS;
        long seedBase = stage.equals("pilot") ? 1_700_000_000L
                : stage.equals("extend") ? 1_710_000_000L : 1_720_000_000L;
        var stageRows = new StringBuilder(HEADER);
        for (int level = 0; level < CAPACITIES.length; level++) {
            int capacity = CAPACITIES[level];
            if (stage.equals("extend") && diagnose(capacity, readRuns(OUTPUT, "pilot", capacity, PILOT_RUNS)).passed()) {
                continue;
            }
            for (int replication = 0; replication < repetitions; replication++) {
                long seed = seedBase + level * 100_000L + replication;
                Path path = runPath(stage, capacity, replication);
                if (!Files.exists(path)) {
                    Run run = simulate(capacity, replication, seed);
                    Files.createDirectories(path.getParent());
                    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
                    Files.writeString(temporary, run.csv());
                    Files.move(temporary, path);
                }
                Run run = Run.parse(Files.readString(path));
                if (run.seed() != seed || run.capacity() != capacity || run.replication() != replication) {
                    throw new IllegalStateException("Saved run does not match the plan");
                }
                stageRows.append(run.csv());
                if ((replication + 1) % 10 == 0) {
                    System.out.println(stage + " K=" + capacity + ": " + (replication + 1) + "/" + repetitions);
                }
            }
        }
        Files.writeString(OUTPUT.resolve(stage + ".csv"), stageRows);
        if (!stage.equals("main")) {
            System.out.println("Pilot passed: " + pilotPassed(OUTPUT));
        } else {
            Json.write(OUTPUT.resolve("analysis.json"), analyze(OUTPUT));
        }
    }

    private static Run simulate(int capacity, int replication, long seed) throws Exception {
        var config = ModelConfig.baseline(HORIZON, seed, 0);
        var net = B2NetFactory.buffered(config, DelaySource.seeded(seed), capacity);
        var result = new SimulationRunner(net, config).run(null, HORIZON / 100);
        var first = PilotExperiments.at(result, WARMUP);
        var last = PilotExperiments.at(result, HORIZON);
        long arrivals = last.arrivedPairs() - first.arrivedPairs();
        long refusals = last.refusedPairs() - first.refusedPairs();
        double queue = PilotExperiments.window(result, WARMUP, HORIZON).get("L_in");
        double refusalWarmup = refusal(result, WARMUP, HORIZON) - refusal(result, HORIZON / 10, HORIZON);
        double refusalLate = refusal(result, .8 * HORIZON, HORIZON) - refusal(result, .6 * HORIZON, .8 * HORIZON);
        double queueWarmup = queue - PilotExperiments.window(result, HORIZON / 10, HORIZON).get("L_in");
        double queueLate = PilotExperiments.window(result, .8 * HORIZON, HORIZON).get("L_in")
                - PilotExperiments.window(result, .6 * HORIZON, .8 * HORIZON).get("L_in");
        return new Run(capacity, replication, seed, arrivals, refusals, (double) refusals / arrivals,
                queue, refusalWarmup, refusalLate, queueWarmup, queueLate);
    }

    private static double refusal(SimulationRunner.Result result, double from, double to) {
        var first = PilotExperiments.at(result, from);
        var last = PilotExperiments.at(result, to);
        return (double) (last.refusedPairs() - first.refusedPairs()) / (last.arrivedPairs() - first.arrivedPairs());
    }

    private record Diagnostic(boolean passed, long requiredRuns, List<Object> comparisons) {}

    private static Diagnostic diagnose(int capacity, List<Run> runs) {
        var comparisons = new ArrayList<Object>();
        long requiredRuns = 20;
        boolean passed = true;
        for (String metric : List.of("pRefusal", "L_in")) {
            double epsilon = metric.equals("pRefusal") ? .005 : .5;
            double[] values = values(runs, metric.equals("pRefusal") ? Run::refusalRate : Run::inputQueue);
            requiredRuns = Math.max(requiredRuns,
                    ExperimentStatistics.meanEstimate(metric, values, epsilon).chebyshevN());
            ToDoubleFunction<Run> warmup = metric.equals("pRefusal") ? Run::refusalWarmup : Run::queueWarmup;
            ToDoubleFunction<Run> late = metric.equals("pRefusal") ? Run::refusalLate : Run::queueLate;
            for (String comparison : List.of("warmup_change", "late_change")) {
                var estimate = ExperimentStatistics.meanEstimate(comparison,
                        values(runs, comparison.equals("warmup_change") ? warmup : late), epsilon);
                boolean accepted = estimate.low() >= -epsilon && estimate.high() <= epsilon;
                passed &= accepted;
                comparisons.add(Map.of("K", capacity, "metric", metric,
                        "comparison", comparison, "estimate", estimate.json(), "passed", accepted));
            }
        }
        return new Diagnostic(passed && requiredRuns <= MAIN_RUNS, requiredRuns, comparisons);
    }

    private static List<Run> diagnosticRuns(Path input, int capacity) throws Exception {
        var extended = readGroup(input, "extend", capacity);
        return extended.isEmpty() ? readRuns(input, "pilot", capacity, PILOT_RUNS)
                : requireRuns(extended, "extend", capacity, EXTENDED_RUNS);
    }

    private static boolean pilotPassed(Path input) throws Exception {
        var reports = new ArrayList<Object>();
        boolean passed = true;
        for (int capacity : CAPACITIES) {
            var runs = diagnosticRuns(input, capacity);
            var diagnostic = diagnose(capacity, runs);
            passed &= diagnostic.passed();
            reports.add(Map.of("K", capacity, "n", runs.size(), "passed", diagnostic.passed(),
                    "requiredRuns", diagnostic.requiredRuns(), "comparisons", diagnostic.comparisons()));
        }
        Json.write(OUTPUT.resolve("pilot-diagnostics.json"), Map.of("passed", passed, "groups", reports));
        return passed;
    }

    public static Map<String, Object> analyze(Path input) throws Exception {
        if (!pilotPassed(input)) {
            throw new IllegalStateException("Unconfirmed pilot");
        }
        var groups = new ArrayList<Object>();
        for (int capacity : CAPACITIES) {
            var runs = readRuns(input, "main", capacity, MAIN_RUNS);
            var diagnostics = diagnosticRuns(input, capacity);
            var q = ExperimentStatistics.meanEstimate("pRefusal", values(runs, Run::refusalRate), .005);
            var queue = ExperimentStatistics.meanEstimate("L_in", values(runs, Run::inputQueue), .5);
            if (q.halfWidth() > q.epsilon() || queue.halfWidth() > queue.epsilon()) {
                throw new IllegalStateException("Main interval precision not achieved");
            }
            var pilotQ = ExperimentStatistics.meanEstimate("pRefusal", values(diagnostics, Run::refusalRate), .005);
            var pilotQueue = ExperimentStatistics.meanEstimate("L_in", values(diagnostics, Run::inputQueue), .5);
            groups.add(Map.of("K", capacity, "n", runs.size(), "pilotN", diagnostics.size(),
                    "q", q.json(), "L_in", queue.json(), "pilotQ", pilotQ.json(),
                    "pilotQueue", pilotQueue.json(), "analyticRefusal", 1.0 / (capacity + 2)));
        }
        return Map.of("groups", groups, "horizon", HORIZON, "warmup", WARMUP,
                "beta", .95, "mainRunsPerLevel", MAIN_RUNS, "provenance", RunProvenance.collect());
    }

    private static double[] values(List<Run> runs, ToDoubleFunction<Run> metric) {
        return runs.stream().mapToDouble(metric).toArray();
    }

    private static List<Run> readGroup(Path input, String stage, int capacity) throws Exception {
        Path csv = input.resolve(stage + ".csv");
        if (stage.equals("extend") && !Files.exists(csv)) {
            return List.of();
        }
        try (var lines = Files.lines(csv)) {
            return lines.skip(1).map(Run::parse).filter(run -> run.capacity() == capacity).toList();
        }
    }

    private static List<Run> readRuns(Path input, String stage, int capacity, int repetitions)
            throws Exception {
        return requireRuns(readGroup(input, stage, capacity), stage, capacity, repetitions);
    }

    private static List<Run> requireRuns(List<Run> runs, String stage, int capacity, int repetitions) {
        if (runs.size() != repetitions) {
            throw new IllegalStateException("Unexpected run count for " + stage + ", K=" + capacity);
        }
        for (int replication = 0; replication < repetitions; replication++) {
            if (runs.get(replication).replication() != replication) {
                throw new IllegalStateException("Unexpected replication in " + stage + ", K=" + capacity);
            }
        }
        return runs;
    }

    private static Path runPath(String stage, int capacity, int replication) {
        return OUTPUT.resolve(stage + "/K" + capacity + "_R" + replication + ".csv");
    }

    private static void freezePlan() throws Exception {
        Files.createDirectories(OUTPUT);
        var plan = new TreeMap<String, Object>();
        plan.put("capacities", CAPACITIES);
        plan.put("horizon", HORIZON);
        plan.put("warmup", WARMUP);
        plan.put("pilotRuns", PILOT_RUNS);
        plan.put("mainRuns", MAIN_RUNS);
        plan.put("extendedRunsIfDiagnosticFails", EXTENDED_RUNS);
        plan.put("epsilon", Map.of("pRefusal", .005, "L_in", .5));
        plan.put("beta", .95);
        plan.put("seedBases", List.of(1_700_000_000L, 1_710_000_000L, 1_720_000_000L));
        plan.put("seedStride", 100_000);
        plan.put("provenance", canonical(RunProvenance.collect()));
        plan.put("source", Files.readString(Path.of("src/CapacityExtensionStudy.java")));
        String text = Json.encode(canonical(plan));
        Path path = OUTPUT.resolve("plan.json");
        if (Files.exists(path) && !Files.readString(path).equals(text)) {
            throw new IllegalStateException("The source or frozen plan changed");
        }
        if (!Files.exists(path)) {
            Files.writeString(path, text);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put(key.toString(), canonical(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CapacityExtensionStudy::canonical).toList();
        }
        return value;
    }
}
