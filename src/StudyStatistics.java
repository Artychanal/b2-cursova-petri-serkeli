import java.nio.file.*;
import java.util.*;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.special.Beta;
import org.apache.commons.math3.stat.inference.OneWayAnova;

/** Статистичні перевірки вибірок. */
public final class StudyStatistics {
    private static final int NORMAL_BOOTSTRAPS = 1999;
    private static final int BINS = 8;
    private static final double[] CUTS = new double[BINS - 1];
    static {
        var normal = new NormalDistribution();
        for (int j = 1; j < BINS; j++) {
            CUTS[j - 1] = normal.inverseCumulativeProbability((double) j / BINS);
        }
    }

    public static Map<String, Object> analyze(Path factorCsv, Path mainCsv) throws Exception {
        var factor = read(factorCsv);
        var results = new LinkedHashMap<String, Object>();
        var factors = new ArrayList<Object>();
        for (String metric : List.of("pRefusal", "L_in")) {
            var groups = new ArrayList<double[]>();
            var estimates = new ArrayList<Object>();
            for (int k : new int[]{
                    5, 18, 38
            }) {
                double[] values = factor.stream().filter(row -> row.get("K") == k)
                    .mapToDouble(row -> row.get(metric)).toArray();
                if (values.length != 40) {
                    throw new IllegalArgumentException("Expected 40 runs per level");
                }
                groups.add(values);
                var estimate = new LinkedHashMap<String, Object>();
                estimate.put("K", k);
                estimate.put("estimate", ExperimentStatistics.meanEstimate(metric, values,
                    metric.equals("pRefusal") ? 0.005 : 0.5).json());
                estimate.put("normality", normality(values, 810_000_000L + k));
                estimates.add(estimate);
            }
            var analysis = new LinkedHashMap<String, Object>();
            analysis.put("metric", metric);
            analysis.put("groups", estimates);
            analysis.put("welch", welch(groups));
            if (metric.equals("pRefusal")) {
                analysis.put("classical", classical(groups));
            }
            var deviations = new ArrayList<double[]>();
            for (double[] values : groups) {
                double[] sorted = values.clone();
                Arrays.sort(sorted);
                double median = (sorted[19] + sorted[20]) / 2;
                deviations.add(Arrays.stream(values).map(value -> Math.abs(value - median)).toArray());
            }
            double f = new OneWayAnova().anovaFValue(deviations);
            analysis.put("brownForsythe", Map.of("F", f, "df1", 2, "df2", 117, "p", fTail(f, 2, 117)));
            factors.add(analysis);
        }
        results.put("factorAnalysis", factors);
        var main = read(mainCsv);
        double arrivals = main.stream().mapToDouble(row -> row.get("arrived_pairs")).average().orElseThrow();
        double ratio = main.stream().mapToDouble(row -> row.get("refused_pairs")).sum()
        / main.stream().mapToDouble(row -> row.get("arrived_pairs")).sum();
        var diagnosticRows = new ArrayList<Object>();
        int index = 0;
        for (String metric : List.of("pRefusalInfluence", "L_full", "L_secondary", "L_in",
            "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end")) {
            double[] values = main.stream().mapToDouble(row -> metric.equals("pRefusalInfluence")
                    ? (row.get("refused_pairs") - ratio * row.get("arrived_pairs")) / arrivals : row.get(metric)).toArray();
            diagnosticRows.add(Map.of("metric", metric, "normality", normality(values, 820_000_000L + index++)));
        }
        results.put("mainNormality", diagnosticRows);
        results.put("normalCalibration", Map.of("bins", BINS, "replications", NORMAL_BOOTSTRAPS,
            "method", "Pearson statistic with fitted-normal equiprobable bins; refitted parametric Monte Carlo null"));
        results.put("source", Files.readString(Path.of("src/StudyStatistics.java")));
        results.put("provenance", RunProvenance.collect());
        return results;
    }

