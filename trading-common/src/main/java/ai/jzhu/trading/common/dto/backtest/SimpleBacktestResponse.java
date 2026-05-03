package ai.jzhu.trading.common.dto.backtest;

import java.util.List;

public record SimpleBacktestResponse(
        String symbol,
        String strategyId,
        String strategyName,
        int totalTrades,
        List<BacktestTradeDetailResponse> trades,
        BacktestMetrics metrics
) {
}
