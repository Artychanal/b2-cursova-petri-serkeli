import java.util.*;

/** Процентильні інтервали бутстрепу. */
public final class BootstrapStatistics {

    private BootstrapStatistics() {
    }

    public static double quantile(double[] sorted, double probability) {
        if (sorted.length == 0 || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("Nonempty sample and probability in [0,1] required");
        }
        double rank = probability * (sorted.length - 1);
        int lowerIndex = (int) Math.floor(rank), upperIndex = (int) Math.ceil(rank);
        return sorted[lowerIndex] + (rank - lowerIndex) * (sorted[upperIndex] - sorted[lowerIndex]);
    }

    public static double[] interval(double[] replicates) {
        double[] sorted = replicates.clone();
        Arrays.sort(sorted);
        return new double[]{
            quantile(sorted, .025), quantile(sorted, .975)
        };
    }

    public static double[] meanInterval(double[] sample, int repetitions, Random random) {
        if (sample.length < 2 || repetitions < 2) {
            throw new IllegalArgumentException("Insufficient sample");
        }
        double[] estimates = new double[repetitions];
        for (int bootstrapIndex = 0; bootstrapIndex < repetitions; bootstrapIndex++) {
            double sum = 0;
            for (int sampleIndex = 0; sampleIndex < sample.length; sampleIndex++) {
                sum += sample[random.nextInt(sample.length)];
            }
            estimates[bootstrapIndex] = sum / sample.length;
        }
        return interval(estimates);
    }

    public static double ratio(double[] arrivals, double[] refusals, int[] indexes) {
        double numerator = 0, denominator = 0;
        for (int index : indexes) {
            numerator += refusals[index];
            denominator += arrivals[index];
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("No arrivals in resampled runs");
        }
        return numerator / denominator;
    }

    /** Спільні індекси прогонів. */
    public static Map<String, double[]> jointIntervals(Map<String, double[]> metrics,
        double[] arrivals, double[] refusals, int repetitions, long seed) {
        int runCount = arrivals.length;
        if (runCount < 2 || refusals.length != runCount || repetitions < 2) {
            throw new IllegalArgumentException("Mismatched or insufficient samples");
        }
        var bootstrapSamples = new LinkedHashMap<String, double[]>();
        for (var entry : metrics.entrySet()) {
            if (entry.getValue().length != runCount) {
                throw new IllegalArgumentException("Mismatched metric");
            }
            bootstrapSamples.put(entry.getKey(), new double[repetitions]);
        }
        bootstrapSamples.put("pRefusal", new double[repetitions]);
        var random = new Random(seed);
        int[] indexes = new int[runCount];
        for (int bootstrapIndex = 0; bootstrapIndex < repetitions; bootstrapIndex++) {
            for (int sampleIndex = 0; sampleIndex < runCount; sampleIndex++) {
                indexes[sampleIndex] = random.nextInt(runCount);
            }
            for (var entry : metrics.entrySet()) {
                double sum = 0;
                for (int index : indexes) {
                    sum += entry.getValue()[index];
                }
                bootstrapSamples.get(entry.getKey())[bootstrapIndex] = sum / runCount;
            }
            bootstrapSamples.get("pRefusal")[bootstrapIndex] = ratio(arrivals, refusals, indexes);
        }
        var result = new LinkedHashMap<String, double[]>();
        bootstrapSamples.forEach((name, values) -> result.put(name, interval(values)));
        return result;
    }

    public static double[] holm(double[] probabilities) {
        Integer[] order = new Integer[probabilities.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> probabilities[i]));
        double[] result = new double[order.length];
        double previous = 0;
        for (int rank = 0; rank < order.length; rank++) {
            previous = Math.max(previous, Math.min(1, (order.length-rank)*probabilities[order[rank]]));
            result[order[rank]] = previous;
        }
        return result;
    }
}
