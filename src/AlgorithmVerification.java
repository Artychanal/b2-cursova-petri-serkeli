import ua.stetsenkoinna.petriobj.*;
import java.util.*;

/** Перевірки загального алгоритму. */
public final class AlgorithmVerification {
    private AlgorithmVerification() { }

    public static void main(String[] args) throws Exception {
        multichannel();
        priorities();
        System.out.println("Generic algorithm: multichannel, coincident outputs and priorities passed");
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

    private static void require(boolean passed, String name) {
        if (!passed) throw new IllegalStateException(name);
    }
}
