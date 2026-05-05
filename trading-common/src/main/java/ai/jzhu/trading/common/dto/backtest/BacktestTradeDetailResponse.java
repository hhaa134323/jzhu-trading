package ai.jzhu.trading.common.dto.backtest;

public record BacktestTradeDetailResponse(
        int openIndex,
        int closeIndex,
        String openDate,
        String closeDate,
        double openPrice,
        double closePrice,
        String direction,
        String openReason,
        String closeReason,
        boolean closed,
        Double fee
) {
}
