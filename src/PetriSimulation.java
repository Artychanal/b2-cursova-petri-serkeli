import ua.stetsenkoinna.petriobj.*;
import java.util.ArrayList;
import java.util.List;

/** Імітація переданої мережі. */
public final class PetriSimulation {
    public record Snapshot(double time, int[] marks, int[] buffers,
            double[] placeAreas, double[] bufferAreas, long[] ends) { }

    public record Result(long[] starts, long[] ends, long[] windowEnds,
            int[] marks, int[] buffers, double[] placeAreas, double[] bufferAreas,
            int[] placeMaxima, List<Snapshot> trajectory) { }

    private final PetriNet net;
    private final PetriSim simulator;
    private final double horizon, warmup;
    private final long[] starts, ends, windowEnds;
    private final double[] placeAreas, bufferAreas;
    private final int[] placeMaxima;
    private final List<Snapshot> trajectory = new ArrayList<>();
    private double sampleEvery, lastAccountingTime;
    private long sampleIndex;
    private boolean used;
    private TransitionFiringListener observer;
    private Runnable settledObserver;

    public PetriSimulation(PetriNet net, double horizon, double warmup) {
        if (!Double.isFinite(horizon) || !Double.isFinite(warmup)
                || horizon <= 0 || warmup < 0 || warmup >= horizon) {
            throw new IllegalArgumentException("Require 0 <= warmup < horizon");
        }
        this.net = net;
        this.horizon = horizon;
        this.warmup = warmup;
        int places = net.getListP().length;
        int transitions = net.getListT().length;
        starts = new long[transitions];
        ends = new long[transitions];
        windowEnds = new long[transitions];
        placeAreas = new double[places];
        bufferAreas = new double[transitions];
        placeMaxima = new int[places];
        for (int i = 0; i < transitions; i++) {
            if (net.getListT()[i].getNumber() != i) {
                throw new IllegalArgumentException("Transition numbers must match array indices");
            }
        }
        simulator = new PetriSim(net);
        simulator.setFiringListener(this::onPhase);
    }

    public Result run(double sampleInterval, TransitionFiringListener observer,
            Runnable settledObserver) {
        if (used || !Double.isFinite(sampleInterval) || sampleInterval < 0) {
            throw new IllegalArgumentException("Use a fresh simulation and nonnegative interval");
        }
        used = true;
        sampleEvery = sampleInterval;
        this.observer = observer;
        this.settledObserver = settledObserver;
        simulator.setSimulationTime(horizon);
        simulator.setTimeCurr(0);
        simulator.input();
        if (simulator.getTimeMin() > 0) {
            recordSettledState(0);
        }
        processEvents();
        return new Result(starts.clone(), ends.clone(), windowEnds.clone(),
                marks(), buffers(), placeAreas.clone(), bufferAreas.clone(),
                placeMaxima.clone(), List.copyOf(trajectory));
    }

    private void onPhase(FiringPhase phase, PetriT transition, double time) {
        int index = transition.getNumber();
        if (phase == FiringPhase.AFTER_ACT_IN) {
            starts[index]++;
        }
        if (phase == FiringPhase.AFTER_ACT_OUT) {
            ends[index]++;
            if (time > warmup && time <= horizon) {
                windowEnds[index]++;
            }
        }
        if (observer != null) {
            observer.onFiringPhase(phase, transition, time);
        }
    }

    private int mark(int index) {
        return net.getListP()[index].getMark();
    }

    private int active(int index) {
        return net.getListT()[index].getBuffer();
    }

    private int[] marks() {
        int[] values = new int[placeAreas.length];
        for (int i = 0; i < values.length; i++) values[i] = mark(i);
        return values;
    }

    private int[] buffers() {
        int[] values = new int[bufferAreas.length];
        for (int i = 0; i < values.length; i++) values[i] = active(i);
        return values;
    }

    private void recordSettledState(double time) {
        if (settledObserver != null) settledObserver.run();
        if (time >= warmup) updateMaxima();
        if (sampleEvery > 0 && sampleIndex * sampleEvery == time) sampleTrajectory(time);
    }

    private void updateMaxima() {
        for (int i = 0; i < placeMaxima.length; i++) {
            placeMaxima[i] = Math.max(placeMaxima[i], mark(i));
        }
    }

    private void sampleTrajectory(double time) {
        int[] marks = marks(), buffers = buffers();
        double[] sampledPlaces = placeAreas.clone(), sampledBuffers = bufferAreas.clone();
        double tail = Math.max(0, time - Math.max(lastAccountingTime, warmup));
        for (int i = 0; i < marks.length; i++) sampledPlaces[i] += marks[i] * tail;
        for (int i = 0; i < buffers.length; i++) sampledBuffers[i] += buffers[i] * tail;
        trajectory.add(new Snapshot(time, marks, buffers, sampledPlaces, sampledBuffers, ends.clone()));
        if (++sampleIndex > 1_000_000) throw new IllegalArgumentException("Too many samples");
    }

    /** Зважування тривалістю інтервалу. */
    private void accumulateStatistics(double from, double to) {
        while (sampleEvery > 0 && sampleIndex * sampleEvery < to) {
            sampleTrajectory(sampleIndex * sampleEvery);
        }
        double dt = Math.max(0, Math.min(to, horizon) - Math.max(from, warmup));
        lastAccountingTime = to;
        if (dt == 0) return;
        for (int i = 0; i < placeAreas.length; i++) placeAreas[i] += mark(i) * dt;
        for (int i = 0; i < bufferAreas.length; i++) bufferAreas[i] += active(i) * dt;
        updateMaxima();
    }

    private void processEvents() {
        double current = 0;
        int microsteps = 0;
        while (true) {
            double next = simulator.getTimeMin();
            if (next == Double.MAX_VALUE || next > horizon) break;
            if (!Double.isFinite(next) || next < current) {
                throw new IllegalStateException("Invalid next event time");
            }
            if (next == current) {
                if (++microsteps > 100_000) throw new IllegalStateException("Zero-time cycle");
            } else {
                microsteps = 0;
            }
            accumulateStatistics(current, next);
            current = next;
            simulator.setTimeCurr(current);
            simulator.output();
            simulator.input();
            if (simulator.getTimeMin() > current) recordSettledState(current);
        }
        accumulateStatistics(current, horizon);
        simulator.setTimeCurr(horizon);
        if (sampleEvery > 0 && (trajectory.isEmpty()
                || trajectory.getLast().time() != horizon)) sampleTrajectory(horizon);
    }
}
