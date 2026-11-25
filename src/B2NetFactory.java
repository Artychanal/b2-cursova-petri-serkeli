import ua.stetsenkoinna.petriobj.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Побудова мереж Петрі. */
public final class B2NetFactory {
    // Формальні номери позицій.
    static final int P0_GENERATOR = 0;
    static final int P1_ARRIVAL_PENDING = 1;
    static final int P2_PRIMARY_FREE = 2;
    static final int P3_SECONDARY_QUEUE = 3;
    static final int P4_SECONDARY_FREE = 4;
    static final int P5_FULL_QUEUE = 5;
    static final int P6_FULL_FREE = 6;
    static final int P7_SECONDARY_DONE = 7;
    static final int P8_FULL_DONE = 8;
    static final int P9_INPUT_QUEUE = 9;
    static final int P10_FREE_CAPACITY = 10;

    // Формальні номери переходів.
    static final int T0_ARRIVAL = 0;
    static final int T1_PRIMARY_DIRECT = 1;
    static final int T2_REFUSAL = 2;
    static final int T3_SECONDARY = 3;
    static final int T4_FULL = 4;
    static final int T5_ENQUEUE = 5;
    static final int T6_PRIMARY_BUFFERED = 6;

    private static final int[] BASELINE_TRANSITIONS = {
        T0_ARRIVAL, T1_PRIMARY_DIRECT, T2_REFUSAL, T3_SECONDARY, T4_FULL
    };
    private static final int[] BUFFERED_TRANSITIONS = {
        T0_ARRIVAL, T2_REFUSAL, T3_SECONDARY, T4_FULL, T5_ENQUEUE, T6_PRIMARY_BUFFERED
    };

    private B2NetFactory() {
    }

    public static PetriNet baseline(ModelConfig config, DelaySource delays)
    throws ExceptionInvalidTimeDelay {
        return create(config, delays, baselineSpec(), 0);
    }

    public static PetriNet buffered(ModelConfig config, DelaySource delays, int capacity)
    throws ExceptionInvalidTimeDelay {
        if (capacity < 0) {
            throw new IllegalArgumentException("K must be nonnegative");
        }
        return create(config, delays, bufferedSpec(capacity), capacity);
    }

    private record PlaceSpec(String name, int initialMark) {
    }

    private record NetworkSpec(String name, String variant, int primaryTransition,
            PlaceSpec[] places, int[] transitionIds, ArcSpec[] inputs, ArcSpec[] outputs) {
    }

    private static PlaceSpec[] baselinePlaces() {
        return new PlaceSpec[]{
            new PlaceSpec("generator_permission", 1),
            new PlaceSpec("arrived_pending_decision", 0),
            new PlaceSpec("primary_free", 1),
            new PlaceSpec("Q_secondary", 0),
            new PlaceSpec("secondary_free", 1),
            new PlaceSpec("Q_full", 0),
            new PlaceSpec("full_free", 1),
            new PlaceSpec("completed_secondary", 0),
            new PlaceSpec("completed_full", 0)
        };
    }

    private static NetworkSpec baselineSpec() {
        return new NetworkSpec("B2_N0", "baseline", T1_PRIMARY_DIRECT,
                baselinePlaces(), BASELINE_TRANSITIONS, baselineInputs(), baselineOutputs());
    }

    private static NetworkSpec bufferedSpec(int capacity) {
        var places = new ArrayList<PlaceSpec>(java.util.Arrays.asList(baselinePlaces()));
        places.add(new PlaceSpec("Q_in", 0));
        places.add(new PlaceSpec("primary_free_capacity", Math.addExact(capacity, 1)));
        return new NetworkSpec("B2_NK", "buffered", T6_PRIMARY_BUFFERED,
                places.toArray(PlaceSpec[]::new), BUFFERED_TRANSITIONS,
                bufferedInputs(), bufferedOutputs());
    }

