package ai.jzhu.trading.common.dto.indicator;

import java.util.List;

public record MacdResult(
        List<Double> difList,
        List<Double> deaList,
        List<Double> macdList
) {
}
