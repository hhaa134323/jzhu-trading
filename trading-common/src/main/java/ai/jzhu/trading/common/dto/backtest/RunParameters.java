package ai.jzhu.trading.common.dto.backtest;

public record RunParameters(
        Double capital,
        Double leverage,
        Double feeRate,
        Double slippageBps,
        Double commissionBps
) {
    public static final double DEFAULT_CAPITAL = 100_000.0;
    public static final double DEFAULT_LEVERAGE = 1.0;
    public static final double DEFAULT_FEE_RATE = 0.0;
    public static final double DEFAULT_SLIPPAGE_BPS = 0.0;
    public static final double DEFAULT_COMMISSION_BPS = 0.0;

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
        if (slippageBps != null && slippageBps < 0) {
            throw new IllegalArgumentException("slippageBps must be >= 0");
        }
        if (commissionBps != null && commissionBps < 0) {
            throw new IllegalArgumentException("commissionBps must be >= 0");
        }
    }

    /** Backwards-compatible 3-arg convenience: capital, leverage, feeRate with zero slippage/commission. */
    public static RunParameters of(Double capital, Double leverage, Double feeRate) {
        return new RunParameters(capital, leverage, feeRate, 0.0, 0.0);
    }

    /** Zero-cost parameters (no slippage, no commission, no fee, default capital+leverage). */
    public static RunParameters zeroCost() {
        return new RunParameters(DEFAULT_CAPITAL, DEFAULT_LEVERAGE, 0.0, 0.0, 0.0);
    }

    public static RunParameters defaults() {
        return new RunParameters(DEFAULT_CAPITAL, DEFAULT_LEVERAGE, DEFAULT_FEE_RATE, DEFAULT_SLIPPAGE_BPS, DEFAULT_COMMISSION_BPS);
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

    public double slippageBpsOrDefault() {
        return slippageBps != null ? slippageBps : DEFAULT_SLIPPAGE_BPS;
    }

    public double commissionBpsOrDefault() {
        return commissionBps != null ? commissionBps : DEFAULT_COMMISSION_BPS;
    }
}