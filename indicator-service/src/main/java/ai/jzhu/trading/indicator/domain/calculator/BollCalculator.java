package ai.jzhu.trading.indicator.domain.calculator;

import ai.jzhu.trading.common.dto.indicator.BollResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BollCalculator {

    public BollResult calculate(List<Double> closes) {
        int size = closes.size();
        List<Double> upper = new ArrayList<>(size);
        List<Double> middle = new ArrayList<>(size);
        List<Double> lower = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            if (i < 19) {
                upper.add(null);
                middle.add(null);
                lower.add(null);
                continue;
            }

            double mean = 0.0;
            for (int j = i - 19; j <= i; j++) {
                mean += closes.get(j);
            }
            mean /= 20.0;

            double variance = 0.0;
            for (int j = i - 19; j <= i; j++) {
                double diff = closes.get(j) - mean;
                variance += diff * diff;
            }
            variance /= 20.0;

            double std = Math.sqrt(variance);
            double up = mean + 2.0 * std;
            double down = mean - 2.0 * std;

            // Full precision throughout; round2 only at DTO serialization boundary
            middle.add(mean);
            upper.add(up);
            lower.add(down);
        }

        return new BollResult(upper, middle, lower);
    }
}
