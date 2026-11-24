import ua.stetsenkoinna.petriobj.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/** Подійний цикл імітації. */
public final class SimulationRunner {

    public record Snapshot(double time, int[] marks, int[] buffers, double[] placeAreas,
        double[] bufferAreas, long[] ends) {
        public long arrivedPairs() {
            return ends[B2NetFactory.T0_ARRIVAL];
        }

        public long refusedPairs() {
            return ends[B2NetFactory.T2_REFUSAL];
        }
    }

    public record Result(ModelConfig config, long[] starts, long[] ends, long[] windowEnds,
        int[] marks, int[] buffers, double[] placeAreas, double[] bufferAreas,
        int fullQueueMax, long invariantChecks, Map<String, Object> delaySource,
        int capacity, int primaryTransition, String variant,
        int[] placeMaxima, List<Snapshot> trajectory) {

        public double duration() {
            return config.horizon()-config.warmup();
        }

        public long arrivedPairs() {
            return ends[B2NetFactory.T0_ARRIVAL];
        }

        public long refusedPairs() {
            return ends[B2NetFactory.T2_REFUSAL];
        }

        public long arrivedPairsInWindow() {
            return windowEnds[B2NetFactory.T0_ARRIVAL];
        }

        public long refusedPairsInWindow() {
            return windowEnds[B2NetFactory.T2_REFUSAL];
        }

        public Double refusal() {
            long arrivals = arrivedPairsInWindow();
            return arrivals == 0 ? null : (double) refusedPairsInWindow() / arrivals;
        }

        public double fullQueueMean() {
            return placeAreas[B2NetFactory.P5_FULL_QUEUE]/duration();
        }

        public Map<String, Object> summary() {
            var summary = new LinkedHashMap<String, Object>();
            summary.put("config", Map.of("arrivalMean", config.arrivalMean(), "primaryMean", config.primaryMean(),
                "secondaryMean", config.secondaryMean(), "fullMean", config.fullMean(),
                "horizon", config.horizon(), "warmup", config.warmup(), "seed", config.seed()));
            summary.put("transitionCounterLabels", transitionLabels());
            summary.put("I", starts);
            summary.put("O", ends);
            summary.put("windowO", windowEnds);
            summary.put("variant", variant);
            summary.put("K", capacity);
            summary.put("delaySource", delaySource);
            summary.put("finalM", marks);
            summary.put("finalB", buffers);
            summary.put("placeAreas", placeAreas);
            summary.put("bufferAreas", bufferAreas);
            summary.put("pRefusal", refusal());
            summary.put("L_full", fullQueueMean());
            summary.put("Q_full_end", marks[B2NetFactory.P5_FULL_QUEUE]);
            summary.put("Q_full_max", fullQueueMax);
            summary.put("L_secondary", placeAreas[B2NetFactory.P3_SECONDARY_QUEUE]/duration());
            summary.put("N_full_stage", (placeAreas[B2NetFactory.P5_FULL_QUEUE]+bufferAreas[B2NetFactory.T4_FULL])/duration());
            summary.put("U_primary", bufferAreas[primaryTransition]/duration());
            summary.put("L_in", marks.length > 9 ? placeAreas[B2NetFactory.P9_INPUT_QUEUE]/duration() : 0);
            summary.put("Q_in_max", marks.length > 9 ? placeMaxima[B2NetFactory.P9_INPUT_QUEUE] : 0);
            summary.put("Q_secondary_max", placeMaxima[B2NetFactory.P3_SECONDARY_QUEUE]);
            summary.put("L_mid", (placeAreas[B2NetFactory.P3_SECONDARY_QUEUE]+placeAreas[B2NetFactory.P5_FULL_QUEUE])/duration());
            summary.put("U_secondary", bufferAreas[B2NetFactory.T3_SECONDARY]/duration());
            summary.put("U_full", bufferAreas[B2NetFactory.T4_FULL]/duration());
            summary.put("throughput", (2*windowEnds[B2NetFactory.T3_SECONDARY]+windowEnds[B2NetFactory.T4_FULL])/duration());
            summary.put("invariantChecks", invariantChecks);
            return summary;
        }

        private List<String> transitionLabels() {
            if (starts.length == 5) {
                return List.of("T0", "T1", "T2", "T3", "T4");
            }
            return List.of("T0", "T1_absent", "T2", "T3", "T4", "T5", "T6");
        }


    }
    private final ModelConfig config;
    private final PetriNet net;
    private final PetriSimulation simulation;
    private final B2NetFactory.Layout layout;
    private final long[] starts, ends, windowEnds;
    private final int capacity, refusalPlace, totalCapacity;
    private long invariantChecks;
    private BufferedWriter trace;

