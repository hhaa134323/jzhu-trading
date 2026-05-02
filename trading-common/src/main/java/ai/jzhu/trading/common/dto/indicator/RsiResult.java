package ai.jzhu.trading.common.dto.indicator;

import java.util.List;

public record RsiResult(
        List<Double> rsi6List,
        List<Double> rsi12List,
        List<Double> rsi24List
) {
}
