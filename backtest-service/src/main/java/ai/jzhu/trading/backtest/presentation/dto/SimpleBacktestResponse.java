package ai.jzhu.trading.backtest.presentation.dto;

import ai.jzhu.trading.backtest.domain.model.BacktestTradeDetail;

import java.util.List;

public record SimpleBacktestResponse(
        String symbol,
        String strategyId,
        String strategyName,
        int totalTrades,
        List<BacktestTradeDetail> trades
) {
}
