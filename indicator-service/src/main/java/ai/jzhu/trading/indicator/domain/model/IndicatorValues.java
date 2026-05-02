package ai.jzhu.trading.indicator.domain.model;

import java.util.List;

public record IndicatorValues(
        List<Double> difList,
        List<Double> deaList,
        List<Double> macdList,
        List<Double> ma5List,
        List<Double> ma10List,
        List<Double> ma20List,
        List<Double> ma30List,
        List<Double> ma60List,
        List<Double> rsi6List,
        List<Double> rsi12List,
        List<Double> rsi24List,
        List<Double> upperList,
        List<Double> middleList,
        List<Double> lowerList
) {
}
