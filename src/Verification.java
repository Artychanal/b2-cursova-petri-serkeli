import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Контрольні сценарії моделі. */
public final class Verification {

    private Verification() {
    }
    private static final List<Map<String, Object>> rows = new ArrayList<>();
    private static final Map<String, Map<String, Object>> scenarios = new LinkedHashMap<>();

    static DelaySource twoPairs() {
        return script(new double[]{
                10, 10
        }, 1_000_000, 30, 30, 100);
    }

    private static DelaySource script(double[] arrivals, double arrivalFallback,
        double primary, double secondary, double full) {
        return DelaySource.scripted(new double[][]{
                arrivals, {
            }, {
            }, {
            }, {
            }
        },
            new double[]{
                arrivalFallback, primary, 0, secondary, full
        });
    }

    private static SimulationRunner.Result run(Path root, String id, double end, double warmup,
        DelaySource delays) throws Exception {
        var config = ModelConfig.baseline(end, 0, warmup);
        var result = new SimulationRunner(B2NetFactory.baseline(config, delays), config)
            .run(root.resolve(id+".trace.csv"));
        scenarios.put(id.substring(0, 3), result.summary());
        return result;
    }

    private static void expect(String id, String quantity, double expected, double actual) {
        boolean passed = Double.isFinite(actual) && Math.abs(expected-actual) <= 1e-10*Math.max(1, Math.abs(expected));
        var row = new LinkedHashMap<String, Object>();
        row.put("scenario", id);
        row.put("quantity", quantity);
        row.put("expected", expected);
        row.put("actual", actual);
        row.put("passed", passed);
        rows.add(row);
    }

    private static void condition(String id, String quantity, boolean value) {
        expect(id, quantity, 1, value ? 1 : 0);
    }

