import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Пілотні серії прогонів. */
public final class PilotExperiments {

    private PilotExperiments() {
    }

    public record Design(int k, double horizon, double warmup, Boolean warmupDiagnosticPassed,
        long requiredN, List<Map<String, Object>> pilotEstimates) {

        public String warmupStatus() {
            return k<0 ? "not_applicable_nonstationary" : Boolean.TRUE.equals(warmupDiagnosticPassed) ? "passed" : "unconfirmed";
        }
    }
    private static final String[] METRICS = {
        "L_full", "L_secondary", "L_in", "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end"
    };
    public static List<Design> execute(Path output, double horizon, int pilotRuns) throws Exception {
        return execute(output, horizon, pilotRuns, 0, false, -1);
    }

    public static List<Design> execute(Path output, double horizon, int pilotRuns, long seedBase, boolean diagnosticsOnly, int onlyCapacity) throws Exception {
        if (onlyCapacity != -1 && (!diagnosticsOnly || (onlyCapacity != 5 && onlyCapacity != 18 && onlyCapacity != 38))) {
            throw new IllegalArgumentException("Warmup capacity must be 5, 18, 38 or -1 (all)");
        }
        if (horizon<100000 || horizon%100000 != 0 || pilotRuns<4 || pilotRuns>100000) {
            throw new IllegalArgumentException("Pilot H must be a positive multiple of 100000; at least four runs");
        }
        double[] horizons = {
            0.03*horizon, 0.1*horizon, 0.3*horizon, horizon
        };
        double proposedWarmup = horizon/5;
        long phaseOffset = horizon>100000?500000:0;
        Path directory = diagnosticsOnly
            ? output.resolve("warmup").resolve("capacity-S-v2").resolve("H"+(long)horizon+"_R"+pilotRuns+"_S"+seedBase+(onlyCapacity<0?"":"_K"+onlyCapacity))
            : output.resolve("pilot").resolve("capacity-S-v2").resolve("H"+(long)horizon+"_R"+pilotRuns);
        Files.createDirectories(directory);
        List<Design> designs = new ArrayList<>();
        List<Object> cases = new ArrayList<>();
        var horizonRows = new StringBuilder("variant,K,seed,T_min,arrived_pairs,refused_pairs,L_full,L_secondary,L_in,U_primary,U_secondary,U_full,throughput,Q_full_end\n");
        var trajectoryRows = new StringBuilder("variant,K,seed,time_min,Q_full,Q_secondary,Q_in,primary_busy,cumulative_L_full\n");
        var replicationRows = new StringBuilder("variant,K,seed,H_min,warmup_min,pRefusal,L_full,L_secondary,L_in,U_primary,U_secondary,U_full,throughput,Q_full_end\n");
        var pairRows = new StringBuilder("K,seed,H_min,metric,unit,warmup_change,late_window_change\n");
        int caseIndex = 0;
        for (int k:diagnosticsOnly?new int[]{
                5, 18, 38
        }
                :new int[]{
                -1, 5, 18, 38
        }) {
            if (diagnosticsOnly && onlyCapacity >= 0 && k != onlyCapacity) {
                caseIndex++;
                continue;
            }
            long caseSeedBase = diagnosticsOnly?Math.addExact(seedBase, caseIndex*100000L):8100000L+phaseOffset+caseIndex*100000L;
            List<SimulationRunner.Result> runs = new ArrayList<>();
            for (int r = 0;r<pilotRuns;r++) {
                long seed = Math.addExact(caseSeedBase, r);
                var c = ModelConfig.baseline(horizon, seed, 0);
                var source = DelaySource.seeded(seed);
                var net = k<0?B2NetFactory.baseline(c, source):B2NetFactory.buffered(c, source, k);
                var result = new SimulationRunner(net, c).run(null, horizon/400);
                runs.add(result);
                if (diagnosticsOnly && (r+1)%20 == 0) {
                    System.out.println("Warmup K="+k+": completed "+(r+1)+"/"+pilotRuns+" independent runs");
                }
                String variant = k<0?"baseline":"buffered";
                for (var sample:result.trajectory()) {
                    int busy = sample.buffers()[sample.buffers().length == 7?6:1];
                    trajectoryRows.append(variant).append(',').append(Math.max(0, k)).append(',').append(seed).append(',').append(sample.time()).append(',')
                        .append(sample.marks()[5]).append(',').append(sample.marks()[3]).append(',').append(sample.marks().length>9?sample.marks()[9]:0)
                        .append(',').append(busy).append(',').append(sample.time() == 0?0:sample.placeAreas()[5]/sample.time()).append('\n');
                }
                for (double t:horizons) {
                    var s = at(result, t);
                    var values = window(result, 0, t);
                    horizonRows.append(variant).append(',').append(Math.max(0, k)).append(',').append(seed).append(',').append(t).append(',')
                        .append(s.arrivedPairs()).append(',').append(s.refusedPairs());
                    for (String metric:METRICS) {
                        horizonRows.append(',').append(values.get(metric));
                    }
                    horizonRows.append('\n');
                }
            }
            double warmup = k<0?0:proposedWarmup;
            for (int r = 0;r<pilotRuns;r++) {
                var values = window(runs.get(r), warmup, horizon);
                var first = at(runs.get(r), warmup);
                var last = at(runs.get(r), horizon);
                double refusal = (double)(last.refusedPairs()-first.refusedPairs())/(last.arrivedPairs()-first.arrivedPairs());
                replicationRows.append(k<0?"baseline":"buffered").append(',').append(Math.max(0, k)).append(',').append(caseSeedBase+r)
                    .append(',').append(horizon).append(',').append(warmup).append(',').append(refusal);
                for (String metric:METRICS) {
                    replicationRows.append(',').append(values.get(metric));
                }
                replicationRows.append('\n');
            }
            var estimates = estimateRuns(runs, warmup, horizon);
            long required = estimates.stream().mapToLong(ExperimentStatistics.Estimate::chebyshevN).max().orElseThrow();
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            boolean accepted = k >= 0;
            if (k >= 0) {
                for (String metric:new String[]{
                        "L_full", "L_secondary", "L_in"
                }) {
                    double[] changes = new double[pilotRuns], drifts = new double[pilotRuns];
                    for (int r = 0;r<pilotRuns;r++) {
                        var run = runs.get(r);
                        changes[r] = window(run, proposedWarmup, horizon).get(metric)-window(run, horizon/10, horizon).get(metric);
                        drifts[r] = window(run, 0.8*horizon, horizon).get(metric)-window(run, 0.6*horizon, 0.8*horizon).get(metric);
                        pairRows.append(k).append(',').append(caseSeedBase+r).append(',').append(horizon).append(',').append(metric).append(',')
                            .append(unit(metric)).append(',').append(changes[r]).append(',').append(drifts[r]).append('\n');
                    }
                    double average = estimates.stream().filter(e -> e.metric().equals(metric)).findFirst().orElseThrow().estimate();
                    double eps = ExperimentStatistics.epsilon(metric, average);
                    var sensitivity = ExperimentStatistics.meanEstimate(metric+"_warmup_change", changes, eps);
                    var drift = ExperimentStatistics.meanEstimate(metric+"_late_window_change", drifts, eps);
                    boolean sensitivityOk = sensitivity.low() >= -eps && sensitivity.high() <= eps;
                    boolean driftOk = drift.low() >= -eps && drift.high() <= eps;
                    accepted &= sensitivityOk && driftOk;
                    diagnostics.add(Map.of("metric", metric, "warmupSensitivity", sensitivity.json(), "lateWindowDrift", drift.json(),
                        "withinEpsilonBothIntervals", sensitivityOk && driftOk,
                        "nextAction", sensitivityOk && driftOk?"criterion_passed":
                        Math.abs(sensitivity.estimate())>eps || Math.abs(drift.estimate())>eps?"investigate_horizon_sensitivity":"increase_replications_for_precision"));
                }
            }
            List<Object> byHorizon = new ArrayList<>();
            for (double t:horizons) {
                double[] values = new double[pilotRuns];
                for (int r = 0;r<pilotRuns;r++) {
                    values[r] = window(runs.get(r), 0, t).get("L_full");
                }
                byHorizon.add(Map.of("T_min", t, "L_full", ExperimentStatistics.meanEstimate("L_full", values).json()));
            }
            var estimatesJson = estimates.stream().map(ExperimentStatistics.Estimate::json).toList();
            var design = new Design(k, horizon, warmup, k<0?null:accepted, required, estimatesJson);
            designs.add(design);
            var caseReport = new LinkedHashMap<String, Object>();
            caseReport.put("variant", k<0?"baseline":"buffered");
            caseReport.put("K", Math.max(0, k));
            caseReport.put("pilotRuns", pilotRuns);
            caseReport.put("seedBase", caseSeedBase);
            caseReport.put("lastSeed", caseSeedBase+pilotRuns-1);
            caseReport.put("H_min", horizon);
            caseReport.put("warmup_min", warmup);
            caseReport.put("warmupDiagnosticPassed", design.warmupDiagnosticPassed());
            caseReport.put("warmupDiagnosticStatus", design.warmupStatus());
            caseReport.put("stationaryQueueMeanApplicable", k >= 5);
            caseReport.put("interpretation", k<0?"nonstationary Q_full: finite-horizon L_full(T), no burn-in":
                accepted?"warmup diagnostics passed within chosen tolerances, not proof of stationarity":"warmup unconfirmed: do not claim stationary queue means");
            caseReport.put("estimates", estimatesJson);
            caseReport.put("diagnostics", diagnostics);
            caseReport.put("L_full_by_horizon", byHorizon);
            if (k<0) {
                double[] growth = new double[pilotRuns];
                for (int r = 0;r<pilotRuns;r++) {
                    growth[r] = runs.get(r).marks()[5]/horizon;
                }
                caseReport.put("Q_full_net_growth", Map.of("definition", "(Q_full(T)-Q_full(0))/T, initially empty",
                    "unit", "aggregate/minute", "theory_asymptotic", 7.0/300,
                    "finite_horizon_estimate", ExperimentStatistics.meanEstimate("Q_full_net_growth", growth, 0.001).json()));
            }
            cases.add(caseReport);
            System.out.println("Pilot "+(k<0?"N0":"K="+k)+": n="+pilotRuns+", H="+horizon+", proposed n="+required+", warmup diagnostics="+design.warmupStatus());
            caseIndex++;
        }
        Files.writeString(directory.resolve("horizons.csv"), horizonRows, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("replications.csv"), replicationRows, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("diagnostic-pairs.csv"), pairRows, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("trajectories.csv"), trajectoryRows, StandardCharsets.UTF_8);
        var report = new LinkedHashMap<String, Object>();
        report.put("kind", diagnosticsOnly?"warmup_diagnostic_series":"independent_pilot_and_tactical_plan");
        report.put("seedPolicy", diagnosticsOnly?"explicit base + caseIndex*100000 + replication; repeat base deliberately reuses streams":"pilot base + caseIndex*100000 + replication; horizons above 100000 share the same seed base");
        report.put("beta", 0.95);
        report.put("cases", cases);
        report.put("provenance", RunProvenance.collect());
        report.put("notes", List.of("All horizon integrals are exact event-based prefixes, not sampled-plot approximations",
            "Chebyshev uses estimated pilot moments; no finite-sample guarantee with unknown population variance",
            "Warmup diagnostics are paired independent-run comparisons; failure blocks a stationary interpretation",
            "Ratio sample-size bound uses denominator and numerator residual with union-Chebyshev bound"));
        Json.write(directory.resolve("design.json"), report);
        Json.write(output.resolve(diagnosticsOnly?"warmup/latest.json":"pilot/latest.json"), Map.of("directory", directory.toString(), "horizon", horizon, "pilotRuns", pilotRuns));
        var summary = new StringBuilder("variant,K,metric,n,estimate,sd,epsilon,beta,ci_low,ci_high,half_width,ci_precision_met,Chebyshev_pilot_required_n,warmup_diagnostic_passed,warmup_diagnostic_status,H_min,warmup_min,unit\n");
        for (Design design:designs) {
            for (var e:design.pilotEstimates()) {
                summary.append(design.k()<0?"baseline":"buffered").append(',').append(Math.max(0, design.k())).append(',')
                    .append(e.get("metric")).append(',').append(e.get("n")).append(',').append(e.get("estimate")).append(',')
                    .append(e.get("sd_between_replications")).append(',').append(e.get("epsilon")).append(',').append(e.get("beta")).append(',')
                    .append(e.get("ciLow")).append(',').append(e.get("ciHigh")).append(',').append(e.get("halfWidth")).append(',')
                    .append(e.get("ciPrecisionMet")).append(',').append(e.get("requiredN_Chebyshev_pilot_plugin")).append(',')
                    .append(design.warmupDiagnosticPassed() == null?"":design.warmupDiagnosticPassed()).append(',')
                    .append(design.warmupStatus()).append(',').append(design.horizon()).append(',').append(design.warmup()).append(',')
                    .append(unit((String)e.get("metric"))).append('\n');
            }
        }
        Files.writeString(directory.resolve("summary.csv"), summary, StandardCharsets.UTF_8);
        return designs;
    }

