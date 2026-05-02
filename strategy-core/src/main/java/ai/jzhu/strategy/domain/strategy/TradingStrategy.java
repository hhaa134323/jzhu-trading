package ai.jzhu.strategy.domain.strategy;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;

import java.util.List;
import java.util.Optional;

public interface TradingStrategy {

    String getId();

    String getName();

    String getDescription();

    Optional<TradeSignal> checkOpenSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, boolean hasPosition);

    Optional<TradeSignal> checkCloseSignal(List<KlineData> klines, IndicatorData indicators, int currentIndex, TradeSignal openSignal);
}