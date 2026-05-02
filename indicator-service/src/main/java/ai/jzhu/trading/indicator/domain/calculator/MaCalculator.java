package ai.jzhu.trading.indicator.domain.calculator;

import ai.jzhu.trading.common.dto.indicator.MaResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class MaCalculator {

    public MaResult calculate(List<Double> closes) {
        return new MaResult(
                movingAverage(closes, 5),
                movingAverage(closes, 10),
                movingAverage(closes, 20),
                movingAverage(closes, 30),
                movingAverage(closes, 60)
        );
    }

    private List<Double> movingAverage(List<Double> closes, int period) {
        List<Double> result = new ArrayList<>(closes.size());
        double rollingSum = 0.0;

        for (int i = 0; i < closes.size(); i++) {
            rollingSum += closes.get(i);
            if (i >= period) {
                rollingSum -= closes.get(i - period);
            }

            if (i < period - 1) {
                result.add(null);
            } else {
                result.add(round2(rollingSum / period));
            }
        }
        return result;
    }

    private Double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
