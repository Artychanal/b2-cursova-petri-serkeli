import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Статистична обробка результатів. */
public final class ResultProcessing {
    private static final Path FACTOR = Path.of("output/factor-capacity");
    private static final Path MAIN = Path.of("main/capacity-S-v2/K18_H10000000_A2000000_R2109_S400000000/replications.csv");
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
        System.out.println("Saved replication estimates and capacity analysis");
    }

    private static void processFactorStudy(Path input) throws Exception {
        Path factor = input.resolve("factor-capacity");
        var analysis = StudyStatistics.analyze(factor.resolve("main.csv"));
        var factors = asObjectRows(analysis.get("factorAnalysis"));
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
        for (String name : List.of("ResultProcessing", "StudyStatistics", "CapacityExtensionStudy")) {
            sources.put(name, Files.readString(Path.of("src", name + ".java")));
        }
        Json.write(Path.of("output/java-statistics-provenance.json"), Map.of(
            "inputSha256", hashes,
            "sources", sources,
            "javaVersion", System.getProperty("java.version"),
            "rng", "java.util.Random",
            "model", RunProvenance.collect()));
    }

    private static void processMainStudy(Path input) throws Exception {
        var rows = readSamples(input.resolve(MAIN));
        Properties plan = new Properties();
        try (var reader = Files.newBufferedReader(input.resolve("main-series-K18.properties"))) {
            plan.load(reader);
        }
        var estimates = new LinkedHashMap<String, Object>();
        estimates.put("pRefusal", ExperimentStatistics.refusal(valuesOf(rows, "arrived_pairs"),
                valuesOf(rows, "refused_pairs")).json());
        for (String name : List.of("L_full", "L_secondary", "L_in", "U_primary",
                "U_secondary", "U_full", "throughput", "Q_full_end")) {
            estimates.put(name, ExperimentStatistics.meanEstimate(name, valuesOf(rows, name),
                    Double.parseDouble(plan.getProperty("epsilon." + name))).json());
        }
        Json.write(Path.of("output/main-estimates.json"), estimates);
    }

    static void verify() {
        var analysis = StudyStatistics.classical(List.of(new double[]{1, 2, 3}, new double[]{4, 5, 6}));
        require(Math.abs(asDouble(analysis.get("SS_between")) - 13.5) < 1e-12, "factor sum");
        require(Math.abs(asDouble(analysis.get("SS_within")) - 4) < 1e-12, "residual sum");
        require(Math.abs(asDouble(analysis.get("F")) - 13.5) < 1e-12, "F statistic");
        require(ExperimentStatistics.mean(new double[]{2, 4, 6}) == 4, "mean");
        require(ExperimentStatistics.variance(new double[]{2, 4, 6}) == 4, "sample variance");
        System.out.println("Statistical checks: 5/5 passed");
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
