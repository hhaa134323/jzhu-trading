package ai.jzhu.strategy.domain.indicator;

import ai.jzhu.trading.common.dto.indicator.MaResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MaData(
        List<Double> ma5List,
        List<Double> ma10List,
        List<Double> ma20List,
        List<Double> ma30List,
        List<Double> ma60List
) {

    public MaData {
        ma5List = normalize(ma5List);
        ma10List = normalize(ma10List);
        ma20List = normalize(ma20List);
        ma30List = normalize(ma30List);
        ma60List = normalize(ma60List);
    }

    public static MaData from(MaResult result) {
        if (result == null) {
            return new MaData(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new MaData(result.ma5List(), result.ma10List(), result.ma20List(), result.ma30List(), result.ma60List());
    }

    public Double getMa5At(int index) {
        return getAt(ma5List, index);
    }

    public Double getMa10At(int index) {
        return getAt(ma10List, index);
    }

    public Double getMa20At(int index) {
        return getAt(ma20List, index);
    }

    public Double getMa30At(int index) {
        return getAt(ma30List, index);
    }

    public Double getMa60At(int index) {
        return getAt(ma60List, index);
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