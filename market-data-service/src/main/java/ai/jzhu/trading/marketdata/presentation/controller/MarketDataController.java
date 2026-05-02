package ai.jzhu.trading.marketdata.presentation.controller;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.marketdata.application.usecase.GetKlineUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping({"/api/market-data", "/api/market"})
public class MarketDataController {

    private final GetKlineUseCase getKlineUseCase;

    public MarketDataController(GetKlineUseCase getKlineUseCase) {
        this.getKlineUseCase = getKlineUseCase;
    }

    @GetMapping("/kline")
    public List<KlineResponse> getKline(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "us") String market,
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);

        return getKlineUseCase.execute(
                symbol.trim().toUpperCase(),
                market.trim().toLowerCase(),
                period.trim().toLowerCase(),
                start,
                end
        );
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        return LocalDate.parse(dateStr.trim());
    }
}
