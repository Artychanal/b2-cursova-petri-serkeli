import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Дослідження місткості буфера. */
public final class CapacityFactorStudy {
    private static final Path OUTPUT = Path.of("output/factor-capacity");
    private static final int[] CAPACITIES = {
        5, 18, 38
    };
    private static final double HORIZON = 10_000_000;
    private static final double WARMUP = 2_000_000;
    private static final int PILOT_RUNS = 20;
    private static final int MAIN_RUNS = 40;
    private static final String HEADER =  "K,replication,seed,T_min,warmup_min,arrived_pairs,refused_pairs,pRefusal,L_in,p_warmup_change,p_late_change,L_in_warmup_change,L_in_late_change\n";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Set.of("pilot", "extend", "main").contains(args[0])) {
            throw new IllegalArgumentException("Use pilot, extend or main");
        }
        boolean extension = args[0].equals("extend");
        boolean pilot = !args[0].equals("main");
        Files.createDirectories(OUTPUT);
        var plan = new LinkedHashMap<String, Object>();
        plan.put("capacities", CAPACITIES);
        plan.put("H_min", HORIZON);
        plan.put("warmup_min", WARMUP);
        plan.put("pilot_runs_per_level", PILOT_RUNS);
        plan.put("main_runs_per_level", MAIN_RUNS);
        plan.put("epsilon", Map.of("pRefusal", 0.005, "L_in", 0.5));
        plan.put("beta", 0.95);
        plan.put("alpha_family_two_responses", 0.05);
        plan.put("pilot_seed_base", 700_000_000L);
        plan.put("main_seed_base", 710_000_000L);
        plan.put("additional_diagnostic_K38", Map.of("runs", 80, "seed_base", 720_200_000L,
            "reason", "Initial 20-run late L_in interval exceeded the fixed tolerance"));
        plan.put("seed_stride_between_levels", 100_000);
        plan.put("provenance", RunProvenance.collect());
        plan.put("study_source", Files.readString(Path.of("src/CapacityFactorStudy.java")));
        Path planPath = OUTPUT.resolve("validated-plan.json");
        String planText = Json.encode(canonical(plan)) + "\n";
        if (Files.exists(planPath) && !Files.readString(planPath).equals(planText)) {
            throw new IllegalStateException("The saved plan or source differs");
        }
        if (!Files.exists(planPath)) {
            Files.writeString(planPath, planText, StandardCharsets.UTF_8);
        }
        if (!pilot) {
            checkPilot();
        }
        int repetitions = extension ? 80 : pilot ? PILOT_RUNS : MAIN_RUNS;
        StringBuilder csv = new StringBuilder(HEADER);
        for (int level = 0; level < CAPACITIES.length; level++) {
            int k = CAPACITIES[level];
            if (extension && k != 38) {
                continue;
            }
            for (int r = 0; r < repetitions; r++) {
                long seed = (extension ? 720_000_000L : pilot ? 700_000_000L : 710_000_000L)
                    + level * 100_000L + r;
                Path runPath = OUTPUT.resolve(args[0] + "/K" + k + "_R" + r + ".csv");
                if (!Files.exists(runPath)) {
                    var config = ModelConfig.baseline(HORIZON, seed, 0);
                    var net = B2NetFactory.buffered(config, DelaySource.seeded(seed), k);
                    var result = new SimulationRunner(net, config).run(null, HORIZON / 100);
                    var first = PilotExperiments.at(result, WARMUP);
                    var last = PilotExperiments.at(result, HORIZON);
                    long arrivals = last.arrivedPairs() - first.arrivedPairs();
                    long refusals = last.refusedPairs() - first.refusedPairs();
                    double lin = PilotExperiments.window(result, WARMUP, HORIZON).get("L_in");
                    double pChange = refusal(result, WARMUP, HORIZON) - refusal(result, HORIZON / 10, HORIZON);
                    double pLate = refusal(result, .8 * HORIZON, HORIZON) - refusal(result, .6 * HORIZON, .8 * HORIZON);
                    double linChange = lin - PilotExperiments.window(result, HORIZON / 10, HORIZON).get("L_in");
                    double linLate = PilotExperiments.window(result, .8 * HORIZON, HORIZON).get("L_in")
                    - PilotExperiments.window(result, .6 * HORIZON, .8 * HORIZON).get("L_in");
                    String row = String.format(Locale.ROOT, "%d,%d,%d,%.0f,%.0f,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        k, r, seed, HORIZON, WARMUP, arrivals, refusals, (double) refusals / arrivals, lin,
                        pChange, pLate, linChange, linLate);
                    Files.createDirectories(runPath.getParent());
                    Path temporary = runPath.resolveSibling(runPath.getFileName() + ".tmp");
                    Files.writeString(temporary, row, StandardCharsets.UTF_8);
                    Files.move(temporary, runPath, StandardCopyOption.REPLACE_EXISTING);
                }
                csv.append(Files.readString(runPath));
                if ((r + 1) % 10 == 0) {
                    System.out.println(args[0] + " K=" + k + ": " + (r + 1) + "/" + repetitions);
                }
            }
        }
        Files.writeString(OUTPUT.resolve(args[0] + ".csv"), csv, StandardCharsets.UTF_8);
        if (pilot) {
            checkPilot();
        }
    }

    private static double refusal(SimulationRunner.Result result, double a, double b) {
        var first = PilotExperiments.at(result, a);
        var last = PilotExperiments.at(result, b);
        return (double) (last.refusedPairs() - first.refusedPairs()) / (last.arrivedPairs() - first.arrivedPairs());
    }

    private static void checkPilot() throws Exception {
        var rows = new ArrayList<>(Files.readAllLines(OUTPUT.resolve("pilot.csv")));
        Path extension = OUTPUT.resolve("extend.csv");
        if (Files.exists(extension)) {
            rows.removeIf(row -> row.startsWith("38,"));
            rows.addAll(Files.readAllLines(extension).subList(1, 81));
        }
        var headers = Arrays.asList(rows.getFirst().split(","));
        var report = new ArrayList<Object>();
        for (int k : CAPACITIES) {
            var group = rows.stream().skip(1).map(row -> row.split(","))
                .filter(row -> Integer.parseInt(row[0]) == k).toList();
            int expected = k == 38 && Files.exists(extension) ? 80 : PILOT_RUNS;
            if (group.size() != expected) {
                throw new IllegalStateException("Incomplete pilot");
            }
            for (String metric : List.of("pRefusal", "L_in")) {
                double epsilon = metric.equals("pRefusal") ? 0.005 : 0.5;
                double[] values = column(group, headers.indexOf(metric));
                var estimate = ExperimentStatistics.meanEstimate(metric, values, epsilon);
                if (estimate.chebyshevN() > MAIN_RUNS) {
                    throw new IllegalStateException("Increase the planned replication count");
                }
                for (String suffix : List.of("warmup_change", "late_change")) {
                    String key = (metric.equals("pRefusal") ? "p" : metric) + "_" + suffix;
                    var diagnostic = ExperimentStatistics.meanEstimate(key, column(group, headers.indexOf(key)), epsilon);
                    report.add(Map.of("K", k, "metric", metric, "comparison", suffix,
                        "estimate", diagnostic.json(), "plannedN", estimate.chebyshevN()));
                    if (diagnostic.low() < -epsilon || diagnostic.high() > epsilon) {
                        throw new IllegalStateException("Warmup not confirmed for K=" + k + ", " + key);
                    }
                }
            }
        }
        Json.write(OUTPUT.resolve("pilot-diagnostics.json"), report);
    }

    private static double[] column(List<String[]> rows, int index) {
        return rows.stream().mapToDouble(row -> Double.parseDouble(row[index])).toArray();
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                sorted.put(entry.getKey().toString(), canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(CapacityFactorStudy::canonical).toList();
        }
        return value;
    }
}