    static String unit(String metric) {
        return switch (metric) {
            case "pRefusal", "U_primary", "U_secondary", "U_full" -> "fraction";
            case "L_in" -> "pair";
            case "throughput" -> "aggregate/minute";
            default -> "aggregate";
        };
    }

    static SimulationRunner.Snapshot at(SimulationRunner.Result r, double time) {
        return r.trajectory().stream().filter(s -> s.time() == time).findFirst().orElseThrow();
    }

    static Map<String, Double> window(SimulationRunner.Result r, double a, double b) {
        var first = at(r, a);
        var last = at(r, b);
        double d = b-a;
        var values = new LinkedHashMap<String, Double>();
        values.put("L_full", (last.placeAreas()[5]-first.placeAreas()[5])/d);
        values.put("L_secondary", (last.placeAreas()[3]-first.placeAreas()[3])/d);
        values.put("L_in", last.marks().length>9?(last.placeAreas()[9]-first.placeAreas()[9])/d:0);
        int primaryIndex = last.buffers().length == 7?6:1;
        double primary = last.bufferAreas()[primaryIndex]-first.bufferAreas()[primaryIndex];
        values.put("U_primary", primary/d);
        values.put("U_secondary", (last.bufferAreas()[3]-first.bufferAreas()[3])/d);
        values.put("U_full", (last.bufferAreas()[4]-first.bufferAreas()[4])/d);
        values.put("throughput", (2*(last.ends()[3]-first.ends()[3])+last.ends()[4]-first.ends()[4])/d);
        values.put("Q_full_end", (double)last.marks()[5]);
        return values;
    }

    static List<ExperimentStatistics.Estimate> estimateRuns(List<SimulationRunner.Result> runs, double a, double b) {
        List<ExperimentStatistics.Estimate> estimates = new ArrayList<>();
        double[] arrivals = new double[runs.size()], refusals = new double[runs.size()];
        for (int r = 0;r<runs.size();r++) {
            var first = at(runs.get(r), a);
            var last = at(runs.get(r), b);
            arrivals[r] = last.arrivedPairs()-first.arrivedPairs();
            refusals[r] = last.refusedPairs()-first.refusedPairs();
        }
        estimates.add(ExperimentStatistics.refusal(arrivals, refusals));
        for (String metric:METRICS) {
            double[] values = new double[runs.size()];
            for (int r = 0;r<runs.size();r++) {
                values[r] = window(runs.get(r), a, b).get(metric);
            }
            estimates.add(ExperimentStatistics.meanEstimate(metric, values));
        }
        return estimates;
    }
}
