package ai.jzhu.trading.backtest.domain.service;

import ai.jzhu.strategy.domain.indicator.IndicatorData;
import ai.jzhu.strategy.domain.model.KlineData;
import ai.jzhu.strategy.domain.model.TradeSignal;
import ai.jzhu.strategy.domain.strategy.TradingStrategy;
import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;
import ai.jzhu.trading.common.dto.backtest.RunParameters;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class BacktestEngine {

    /**
     * Run backtest with default parameters (no slippage/commission in fill price).
     */
    public List<BacktestTradeDetail> run(List<KlineData> klines, IndicatorData indicators, TradingStrategy strategy) {
        return run(klines, indicators, strategy, null);
    }

    /**
     * Run backtest with the given run parameters.
     *
     * <p>Fill price model (avoiding look-ahead bias):
     * <ul>
     *   <li>Signal generated at bar t (using close/indicators of bar t for decision).</li>
     *   <li>Execution (fill) happens at bar t+1 open.</li>
     *   <li>If there is no t+1 bar, the signal is skipped (no trade).</li>
     *   <li>Slippage is applied to the fill price based on direction.</li>
     * </ul>
     */
    public List<BacktestTradeDetail> run(List<KlineData> klines, IndicatorData indicators, TradingStrategy strategy, RunParameters runParams) {
        List<BacktestTradeDetail> trades = new ArrayList<>();
        if (klines == null || klines.isEmpty() || indicators == null || strategy == null) {
            return trades;
        }

        double slippageBps = runParams != null ? runParams.slippageBpsOrDefault() : 0.0;
        double commissionBps = runParams != null ? runParams.commissionBpsOrDefault() : 0.0;
        // For per-trade commission we store it in the metrics calculator; slippage is applied per fill.

        TradeSignal position = null;

        for (int i = 0; i < klines.size(); i++) {
            if (klines.get(i) == null) {
                continue;
            }

            if (position == null) {
                Optional<TradeSignal> openSignal = strategy.checkOpenSignal(klines, indicators, i, false);
                if (openSignal.isPresent()) {
                    TradeSignal sig = openSignal.get();
                    // Must have a next bar to fill at
                    int fillIndex = sig.index() + 1;
                    if (fillIndex >= klines.size()) {
                        // Cannot fill — signal at last bar, no next bar to trade
                        position = null;
                        continue;
                    }
                    KlineData fillK = klines.get(fillIndex);
                    double rawFillPrice = fillK.open();
                    double fillPrice = applySlippage(rawFillPrice, sig.direction(), true, slippageBps);
                    position = new TradeSignal(fillIndex, fillPrice, sig.direction(), sig.type(), sig.reason());
                }
            } else {
                Optional<TradeSignal> closeSignal = strategy.checkCloseSignal(klines, indicators, i, position);
                if (closeSignal.isPresent()) {
                    TradeSignal sig = closeSignal.get();
                    int fillIndex = sig.index() + 1;
                    double fillPrice;
                    double closeReasonIdxPrice;
                    boolean closed;
                    String closeDate;
                    int realCloseIndex;
                    if (fillIndex >= klines.size()) {
                        // No next bar — last bar triggered close signal.
                        // Use current bar's close as fill price (mark-to-market at bar end).
                        // closeIndex = sig.index() (NOT fillIndex) to stay within array bounds.
                        realCloseIndex = sig.index();
                        fillPrice = klines.get(realCloseIndex).close();
                        fillPrice = applySlippage(fillPrice, sig.direction(), false, slippageBps);
                        closeReasonIdxPrice = fillPrice;
                        closed = true;
                        closeDate = klines.get(realCloseIndex).date();
                    } else {
                        realCloseIndex = fillIndex;
                        KlineData fillK = klines.get(realCloseIndex);
                        fillPrice = applySlippage(fillK.open(), sig.direction(), false, slippageBps);
                        closeReasonIdxPrice = fillPrice;
                        closed = true;
                        closeDate = fillK.date();
                    }
                    trades.add(new BacktestTradeDetail(
                            position.index(),
                            realCloseIndex,
                            klines.get(position.index()).date(),
                            closeDate,
                            position.price(),
                            fillPrice,
                            position.direction().name(),
                            position.reason(),
                            sig.reason(),
                            closed
                    ));
                    position = null;
                }
            }
        }

        // Handle unclosed position at end: force-close at last bar's close (mark-to-market liquidation)
        if (position != null) {
            KlineData last = klines.get(klines.size() - 1);
            double liquidationPrice = applySlippage(last.close(), position.direction(), false, slippageBps);
            trades.add(new BacktestTradeDetail(
                    position.index(),
                    klines.size() - 1,
                    klines.get(position.index()).date(),
                    last.date(),
                    position.price(),
                    liquidationPrice,
                    position.direction().name(),
                    position.reason(),
                    "回测结束强制平仓",
                    true
            ));
        }

        return trades;
    }

    /**
     * Apply slippage to a raw fill price based on trade direction and side (open/close).
     *
     * <p>LONG open: buy → price increases (raw * (1 + bps/10000))<br>
     * LONG close: sell → price decreases (raw * (1 - bps/10000))<br>
     * SHORT open: sell → price decreases (raw * (1 - bps/10000))<br>
     * SHORT close: buy back → price increases (raw * (1 + bps/10000))
     */
    private static double applySlippage(double rawPrice, ai.jzhu.strategy.domain.model.Direction direction, boolean isOpen, double slippageBps) {
        if (slippageBps <= 0.0) {
            return rawPrice;
        }
        double factor = slippageBps / 10000.0;
        boolean isLong = direction == ai.jzhu.strategy.domain.model.Direction.LONG;
        // open long  / close short → buy (price ↑)
        // close long / open short  → sell (price ↓)
        boolean isBuy = (isLong && isOpen) || (!isLong && !isOpen);
        if (isBuy) {
            return rawPrice * (1.0 + factor);
        } else {
            return rawPrice * (1.0 - factor);
        }
    }
}
