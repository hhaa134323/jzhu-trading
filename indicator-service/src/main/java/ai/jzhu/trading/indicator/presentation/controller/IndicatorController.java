package ai.jzhu.trading.indicator.presentation.controller;

import ai.jzhu.trading.common.dto.indicator.IndicatorRequest;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import ai.jzhu.trading.indicator.application.usecase.CalculateIndicatorsUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final CalculateIndicatorsUseCase calculateIndicatorsUseCase;

    public IndicatorController(CalculateIndicatorsUseCase calculateIndicatorsUseCase) {
        this.calculateIndicatorsUseCase = calculateIndicatorsUseCase;
    }

    @PostMapping("/calculate")
    public IndicatorResponse calculate(@RequestBody IndicatorRequest request) {
        return calculateIndicatorsUseCase.execute(request);
    }
}
