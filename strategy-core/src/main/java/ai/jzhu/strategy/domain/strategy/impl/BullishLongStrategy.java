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

@Component
public class BullishLongStrategy implements TradingStrategy {

    @Override
    public String getId() {
        return "bullishLong";
    }

    @Override
    public String getName() {
        return "高位突破-做多";
    }

    @Override
    public String getDescription() {
        return "高位突破并回踩均线后的做多策略";
    }

    @Override
    public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
        if (hasPosition || klines == null || indicators == null || currentIndex < 10 || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        if (currentIndex - 1 < 0) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        KlineData lastK1 = klines.get(currentIndex - 1);
        if (currK == null || lastK1 == null || indicators.macd() == null || indicators.ma() == null) {
            return Optional.empty();
        }

        double maxHigh = StrategyCalculator.getValidMaxCloseHighBetweenLastPeriod(klines, currentIndex - 2, 250, 5);
        if (Double.isNaN(maxHigh)) {
            return Optional.empty();
        }

        Double dif = indicators.macd().getDifAt(currentIndex);
        Double dea = indicators.macd().getDeaAt(currentIndex);
        Double ma5 = indicators.ma().getMa5At(currentIndex);

        if (dif == null || dea == null || ma5 == null) {
            return Optional.empty();
        }

        boolean openCondition = currK.close() > maxHigh
                && currK.isBullishPillar()
                && lastK1.high() <= maxHigh
                && dif > dea
                && Math.min(lastK1.close(), currK.low()) <= ma5;

        if (!openCondition) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.openLong(
                currentIndex,
                currK.close(),
                "突破前高后回踩MA5并确认MACD金叉"
        ));
    }

    @Override
    public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
        if (openSignal == null || openSignal.direction() != Direction.LONG || klines == null || indicators == null || currentIndex < 5 || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        int prevIndex = currentIndex - 1;
        if (prevIndex < 0) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        KlineData prevK = klines.get(prevIndex);
        if (currK == null || prevK == null || indicators.ma() == null) {
            return Optional.empty();
        }

        Double currMa5 = indicators.ma().getMa5At(currentIndex);
        Double currMa10 = indicators.ma().getMa10At(currentIndex);
        Double prevMa5 = indicators.ma().getMa5At(prevIndex);
        Double prevMa10 = indicators.ma().getMa10At(prevIndex);

        if (currMa5 == null || currMa10 == null || prevMa5 == null || prevMa10 == null) {
            return Optional.empty();
        }

        boolean closeCondition = prevK.close() < prevMa5
                && prevK.close() < prevMa10
                && currK.close() < currMa5
                && currK.close() < currMa10;

        if (!closeCondition) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.closeLong(
                currentIndex,
                currK.close(),
                "连续2根K线收盘价跌破MA5和MA10"
        ));
    }
}