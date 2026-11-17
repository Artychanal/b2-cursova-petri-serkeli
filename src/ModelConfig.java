/** Параметри одного запуску. */
public record ModelConfig(double arrivalMean, double primaryMean, double secondaryMean,
    double fullMean, double horizon, double warmup, long seed) {
    public ModelConfig {
        for (double x : new double[]{
                arrivalMean, primaryMean, secondaryMean, fullMean, horizon
        }) {
            if (!Double.isFinite(x) || x <= 0) {
                throw new IllegalArgumentException("Positive finite time required");
            }
        }
        if (!Double.isFinite(warmup) || warmup < 0 || warmup >= horizon) {
            throw new IllegalArgumentException("Require 0 <= warmup < horizon");
        }
    }

    public static ModelConfig baseline(double horizon, long seed, double warmup) {
        return new ModelConfig(30, 30, 30, 100, horizon, warmup, seed);
    }
}