    public static void execute(Path output) throws Exception {
        rows.clear();
        scenarios.clear();
        Path root = output.resolve("verification");
        Files.createDirectories(root);
        var none = run(root, "V01_no_arrivals", 100, 0, script(new double[]{
                1000
        }, 1000, 30, 30, 100));
        expect("V01", "arrived_pairs", 0, none.arrivedPairs());
        expect("V01", "L_full", 0, none.fullQueueMean());
        condition("V01", "refusal_undefined_without_arrivals", none.refusal() == null);
        var single = run(root, "V02_one_pair", 70, 0, script(new double[]{
                10
        }, 1_000_000, 30, 30, 100));
        expect("V02", "arrived_pairs", 1, single.arrivedPairs());
        expect("V02", "secondary_completed_aggregates_at_70", 2, single.marks()[7]);
        expect("V02", "full_completed_aggregates", 0, single.marks()[8]);
        expect("V02", "primary_busy_area_min", 30, single.bufferAreas()[1]);
        expect("V02", "secondary_busy_area_min", 30, single.bufferAreas()[3]);
        var two = run(root, "V03_two_pairs", 220, 0, twoPairs());
        expect("V03", "arrived_pairs", 2, two.arrivedPairs());
        expect("V03", "refused_pairs", 1, two.refusedPairs());
        expect("V03", "P_refusal", 0.5, two.refusal());
        expect("V03", "secondary_completed_aggregates", 2, two.marks()[7]);
        expect("V03", "full_completed_aggregates", 2, two.marks()[8]);
        expect("V03", "full_busy_area_min", 200, two.bufferAreas()[4]);
        expect("V03", "L_full", 100.0/220, two.fullQueueMean());
        expect("V03", "Q_full_max", 1, two.fullQueueMax());
        var c = ModelConfig.baseline(100, 0, 0);
        scenarios.put("V04", Map.of("config", Map.of("horizon", 100, "warmup", 0),
            "delaySource", Map.of("kind", "isolated_predicate_check_no_time_simulation"),
            "note", "Artificial M3=1 then 2, M4=1 then 0; no runner or global-state trajectory"));
        var predicateNet = B2NetFactory.baseline(c, DelaySource.seeded(0));
        predicateNet.getListP()[3].setMark(1);
        condition("V04", "one_aggregate_cannot_start_secondary", !predicateNet.getListT()[3].condition(predicateNet.getListP()));
        predicateNet.getListP()[3].setMark(2);
        condition("V04", "two_aggregates_can_start_secondary", predicateNet.getListT()[3].condition(predicateNet.getListP()));
        predicateNet.getListP()[4].setMark(0);
        condition("V04", "secondary_requires_resource", !predicateNet.getListT()[3].condition(predicateNet.getListP()));
        var before = run(root, "V05_before_full_completion", 119, 0, twoPairs());
        expect("V05", "full_completions_before_120", 0, before.ends()[4]);
        expect("V05", "full_active_channels", 1, before.buffers()[4]);
        expect("V05", "one_aggregate_waits", 1, before.marks()[5]);
        var at = run(root, "V06_at_full_completion", 120, 0, twoPairs());
        expect("V06", "full_completions_at_120", 1, at.ends()[4]);
        expect("V06", "second_full_operation_active", 1, at.buffers()[4]);
        expect("V06", "queue_after_dispatch", 0, at.marks()[5]);
        var tie = run(root, "V07_arrival_completion_tie", 50, 0,
            script(new double[]{
                10, 20
        }, 1_000_000, 20, 30, 100));
        expect("V07", "primary_starts", 2, tie.starts()[1]);
        expect("V07", "refusals_at_completion_tie", 0, tie.refusedPairs());
        var boundary = run(root, "V08_warmup_boundary", 220, 20, twoPairs());
        expect("V08", "arrivals_in_open_left_window", 0, boundary.arrivedPairsInWindow());
        expect("V08", "refusals_in_open_left_window", 0, boundary.refusedPairsInWindow());
        expect("V08", "L_full_after_warmup", 0.5, boundary.fullQueueMean());
        var cut = run(root, "V09_warmup_inside_interval", 220, 25, twoPairs());
        expect("V09", "clipped_queue_area_min", 95, cut.placeAreas()[5]);
        expect("V09", "clipped_primary_busy_area_min", 15, cut.bufferAreas()[1]);
        expect("V09", "L_full", 95.0/195, cut.fullQueueMean());
        var overloaded = run(root, "V10_primary_channel_limit", 5, 0,
            script(new double[]{
                1
        }, 1, 100, 30, 100));
        expect("V10", "arrived_pairs_including_t5", 5, overloaded.arrivedPairs());
        expect("V10", "only_one_primary_operation", 1, overloaded.starts()[1]);
        expect("V10", "refused_pairs", 4, overloaded.refusedPairs());
        expect("V10", "full_queue_aggregates", 7, overloaded.marks()[5]);
        var seededConfig = ModelConfig.baseline(10_000, 17, 0);
        var r1 = new SimulationRunner(B2NetFactory.baseline(seededConfig, DelaySource.seeded(17)), seededConfig).run(null);
        var r2 = new SimulationRunner(B2NetFactory.baseline(seededConfig, DelaySource.seeded(17)), seededConfig).run(null);
        scenarios.put("V11", r1.summary());
        condition("V11", "same_seed_identical_results", Json.encode(r1.summary()).equals(Json.encode(r2.summary())));
        var longConfig = ModelConfig.baseline(100_000, 20261101, 0);
        var longRun = new SimulationRunner(B2NetFactory.baseline(longConfig, DelaySource.seeded(longConfig.seed())), longConfig).run(null);
        scenarios.put("V12", longRun.summary());
        condition("V12", "primary_refusal_near_analytic_half_tolerance_0.04", Math.abs(longRun.refusal()-0.5) < 0.04);
        Json.write(root.resolve("V12_stochastic_check.json"), longRun.summary());
        for (var row : rows) {
            var scenario = scenarios.get(row.get("scenario").toString());
            var configuration = (Map<?, ?>)scenario.get("config");
            var delay = (Map<?, ?>)scenario.get("delaySource");
            row.put("horizon_min", configuration.get("horizon"));
            row.put("warmup_min", configuration.get("warmup"));
            row.put("delay_kind", delay.get("kind"));
        }
        Json.write(output.resolve("verification.json"), Map.of("kind", "baseline_implementation_checks",
            "deterministic_scenarios_use_scripted_delays", true, "scenarios", scenarios, "checks", rows));
        var csv = new StringBuilder("scenario,quantity,expected,actual,passed,horizon_min,warmup_min,delay_kind\n");
        for (var row : rows) {
            csv.append(row.get("scenario")).append(',').append(row.get("quantity")).append(',')
                .append(row.get("expected")).append(',').append(row.get("actual")).append(',').append(row.get("passed")).append(',')
                .append(row.get("horizon_min")).append(',').append(row.get("warmup_min")).append(',').append(row.get("delay_kind")).append('\n');
        }
        Files.writeString(output.resolve("verification.csv"), csv, StandardCharsets.UTF_8);
        long failed = rows.stream().filter(row -> !Boolean.TRUE.equals(row.get("passed"))).count();
        System.out.println("Baseline verification: "+(rows.size()-failed)+"/"+rows.size()+" checks passed");
        if (failed != 0) {
            throw new IllegalStateException("Verification failures: "+failed);
        }
    }
}
