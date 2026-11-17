import java.util.Random;
import java.util.Map;

/** Генерація тривалостей операцій. */
@FunctionalInterface
public interface DelaySource {
    double next(int transition, double mean);

    default Map<String, Object> description() {
        return Map.of("kind", "custom");
    }

    static DelaySource seeded(long seed) {
        Random master = new Random(seed);
        Random[] streams = new Random[5];
        long[] seeds = new long[5];
        for (int streamIndex = 0; streamIndex < streams.length; streamIndex++) {
            seeds[streamIndex] = master.nextLong();
            streams[streamIndex] = new Random(seeds[streamIndex]);
        }
        return new DelaySource() {

            @Override public double next(int streamIndex, double mean) {
                double uniform;
                do {
                    uniform = streams[streamIndex].nextDouble();
                } while (uniform == 0);
                return -mean * Math.log(uniform);
            }

            @Override public Map<String, Object> description() {
                return Map.of("kind", "exponential", "rng", "java.util.Random", "masterSeed", seed,
                    "operationSeeds_arrival_primary_unused_secondary_full", seeds.clone(), "formula", "-mean*log(U), U>0");
            }
        };
    }

    /** Задані контрольні затримки. */
    static DelaySource scripted(double[][] sequences, double[] fallback) {
        if (sequences.length < 5 || sequences.length != fallback.length) {
            throw new IllegalArgumentException("Matching arrays of at least five transitions required");
        }
        double[][] scriptedDelays = new double[sequences.length][];
        for (int streamIndex = 0; streamIndex < scriptedDelays.length; streamIndex++) {
            scriptedDelays[streamIndex] = sequences[streamIndex].clone();
        }
        double[] fallbackDelays = fallback.clone();
        int[] nextDelayIndexes = new int[scriptedDelays.length];
        return new DelaySource() {

            @Override public double next(int streamIndex, double mean) {
                int delayIndex = nextDelayIndexes[streamIndex]++;
                return delayIndex < scriptedDelays[streamIndex].length ? scriptedDelays[streamIndex][delayIndex] : fallbackDelays[streamIndex];
            }

            @Override public Map<String, Object> description() {
                return Map.of("kind", "scripted_verification_not_exponential", "sequences_by_transition", scriptedDelays,
                    "fallback_by_transition", fallbackDelays.clone(), "note", "Immediate transitions use constant zero without the provider");
            }
        };
    }
}
