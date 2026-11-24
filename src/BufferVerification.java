import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Перевірки буферної моделі. */
public final class BufferVerification {
    private static final List<Map<String, Object>> checks = new ArrayList<>();
    private static final Map<String, Object> scenarios = new LinkedHashMap<>();

    private BufferVerification() {
    }

    public static DelaySource script(double[] arrivals, double primary) {
        return DelaySource.scripted(new double[][]{
                arrivals, {
            }, {
            }, {
            }, {
            }, {
            }, {
            }, {
            }
        },
            new double[]{
                1_000_000, primary, 0, 30, 100, 0, 0, primary
        });
    }

    private static SimulationRunner.Result run(Path output, String id, int k, double end, DelaySource source) throws Exception {
        var c = ModelConfig.baseline(end, 0, 0);
        var r = new SimulationRunner(B2NetFactory.buffered(c, source, k), c)
            .run(output.resolve(id+".trace.csv"), 10);
        scenarios.put(id, r.summary());
        return r;
    }

    private static void expect(String id, String name, double expected, double actual) {
        checks.add(Map.of("scenario", id, "quantity", name, "expected", expected, "actual", actual,
            "passed", Double.isFinite(actual) && Math.abs(expected-actual)<1e-9*Math.max(1, Math.abs(expected))));
    }

    private static void condition(String id, String name, boolean passed) {
        expect(id, name, 1, passed ? 1 : 0);
    }

