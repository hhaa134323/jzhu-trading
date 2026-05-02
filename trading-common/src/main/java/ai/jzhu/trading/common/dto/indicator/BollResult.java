package ai.jzhu.trading.common.dto.indicator;

import java.util.List;

public record BollResult(
        List<Double> upperList,
        List<Double> middleList,
        List<Double> lowerList
) {
}
