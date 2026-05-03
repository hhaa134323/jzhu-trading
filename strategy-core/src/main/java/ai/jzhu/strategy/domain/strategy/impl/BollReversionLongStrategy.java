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
public class BollReversionLongStrategy implements TradingStrategy {

    private static final int DEFAULT_BOLL_WINDOW = 20;
    private static final double DEFAULT_BOLL_K = 2.0D;
    private static final int DEFAULT_RSI_PERIOD = 12;
    private static final double DEFAULT_RSI_BUY_THRESHOLD = 30.0D;

    @Override
    public String getId() {
        return "bollReversionLong";
    }

    @Override
    public String getName() {
        return "布林带均值回归-做多";
    }

    @Override
    public String getDescription() {
        return "收盘价先跌破下轨，再在下一根K线回到带内做多；以中轨/上轨止盈、下轨止损。当前实现使用 RSI12 作为超卖辅助，默认参数 bollWindow=20, k=2, rsiBuyThreshold=30";
    }

    @Override
    public Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition) {
        if (hasPosition || klines == null || klines.isEmpty() || indicators == null || indicators.boll() == null || currentIndex < DEFAULT_BOLL_WINDOW || currentIndex >= klines.size()) {
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

        Double prevLower = indicators.boll().getLowerAt(prevIndex);
        Double currLower = indicators.boll().getLowerAt(currentIndex);
        Double currMiddle = indicators.boll().getMiddleAt(currentIndex);
        Double currRsi = indicators.rsi() == null ? null : indicators.rsi().getRsi12At(currentIndex);
        if (prevLower == null || currLower == null || currMiddle == null) {
            return Optional.empty();
        }

        boolean reenteredBand = prevK.close() < prevLower && currK.close() >= currLower;
        boolean reboundConfirmed = currK.close() > prevK.close();
        boolean insideBandWithSupport = currK.close() <= currMiddle || (currRsi != null && currRsi <= DEFAULT_RSI_BUY_THRESHOLD);
        if (!reenteredBand || !reboundConfirmed || !insideBandWithSupport) {
            return Optional.empty();
        }

        return Optional.of(TradeSignal.openLong(
                currentIndex,
                currK.close(),
                String.format(Locale.ROOT,
                        "前一根收盘价跌破下轨，当前收盘回到带内并满足反弹确认; prevClose=%.2f, prevLower=%.2f, close=%.2f, lower=%.2f, middle=%.2f, rsi%d=%s, rsiBuyThreshold=%.2f, bollWindow=%d, k=%.1f",
                        prevK.close(),
                        prevLower,
                        currK.close(),
                        currLower,
                        currMiddle,
                        DEFAULT_RSI_PERIOD,
                        currRsi == null ? "null" : String.format(Locale.ROOT, "%.2f", currRsi),
                        DEFAULT_RSI_BUY_THRESHOLD,
                        DEFAULT_BOLL_WINDOW,
                        DEFAULT_BOLL_K)
        ));
    }

    @Override
    public Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal) {
        if (openSignal == null || openSignal.direction() != Direction.LONG || klines == null || klines.isEmpty() || indicators == null || indicators.boll() == null || currentIndex < DEFAULT_BOLL_WINDOW || currentIndex >= klines.size()) {
            return Optional.empty();
        }

        KlineData currK = klines.get(currentIndex);
        if (currK == null) {
            return Optional.empty();
        }

        Double currLower = indicators.boll().getLowerAt(currentIndex);
        Double currMiddle = indicators.boll().getMiddleAt(currentIndex);
        Double currUpper = indicators.boll().getUpperAt(currentIndex);
        if (currLower == null || currMiddle == null || currUpper == null) {
            return Optional.empty();
        }

        boolean stopLoss = currK.close() < currLower;
        boolean takeProfitUpper = currK.close() >= currUpper;
        boolean takeProfitMiddle = currK.close() >= currMiddle;

        if (!stopLoss && !takeProfitUpper && !takeProfitMiddle) {
            return Optional.empty();
        }

        String reason = stopLoss
                ? String.format(Locale.ROOT,
                        "收盘价跌破下轨止损; lower=%.2f, middle=%.2f, upper=%.2f, close=%.2f, bollWindow=%d, k=%.1f",
                        currLower,
                        currMiddle,
                        currUpper,
                        currK.close(),
                        DEFAULT_BOLL_WINDOW,
                        DEFAULT_BOLL_K)
                : takeProfitUpper
                ? String.format(Locale.ROOT,
                        "收盘价触及上轨止盈; lower=%.2f, middle=%.2f, upper=%.2f, close=%.2f, bollWindow=%d, k=%.1f",
                        currLower,
                        currMiddle,
                        currUpper,
                        currK.close(),
                        DEFAULT_BOLL_WINDOW,
                        DEFAULT_BOLL_K)
                : String.format(Locale.ROOT,
                        "收盘价触及中轨止盈; lower=%.2f, middle=%.2f, upper=%.2f, close=%.2f, bollWindow=%d, k=%.1f",
                        currLower,
                        currMiddle,
                        currUpper,
                        currK.close(),
                        DEFAULT_BOLL_WINDOW,
                        DEFAULT_BOLL_K);

        return Optional.of(TradeSignal.closeLong(currentIndex, currK.close(), reason));
    }
}