    public static void execute(Path output) throws Exception {
        checks.clear();
        scenarios.clear();
        Path directory = output.resolve("buffer-verification");
        Files.createDirectories(directory);
        var c = ModelConfig.baseline(10_000, 17, 0);
        var n0 = new SimulationRunner(B2NetFactory.baseline(c, DelaySource.seeded(17)), c).run(null);
        var nk0 = new SimulationRunner(B2NetFactory.buffered(c, DelaySource.seeded(17), 0), c).run(null);
        scenarios.put("B01", nk0.summary());
        condition("B01", "K0_same_marks", Arrays.equals(n0.marks(), Arrays.copyOf(nk0.marks(), 9)));
        condition("B01", "K0_same_starts", n0.starts()[1] == nk0.starts()[6] && n0.starts()[0] == nk0.starts()[0] && n0.starts()[2] == nk0.starts()[2] && n0.starts()[3] == nk0.starts()[3] && n0.starts()[4] == nk0.starts()[4]);
        condition("B01", "K0_same_ends", n0.ends()[1] == nk0.ends()[6] && n0.arrivedPairs() == nk0.arrivedPairs() && n0.refusedPairs() == nk0.refusedPairs() && n0.ends()[3] == nk0.ends()[3] && n0.ends()[4] == nk0.ends()[4]);
        condition("B01", "K0_same_place_integrals", Arrays.equals(n0.placeAreas(), Arrays.copyOf(nk0.placeAreas(), 9)));
        condition("B01", "K0_same_buffer_integrals", n0.bufferAreas()[1] == nk0.bufferAreas()[6] && n0.bufferAreas()[0] == nk0.bufferAreas()[0] && n0.bufferAreas()[2] == nk0.bufferAreas()[2] && n0.bufferAreas()[3] == nk0.bufferAreas()[3] && n0.bufferAreas()[4] == nk0.bufferAreas()[4]);
        var v03 = run(directory, "B01_V03", 0, 220, Verification.twoPairs());
        expect("B01_V03", "arrivals", 2, v03.arrivedPairs());
        expect("B01_V03", "refusals", 1, v03.refusedPairs());
        var tie0 = run(directory, "B01_tie", 0, 130, script(new double[]{
                10, 30
        }, 30));
        expect("B01_tie", "no_refusal", 0, tie0.refusedPairs());
        expect("B01_tie", "both_pairs_complete", 4, tie0.marks()[7]);
        var queued = run(directory, "B02", 1, 100, script(new double[]{
                10, 10
        }, 30));
        expect("B02", "no_refusals", 0, queued.refusedPairs());
        expect("B02", "two_accepted_pairs", 2, queued.ends()[5]);
        expect("B02", "two_primary_completions", 2, queued.ends()[6]);
        expect("B02", "direct_transition_absent", 0, queued.ends()[1]);
        expect("B02", "completed_aggregates", 4, queued.marks()[7]);
        expect("B02", "queue_pair_minutes", 20, queued.placeAreas()[9]);
        expect("B02", "primary_busy_minutes_both_routes", 60, queued.bufferAreas()[6]);
        var overflow = run(directory, "B03", 1, 230, script(new double[]{
                10, 10, 10
        }, 30));
        expect("B03", "refused_pairs", 1, overflow.refusedPairs());
        expect("B03", "secondary_completed", 4, overflow.marks()[7]);
        expect("B03", "full_completed", 2, overflow.marks()[8]);
        expect("B03", "max_waiting_pairs", 1, overflow.placeMaxima()[9]);
        var tie = run(directory, "B04", 1, 130, script(new double[]{
                10, 10, 20
        }, 30));
        expect("B04", "no_false_refusal_at_tie", 0, tie.refusedPairs());
        expect("B04", "all_pairs_use_T6", 3, tie.ends()[6]);
        expect("B04", "T1_absent", 0, tie.starts()[1]);
        expect("B04", "all_three_pairs_complete", 6, tie.marks()[7]);
        expect("B04", "queue_pair_minutes", 50, tie.placeAreas()[9]);
        var trace = Files.readAllLines(directory.resolve("B04.trace.csv"));
        int returned = -1, accepted = -1;
        for (int n = 0; n < trace.size(); n++) {
            if (trace.get(n).startsWith("40.0,T6,AFTER_ACT_OUT,")) {
                returned = n;
            }
            if (trace.get(n).startsWith("40.0,T5,BEFORE_ACT_IN,")) {
                accepted = n;
            }
        }
        condition("B04", "slot_return_precedes_new_enqueue", returned >= 0 && accepted>returned);
        int primaryStart = -1, admissionEnd = -1;
        for (int n = 0;n<trace.size();n++) {
            if (trace.get(n).startsWith("40.0,T6,AFTER_ACT_IN,")) {
                primaryStart = n;
            }
            if (trace.get(n).startsWith("40.0,T5,AFTER_ACT_OUT,")) {
                admissionEnd = n;
            }
        }
        condition("B04", "stock_zero_output_is_separate_microstep", primaryStart >= 0 && primaryStart<accepted && accepted<admissionEnd);
        condition("B04", "trajectory_contains_no_duplicate_times", tie.trajectory().stream().map(SimulationRunner.Snapshot::time).distinct().count() == tie.trajectory().size());
        var boundary = run(directory, "B04_boundary", 0, 10, script(new double[]{
                10
        }, 30));
        expect("B04_boundary", "horizon_zero_steps_drained", 0, boundary.buffers()[5]);
        expect("B04_boundary", "primary_started_at_horizon", 1, boundary.buffers()[6]);
        expect("B04_boundary", "queue_settled_at_horizon", 0, boundary.marks()[9]);
        var warmConfig = ModelConfig.baseline(130, 0, 25);
        var warm = new SimulationRunner(B2NetFactory.buffered(warmConfig, script(new double[]{
                10, 10, 20
        }, 30), 1), warmConfig).run(null, 10);
        expect("B04_warmup", "clipped_primary_integral", 75, warm.bufferAreas()[6]);
        expect("B04_warmup", "clipped_queue_integral", 45, warm.placeAreas()[9]);
        var full = run(directory, "B05", 2, 40, script(new double[]{
                10, 10, 10, 10
        }, 100));
        expect("B05", "two_pairs_wait", 2, full.marks()[9]);
        expect("B05", "no_free_slots", 0, full.marks()[10]);
        expect("B05", "one_overflow_pair", 1, full.refusedPairs());
        expect("B05", "queue_pair_minutes", 30, full.placeAreas()[9]);
        var start = run(directory, "B06", 1, 40, script(new double[]{
                10, 10
        }, 30));
        expect("B06", "S_excludes_active_and_waiting", 1, start.marks()[10]);
        expect("B06", "buffered_primary_active", 1, start.buffers()[6]);
        expect("B06", "position_count", 11, start.marks().length);
        expect("B06", "primary_resource_still_busy", 0, start.marks()[2]);
        expect("B06", "queue_empty", 0, start.marks()[9]);
        var sample = start.trajectory().stream().filter(s -> s.time() == 40).findFirst().orElseThrow();
        expect("B06", "trajectory_at_event_uses_settled_state", 1, sample.buffers()[6]);
        expect("B06", "prefix_queue_integral_at_40", 20, sample.placeAreas()[9]);
        expect("B06", "prefix_primary_integral_at_40", 30, sample.bufferAreas()[6]);
        var mid = start.trajectory().stream().filter(s -> s.time() == 30).findFirst().orElseThrow();
        expect("B06", "prefix_queue_integral_inside_event_interval", 10, mid.placeAreas()[9]);
        for (int k : new int[]{
                0, 4, 5, 18
        }) {
            var cfg = ModelConfig.baseline(100_000, 7000+k, 0);
            var r = new SimulationRunner(B2NetFactory.buffered(cfg, DelaySource.seeded(cfg.seed()), k), cfg).run(null);
            var observation = r.summary();
            observation.put("analyticPRefusal", 1.0/(k+2));
            scenarios.put("B07_K"+k, observation);
            var replay = new SimulationRunner(B2NetFactory.buffered(cfg, DelaySource.seeded(cfg.seed()), k), cfg).run(null);
            condition("B07_K"+k, "same_seed_identical_buffered_results", Json.encode(r.summary()).equals(Json.encode(replay.summary())));
            condition("B07_K"+k, "queue_does_not_exceed_K", r.placeMaxima()[9] <= k);
        }
        var mean = ExperimentStatistics.meanEstimate("reference", new double[]{
                1, 2, 3
        }, 0.5);
        expect("B08", "sample_mean", 2, mean.estimate());
        expect("B08", "sample_sd", 1, mean.sd());
        expect("B08", "Chebyshev_n_for_sd1_epsilon_half_beta095", 80, mean.chebyshevN());
        condition("B08", "Student_interval_uses_t_not_normal", mean.halfWidth()>2.48 && mean.halfWidth()<2.49);
        var ratio = ExperimentStatistics.refusal(new double[]{
                10, 20, 30
        }, new double[]{
                1, 4, 9
        });
        expect("B08", "mean_of_run_refusals", 0.2, ratio.estimate());
        expect("B08", "run_refusal_sd", 0.1, ratio.sd());
        Json.write(output.resolve("buffer-verification.json"), Map.of("checks", checks, "scenarios", scenarios));
        var csv = new StringBuilder("scenario,quantity,expected,actual,passed\n");
        for (var row : checks) {
            csv.append(row.get("scenario")).append(',').append(row.get("quantity")).append(',')
                .append(row.get("expected")).append(',').append(row.get("actual")).append(',').append(row.get("passed")).append('\n');
        }
        Files.writeString(output.resolve("buffer-verification.csv"), csv, StandardCharsets.UTF_8);
        long failed = checks.stream().filter(row -> !Boolean.TRUE.equals(row.get("passed"))).count();
        System.out.println("Buffered verification: "+(checks.size()-failed)+"/"+checks.size()+" checks passed");
        if (failed>0) {
            throw new IllegalStateException("Buffered verification failures: "+failed);
        }
    }
}
