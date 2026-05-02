package ai.jzhu.strategy.domain.indicator;

import ai.jzhu.trading.common.dto.indicator.RsiResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record RsiData(
        List<Double> rsi6List,
        List<Double> rsi12List,
        List<Double> rsi24List
) {

    public RsiData {
        rsi6List = normalize(rsi6List);
        rsi12List = normalize(rsi12List);
        rsi24List = normalize(rsi24List);
    }

    public static RsiData from(RsiResult result) {
        if (result == null) {
            return new RsiData(List.of(), List.of(), List.of());
        }
        return new RsiData(result.rsi6List(), result.rsi12List(), result.rsi24List());
    }

    public Double getRsi6At(int index) {
        return getAt(rsi6List, index);
    }

    public Double getRsi12At(int index) {
        return getAt(rsi12List, index);
    }

    public Double getRsi24At(int index) {
        return getAt(rsi24List, index);
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