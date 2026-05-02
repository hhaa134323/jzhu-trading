package ai.jzhu.strategy.domain.indicator;

import ai.jzhu.trading.common.dto.indicator.MacdResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MacdData(
        List<Double> difList,
        List<Double> deaList,
        List<Double> macdList
) {

    public MacdData {
        difList = normalize(difList);
        deaList = normalize(deaList);
        macdList = normalize(macdList);
    }

    public static MacdData from(MacdResult result) {
        if (result == null) {
            return new MacdData(List.of(), List.of(), List.of());
        }
        return new MacdData(result.difList(), result.deaList(), result.macdList());
    }

    public Double getDifAt(int index) {
        return getAt(difList, index);
    }

    public Double getDeaAt(int index) {
        return getAt(deaList, index);
    }

    public Double getMacdAt(int index) {
        return getAt(macdList, index);
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