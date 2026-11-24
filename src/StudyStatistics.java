import java.nio.file.*;
import java.util.*;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.special.Beta;
import org.apache.commons.math3.stat.inference.OneWayAnova;

/** Однофакторний дисперсійний аналіз. */
public final class StudyStatistics {
    private StudyStatistics() { }

    public static Map<String, Object> analyze(Path factorCsv) throws Exception {
        var rows = read(factorCsv);
        var analyses = new ArrayList<Object>();
        for (String metric : List.of("pRefusal", "L_in")) {
            var groups = new ArrayList<double[]>();
            var estimates = new ArrayList<Object>();
            boolean normalGroups = true;
            for (int capacity : new int[]{5, 18, 38}) {
                double[] values = rows.stream().filter(row -> row.get("K") == capacity)
                        .mapToDouble(row -> row.get(metric)).toArray();
                if (values.length != 40) throw new IllegalArgumentException("Expected 40 runs per level");
                groups.add(values);
                var estimate = new LinkedHashMap<String, Object>();
                estimate.put("K", capacity);
                estimate.put("estimate", ExperimentStatistics.meanEstimate(metric, values,
                        metric.equals("pRefusal") ? .005 : .5).json());
                if (metric.equals("pRefusal")) {
                    var normality = normality(values);
                    normalGroups &= (boolean) normality.get("passed");
                    estimate.put("normality", normality);
                }
                estimates.add(estimate);
            }
            var analysis = new LinkedHashMap<String, Object>();
            analysis.put("metric", metric);
            analysis.put("groups", estimates);
            if (metric.equals("pRefusal")) {
                if (!normalGroups) {
                    throw new IllegalStateException("Normality check failed: classical ANOVA not reported");
                }
                var homogeneity = cochran(groups);
                analysis.put("cochran", homogeneity);
                if (!(boolean) homogeneity.get("passed")) {
                    throw new IllegalStateException("Unequal variances: classical ANOVA not reported");
                }
                analysis.put("classical", classical(groups));
            }
            analyses.add(analysis);
        }
        return new LinkedHashMap<>(Map.of("factorAnalysis", analyses));
    }

    public static Map<String, Object> cochran(List<double[]> groups) {
        int count = groups.size(), df = groups.getFirst().length - 1;
        double sum = 0, maximum = 0;
        for (double[] values : groups) {
            if (values.length != df + 1) throw new IllegalArgumentException("Equal group sizes required");
            double variance = ExperimentStatistics.variance(values);
            sum += variance;
            maximum = Math.max(maximum, variance);
        }
        double statistic = maximum / sum;
        double f = new FDistribution(df, (count - 1) * df)
                .inverseCumulativeProbability(1 - .05 / count);
        double critical = f / (f + count - 1);
        return Map.of("G", statistic, "critical", critical, "df", df,
                "groups", count, "passed", statistic <= critical);
    }

    public static Map<String, Object> normality(double[] values) {
        int bins = 8;
        double mean = ExperimentStatistics.mean(values);
        double sd = Math.sqrt(ExperimentStatistics.variance(values));
        if (sd == 0) throw new IllegalArgumentException("Nonconstant sample required");
        var normal = new NormalDistribution();
        int[] counts = new int[bins];
        for (double value : values) {
            int bin = Math.min(bins - 1, (int) (bins * normal.cumulativeProbability((value - mean) / sd)));
            counts[bin]++;
        }
        double expected = (double) values.length / bins, statistic = 0;
        for (int count : counts) statistic += (count - expected) * (count - expected) / expected;
        int df = bins - 1 - 2;
        double critical = new ChiSquaredDistribution(df).inverseCumulativeProbability(.95);
        return Map.of("chiSquare", statistic, "df", df, "critical", critical,
                "counts", counts, "expected", expected, "passed", statistic <= critical);
    }

    public static Map<String, Object> classical(List<double[]> groups) {
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
