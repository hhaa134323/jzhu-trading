package ai.jzhu.strategy.domain.strategy.impl;

import ai.jzhu.strategy.domain.indicator.BollData;
import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.indicator.MaData;
import ai.jzhu.strategy.domain.indicator.MacdData;
import ai.jzhu.strategy.domain.indicator.RsiData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeriousBuiltinStrategiesTest {

    @Test
    void maCrossOpenAndCloseWorkAndIgnoreFutureBars() {
        MaCrossLongStrategy strategy = new MaCrossLongStrategy();
        List<KlineData> klines = buildKlines(30);
        List<Double> ma10 = mutableSeries(30);
        List<Double> ma20 = mutableSeries(30);
        setDouble(ma10, 19, 10.0);
        setDouble(ma10, 20, 11.0);
        setDouble(ma10, 21, 10.2);
        setDouble(ma20, 19, 10.5);
        setDouble(ma20, 20, 10.6);
        setDouble(ma20, 21, 10.4);
        IndicatorData indicators = new IndicatorData(
            new MacdData(List.of(), List.of(), List.of()),
            new MaData(mutableSeries(30), ma10, ma20, mutableSeries(30), mutableSeries(30)),
            new RsiData(List.of(), List.of(), List.of()),
            new BollData(List.of(), List.of(), List.of())
        );

        setKline(klines, 19, kline(20, 99.0, 99.4, 98.7, 99.1));
        setKline(klines, 20, kline(21, 100.2, 100.9, 99.8, 101.2));
        setKline(klines, 21, kline(22, 100.4, 100.8, 99.6, 100.0));

        Optional<TradeSignal> openSignal = strategy.checkOpenSignal(klines, indicators, 20, false);
        assertTrue(openSignal.isPresent());
        assertNotNull(openSignal.get().reason());

        List<KlineData> futureMutated = new ArrayList<>(klines);
        futureMutated.set(22, kline(23, 500.0, 900.0, 1.0, 800.0));
        assertEquals(openSignal, strategy.checkOpenSignal(futureMutated, indicators, 20, false));

        Optional<TradeSignal> closeSignal = strategy.checkCloseSignal(klines, indicators, 21, openSignal.get());
        assertTrue(closeSignal.isPresent());
        assertNotNull(closeSignal.get().reason());
    }

    @Test
    void bollReversionUsesPreviousBarAndIgnoresFutureBars() {
        BollReversionLongStrategy strategy = new BollReversionLongStrategy();
        List<KlineData> klines = buildKlines(30);
        List<Double> lower = mutableSeries(30);
        List<Double> middle = mutableSeries(30);
        List<Double> upper = mutableSeries(30);
        List<Double> rsi12 = mutableSeries(30);
        setDouble(lower, 19, 100.0);
        setDouble(lower, 20, 101.0);
        setDouble(lower, 21, 101.5);
        setDouble(middle, 20, 104.0);
        setDouble(middle, 21, 104.5);
        setDouble(upper, 20, 107.0);
        setDouble(upper, 21, 107.5);
        setDouble(rsi12, 20, 27.0);
        IndicatorData indicators = new IndicatorData(
            new MacdData(List.of(), List.of(), List.of()),
            new MaData(List.of(), List.of(), List.of(), List.of(), List.of()),
            new RsiData(mutableSeries(30), rsi12, mutableSeries(30)),
            new BollData(upper, middle, lower)
        );

        setKline(klines, 19, kline(20, 98.0, 98.4, 97.4, 99.0));
        setKline(klines, 20, kline(21, 100.0, 103.0, 99.8, 102.0));
        setKline(klines, 21, kline(22, 102.0, 105.0, 101.2, 104.6));

        Optional<TradeSignal> openSignal = strategy.checkOpenSignal(klines, indicators, 20, false);
        assertTrue(openSignal.isPresent());
        assertTrue(openSignal.get().reason().contains("带内"));

        List<KlineData> futureMutated = new ArrayList<>(klines);
        futureMutated.set(22, kline(23, 500.0, 900.0, 1.0, 800.0));
        assertEquals(openSignal, strategy.checkOpenSignal(futureMutated, indicators, 20, false));

        Optional<TradeSignal> closeSignal = strategy.checkCloseSignal(klines, indicators, 21, openSignal.get());
        assertTrue(closeSignal.isPresent());
        assertNotNull(closeSignal.get().reason());
    }

    @Test
    void donchianBreakoutUsesPastWindowOnly() {
        BullishLongStrategy strategy = new BullishLongStrategy();
        List<KlineData> klines = buildKlines(30);
        List<Double> ma20 = mutableSeries(30);

        setKline(klines, 0, kline(1, 99.0, 100.0, 98.0, 99.0));
        for (int i = 1; i < 20; i++) {
            setKline(klines, i, kline(i + 1, 99.0, 100.0, 98.0, 99.0));
        }
        setKline(klines, 19, kline(20, 99.0, 100.0, 98.0, 99.0));
        setKline(klines, 20, kline(21, 100.0, 101.5, 99.5, 101.2));
        setKline(klines, 21, kline(22, 101.0, 102.0, 100.2, 101.6));
        setDouble(ma20, 21, 102.0);

        IndicatorData indicators = new IndicatorData(
                new MacdData(List.of(), List.of(), List.of()),
            new MaData(List.of(), List.of(), ma20, List.of(), List.of()),
                new RsiData(List.of(), List.of(), List.of()),
                new BollData(List.of(), List.of(), List.of())
        );

        Optional<TradeSignal> openSignal = strategy.checkOpenSignal(klines, indicators, 20, false);
        assertTrue(openSignal.isPresent());
        assertNotNull(openSignal.get().reason());

        List<KlineData> futureMutated = new ArrayList<>(klines);
        futureMutated.set(22, kline(23, 500.0, 999.0, 1.0, 800.0));
        assertEquals(openSignal, strategy.checkOpenSignal(futureMutated, indicators, 20, false));

        Optional<TradeSignal> closeSignal = strategy.checkCloseSignal(klines, indicators, 21, openSignal.get());
        assertTrue(closeSignal.isPresent());
        assertNotNull(closeSignal.get().reason());
    }

    @Test
    void insufficientDataReturnsEmpty() {
        MaCrossLongStrategy maCross = new MaCrossLongStrategy();
        BollReversionLongStrategy boll = new BollReversionLongStrategy();
        BullishLongStrategy donchian = new BullishLongStrategy();
        List<KlineData> shortKlines = buildKlines(8);
        IndicatorData emptyIndicators = new IndicatorData(
                new MacdData(List.of(), List.of(), List.of()),
                new MaData(List.of(), List.of(), List.of(), List.of(), List.of()),
                new RsiData(List.of(), List.of(), List.of()),
                new BollData(List.of(), List.of(), List.of())
        );

        assertTrue(maCross.checkOpenSignal(shortKlines, emptyIndicators, 7, false).isEmpty());
        assertTrue(boll.checkOpenSignal(shortKlines, emptyIndicators, 7, false).isEmpty());
        assertTrue(donchian.checkOpenSignal(shortKlines, emptyIndicators, 7, false).isEmpty());
    }

    private static List<KlineData> buildKlines(int size) {
        List<KlineData> klines = new ArrayList<>(Collections.nCopies(size, null));
        for (int i = 0; i < size; i++) {
            double close = 100.0 + (i * 0.2);
            klines.set(i, kline(i + 1, close - 0.2, close + 0.4, close - 0.6, close));
        }
        return klines;
    }

    private static KlineData kline(int day, double open, double high, double low, double close) {
        return new KlineData("2026-01-" + String.format("%02d", day), open, high, low, close, 1000L);
    }

    private static List<Double> mutableSeries(int size) {
        return new ArrayList<>(Collections.nCopies(size, null));
    }

    private static void setKline(List<KlineData> list, int index, KlineData value) {
        list.set(index, value);
    }

    private static void setDouble(List<Double> list, int index, Double value) {
        list.set(index, value);
    }
}