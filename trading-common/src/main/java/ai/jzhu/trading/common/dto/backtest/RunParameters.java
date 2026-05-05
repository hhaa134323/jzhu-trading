package ai.jzhu.trading.common.dto.backtest;

public record RunParameters(
        Double capital,
        Double leverage,
        Double feeRate
) {
    public static final double DEFAULT_CAPITAL = 100_000.0;
    public static final double DEFAULT_LEVERAGE = 1.0;
    public static final double DEFAULT_FEE_RATE = 0.0;

    public RunParameters {
        if (capital != null && capital <= 0) {
            throw new IllegalArgumentException("capital must be > 0");
        }
        if (leverage != null && leverage < 1.0) {
            throw new IllegalArgumentException("leverage must be >= 1.0");
        }
        if (feeRate != null && feeRate < 0) {
            throw new IllegalArgumentException("feeRate must be >= 0");
        }
    }

    public static RunParameters defaults() {
        return new RunParameters(DEFAULT_CAPITAL, DEFAULT_LEVERAGE, DEFAULT_FEE_RATE);
    }

    public double capitalOrDefault() {
        return capital != null ? capital : DEFAULT_CAPITAL;
    }

    public double leverageOrDefault() {
        return leverage != null ? leverage : DEFAULT_LEVERAGE;
    }

    public double feeRateOrDefault() {
        return feeRate != null ? feeRate : DEFAULT_FEE_RATE;
    }
}