    private static Map<String, Object> welch(List<double[]> groups) {
        int k = groups.size();
        double[] weights = groups.stream().mapToDouble(x -> x.length / ExperimentStatistics.variance(x)).toArray();
        double total = Arrays.stream(weights).sum();
        double weightedMean = 0;
        for (int j = 0; j < k; j++) {
            weightedMean += weights[j] * ExperimentStatistics.mean(groups.get(j)) / total;
        }
        double statistic = 0, correction = 0;
        for (int j = 0; j < k; j++) {
            double delta = ExperimentStatistics.mean(groups.get(j)) - weightedMean;
            statistic += weights[j] * delta * delta / (k - 1);
            correction += Math.pow(1 - weights[j] / total, 2) / (groups.get(j).length - 1);
        }
        correction /= k * k - 1;
        statistic /= 1 + 2 * (k - 2) * correction;
        double denominatorDf = 1 / (3 * correction);
        return Map.of("F", statistic, "df1", k - 1, "df2", denominatorDf,
            "p", fTail(statistic, k - 1, denominatorDf));
    }

    private static Map<String, Object> classical(List<double[]> groups) {
        int n = groups.stream().mapToInt(values -> values.length).sum();
        int k = groups.size();
        double overallMean = groups.stream().flatMapToDouble(Arrays::stream).average().orElseThrow();
        double between = 0, within = 0;
        for (double[] values : groups) {
            double mean = ExperimentStatistics.mean(values);
            between += values.length * Math.pow(mean - overallMean, 2);
            for (double value : values) {
                within += Math.pow(value - mean, 2);
            }
        }
        double factorVariance = between / (k - 1);
        double residualVariance = within / (n - k);
        double f = factorVariance / residualVariance;
        double libraryF = new OneWayAnova().anovaFValue(groups);
        if (Math.abs(f - libraryF) > 1e-9 * Math.abs(libraryF)) {
            throw new IllegalStateException("ANOVA sums do not reproduce the library statistic");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("SS_between", between);
        result.put("SS_within", within);
        result.put("SS_total", between + within);
        result.put("MS_between", factorVariance);
        result.put("MS_within", residualVariance);
        result.put("F", f);
        result.put("df1", k - 1);
        result.put("df2", n - k);
        result.put("F_critical_005", new FDistribution(k - 1, n - k).inverseCumulativeProbability(.95));
        result.put("p", fTail(f, k - 1, n - k));
        return result;
    }

    private static double fTail(double value, double numeratorDf, double denominatorDf) {
        return Beta.regularizedBeta(denominatorDf / (denominatorDf + numeratorDf * value),
            denominatorDf / 2, numeratorDf / 2);
    }

    private static Map<String, Object> normality(double[] values, long seed) {
        double observed = pearson(values);
        var random = new Random(seed);
        double[] simulated = new double[values.length];
        int exceedances = 0;
        for (int b = 0; b < NORMAL_BOOTSTRAPS; b++) {
            for (int j = 0; j < simulated.length; j++) {
                simulated[j] = random.nextGaussian();
            }
            if (pearson(simulated) >= observed - 1e-12) {
                exceedances++;
            }
        }
        return Map.of("n", values.length, "statistic", observed,
            "p", (exceedances + 1.0) / (NORMAL_BOOTSTRAPS + 1.0), "seed", seed);
    }

    private static double pearson(double[] values) {
        double mean = ExperimentStatistics.mean(values);
        double sd = Math.sqrt(ExperimentStatistics.variance(values));
        if (!(sd > 0)) {
            throw new IllegalArgumentException("Constant normality sample");
        }
        int[] counts = new int[BINS];
        for (double value : values) {
            double z = (value - mean) / sd;
            int cell = 0;
            while (cell < CUTS.length && z > CUTS[cell]) {
                cell++;
            }
            counts[cell]++;
        }
        double expected = (double) values.length / BINS;
        double statistic = 0;
        for (int count : counts) {
            statistic += (count - expected) * (count - expected) / expected;
        }
        return statistic;
    }

    private static List<Map<String, Double>> read(Path path) throws Exception {
        var lines = Files.readAllLines(path);
        String[] headers = lines.getFirst().split(",");
        var result = new ArrayList<Map<String, Double>>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",");
            var row = new LinkedHashMap<String, Double>();
            for (int j = 0; j < headers.length; j++) {
                row.put(headers[j], Double.parseDouble(fields[j]));
            }
            result.add(row);
        }
        return result;
    }
}
