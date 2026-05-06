package ai.jzhu.trading.indicator.domain.calculator;

import ai.jzhu.trading.common.dto.indicator.MacdResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MacdCalculator {

    public MacdResult calculate(List<Double> closes) {
        int size = closes.size();
        List<Double> difList = new ArrayList<>(size);
        List<Double> deaList = new ArrayList<>(size);
        List<Double> macdList = new ArrayList<>(size);

        if (size == 0) {
            return new MacdResult(difList, deaList, macdList);
        }

        double ema12 = closes.get(0);
        double ema26 = closes.get(0);
        double dea = 0.0;

        for (int i = 0; i < size; i++) {
            double close = closes.get(i);
            if (i == 0) {
                ema12 = close;
                ema26 = close;
            } else {
                ema12 = close * (2.0 / 13.0) + ema12 * (11.0 / 13.0);
                ema26 = close * (2.0 / 27.0) + ema26 * (25.0 / 27.0);
            }

            double dif = ema12 - ema26;
            if (i == 0) {
                dea = dif;
            } else {
                dea = dif * (2.0 / 10.0) + dea * (8.0 / 10.0);
            }
            double macd = (dif - dea) * 2.0;

            // Full precision throughout; round2 only at DTO serialization boundary
            difList.add(dif);
            deaList.add(dea);
            macdList.add(macd);
        }

        return new MacdResult(difList, deaList, macdList);
    }
}
