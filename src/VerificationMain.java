import java.nio.file.*;
import java.util.*;

/** Перевірки курсової моделі. */
public final class VerificationMain {
    private static int checks;

    private VerificationMain() {
    }

    private static void require(boolean passed, String name) {
        checks++;
        if (!passed) {
            throw new IllegalStateException(name);
        }
    }

    private static void fractionalSamples() throws Exception {
        for (double arrival : new double[]{Math.nextDown(.3), .3, Math.nextUp(.3)}) {
            var config = ModelConfig.baseline(.95, 0, 0);
            var result = new SimulationRunner(B2NetFactory.buffered(config,
                BufferVerification.script(new double[]{arrival}, 10), 1), config).run(null, .1);
            require(result.trajectory().size() == 11, "fractional grid size");
            for (int index = 0; index < 10; index++) {
                var sample = result.trajectory().get(index);
                double time = index * .1;
                require(sample.time() == time, "fractional grid time");
                require(sample.arrivedPairs() == (time >= arrival ? 1 : 0), "sample event side");
            }
            require(result.trajectory().getLast().time() == .95, "fractional final sample");
        }
        var config = ModelConfig.baseline(.3, 0, .1);
        var result = new SimulationRunner(B2NetFactory.buffered(config,
            BufferVerification.script(new double[]{.1, .1, .1}, 1), 0), config).run(null, .1);
        require(result.arrivedPairsInWindow() == 1 && result.refusedPairsInWindow() == 1,
            "floating arrival after horizon");
        require(result.trajectory().getLast().time() == .3, "horizon sample");
    }

    private static Map<String, Object> place(String id, String name, int initial) {
        return Map.of("id", id, "name", name, "initial", initial);
    }

    private static Map<String, Object> transition(String id, String name, double delay,
            int priority, Map<String, Integer> inputs, Map<String, Integer> outputs) {
        var timing = delay == 0 ? Map.of("kind", "constant", "value", 0.0)
                : Map.of("kind", "exp", "mean", delay);
        return Map.of("id", id, "name", name, "delay", timing, "priority", priority,
                "probability", 1.0, "inputs", inputs, "outputs", outputs);
    }

    private static void verifyStructure(boolean buffered, int capacity) throws Exception {
        var config = ModelConfig.baseline(220, 20261101, 0);
        var delays = DelaySource.seeded(config.seed());
        var net = buffered ? B2NetFactory.buffered(config, delays, capacity)
                : B2NetFactory.baseline(config, delays);
        var actual = B2NetFactory.structure(net);

        // Еталон із розділу 2.
        var places = List.of(
            place("P0", "generator_permission", 1),
            place("P1", "arrived_pending_decision", 0),
            place("P2", "primary_free", 1),
            place("P3", "Q_secondary", 0),
            place("P4", "secondary_free", 1),
            place("P5", "Q_full", 0),
            place("P6", "full_free", 1),
            place("P7", "completed_secondary", 0),
            place("P8", "completed_full", 0),
            place("P9", "Q_in", 0),
            place("P10", "primary_free_capacity", capacity + 1)
        );
        var transitions = List.of(
            transition("T0", "arrival", 30.0, 0,
                Map.of("P0", 1), Map.of("P0", 1, "P1", 2)),
            transition("T1", "primary_direct", 30.0, 3,
                Map.of("P1", 2, "P2", 1), Map.of("P2", 1, "P3", 2)),
            transition("T2", "primary_refusal", 0.0, 1,
                Map.of("P1", 2), Map.of("P5", 2)),
            transition("T3", "secondary", 30.0, 0,
                Map.of("P3", 2, "P4", 1), Map.of("P4", 1, "P7", 2)),
            transition("T4", "full", 100.0, 0,
                Map.of("P5", 1, "P6", 1), Map.of("P6", 1, "P8", 1)),
            transition("T5", "enqueue", 0.0, 2,
                Map.of("P1", 2, "P10", 1), Map.of("P9", 1)),
            transition("T6", "primary", 30.0, 3,
                Map.of("P2", 1, "P9", 1), Map.of("P10", 1, "P2", 1, "P3", 2))
        );
        var expectedPlaces = buffered ? places : places.subList(0, 9);
        var expectedTransitions = buffered
                ? List.of(transitions.get(0), transitions.get(2), transitions.get(3),
                          transitions.get(4), transitions.get(5), transitions.get(6))
                : transitions.subList(0, 5);
        String variant = buffered ? "buffered" : "baseline";
        require(actual.get("variant").equals(variant) && actual.get("capacity").equals(capacity),
                "Network variant or capacity mismatch");
        require(expectedPlaces.equals(actual.get("places")), "Places differ from section 2");
        require(expectedTransitions.equals(actual.get("transitions")), "Transitions differ from section 2");
    }

    private static void replaySavedRuns() throws Exception {
        Path csv = Path.of("data/main/capacity-S-v2/K18_H10000000_A2000000_R2109_S400000000/replications.csv");
        if (!Files.exists(csv)) throw new IllegalStateException("Saved main series is required");
        var rows = Files.readAllLines(csv);
        String[] names = rows.getFirst().split(",");
        for (int index : new int[]{0, 1054, 2108}) {
            String[] cells = rows.get(index + 1).split(",");
            long seed = Long.parseLong(cells[1]);
            var config = ModelConfig.baseline(10_000_000, seed, 2_000_000);
            var result = new SimulationRunner(B2NetFactory.buffered(config,
                    DelaySource.seeded(seed), 18), config).run(null);
            require(result.arrivedPairsInWindow() == Long.parseLong(cells[2]), "replayed arrivals");
            require(result.refusedPairsInWindow() == Long.parseLong(cells[3]), "replayed refusals");
            var actual = result.summary();
            for (int column = 4; column < names.length; column++) {
                double expected = Double.parseDouble(cells[column]);
                double value = ((Number) actual.get(names[column])).doubleValue();
                require(Double.doubleToLongBits(expected) == Double.doubleToLongBits(value),
                        "replayed " + names[column]);
            }
        }
        System.out.println("Historical full-model replay: 3/3 runs, all saved metrics match exactly");
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of("output");
        checks = 0;
        verifyStructure(false, 0);
        verifyStructure(true, 0);
        verifyStructure(true, 18);
        System.out.println("Formal structure: N0, NK (K=0) and NK (K=18) match");
        Verification.execute(output);
        BufferVerification.execute(output);
        AlgorithmVerification.main(new String[0]);
        InputVerification.main(new String[0]);
        ResultProcessing.verify();
        checks = 0;
        fractionalSamples();
        System.out.println("Fractional time checks: " + checks);
        if (args.length > 0 && args[0].equals("replay")) replaySavedRuns();
    }
}