    private static PetriNet create(ModelConfig config, DelaySource delays,
            NetworkSpec spec, int capacity) throws ExceptionInvalidTimeDelay {
        var places = createPlaces(spec.places());
        int[] transitionIds = spec.transitionIds();
        var transitions = createTransitions(config, delays, transitionIds);
        var inputs = connectInputs(places, transitions, spec.inputs(), transitionIds);
        var outputs = connectOutputs(places, transitions, spec.outputs(), transitionIds);
        var layout = new Layout(transitionIds.clone(), capacity,
                spec.primaryTransition(), spec.variant());
        var net = new ModelNet(spec.name(), places, transitions, inputs, outputs, layout);
        net.validateStructure();
        return net;
    }

    private static ArrayList<PetriP> createPlaces(PlaceSpec[] specs) {
        var places = new ArrayList<PetriP>();
        for (PlaceSpec spec : specs) {
            addPlace(places, spec.name(), spec.initialMark());
        }
        return places;
    }

    private static void addPlace(List<PetriP> places, String name, int initialMark) {
        var place = new PetriP(name, initialMark);
        place.setNumber(places.size());
        places.add(place);
    }

    private static ArrayList<PetriT> createTransitions(ModelConfig config,
        DelaySource delays, int[] transitionIds) {
        String[] names = {
            "arrival", "primary_direct", "primary_refusal", "secondary",
            "full", "enqueue", "primary"
        };
        double[] means = {
            config.arrivalMean(), config.primaryMean(), 0, config.secondaryMean(),
            config.fullMean(), 0, config.primaryMean()
        };
        int[] priorities = {
            0, 3, 1, 0, 0, 2, 3
        };
        var transitions = new ArrayList<PetriT>();
        for (int index = 0; index < transitionIds.length; index++) {
            int logicalId = transitionIds[index];
            double mean = means[logicalId];
            // Спільний первинний потік.
            int delayStream = logicalId == T6_PRIMARY_BUFFERED ? T1_PRIMARY_DIRECT : logicalId;
            var transition = new SeededTransition(names[logicalId], mean, delayStream, delays);
            transition.setNumber(index);
            transition.setPriority(priorities[logicalId]);
            transition.setProbability(1);
            if (mean > 0) {
                transition.setDistribution("exp", mean);
            }
            transitions.add(transition);
        }
        return transitions;
    }

    private record ArcSpec(int transition, int place, int weight) {
    }

    private static ArcSpec[] baselineInputs() {
        return new ArcSpec[]{
            new ArcSpec(T0_ARRIVAL, P0_GENERATOR, 1),
            new ArcSpec(T1_PRIMARY_DIRECT, P1_ARRIVAL_PENDING, 2),
            new ArcSpec(T1_PRIMARY_DIRECT, P2_PRIMARY_FREE, 1),
            new ArcSpec(T2_REFUSAL, P1_ARRIVAL_PENDING, 2),
            new ArcSpec(T3_SECONDARY, P3_SECONDARY_QUEUE, 2),
            new ArcSpec(T3_SECONDARY, P4_SECONDARY_FREE, 1),
            new ArcSpec(T4_FULL, P5_FULL_QUEUE, 1),
            new ArcSpec(T4_FULL, P6_FULL_FREE, 1)
        };
    }

    private static ArcSpec[] bufferedInputs() {
        return new ArcSpec[]{
            new ArcSpec(T0_ARRIVAL, P0_GENERATOR, 1),
            new ArcSpec(T2_REFUSAL, P1_ARRIVAL_PENDING, 2),
            new ArcSpec(T3_SECONDARY, P3_SECONDARY_QUEUE, 2),
                new ArcSpec(T3_SECONDARY, P4_SECONDARY_FREE, 1),
            new ArcSpec(T4_FULL, P5_FULL_QUEUE, 1),
                new ArcSpec(T4_FULL, P6_FULL_FREE, 1),
            new ArcSpec(T5_ENQUEUE, P1_ARRIVAL_PENDING, 2),
                new ArcSpec(T5_ENQUEUE, P10_FREE_CAPACITY, 1),
            new ArcSpec(T6_PRIMARY_BUFFERED, P9_INPUT_QUEUE, 1),
                new ArcSpec(T6_PRIMARY_BUFFERED, P2_PRIMARY_FREE, 1)
        };
    }

