package ai.jzhu.strategy.domain.indicator;

import ai.jzhu.trading.common.dto.indicator.BollResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record BollData(
        List<Double> upperList,
        List<Double> middleList,
        List<Double> lowerList
) {

    public BollData {
        upperList = normalize(upperList);
        middleList = normalize(middleList);
        lowerList = normalize(lowerList);
    }

    public static BollData from(BollResult result) {
        if (result == null) {
            return new BollData(List.of(), List.of(), List.of());
        }
        return new BollData(result.upperList(), result.middleList(), result.lowerList());
    }

    public Double getUpperAt(int index) {
        return getAt(upperList, index);
    }

    public Double getMiddleAt(int index) {
        return getAt(middleList, index);
    }

    public Double getLowerAt(int index) {
        return getAt(lowerList, index);
    }

    private static Double getAt(List<Double> values, int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }

    private static List<Double> normalize(List<Double> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}