    public SimulationRunner(PetriNet net, ModelConfig config) {
        this.net = net;
        this.config = config;
        layout = B2NetFactory.layout(net);
        capacity = layout.capacity();
        boolean buffered = layout.primaryTransition() == B2NetFactory.T6_PRIMARY_BUFFERED;
        refusalPlace = buffered ? B2NetFactory.P10_FREE_CAPACITY : B2NetFactory.P2_PRIMARY_FREE;
        totalCapacity = buffered ? capacity + 1 : 0;
        starts = new long[layout.counterCount()];
        ends = new long[starts.length];
        windowEnds = new long[starts.length];
        simulation = new PetriSimulation(net, config.horizon(), config.warmup());
    }
    private int mark(int place) {
        return place < net.getListP().length ? net.getListP()[place].getMark() : 0;
    }

    private int active(int transition) {
        int index = layout.indexOf(transition);
        return index >= 0 ? net.getListT()[index].getBuffer() : 0;
    }

    private long started(int transitionId) {
        return transitionId < starts.length ? starts[transitionId] : 0;
    }

    private long completed(int transitionId) {
        return transitionId < ends.length ? ends[transitionId] : 0;
    }

    private void onPhase(FiringPhase phase, PetriT transition, double time) {
        int transitionId = layout.transitionIds()[transition.getNumber()];
        if (phase == FiringPhase.BEFORE_ACT_IN) {
            checkBeforeInput(transitionId);
        }
        if (phase == FiringPhase.AFTER_ACT_IN) {
            starts[transitionId]++;
        }
        if (phase == FiringPhase.AFTER_ACT_OUT) {
            ends[transitionId]++;
            if (time > config.warmup() && time <= config.horizon()) {
                windowEnds[transitionId]++;
            }
        }
        if (phase == FiringPhase.AFTER_ACT_IN || phase == FiringPhase.AFTER_ACT_OUT) {
            checkInvariants();
        }
        if (trace != null) {
            writeTraceRow(phase, transitionId, time);
        }
    }

    private void checkBeforeInput(int transitionId) {
        if (transitionId == B2NetFactory.T1_PRIMARY_DIRECT) {
            require(mark(B2NetFactory.P9_INPUT_QUEUE) == 0, "new pair bypassed waiting FIFO pair");
        }
        if (transitionId == B2NetFactory.T2_REFUSAL) {
            boolean refusalAllowed = mark(refusalPlace) == 0;
            require(refusalAllowed, "premature refusal");
        }
        if (starts[transitionId] > 10_000_000) {
            throw new IllegalStateException("Firing limit exceeded");
        }
    }