    private static ArcSpec[] baselineOutputs() {
        return new ArcSpec[]{
            new ArcSpec(T0_ARRIVAL, P0_GENERATOR, 1),
            new ArcSpec(T0_ARRIVAL, P1_ARRIVAL_PENDING, 2),
            new ArcSpec(T1_PRIMARY_DIRECT, P2_PRIMARY_FREE, 1),
            new ArcSpec(T1_PRIMARY_DIRECT, P3_SECONDARY_QUEUE, 2),
            new ArcSpec(T2_REFUSAL, P5_FULL_QUEUE, 2),
            new ArcSpec(T3_SECONDARY, P4_SECONDARY_FREE, 1),
            new ArcSpec(T3_SECONDARY, P7_SECONDARY_DONE, 2),
            new ArcSpec(T4_FULL, P6_FULL_FREE, 1),
            new ArcSpec(T4_FULL, P8_FULL_DONE, 1)
        };
    }

    private static ArcSpec[] bufferedOutputs() {
        return new ArcSpec[]{
            new ArcSpec(T0_ARRIVAL, P0_GENERATOR, 1),
                new ArcSpec(T0_ARRIVAL, P1_ARRIVAL_PENDING, 2),
            new ArcSpec(T2_REFUSAL, P5_FULL_QUEUE, 2),
            new ArcSpec(T3_SECONDARY, P4_SECONDARY_FREE, 1),
                new ArcSpec(T3_SECONDARY, P7_SECONDARY_DONE, 2),
            new ArcSpec(T4_FULL, P6_FULL_FREE, 1),
                new ArcSpec(T4_FULL, P8_FULL_DONE, 1),
            new ArcSpec(T5_ENQUEUE, P9_INPUT_QUEUE, 1),
            new ArcSpec(T6_PRIMARY_BUFFERED, P2_PRIMARY_FREE, 1),
                new ArcSpec(T6_PRIMARY_BUFFERED, P10_FREE_CAPACITY, 1),
                new ArcSpec(T6_PRIMARY_BUFFERED, P3_SECONDARY_QUEUE, 2)
        };
    }

    private static ArrayList<ArcIn> connectInputs(List<PetriP> places,
            List<PetriT> transitions, ArcSpec[] arcs, int[] transitionIds) {
        var inputs = new ArrayList<ArcIn>();
        for (ArcSpec arc : arcs) {
            int index = indexOf(transitionIds, arc.transition());
            inputs.add(new ArcIn(places.get(arc.place()), transitions.get(index), arc.weight()));
        }
        return inputs;
    }

    private static ArrayList<ArcOut> connectOutputs(List<PetriP> places,
            List<PetriT> transitions, ArcSpec[] arcs, int[] transitionIds) {
        var outputs = new ArrayList<ArcOut>();
        for (ArcSpec arc : arcs) {
            int index = indexOf(transitionIds, arc.transition());
            outputs.add(new ArcOut(transitions.get(index), places.get(arc.place()), arc.weight()));
        }
        return outputs;
    }

    public record Layout(int[] transitionIds, int capacity, int primaryTransition,
            String variant, int[] indices, int counterCount) {
        Layout(int[] transitionIds, int capacity, int primaryTransition, String variant) {
            this(transitionIds, capacity, primaryTransition, variant,
                    indicesOf(transitionIds), java.util.Arrays.stream(transitionIds).max().orElseThrow() + 1);
        }

        public int indexOf(int logicalId) {
            return logicalId >= 0 && logicalId < indices.length ? indices[logicalId] : -1;
        }
    }

