import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.math3.distribution.TDistribution;

/** Статистика незалежних прогонів. */
public final class ExperimentStatistics {
    public static final double BETA = 0.95;

    private ExperimentStatistics() {
    }

    public record Estimate(String metric, int n, double estimate, double sd, double epsilon,
        double low, double high, double halfWidth, long chebyshevN, String method) {

        public Map<String, Object> json() {
            var values = new LinkedHashMap<String, Object>();
            values.put("metric", metric);
            values.put("n", n);
            values.put("estimate", estimate);
            values.put("sd_between_replications", sd);
            values.put("epsilon", epsilon);
            values.put("beta", BETA);
            values.put("ciLow", low);
            values.put("ciHigh", high);
            values.put("halfWidth", halfWidth);
            values.put("ciPrecisionMet", halfWidth <= epsilon);
            values.put("requiredN_Chebyshev_pilot_plugin", chebyshevN);
            values.put("method", method);
            return values;
        }
    }

    public static double mean(double[] values) {
        return Arrays.stream(values).average().orElseThrow();
    }

    public static double variance(double[] values) {
        if (values.length < 2) {
            throw new IllegalArgumentException("At least two independent runs required");
        }
        double average = mean(values);
        double squaredDeviations = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Nonfinite sample");
            }
            squaredDeviations += (value - average) * (value - average);
        }
        return squaredDeviations / (values.length - 1);
    }

    public static double epsilon(String metric, double value) {
        return switch (metric) {
            case "pRefusal" -> 0.005;
            case "U_primary", "U_secondary", "U_full" -> 0.01;
            case "L_in" -> Math.max(0.25, 0.05 * Math.abs(value));
            case "Q_full_end", "Q_full_max" -> Math.max(1, 0.05 * Math.abs(value));
            case "throughput" -> Math.max(0.001, 0.05 * Math.abs(value));
            default -> Math.max(0.5, 0.05 * Math.abs(value));
        };
    }

    public static Estimate meanEstimate(String metric, double[] values) {
        return meanEstimate(metric, values, epsilon(metric, mean(values)));
    }

    public static Estimate meanEstimate(String metric, double[] values, double epsilon) {
        double average = mean(values);
        double standardDeviation = Math.sqrt(variance(values));
        double halfWidth = confidenceHalfWidth(values.length, standardDeviation);
        long replications = required(standardDeviation * standardDeviation
            / (epsilon * epsilon * (1 - BETA)));
        return new Estimate(metric, values.length, average, standardDeviation, epsilon,
            average - halfWidth, average + halfWidth, halfWidth, replications,
            "Student-t across independent runs; Chebyshev n uses pilot sample variance, not known population variance");
    }

    public static Estimate refusal(double[] arrivals, double[] refusals) {
        if (arrivals.length != refusals.length || arrivals.length < 2) {
            throw new IllegalArgumentException("Mismatched counts");
        }
        double meanArrivals = mean(arrivals);
        if (meanArrivals <= 0) {
            throw new IllegalArgumentException("No arrivals");
        }
        double refusalRate = mean(refusals) / meanArrivals;
        double epsilon = 0.005;
        double[] residuals = new double[arrivals.length];
        double[] influences = new double[arrivals.length];
        for (int index = 0; index < arrivals.length; index++) {
            residuals[index] = refusals[index] - refusalRate * arrivals[index];
            influences[index] = residuals[index] / meanArrivals;
        }
        double standardDeviation = Math.sqrt(variance(influences));
        double halfWidth = confidenceHalfWidth(arrivals.length, standardDeviation);
        // Оцінка випадкового знаменника.
        double conservativeCount = 4 * (variance(arrivals) + variance(residuals) / (epsilon * epsilon))
        / ((1 - BETA) * meanArrivals * meanArrivals);
        return new Estimate("pRefusal", arrivals.length, refusalRate, standardDeviation, epsilon,
            Math.max(0, refusalRate - halfWidth), Math.min(1, refusalRate + halfWidth),
            halfWidth, required(conservativeCount),
            "ratio of mean counts; Student-t delta interval; pilot plug-in Chebyshev ratio bound");
    }

    private static double confidenceHalfWidth(int sampleSize, double standardDeviation) {
        double criticalValue = new TDistribution(sampleSize - 1)
            .inverseCumulativeProbability((1 + BETA) / 2);
        return criticalValue * standardDeviation / Math.sqrt(sampleSize);
    }

    private static long required(double count) {
        if (!Double.isFinite(count) || count > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(20, (long) Math.ceil(count));
    }
}
