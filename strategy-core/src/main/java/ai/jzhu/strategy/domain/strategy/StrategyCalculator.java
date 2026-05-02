package ai.jzhu.strategy.domain.strategy;

import ai.jzhu.strategy.domain.model.KlineData;

import java.util.List;

public final class StrategyCalculator {

    private StrategyCalculator() {
    }

    public static double getValidMaxCloseHighBetweenLastPeriod(List<KlineData> klines, int endIndex, int period, int minPeriod) {
        if (klines == null || klines.isEmpty() || endIndex < 0 || endIndex >= klines.size() || period <= 0 || minPeriod <= 0) {
            return Double.NaN;
        }

        int startIndex = Math.max(0, endIndex - period + 1);
        int actualCount = endIndex - startIndex + 1;
        if (actualCount < minPeriod) {
            return Double.NaN;
        }

        double maxHigh = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (int i = startIndex; i <= endIndex; i++) {
            KlineData kline = klines.get(i);
            if (kline == null) {
                continue;
            }
            maxHigh = Math.max(maxHigh, kline.high());
            found = true;
        }

        return found ? maxHigh : Double.NaN;
    }
}