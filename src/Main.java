import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Запуск режимів моделі. */
public final class Main {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0];
        Path output = Path.of("output");
        switch (mode) {
            case "all" -> {
                VerificationMain.main(new String[0]);
                demo(output);
                bufferDemo(output);
            }
            case "verify" -> VerificationMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            case "inputs" -> InputVerification.main(new String[0]);
            case "main-series" -> MainExperiments.execute(output, Path.of(args.length>1?args[1]:"data/main-series-K18.properties"));
            case "demo" -> demo(output);
            case "buffer-demo" -> bufferDemo(output);
            case "pilot" -> PilotExperiments.execute(output, args.length>1?Double.parseDouble(args[1]):1000000,
                args.length>2?Integer.parseInt(args[2]):20);
            case "warmup" -> PilotExperiments.execute(output, args.length>1?Double.parseDouble(args[1]):2000000,
                args.length>2?Integer.parseInt(args[2]):40, args.length>3?Long.parseLong(args[3]):40000000L, true,
                args.length>4?Integer.parseInt(args[4]):-1);
            case "buffered" -> {
                int k = args.length>1 ? Integer.parseInt(args[1]) : 18;
                double horizon = args.length>2 ? Double.parseDouble(args[2]) : 10_000;
                long seed = args.length>3 ? Long.parseLong(args[3]) : 20261101;
                double warmup = args.length>4 ? Double.parseDouble(args[4]) : 0;
                var config = ModelConfig.baseline(horizon, seed, warmup);
                var result = new SimulationRunner(B2NetFactory.buffered(config, DelaySource.seeded(seed), k), config).run(null);
                save(output.resolve("buffered-simulation.json"), "stochastic_buffered_single_run", result);
                System.out.println("N_K, K="+k+", P_refusal="+result.refusal()+", L_full="+result.fullQueueMean());
            }
            case "simulate" -> {
                double horizon = args.length > 1 ? Double.parseDouble(args[1]) : 10_000;
                long seed = args.length > 2 ? Long.parseLong(args[2]) : 20261101;
                double warmup = args.length > 3 ? Double.parseDouble(args[3]) : 0;
                var config = ModelConfig.baseline(horizon, seed, warmup);
                var result = new SimulationRunner(B2NetFactory.baseline(config, DelaySource.seeded(seed)), config).run(null);
                save(output.resolve("simulation.json"), "stochastic_baseline_single_run", result);
                System.out.println("N0, T="+horizon+", seed="+seed+", P_refusal="+result.refusal()+", L_full="+result.fullQueueMean());
            }
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static void demo(Path output) throws Exception {
        var config = ModelConfig.baseline(220, 0, 0);
        var result = new SimulationRunner(B2NetFactory.baseline(config, Verification.twoPairs()), config)
            .run(output.resolve("demo-trace.csv"));
        save(output.resolve("demo.json"), "deterministic_demo_not_exponential_experiment", result);
        System.out.println("Demo: 2 arriving pairs, 1 refused pair, "+result.marks()[7]+" secondary + "+result.marks()[8]+" full completions");
    }

    private static void bufferDemo(Path output) throws Exception {
        var config = ModelConfig.baseline(130, 0, 0);
        var result = new SimulationRunner(B2NetFactory.buffered(config, BufferVerification.script(new double[]{
                10, 10, 20
        }, 30), 1), config)
            .run(output.resolve("buffer-demo-trace.csv"));
        save(output.resolve("buffer-demo.json"), "deterministic_buffer_demo_not_exponential_experiment", result);
        System.out.println("Buffer demo: K=1, 3 pairs, no refusals at arrival/completion tie, "+result.marks()[7]+" completed aggregates");
    }

    private static void save(Path path, String kind, SimulationRunner.Result result) throws Exception {
        var value = new LinkedHashMap<String, Object>();
        value.put("kind", kind);
        value.put("result", result.summary());
        value.putAll(RunProvenance.collect());
        Json.write(path, value);
    }
}
