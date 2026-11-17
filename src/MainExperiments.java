import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Незалежні основні прогони. */
public final class MainExperiments {
    private static final String[] METRICS = {
        "L_full", "L_secondary", "L_in", "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end"
    };
    private static final String HEADER = "replication,seed,arrived_pairs,refused_pairs,L_full,L_secondary,L_in,U_primary,U_secondary,U_full,throughput,Q_full_end";

    private MainExperiments() {
    }

    public static void execute(Path output, Path planPath) throws Exception {
        Properties plan = new Properties();
        try (var reader = Files.newBufferedReader(planPath, StandardCharsets.UTF_8)) {
            plan.load(reader);
        }
        int k = Integer.parseInt(plan.getProperty("K")), n = Integer.parseInt(plan.getProperty("replications"));
        double horizon = Double.parseDouble(plan.getProperty("horizon_min")), warmup = Double.parseDouble(plan.getProperty("warmup_min"));
        long seedBase = Long.parseLong(plan.getProperty("seed_base"));
        double target = Double.parseDouble(plan.getProperty("refusal_target"));
        if (k<0 || n<2 || !Double.isFinite(target) || target <= 0 || target >= 1) {
            throw new IllegalArgumentException("Invalid main-series plan");
        }
        ModelConfig.baseline(horizon, seedBase, warmup);
        Math.addExact(seedBase, n-1L);
        int plannedN = 0;
        for (String metric:allMetrics()) {
            double epsilon = Double.parseDouble(plan.getProperty("epsilon."+metric));
            if (!Double.isFinite(epsilon) || epsilon <= 0) {
                throw new IllegalArgumentException("Invalid epsilon for "+metric);
            }
            plannedN = Math.max(plannedN, Integer.parseInt(plan.getProperty("pilot_n."+metric)));
        }
        if (n<plannedN) {
            throw new IllegalArgumentException("Main series smaller than frozen pilot plan");
        }
        if (Double.parseDouble(plan.getProperty("epsilon.pRefusal")) != 0.005) {
            throw new IllegalArgumentException("Ratio estimator uses epsilon.pRefusal=0.005");
        }
        String pilotPath = plan.getProperty("pilot_design");
        String pilotHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(pilotPath))));
        if (!pilotHash.equals(plan.getProperty("pilot_design_sha256"))) {
            throw new IllegalStateException("Pilot design changed");
        }
        Path directory = output.resolve("main").resolve("capacity-S-v2").resolve("K"+k+"_H"+(long)horizon+"_A"+(long)warmup+"_R"+n+"_S"+seedBase);
        Files.createDirectories(directory.resolve("runs"));
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("kind", "fixed_independent_main_series");
        frozen.put("plan", new TreeMap<>(plan));
        frozen.put("provenance", RunProvenance.collect());
        frozen.put("targetRule", "upper endpoint of two-sided 95% refusal interval <= refusal_target");
        frozen.put("timeUnit", "minute");
        String design = Json.encode(frozen)+"\n";
        Path designFile = directory.resolve("design.json");
        if (Files.exists(designFile) && !Files.readString(designFile).equals(design)) {
            throw new IllegalStateException("Resume refused: plan, code, library or provenance differs");
        }
        atomicWrite(designFile, design);
        double[] arrivals = new double[n], refusals = new double[n];
        Map<String, double[]> values = new LinkedHashMap<>();
        for (String metric:METRICS) {
            values.put(metric, new double[n]);
        }
        var csv = new StringBuilder(HEADER+"\n");
        int resumed = 0;
        for (int r = 0;r<n;r++) {
            long seed = seedBase+r;
            Path checkpoint = directory.resolve("runs").resolve(String.format(Locale.ROOT, "%05d.csv", r));
            String row;
            if (Files.exists(checkpoint)) {
                row = Files.readString(checkpoint).strip();
                resumed++;
            } else {
                var config = ModelConfig.baseline(horizon, seed, warmup);
                var result = new SimulationRunner(B2NetFactory.buffered(config, DelaySource.seeded(seed), k), config).run(null);
                var summary = result.summary();
                var line = new StringBuilder(r+","+seed+","+result.arrivedPairsInWindow()+","+result.refusedPairsInWindow());
                for (String metric:METRICS) {
                    line.append(',').append(summary.get(metric));
                }
                row = line.toString();
                atomicWrite(checkpoint, row+"\n");
            }
            String[] cells = row.split(",", -1);
            if (cells.length != 12 || Integer.parseInt(cells[0]) != r || Long.parseLong(cells[1]) != seed) {
                throw new IllegalStateException("Invalid checkpoint "+checkpoint);
            }
            arrivals[r] = Double.parseDouble(cells[2]);
            refusals[r] = Double.parseDouble(cells[3]);
            if (arrivals[r] <= 0 || refusals[r]<0 || refusals[r]>arrivals[r]) {
                throw new IllegalStateException("Invalid counters");
            }
            for (int j = 0;j<METRICS.length;j++) {
                double value = Double.parseDouble(cells[j+4]);
                if (!Double.isFinite(value) || value<0 || (METRICS[j].startsWith("U_") && value>1+1e-10)) {
                    throw new IllegalStateException("Invalid checkpoint metric "+METRICS[j]);
                }
                values.get(METRICS[j])[r] = value;
            }
            csv.append(row).append('\n');
            if ((r+1)%50 == 0 || r+1 == n) {
                Json.write(directory.resolve("progress.json"), Map.of("completed", r+1, "planned", n, "resumed", resumed, "lastSeed", seed));
                System.out.println("Main K="+k+": "+(r+1)+"/"+n+" completed ("+resumed+" resumed)");
            }
        }
        atomicWrite(directory.resolve("replications.csv"), csv.toString());
        var estimates = new ArrayList<ExperimentStatistics.Estimate>();
        var refusal = ExperimentStatistics.refusal(arrivals, refusals);
        estimates.add(refusal);
        for (String metric:METRICS) {
            estimates.add(ExperimentStatistics.meanEstimate(metric, values.get(metric), Double.parseDouble(plan.getProperty("epsilon."+metric))));
        }
        String verdict = refusal.high() <= target?"confirmed":refusal.low()>target?"above_target":"not_confirmed";
        var report = new LinkedHashMap<String, Object>();
        report.put("status", "completed");
        report.put("K", k);
        report.put("n", n);
        report.put("horizon_min", horizon);
        report.put("warmup_min", warmup);
        report.put("observation_min", horizon-warmup);
        report.put("seedBase", seedBase);
        report.put("pilotPlannedN", plannedN);
        report.put("plannedSampleSizeMet", n >= plannedN);
        report.put("allIntervalPrecisionsMet", estimates.stream().allMatch(e -> e.halfWidth() <= e.epsilon()));
        report.put("estimates", estimates.stream().map(ExperimentStatistics.Estimate::json).toList());
        report.put("refusalTarget", Map.of("value", target, "rule", frozen.get("targetRule"), "verdict", verdict, "analytic", 1.0/(k+2)));
        report.put("notes", List.of("Fixed pilot-based sample size; no optional stopping to obtain a favorable target decision",
            "Pilot variance is estimated, not known population variance; interval coverage is approximate",
            "Beta=0.95 is per metric, not simultaneous coverage of all nine metrics",
            "Main observations are not pooled with pilot or warmup diagnostics"));
        Json.write(directory.resolve("summary.json"), report);
        var table = new StringBuilder("K,n,H_min,warmup_min,observation_min,metric,unit,estimate,sd,epsilon,beta,ciLow,ciHigh,halfWidth,precisionMet,pilotPlannedN\n");
        for (var e:estimates) {
            table.append(k).append(',').append(n).append(',').append(horizon).append(',').append(warmup).append(',').append(horizon-warmup)
                .append(',').append(e.metric()).append(',').append(PilotExperiments.unit(e.metric())).append(',').append(e.estimate()).append(',').append(e.sd())
                .append(',').append(e.epsilon()).append(',').append(ExperimentStatistics.BETA).append(',').append(e.low()).append(',').append(e.high())
                .append(',').append(e.halfWidth()).append(',').append(e.halfWidth() <= e.epsilon()).append(',').append(plan.getProperty("pilot_n."+e.metric())).append('\n');
        }
        atomicWrite(directory.resolve("summary.csv"), table.toString());
        Json.write(output.resolve("main/latest.json"), Map.of("directory", directory.toString(), "status", "completed", "K", k, "n", n));
        System.out.println("Main K="+k+": precision="+report.get("allIntervalPrecisionsMet")+", refusal target="+verdict+", p="+refusal.estimate());
    }

    private static List<String> allMetrics() {
        var metrics = new ArrayList<>(List.of("pRefusal"));
        metrics.addAll(Arrays.asList(METRICS));
        return metrics;
    }

    private static void atomicWrite(Path path, String text) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
