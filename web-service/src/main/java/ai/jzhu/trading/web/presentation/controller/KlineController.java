package ai.jzhu.trading.web.presentation.controller;

import ai.jzhu.trading.common.dto.KlineWithIndicatorsResponse;
import ai.jzhu.trading.web.application.usecase.GetKlineUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web")
public class KlineController {

    private final GetKlineUseCase getKlineUseCase;

    public KlineController(GetKlineUseCase getKlineUseCase) {
        this.getKlineUseCase = getKlineUseCase;
    }

    @GetMapping("/kline")
    public KlineWithIndicatorsResponse getKline(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "us") String market,
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return getKlineUseCase.execute(
                symbol.trim().toUpperCase(),
                market.trim().toLowerCase(),
                period.trim().toLowerCase(),
                startDate,
                endDate
        );
    }
}
