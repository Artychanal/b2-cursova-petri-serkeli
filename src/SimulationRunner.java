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
        int capacity, int[] placeMaxima, List<Snapshot> trajectory) {

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
            summary.put("variant", variantName());
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
            summary.put("U_primary", (bufferAreas.length == 7 ? bufferAreas[B2NetFactory.T6_PRIMARY_BUFFERED] : bufferAreas[B2NetFactory.T1_PRIMARY_DIRECT])/duration());
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

        private String variantName() {
            if (marks.length == 9) {
                return "baseline";
            }
            return "buffered";
        }
    }
    private final ModelConfig config;
    private final PetriNet net;
    private final PetriSim simulator;
    private final long[] starts, ends, windowEnds;
    private final double[] placeAreas, bufferAreas;
    private final int capacity;
    private final int[] placeMaxima;
    private final List<Snapshot> trajectory = new ArrayList<>();
    private double sampleEvery;
    private long sampleIndex;
    private double lastAccountingTime;
    private long invariantChecks;
    private int fullQueueMax;
    private BufferedWriter trace;
    private boolean used;

    public SimulationRunner(PetriNet net, ModelConfig config) {
        this.net = net;
        this.config = config;
        int placeCount = net.getListP().length;
        int transitionCount = net.getListT().length;
        boolean buffered = placeCount == 11 && transitionCount == 6;
        if (!(buffered || (placeCount == 9 && transitionCount == 5))) {
            throw new IllegalArgumentException("Expected N0 or N_K");
        }
        // Формальні номери переходів.
        int counterCount = buffered ? 7 : 5;
        starts = new long[counterCount];
        ends = new long[counterCount];
        windowEnds = new long[counterCount];
        placeAreas = new double[placeCount];
        bufferAreas = new double[counterCount];
        placeMaxima = new int[placeCount];
        if (buffered) {
            capacity = net.getListP()[B2NetFactory.P10_FREE_CAPACITY].getMark() - 1;
        } else {
            capacity = 0;
        }
        simulator = new PetriSim(net);
        simulator.setFiringListener(this::onPhase);
    }
    // Стан елементів мережі.

    private int mark(int place) {
        return place < net.getListP().length ? net.getListP()[place].getMark() : 0;
    }

    private int active(int transition) {
        int index = B2NetFactory.denseIndex(net, transition);
        return index >= 0 ? net.getListT()[index].getBuffer() : 0;
    }

    private long started(int transitionId) {
        return transitionId < starts.length ? starts[transitionId] : 0;
    }

    private long completed(int transitionId) {
        return transitionId < ends.length ? ends[transitionId] : 0;
    }

    private void onPhase(FiringPhase phase, PetriT transition, double time) {
        int transitionId = B2NetFactory.logicalId(net, transition.getNumber());
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
            boolean refusalAllowed = starts.length == 5 ? mark(B2NetFactory.P2_PRIMARY_FREE) == 0 : mark(B2NetFactory.P10_FREE_CAPACITY) == 0;
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
        int totalCapacity = starts.length == 5 ? 0 : capacity + 1;
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

    private void recordSettledState(double time) {
        checkInvariants();
        require(mark(B2NetFactory.P1_ARRIVAL_PENDING) == 0 && active(B2NetFactory.T2_REFUSAL) == 0 && active(B2NetFactory.T5_ENQUEUE) == 0, "pending immediate decision");
        require(!(mark(B2NetFactory.P9_INPUT_QUEUE)>0 && mark(B2NetFactory.P2_PRIMARY_FREE)>0), "buffered pair left idle");
        if (capacity >= 0) {
            require(mark(B2NetFactory.P9_INPUT_QUEUE) <= capacity, "settled queue exceeds K");
        }
        require(!(mark(B2NetFactory.P3_SECONDARY_QUEUE) >= 2 && mark(B2NetFactory.P4_SECONDARY_FREE)>0), "secondary work left idle");
        require(!(mark(B2NetFactory.P5_FULL_QUEUE)>0 && mark(B2NetFactory.P6_FULL_FREE)>0), "full work left idle");
        if (time >= config.warmup()) {
            updateMaxima();
        }
        if (sampleEvery > 0 && sampleIndex*sampleEvery == time) {
            sampleTrajectory(time);
        }
    }

    private void updateMaxima() {
        for (int j = 0; j < placeMaxima.length; j++) {
            placeMaxima[j] = Math.max(placeMaxima[j], mark(j));
        }
        fullQueueMax = placeMaxima[B2NetFactory.P5_FULL_QUEUE];
    }

    private void sampleTrajectory(double time) {
        int[] marks = new int[placeAreas.length], buffers = new int[bufferAreas.length];
        for (int j = 0; j < marks.length; j++) {
            marks[j] = mark(j);
        }
        for (int j = 0; j < buffers.length; j++) {
            buffers[j] = active(j);
        }
        double[] sampledPlaces = placeAreas.clone(), sampledBuffers = bufferAreas.clone();
        double tail = Math.max(0, time-Math.max(lastAccountingTime, config.warmup()));
        for (int j = 0; j < marks.length; j++) {
            sampledPlaces[j] += marks[j]*tail;
        }
        for (int j = 0; j < buffers.length; j++) {
            sampledBuffers[j] += buffers[j]*tail;
        }
        trajectory.add(new Snapshot(time, marks, buffers, sampledPlaces, sampledBuffers, ends.clone()));
        sampleIndex++;
        if (trajectory.size() > 1_000_000) {
            throw new IllegalArgumentException("Too many trajectory samples");
        }
    }

    private void integrate(double from, double to) {
        while (sampleEvery > 0 && sampleIndex*sampleEvery < to) {
            sampleTrajectory(sampleIndex*sampleEvery);
        }
        double windowStart = Math.max(from, config.warmup());
        double windowEnd = Math.min(to, config.horizon());
        double dt = Math.max(0, windowEnd - windowStart);
        lastAccountingTime = to;
        if (dt == 0) {
            return;
        }
        for (int j = 0; j < placeAreas.length; j++) {
            placeAreas[j] += mark(j)*dt;
        }
        for (int j = 0; j < bufferAreas.length; j++) {
            bufferAreas[j] += active(j)*dt;
        }
        updateMaxima();
    }

    public Result run(Path tracePath) throws IOException {
        return run(tracePath, 0);
    }

    public Result run(Path tracePath, double sampleInterval) throws IOException {
        if (!Double.isFinite(sampleInterval) || sampleInterval < 0) {
            throw new IllegalArgumentException("Invalid sample interval");
        }
        sampleEvery = sampleInterval;
        if (used) {
            throw new IllegalStateException("Create a fresh runner/net for each run");
        }
        used = true;
        openTrace(tracePath);
        try {
            writeTraceHeader();
            initializeSimulation();
            processEvents();
            return createResult();
        } finally {
            if (trace != null) {
                trace.close();
                trace = null;
            }
        }
    }

    private void openTrace(Path tracePath) throws IOException {
        if (tracePath != null) {
            Files.createDirectories(tracePath.toAbsolutePath().getParent());
            trace = Files.newBufferedWriter(tracePath, StandardCharsets.UTF_8);
        }
    }

    private void writeTraceHeader() throws IOException {
        if (trace == null) {
            return;
        }
        trace.write("time_min,transition,phase");
        for (String group : new String[]{
                "P", "B", "I", "O"
        }) {
            int count = group.equals("P") ? placeAreas.length : bufferAreas.length;
            for (int index = 0; index < count; index++) {
                trace.write("," + group + index);
            }
        }
        trace.newLine();
    }

    private void initializeSimulation() {
        checkInvariants();
        simulator.setSimulationTime(config.horizon());
        simulator.setTimeCurr(0);
        simulator.input();
        if (simulator.getTimeMin() > 0) {
            recordSettledState(0);
        }
    }

    private void processEvents() {
        double current = 0;
        int microsteps = 0;
        while (true) {
            double next = simulator.getTimeMin();
            if (next == Double.MAX_VALUE || next > config.horizon()) {
                break;
            }
            require(next >= current, "time reversed");
            if (next == current) {
                if (++microsteps > 100_000) {
                    throw new IllegalStateException("Nonterminating zero-time closure");
                }
            } else {
                microsteps = 0;
            }
            integrate(current, next);
            current = next;
            simulator.setTimeCurr(current);
            simulator.output();
            simulator.input();
            if (simulator.getTimeMin() > current) {
                recordSettledState(current);
            }
        }
        integrate(current, config.horizon());
        simulator.setTimeCurr(config.horizon());
        checkInvariants();
        if (sampleEvery > 0 && (trajectory.isEmpty()
                || trajectory.getLast().time() != config.horizon())) {
            sampleTrajectory(config.horizon());
        }
    }

    private Result createResult() {
        int[] marks = new int[placeAreas.length];
        int[] buffers = new int[bufferAreas.length];
        for (int index = 0; index < marks.length; index++) {
            marks[index] = mark(index);
        }
        for (int index = 0; index < buffers.length; index++) {
            buffers[index] = active(index);
        }
        return new Result(config, starts.clone(), ends.clone(), windowEnds.clone(), marks, buffers,
            placeAreas.clone(), bufferAreas.clone(), fullQueueMax, invariantChecks,
            B2NetFactory.delayDescription(net), capacity, placeMaxima.clone(), List.copyOf(trajectory));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("B2 invariant failed: "+message);
        }
    }
}
