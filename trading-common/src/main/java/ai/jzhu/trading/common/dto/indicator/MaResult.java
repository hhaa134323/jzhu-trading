package ai.jzhu.trading.common.dto.indicator;

import java.util.List;

public record MaResult(
        List<Double> ma5List,
        List<Double> ma10List,
        List<Double> ma20List,
        List<Double> ma30List,
        List<Double> ma60List
) {
}
