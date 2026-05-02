package ai.jzhu.strategy.domain.model;

public record KlineData(
        String date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {

    public boolean isBullishPillar() {
        return close > open;
    }

    public boolean isBearishPillar() {
        return close < open;
    }
}