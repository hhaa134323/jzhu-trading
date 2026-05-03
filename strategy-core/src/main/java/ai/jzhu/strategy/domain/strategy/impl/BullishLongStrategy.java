package ai.jzhu.strategy.domain.strategy.impl;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.Direction;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.StrategyCalculator;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Locale;

@Component
public class BullishLongStrategy implements TradingStrategy {

    private static final int DEFAULT_LOOKBACK = 20;
    private static final int DEFAULT_EXIT_LOOKBACK = 10;

    @Override
    public String getId() {
        return "donchianBreakoutLong";
    }

    @Override
    public String getName() {
        return "Donchian通道突破-做多";
    }

    @Override
    public String getDescription() {
        return "收盘价突破过去N日高点后做多，跌破过去M日低点或跌破均线退出；默认参数 lookback=20, exitLookback=10";
    }

    @Override
    public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
        if (hasPosition || klines == null || klines.isEmpty() || indicators == null || currentIndex < DEFAULT_LOOKBACK || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        if (currentIndex < 1) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        KlineData prevK = klines.get(currentIndex - 1);
        if (currK == null || prevK == null) {
            return Optional.empty();
        }

        double maxHigh = StrategyCalculator.getValidMaxCloseHighBetweenLastPeriod(klines, currentIndex - 1, DEFAULT_LOOKBACK, DEFAULT_LOOKBACK);
        if (Double.isNaN(maxHigh)) {
            return Optional.empty();
        }

        Double prevClose = prevK.close();
        boolean openCondition = prevClose <= maxHigh && currK.close() > maxHigh;

        if (!openCondition) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.openLong(
                currentIndex,
                currK.close(),
                String.format(Locale.ROOT,
                        "收盘价突破过去%d根K线最高价并完成上破确认; prevClose=%.2f, breakoutHigh=%.2f, close=%.2f",
                        DEFAULT_LOOKBACK,
                        prevClose,
                        maxHigh,
                        currK.close())
        ));
    }

    @Override
    public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
        if (openSignal == null || openSignal.direction() != Direction.LONG || klines == null || klines.isEmpty() || indicators == null || currentIndex < DEFAULT_EXIT_LOOKBACK || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        if (currentIndex < 1) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        if (currK == null) {
            return Optional.empty();
        }

        double minLow = getValidMinLowBetweenLastPeriod(klines, currentIndex - 1, DEFAULT_EXIT_LOOKBACK, DEFAULT_EXIT_LOOKBACK);
        if (Double.isNaN(minLow)) {
            return Optional.empty();
        }

        Double currMa20 = indicators.ma() == null ? null : indicators.ma().getMa20At(currentIndex);
        boolean exitByBreakdown = currK.close() < minLow;
        boolean exitByMa = currMa20 != null && currK.close() < currMa20;

        if (!exitByBreakdown && !exitByMa) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.closeLong(
                currentIndex,
                currK.close(),
                exitByBreakdown
                        ? String.format(Locale.ROOT,
                                "收盘价跌破过去%d根K线最低价; exitLow=%.2f, close=%.2f",
                                DEFAULT_EXIT_LOOKBACK,
                                minLow,
                                currK.close())
                        : String.format(Locale.ROOT,
                                "收盘价跌破MA20过滤线; ma20=%.2f, close=%.2f",
                                currMa20,
                                currK.close())
        ));
    }

    private static double getValidMinLowBetweenLastPeriod(List<KlineData> klines, int endIndex, int period, int minPeriod) {
        if (klines == null || klines.isEmpty() || endIndex < 0 || endIndex >= klines.size() || period <= 0 || minPeriod <= 0) {
            return Double.NaN;
        }

        int startIndex = Math.max(0, endIndex - period + 1);
        int actualCount = endIndex - startIndex + 1;
        if (actualCount < minPeriod) {
            return Double.NaN;
        }

        double minLow = Double.POSITIVE_INFINITY;
        boolean found = false;
        for (int i = startIndex; i <= endIndex; i++) {
            KlineData kline = klines.get(i);
            if (kline == null) {
                continue;
            }
            minLow = Math.min(minLow, kline.low());
            found = true;
        }

        return found ? minLow : Double.NaN;
    }
}