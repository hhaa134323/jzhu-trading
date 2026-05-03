package ai.jzhu.strategy.domain.strategy.impl;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.Direction;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class MaCrossLongStrategy implements TradingStrategy {

    private static final int DEFAULT_FAST_PERIOD = 10;
    private static final int DEFAULT_SLOW_PERIOD = 20;

    @Override
    public String getId() {
        return "maCrossLong";
    }

    @Override
    public String getName() {
        return "双均线交叉-做多";
    }

    @Override
    public String getDescription() {
        return "MA10 上穿 MA20 且收盘价站上慢线时做多，MA10 下穿 MA20 或收盘价跌破慢线退出；默认参数 fast=10, slow=20";
    }

    @Override
    public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
        if (hasPosition || klines == null || klines.isEmpty() || indicators == null || indicators.ma() == null || currentIndex < DEFAULT_SLOW_PERIOD || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        int prevIndex = currentIndex - 1;
        if (prevIndex < 0) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        KlineData prevK = klines.get(prevIndex);
        if (currK == null || prevK == null) {
            return Optional.empty();
        }

        Double prevFast = indicators.ma().getMa10At(prevIndex);
        Double prevSlow = indicators.ma().getMa20At(prevIndex);
        Double currFast = indicators.ma().getMa10At(currentIndex);
        Double currSlow = indicators.ma().getMa20At(currentIndex);
        if (prevFast == null || prevSlow == null || currFast == null || currSlow == null) {
            return Optional.empty();
        }

        boolean crossUp = prevFast <= prevSlow && currFast > currSlow;
        boolean closeAboveSlow = currK.close() > currSlow;
        if (!crossUp || !closeAboveSlow) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.openLong(
                currentIndex,
                currK.close(),
                String.format(Locale.ROOT,
                        "MA%d 上穿 MA%d 且收盘价站上慢线; prevFast=%.2f, prevSlow=%.2f, fast=%.2f, slow=%.2f, close=%.2f",
                        DEFAULT_FAST_PERIOD,
                        DEFAULT_SLOW_PERIOD,
                        prevFast,
                        prevSlow,
                        currFast,
                        currSlow,
                        currK.close())
        ));
    }

    @Override
    public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
        if (openSignal == null || openSignal.direction() != Direction.LONG || klines == null || klines.isEmpty() || indicators == null || indicators.ma() == null || currentIndex < DEFAULT_SLOW_PERIOD || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        int prevIndex = currentIndex - 1;
        if (prevIndex < 0) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        KlineData prevK = klines.get(prevIndex);
        if (currK == null || prevK == null) {
            return Optional.empty();
        }

        Double prevFast = indicators.ma().getMa10At(prevIndex);
        Double prevSlow = indicators.ma().getMa20At(prevIndex);
        Double currFast = indicators.ma().getMa10At(currentIndex);
        Double currSlow = indicators.ma().getMa20At(currentIndex);
        if (prevFast == null || prevSlow == null || currFast == null || currSlow == null) {
            return Optional.empty();
        }

        boolean crossDown = prevFast >= prevSlow && currFast < currSlow;
        boolean closeBelowSlow = currK.close() < currSlow;
        if (!crossDown && !closeBelowSlow) {
            return Optional.empty();
        }

        String reason = crossDown
                ? String.format(Locale.ROOT,
                        "MA%d 下穿 MA%d; prevFast=%.2f, prevSlow=%.2f, fast=%.2f, slow=%.2f, close=%.2f",
                        DEFAULT_FAST_PERIOD,
                        DEFAULT_SLOW_PERIOD,
                        prevFast,
                        prevSlow,
                        currFast,
                        currSlow,
                        currK.close())
                : String.format(Locale.ROOT,
                        "收盘价跌破 MA%d; slow=%.2f, close=%.2f",
                        DEFAULT_SLOW_PERIOD,
                        currSlow,
                        currK.close());

        return Optional.of(TradeSignal.closeLong(currentIndex, currK.close(), reason));
    }
}