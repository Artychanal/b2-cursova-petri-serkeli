import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Статистична обробка результатів. */
public final class ResultProcessing {
    private static final Path FACTOR = Path.of("output/factor-capacity");
    private static final Path MAIN = Path.of("main/capacity-S-v2/K18_H10000000_A2000000_R2109_S400000000/replications.csv");
    private static final int REPETITIONS = 5000;
    private static final List<Path> INPUTS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Use analysis [input directory]");
        }
        Path input = Path.of(args.length == 0 ? "data" : args[0]);
        INPUTS.clear();
        processFactorStudy(input);
        processMainStudy(input);
        saveProvenance();
        System.out.println("Saved Java statistics, bootstrap intervals and plot coordinates");
    }

    private static void processFactorStudy(Path input) throws Exception {
        Path factor = input.resolve("factor-capacity");
        var analysis = StudyStatistics.analyze(factor.resolve("main.csv"), input.resolve(MAIN));
        var diagnostics = asObjectRows(analysis.get("mainNormality"));
        double[] probabilities = new double[diagnostics.size()];
        for (int index = 0; index < probabilities.length; index++) {
            var test = asObjectMap(diagnostics.get(index).get("normality"));
            probabilities[index] = asDouble(test.get("p"));
        }
        analysis.put("mainNormalityHolm", BootstrapStatistics.holm(probabilities));
        var factors = asObjectRows(analysis.get("factorAnalysis"));
        double refusalP = asDouble(asObjectMap(factors.get(0).get("classical")).get("p"));
        double inputQueueP = asDouble(asObjectMap(factors.get(1).get("welch")).get("p"));
        analysis.put("factorHolm", BootstrapStatistics.holm(new double[]{
                refusalP, inputQueueP
        }));
        analysis.put("pilotPlanning", planFactorReplications(factor));
        var expanded = expandedCapacityStudy(factors, input.resolve("capacity-extension"));
        analysis.put("expandedCapacityStudy", expanded);
        analysis.put("refusalCurve", refusalCurve(asObjectRows(expanded.get("groups"))));
        Json.write(FACTOR.resolve("analysis.json"), analysis);
    }

    private static Map<String, Object> expandedCapacityStudy(List<Map<String, Object>> factors,
            Path input) throws Exception {
        var additional = CapacityExtensionStudy.analyze(input);
        Json.write(Path.of("output/capacity-extension/analysis.json"), additional);
        var groups = new ArrayList<Map<String, Object>>();
        var originalRefusals = asObjectRows(factors.get(0).get("groups"));
        var originalQueues = asObjectRows(factors.get(1).get("groups"));
        for (int index = 0; index < originalRefusals.size(); index++) {
            int capacity = ((Number) originalRefusals.get(index).get("K")).intValue();
            groups.add(capacityRow(capacity,
                    asObjectMap(originalRefusals.get(index).get("estimate")),
                    asObjectMap(originalQueues.get(index).get("estimate")), "initial"));
        }
        for (var group : asObjectRows(additional.get("groups"))) {
            int capacity = ((Number) group.get("K")).intValue();
            groups.add(capacityRow(capacity, asObjectMap(group.get("q")),
                    asObjectMap(group.get("L_in")), "additional"));
        }
        groups.sort(Comparator.comparingInt(row -> ((Number) row.get("K")).intValue()));
        for (String file : List.of("pilot.csv", "extend.csv", "main.csv")) {
            Path csv = input.resolve(file);
            if (Files.exists(csv)) {
                INPUTS.add(csv);
            }
        }
        return Map.of("groups", groups, "additional", additional,
                "initialRuns", 120, "additionalRuns", 160, "totalRuns", 280);
    }

    private static Map<String, Object> capacityRow(int capacity, Map<String, Object> refusal,
            Map<String, Object> queue, String series) {
        var row = new LinkedHashMap<String, Object>();
        row.put("K", capacity);
        row.put("n", refusal.get("n"));
        row.put("series", series);
        row.put("analyticPercent", 100.0 / (capacity + 2));
        row.put("meanPercent", 100 * asDouble(refusal.get("estimate")));
        row.put("lowPercent", 100 * asDouble(refusal.get("ciLow")));
        row.put("highPercent", 100 * asDouble(refusal.get("ciHigh")));
        row.put("queueMean", queue.get("estimate"));
        row.put("queueLow", queue.get("ciLow"));
        row.put("queueHigh", queue.get("ciHigh"));
        return row;
    }

    private static Map<String, Object> refusalCurve(List<Map<String, Object>> groups) {
        int[] capacities = new int[41];
        double[] analyticPercent = new double[41];
        for (int capacity = 0; capacity < capacities.length; capacity++) {
            capacities[capacity] = capacity;
            analyticPercent[capacity] = 100.0 / (capacity + 2);
        }
        var points = new ArrayList<Object>();
        for (var group : groups) {
            points.add(Map.of(
                    "K", group.get("K"),
                    "meanPercent", group.get("meanPercent"),
                    "lowPercent", group.get("lowPercent"),
                    "highPercent", group.get("highPercent")));
        }
        return Map.of("capacities", capacities, "analyticPercent", analyticPercent, "points", points);
    }

    private static List<Object> planFactorReplications(Path input) throws Exception {
        var pilot = readSamples(input.resolve("pilot.csv"));
        var extension = readSamples(input.resolve("extend.csv"));
        var planning = new ArrayList<Object>();
        for (int capacity : new int[]{
                5, 18, 38
        }) {
            var pilotRuns = capacity == 38 ? extension : pilot;
            for (String metric : List.of("pRefusal", "L_in")) {
                double[] sample = pilotRuns.stream()
                    .filter(row -> row.get("K") == capacity)
                    .mapToDouble(row -> row.get(metric))
                    .toArray();
                double epsilon = metric.equals("pRefusal") ? .005 : .5;
                double variance = ExperimentStatistics.variance(sample);
                long requiredRuns = Math.max(20, (long) Math.ceil(variance / (epsilon * epsilon * .05)));
                if (requiredRuns > 40) {
                    throw new IllegalStateException("Saved factor plan does not meet pilot requirement");
                }
                planning.add(Map.of(
                    "K", capacity,
                    "metric", metric,
                    "sd", Math.sqrt(variance),
                    "epsilon", epsilon,
                    "requiredN", requiredRuns));
            }
        }
        return planning;
    }

    private static void saveProvenance() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (Path path : INPUTS) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            hashes.put(path.toString(), HexFormat.of().formatHex(digest));
        }
        var sources = new LinkedHashMap<String, String>();
        for (String name : List.of("ResultProcessing", "BootstrapStatistics", "StudyStatistics", "CapacityExtensionStudy")) {
            sources.put(name, Files.readString(Path.of("src", name + ".java")));
        }
        Json.write(Path.of("output/java-statistics-provenance.json"), Map.of(
            "inputSha256", hashes,
            "sources", sources,
            "javaVersion", System.getProperty("java.version"),
            "rng", "java.util.Random",
            "bootstrapReplications", REPETITIONS,
            "model", RunProvenance.collect()));
    }

    private static void processMainStudy(Path input) throws Exception {
        var rows = readSamples(input.resolve(MAIN));
        double[] arrivals = valuesOf(rows, "arrived_pairs"), refusals = valuesOf(rows, "refused_pairs");
        var metrics = new LinkedHashMap<String, double[]>();
        for (String name:List.of("L_full", "L_secondary", "L_in", "U_primary", "U_secondary", "U_full", "throughput", "Q_full_end")){
            metrics.put(name, valuesOf(rows, name));
        }
        var intervals = BootstrapStatistics.jointIntervals(metrics, arrivals, refusals, REPETITIONS, 830000000L);
        var distances = new LinkedHashMap<String, Double>();
        intervals.forEach((name, ci) -> {
                double mean = name.equals("pRefusal")
                    ? Arrays.stream(refusals).sum() / Arrays.stream(arrivals).sum()
                    : ExperimentStatistics.mean(metrics.get(name));
                distances.put(name, Math.max(mean-ci[0], ci[1]-mean));
        });
        Json.write(FACTOR.resolve("bootstrap.json"), Map.of("replications", REPETITIONS, "seed", 830000000L,                 "rng", "java.util.Random", "method", "percentile resampling of whole independent runs", "intervals", intervals, "maxDistance", distances));
        double ratio = Arrays.stream(refusals).sum()/Arrays.stream(arrivals).sum(), meanArrivals = ExperimentStatistics.mean(arrivals);
        double[] influences = new double[rows.size()];
        for (int i = 0;i<influences.length;i++){
            influences[i] = 100*(refusals[i]-ratio*arrivals[i])/meanArrivals;
        }
        var mainPanels = new LinkedHashMap<String, Object>();
        mainPanels.put("pRefusalInfluence", histogramData(influences));
        for (String name:List.of("L_full", "L_secondary", "Q_full_end")){
            mainPanels.put(name, histogramData(metrics.get(name)));
        }
        var factorPanels = new LinkedHashMap<String, Object>();
        var factor = readSamples(input.resolve("factor-capacity/main.csv"));
        for (int k:new int[]{
                5, 18, 38
        }){
            for (String name:List.of("pRefusal", "L_in")) {
                double scale = name.equals("pRefusal")?100:1;
                double[] sample = factor.stream().filter(row -> row.get("K") == k).mapToDouble(row -> row.get(name)*scale).toArray();
                factorPanels.put(k+"_"+name, histogramData(sample));
            }
        }
        Json.write(FACTOR.resolve("plot-data.json"), Map.of("main", mainPanels, "factor", factorPanels));
    }

    private static Map<String, Object> histogramData(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double mean = ExperimentStatistics.mean(values), sd = Math.sqrt(ExperimentStatistics.variance(values));
        double width = 2*(BootstrapStatistics.quantile(sorted, .75)-BootstrapStatistics.quantile(sorted, .25))/Math.cbrt(n);
        if (width <= 0){
            width = 3.5*sd/Math.cbrt(n);
        }
        int bins = width>0?Math.max(1, (int)Math.ceil((sorted[n-1]-sorted[0])/width)):1;
        double low = sorted[0], high = sorted[n-1];
        if (high == low) {
            low-=.5;
            high+=.5;
        }
        double step = (high-low)/bins;
        double[] edges = new double[bins+1], density = new double[bins];
        for (int i = 0;i <= bins;i++){
            edges[i] = low+i*step;
        }
        for (double v:values){
            density[Math.min(bins-1, (int)((v-low)/step))]+=1.0/(n*step);
        }
        double[] x = new double[200], y = new double[200];
        double left = Math.min(low, mean-3*sd), right = Math.max(high, mean+3*sd);
        for (int i = 0;i<x.length;i++) {
            x[i] = left+(right-left)*i/(x.length-1);
            y[i] = sd>0?Math.exp(-.5*Math.pow((x[i]-mean)/sd, 2))/(sd*Math.sqrt(2*Math.PI)):0;
        }
        return Map.of("edges", edges, "density", density, "normalX", x, "normalY", y);
    }

    static void verify() {
        require(BootstrapStatistics.quantile(new double[]{
                0, 10, 20, 30
        }, .25) == 7.5, "linear quantile");
        require(BootstrapStatistics.quantile(new double[]{
                7
        }, .95) == 7, "singleton quantile");
        require(Arrays.equals(BootstrapStatistics.meanInterval(new double[]{
                7, 7, 7
        }, 100, new Random(1)), new double[]{
                7, 7
        }), "constant mean interval");
        require(BootstrapStatistics.ratio(new double[]{
                10, 20
        }, new double[]{
                1, 10
        }, new int[]{
                0, 1
        }) == 11.0/30, "ratio of sums");
        require(BootstrapStatistics.ratio(new double[]{
                10, 20
        }, new double[]{
                1, 10
        }, new int[]{
                1, 1
        }) == .5, "paired resampling");
        double[] a = BootstrapStatistics.meanInterval(new double[]{
                1, 2, 5
        }, 100, new Random(17));
        require(Arrays.equals(a, BootstrapStatistics.meanInterval(new double[]{
                1, 2, 5
        }, 100, new Random(17))), "seed reproducibility");
        require(Arrays.equals(BootstrapStatistics.holm(new double[]{
                    .01, .04, .03
        }), new double[]{
                    .03, .06, .06
        }), "Holm correction");
        var joint = BootstrapStatistics.jointIntervals(Map.of("constant", new double[]{
                7, 7
        }), new double[]{
                10, 10
        }, new double[]{
                2, 2
        }, 100, 4);
        require(Arrays.equals(joint.get("pRefusal"), new double[]{
                    .2, .2
        }) && Arrays.equals(joint.get("constant"), new double[]{
                7, 7
        }), "constant joint intervals");
        System.out.println("Java postprocessing verification: 8/8");
    }

    private static void require(boolean value, String message) {
        if (!value){
            throw new IllegalStateException(message);
        }
    }

    private static double asDouble(Object value) {
        return ((Number)value).doubleValue();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> asObjectMap(Object value) {
        return (Map<String, Object>)value;
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> asObjectRows(Object value) {
        return (List<Map<String, Object>>)value;
    }

    private static double[] valuesOf(List<Map<String, Double>> rows, String name) {
        return rows.stream().mapToDouble(row -> row.get(name)).toArray();
    }

    private static List<Map<String, Double>> readSamples(Path path)throws Exception{
        INPUTS.add(path);
        var lines = Files.readAllLines(path);
        String[] names = lines.getFirst().split(",");
        var rows = new ArrayList<Map<String, Double>>();
        for (String line:lines.subList(1, lines.size())) {
            if (line.isBlank()){
                continue;
            }
            String[] cells = line.split(",");
            if (cells.length != names.length){
                throw new IllegalArgumentException("Wrong CSV width");
            }
            var row = new LinkedHashMap<String, Double>();
            for (int i = 0;i<names.length;i++){
                row.put(names[i], Double.parseDouble(cells[i]));
            }
            rows.add(row);
        }
        return rows;
    }
}