    private static int[] indicesOf(int[] ids) {
        int size = java.util.Arrays.stream(ids).max().orElseThrow() + 1;
        int[] indices = new int[size];
        java.util.Arrays.fill(indices, -1);
        for (int i = 0; i < ids.length; i++) indices[ids[i]] = i;
        return indices;
    }

    private static final class ModelNet extends PetriNet {
        final Layout layout;
        ModelNet(String name, ArrayList<PetriP> places, ArrayList<PetriT> transitions,
                ArrayList<ArcIn> inputs, ArrayList<ArcOut> outputs, Layout layout)
                throws ExceptionInvalidTimeDelay {
            super(name, places, transitions, inputs, outputs);
            this.layout = layout;
        }
    }

    public static Layout layout(PetriNet net) {
        if (net instanceof ModelNet model) return model.layout;
        throw new IllegalArgumentException("Use a network created by B2NetFactory");
    }

    private static int indexOf(int[] ids, int logicalId) {
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] == logicalId) {
                return index;
            }
        }
        return -1;
    }

    /** Знімок структури мережі. */
    public static Map<String, Object> structure(PetriNet net) {
        return structure(net, layout(net));
    }

    public static Map<String, Object> structure(PetriNet net, Layout layout) {
        var places = new ArrayList<Object>();
        for (PetriP place : net.getListP()) {
            places.add(Map.of("id", "P" + place.getNumber(),
                "name", place.getName(), "initial", place.getMark()));
        }
        var transitions = new ArrayList<Object>();
        for (PetriT transition : net.getListT()) {
            transitions.add(describeTransition(net, transition, layout));
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("variant", layout.variant());
        snapshot.put("capacity", layout.capacity());
        snapshot.put("places", places);
        snapshot.put("transitions", transitions);
        return snapshot;
    }

    private static Map<String, Object> describeTransition(PetriNet net, PetriT transition, Layout layout) {
        var inputs = new LinkedHashMap<String, Integer>();
        for (ArcIn arc : net.getArcIn()) {
            if (arc.getNumT() == transition.getNumber()) {
                if (arc.getIsInf()) {
                    throw new IllegalStateException("Unexpected read arc");
                }
                if (inputs.put("P" + arc.getNumP(), arc.getQuantity()) != null) {
                    throw new IllegalStateException("Duplicate input arc");
                }
            }
        }
        var outputs = new LinkedHashMap<String, Integer>();
        for (ArcOut arc : net.getArcOut()) {
            if (arc.getNumT() == transition.getNumber()) {
                if (outputs.put("P" + arc.getNumP(), arc.getQuantity()) != null) {
                    throw new IllegalStateException("Duplicate output arc");
                }
            }
        }
        Object delay = transition.getDistribution() == null
            ? Map.of("kind", "constant", "value", transition.getParameter())
            : Map.of("kind", transition.getDistribution(), "mean", transition.getParameter());
        var description = new LinkedHashMap<String, Object>();
        description.put("id", "T" + layout.transitionIds()[transition.getNumber()]);
        description.put("name", transition.getName());
        description.put("inputs", inputs);
        description.put("outputs", outputs);
        description.put("delay", delay);
        description.put("priority", transition.getPriority());
        description.put("probability", transition.getProbability());
        return description;
    }

    public static Map<String, Object> delayDescription(PetriNet net) {
        if (!(net.getListT()[0] instanceof SeededTransition transition)) {
            throw new IllegalArgumentException("Expected a model created by B2NetFactory");
        }
        return transition.delays.description();
    }
    private static final class SeededTransition extends PetriT {
        private final int delayStream;
        private final DelaySource delays;
        SeededTransition(String name, double mean, int delayStream, DelaySource delays) {
            super(name, mean);
            this.delayStream = delayStream;
            this.delays = delays;
        }

        @Override
        public double generateTimeServ() {
            double delay = delays.next(delayStream, getParameter());
            if (!Double.isFinite(delay) || delay <= 0) {
                throw new IllegalStateException("Invalid delay for " + getName() + ": " + delay);
            }
            return delay;
        }
    }
}