    private void writeTraceRow(FiringPhase phase, int transitionId, double time) {
        try {
            trace.write(Double.toString(time) + ",T" + transitionId + "," + phase);
            for (PetriP place : net.getListP()) {
                trace.write("," + place.getMark());
            }
            for (int index = 0; index < starts.length; index++) {
                trace.write("," + active(index));
            }
            for (long count : starts) {
                trace.write("," + count);
            }
            for (long count : ends) {
                trace.write("," + count);
            }
            trace.newLine();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void checkInvariants() {
        invariantChecks++;
        checkActiveOperations();
        checkResourceBalances();
        checkCapacityBalance();
        checkAggregateBalance();
        checkRouteBalances();
    }

    private void checkActiveOperations() {
        for (PetriP place : net.getListP()) {
            require(place.getMark() >= 0, "negative marking");
        }
        for (int transitionId = 0; transitionId < starts.length; transitionId++) {
            int operations = active(transitionId);
            require(operations >= 0 && operations <= 1
                    && operations == starts[transitionId] - ends[transitionId],
                "active operations T" + transitionId);
        }
    }

    private void checkResourceBalances() {
        require(mark(B2NetFactory.P0_GENERATOR) + active(B2NetFactory.T0_ARRIVAL) == 1, "generator resource");
        require(mark(B2NetFactory.P2_PRIMARY_FREE) + active(B2NetFactory.T1_PRIMARY_DIRECT) + active(B2NetFactory.T6_PRIMARY_BUFFERED) == 1, "primary resource");
        require(mark(B2NetFactory.P4_SECONDARY_FREE) + active(B2NetFactory.T3_SECONDARY) == 1, "secondary resource");
        require(mark(B2NetFactory.P6_FULL_FREE) + active(B2NetFactory.T4_FULL) == 1, "full resource");
    }

    private void checkCapacityBalance() {
        require(mark(B2NetFactory.P10_FREE_CAPACITY) + mark(B2NetFactory.P9_INPUT_QUEUE) + active(B2NetFactory.T5_ENQUEUE) + active(B2NetFactory.T6_PRIMARY_BUFFERED) == totalCapacity,
                "buffer capacity");
    }

    private void checkAggregateBalance() {
        long aggregatesInPlaces = (long) mark(B2NetFactory.P1_ARRIVAL_PENDING) + mark(B2NetFactory.P3_SECONDARY_QUEUE) + mark(B2NetFactory.P5_FULL_QUEUE)
            + mark(B2NetFactory.P7_SECONDARY_DONE) + mark(B2NetFactory.P8_FULL_DONE) + 2L * mark(B2NetFactory.P9_INPUT_QUEUE);
        long aggregatesInTransitions = 2L * active(B2NetFactory.T1_PRIMARY_DIRECT) + 2L * active(B2NetFactory.T2_REFUSAL)
            + 2L * active(B2NetFactory.T3_SECONDARY) + active(B2NetFactory.T4_FULL) + 2L * active(B2NetFactory.T5_ENQUEUE) + 2L * active(B2NetFactory.T6_PRIMARY_BUFFERED);
        require(2 * ends[B2NetFactory.T0_ARRIVAL] == aggregatesInPlaces + aggregatesInTransitions,
            "aggregate balance");
    }

    private void checkRouteBalances() {
        require(2 * ends[B2NetFactory.T0_ARRIVAL] == 2 * (starts[B2NetFactory.T1_PRIMARY_DIRECT] + starts[B2NetFactory.T2_REFUSAL] + started(B2NetFactory.T5_ENQUEUE)) + mark(B2NetFactory.P1_ARRIVAL_PENDING),
            "arrival decisions");
        require(2 * ends[B2NetFactory.T2_REFUSAL] == (long) mark(B2NetFactory.P5_FULL_QUEUE) + active(B2NetFactory.T4_FULL) + mark(B2NetFactory.P8_FULL_DONE), "full route balance");
        require(2 * (ends[B2NetFactory.T1_PRIMARY_DIRECT] + completed(B2NetFactory.T6_PRIMARY_BUFFERED)) == (long) mark(B2NetFactory.P3_SECONDARY_QUEUE) + 2L * active(B2NetFactory.T3_SECONDARY) + mark(B2NetFactory.P7_SECONDARY_DONE),
            "secondary route balance");
        require(mark(B2NetFactory.P7_SECONDARY_DONE) == 2 * ends[B2NetFactory.T3_SECONDARY] && mark(B2NetFactory.P8_FULL_DONE) == ends[B2NetFactory.T4_FULL], "completed counters");
        require(mark(B2NetFactory.P1_ARRIVAL_PENDING) % 2 == 0 && mark(B2NetFactory.P3_SECONDARY_QUEUE) % 2 == 0 && mark(B2NetFactory.P7_SECONDARY_DONE) % 2 == 0, "pair parity");
    }

    private void recordSettledState() {
        checkInvariants();
        require(mark(B2NetFactory.P1_ARRIVAL_PENDING) == 0 && active(B2NetFactory.T2_REFUSAL) == 0 && active(B2NetFactory.T5_ENQUEUE) == 0, "pending immediate decision");
        require(!(mark(B2NetFactory.P9_INPUT_QUEUE)>0 && mark(B2NetFactory.P2_PRIMARY_FREE)>0), "buffered pair left idle");
        if (capacity >= 0) {
            require(mark(B2NetFactory.P9_INPUT_QUEUE) <= capacity, "settled queue exceeds K");
        }
        require(!(mark(B2NetFactory.P3_SECONDARY_QUEUE) >= 2 && mark(B2NetFactory.P4_SECONDARY_FREE)>0), "secondary work left idle");
        require(!(mark(B2NetFactory.P5_FULL_QUEUE)>0 && mark(B2NetFactory.P6_FULL_FREE)>0), "full work left idle");
    }

    public Result run(Path tracePath) throws IOException {
        return run(tracePath, 0);
    }

    public Result run(Path tracePath, double sampleInterval) throws IOException {
        if (tracePath != null) {
            Files.createDirectories(tracePath.toAbsolutePath().getParent());
            trace = Files.newBufferedWriter(tracePath, StandardCharsets.UTF_8);
        }
        try {
            writeTraceHeader();
            checkInvariants();
            var result = simulation.run(sampleInterval, this::onPhase, this::recordSettledState);
            checkInvariants();
            var trajectory = new ArrayList<Snapshot>();
            for (var point : result.trajectory()) {
                trajectory.add(new Snapshot(point.time(), point.marks(), expand(point.buffers()),
                        point.placeAreas(), expand(point.bufferAreas()), expand(point.ends())));
            }
            return new Result(config, starts.clone(), ends.clone(), windowEnds.clone(),
                    result.marks(), expand(result.buffers()), result.placeAreas(),
                    expand(result.bufferAreas()), result.placeMaxima()[B2NetFactory.P5_FULL_QUEUE],
                    invariantChecks, B2NetFactory.delayDescription(net), capacity,
                    layout.primaryTransition(), layout.variant(), result.placeMaxima(), List.copyOf(trajectory));
        } finally {
            if (trace != null) {
                trace.close();
                trace = null;
            }
        }
    }

    private double[] expand(double[] values) {
        double[] result = new double[starts.length];
        for (int i = 0; i < values.length; i++) result[layout.transitionIds()[i]] = values[i];
        return result;
    }

    private int[] expand(int[] values) {
        int[] result = new int[starts.length];
        for (int i = 0; i < values.length; i++) result[layout.transitionIds()[i]] = values[i];
        return result;
    }

    private long[] expand(long[] values) {
        long[] result = new long[starts.length];
        for (int i = 0; i < values.length; i++) result[layout.transitionIds()[i]] = values[i];
        return result;
    }

    private void writeTraceHeader() throws IOException {
        if (trace == null) return;
        trace.write("time_min,transition,phase");
        for (String group : new String[]{"P", "B", "I", "O"}) {
            int count = group.equals("P") ? net.getListP().length : starts.length;
            for (int i = 0; i < count; i++) trace.write("," + group + i);
        }
        trace.newLine();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("B2 check failed: " + message);
    }
}
