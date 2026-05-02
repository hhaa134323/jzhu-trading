package ai.jzhu.trading.indicator.domain.calculator;

import ai.jzhu.trading.common.dto.indicator.RsiResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class RsiCalculator {

    public RsiResult calculate(List<Double> closes) {
        return new RsiResult(
                calculateRsi(closes, 6),
                calculateRsi(closes, 12),
                calculateRsi(closes, 24)
        );
    }

    private List<Double> calculateRsi(List<Double> closes, int period) {
        int size = closes.size();
        List<Double> rsi = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rsi.add(null);
        }

        if (size <= period) {
            return rsi;
        }

        double gainSum = 0.0;
        double lossSum = 0.0;
        for (int i = 1; i <= period; i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            if (diff > 0) {
                gainSum += diff;
            } else {
                lossSum += -diff;
            }
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        rsi.set(period, round2(toRsi(avgGain, avgLoss)));

        for (int i = period + 1; i < size; i++) {
            double diff = closes.get(i) - closes.get(i - 1);
            double gain = Math.max(diff, 0.0);
            double loss = Math.max(-diff, 0.0);

            avgGain = ((avgGain * (period - 1)) + gain) / period;
            avgLoss = ((avgLoss * (period - 1)) + loss) / period;

            rsi.set(i, round2(toRsi(avgGain, avgLoss)));
        }

        return rsi;
    }

    private double toRsi(double avgGain, double avgLoss) {
        if (avgLoss == 0.0) {
            return 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private Double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
