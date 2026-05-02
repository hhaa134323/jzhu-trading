package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class BacktestEngine {

    public List<BacktestTradeDetail> run(List<KlineData> klines, IndicatorData indicators, TradingStrategy strategy) {
        List<BacktestTradeDetail> trades = new ArrayList<>();
        if (klines == null || klines.isEmpty() || indicators == null || strategy == null) {
            return trades;
        }

        TradeSignal position = null;

        for (int i = 0; i < klines.size(); i++) {
            KlineData currK = klines.get(i);
            if (currK == null) {
                continue;
            }

            if (position == null) {
                Optional<TradeSignal> openSignal = strategy.checkOpenSignal(klines, indicators, i, false);
                if (openSignal.isPresent()) {
                    position = openSignal.get();
                }
            } else {
                Optional<TradeSignal> closeSignal = strategy.checkCloseSignal(klines, indicators, i, position);
                if (closeSignal.isPresent()) {
                    TradeSignal close = closeSignal.get();
                    trades.add(new BacktestTradeDetail(
                            position.index(),
                            close.index(),
                            klines.get(position.index()).date(),
                            klines.get(close.index()).date(),
                            position.price(),
                            close.price(),
                            position.direction().name(),
                            position.reason(),
                            close.reason(),
                            true
                    ));
                    position = null;
                }
            }
        }

        if (position != null) {
            KlineData last = klines.get(klines.size() - 1);
            trades.add(new BacktestTradeDetail(
                    position.index(),
                    -1,
                    klines.get(position.index()).date(),
                    null,
                    position.price(),
                    last.close(),
                    position.direction().name(),
                    position.reason(),
                    null,
                    false
            ));
        }

        return trades;
    }
}
