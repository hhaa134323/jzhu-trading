package ai.jzhu.trading.backtest.domain.model;

public record BacktestTradeDetail(
        int openIndex,
        int closeIndex,
        String openDate,
        String closeDate,
        double openPrice,
        double closePrice,
        String direction,
        String openReason,
        String closeReason,
        boolean closed
) {
}
