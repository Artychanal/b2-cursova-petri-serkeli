import ua.stetsenkoinna.petriobj.*;
import java.util.*;

/** Перевірки загального алгоритму. */
public final class AlgorithmVerification {
    private AlgorithmVerification() { }

    public static void main(String[] args) throws Exception {
        multichannel();
        priorities();
        parallel();
        immediateChain();
        deadlock();
        System.out.println("Generic algorithm: five networks passed");
    }

    private static void multichannel() throws Exception {
        var places = new ArrayList<PetriP>(List.of(new PetriP("waiting", 3),
                new PetriP("free", 2), new PetriP("done", 0)));
        var transition = new PetriT("service", 5);
        transition.setNumber(0);
        for (int i = 0; i < places.size(); i++) places.get(i).setNumber(i);
        var transitions = new ArrayList<PetriT>(List.of(transition));
        var inputs = new ArrayList<ArcIn>(List.of(new ArcIn(places.get(0), transition, 1),
                new ArcIn(places.get(1), transition, 1)));
        var outputs = new ArrayList<ArcOut>(List.of(new ArcOut(transition, places.get(1), 1),
                new ArcOut(transition, places.get(2), 1)));
        var net = new PetriNet("two channels", places, transitions, inputs, outputs);
        var result = new PetriSimulation(net, 10, 0).run(1, null, null);
        require(result.ends()[0] == 3 && result.marks()[2] == 3, "all three jobs complete");
        require(result.trajectory().get(0).buffers()[0] == 2, "two simultaneous operations");
        require(result.trajectory().get(5).ends()[0] == 2, "all coincident outputs precede input");
        require(result.bufferAreas()[0] == 15 && result.placeAreas()[0] == 5,
                "duration-weighted statistics");
    }

    private static void priorities() throws Exception {
        var places = new ArrayList<PetriP>(List.of(new PetriP("choice", 1),
                new PetriP("preferred", 0), new PetriP("refused", 0)));
        var low = new PetriT("low", 0);
        var high = new PetriT("high", 0);
        low.setNumber(0);
        high.setNumber(1);
        high.setPriority(1);
        for (int i = 0; i < places.size(); i++) places.get(i).setNumber(i);
        var net = new PetriNet("priority", places, new ArrayList<>(List.of(low, high)),
                new ArrayList<>(List.of(new ArcIn(places.get(0), low, 1),
                        new ArcIn(places.get(0), high, 1))),
                new ArrayList<>(List.of(new ArcOut(low, places.get(2), 1),
                        new ArcOut(high, places.get(1), 1))));
        var result = new PetriSimulation(net, 1, 0).run(0, null, null);
        require(result.marks()[1] == 1 && result.ends()[0] == 0, "explicit priority wins");
    }

    private static void parallel() throws Exception {
        var places = new ArrayList<PetriP>(List.of(new PetriP("first waiting", 1),
                new PetriP("second waiting", 1), new PetriP("first done", 0),
                new PetriP("second done", 0)));
        var first = new PetriT("first service", 3);
        var second = new PetriT("second service", 7);
        first.setNumber(0);
        second.setNumber(1);
        for (int i = 0; i < places.size(); i++) places.get(i).setNumber(i);
        var net = new PetriNet("parallel services", places,
                new ArrayList<>(List.of(first, second)),
                new ArrayList<>(List.of(new ArcIn(places.get(0), first, 1),
                        new ArcIn(places.get(1), second, 1))),
                new ArrayList<>(List.of(new ArcOut(first, places.get(2), 1),
                        new ArcOut(second, places.get(3), 1))));
        var completions = new ArrayList<Double>();
        var result = new PetriSimulation(net, 10, 4).run(1,
                (phase, transition, time) -> {
                    if (phase == FiringPhase.AFTER_ACT_OUT) completions.add(time);
                }, null);
        require(Arrays.equals(result.trajectory().getFirst().buffers(), new int[]{1, 1}),
                "independent transitions start together");
        require(completions.equals(List.of(3.0, 7.0)), "independent completion times");
        require(Arrays.equals(result.ends(), new long[]{1, 1}), "parallel completions");
        require(Arrays.equals(result.windowEnds(), new long[]{0, 1}), "warmup event window");
        require(result.bufferAreas()[0] == 0 && result.bufferAreas()[1] == 3,
                "warmup clips busy intervals");
        require(result.placeAreas()[2] == 6 && result.placeAreas()[3] == 3,
                "completed markings persist to horizon");
    }

    private static void immediateChain() throws Exception {
        var places = new ArrayList<PetriP>(List.of(new PetriP("start", 1),
                new PetriP("first step", 0), new PetriP("second step", 0),
                new PetriP("done", 0)));
        var transitions = new ArrayList<PetriT>();
        var inputs = new ArrayList<ArcIn>();
        var outputs = new ArrayList<ArcOut>();
        for (int i = 0; i < places.size(); i++) places.get(i).setNumber(i);
        for (int i = 0; i < 3; i++) {
            var transition = new PetriT("step " + i, i == 0 ? 3 : 0);
            transition.setNumber(i);
            transitions.add(transition);
            inputs.add(new ArcIn(places.get(i), transition, 1));
            outputs.add(new ArcOut(transition, places.get(i + 1), 1));
        }
        var times = new ArrayList<Double>();
        var net = new PetriNet("immediate chain", places, transitions, inputs, outputs);
        var result = new PetriSimulation(net, 10, 0).run(1,
                (phase, transition, time) -> {
                    if (phase == FiringPhase.AFTER_ACT_OUT) times.add(time);
                }, null);
        require(times.equals(List.of(3.0, 3.0, 3.0)), "zero delays preserve event time");
        require(Arrays.equals(result.ends(), new long[]{1, 1, 1}), "chain fires once");
        require(Arrays.equals(result.marks(), new int[]{0, 0, 0, 1}), "chain final marking");
        require(result.bufferAreas()[0] == 3 && result.bufferAreas()[1] == 0
                && result.bufferAreas()[2] == 0, "zero delays add no busy time");
        require(result.placeAreas()[1] == 0 && result.placeAreas()[2] == 0
                && result.placeAreas()[3] == 7, "zero-time states add no area");
        require(result.trajectory().get(3).marks()[3] == 1,
                "trajectory records completed zero-time chain");
    }

    private static void deadlock() throws Exception {
        var places = new ArrayList<PetriP>(List.of(new PetriP("insufficient", 1),
                new PetriP("done", 0)));
        for (int i = 0; i < places.size(); i++) places.get(i).setNumber(i);
        var transition = new PetriT("needs two", 5);
        transition.setNumber(0);
        var net = new PetriNet("deadlock", places, new ArrayList<>(List.of(transition)),
                new ArrayList<>(List.of(new ArcIn(places.get(0), transition, 2))),
                new ArrayList<>(List.of(new ArcOut(transition, places.get(1), 1))));
        var result = new PetriSimulation(net, 10, 2).run(2, null, null);
        require(result.starts()[0] == 0 && result.ends()[0] == 0
                && result.buffers()[0] == 0, "deadlock schedules no operations");
        require(Arrays.equals(result.marks(), new int[]{1, 0}), "deadlock preserves marking");
        require(result.placeAreas()[0] == 8 && result.bufferAreas()[0] == 0,
                "deadlock accounts remaining observation time");
        require(result.trajectory().getLast().time() == 10, "deadlock reaches horizon");
    }

    private static void require(boolean passed, String name) {
        if (!passed) throw new IllegalStateException(name);
    }
}
