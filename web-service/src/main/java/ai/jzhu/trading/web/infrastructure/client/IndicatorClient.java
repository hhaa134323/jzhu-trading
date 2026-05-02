package ai.jzhu.trading.web.infrastructure.client;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.common.dto.indicator.IndicatorRequest;
import ai.jzhu.trading.common.dto.indicator.IndicatorResponse;
import ai.jzhu.trading.web.domain.port.IndicatorPort;
import ai.jzhu.trading.web.presentation.exception.DownstreamServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Component
public class IndicatorClient implements IndicatorPort {

    private final WebClient webClient;
    private final String indicatorUrl;

    public IndicatorClient(WebClient webClient, @Value("${service.indicator.url}") String indicatorUrl) {
        this.webClient = webClient;
        this.indicatorUrl = indicatorUrl;
    }

    @Override
    public IndicatorResponse calculate(List<KlineResponse> klines, String symbol, String market, String period) {
        try {
            IndicatorRequest request = new IndicatorRequest(klines, symbol, market, period);
            return webClient.post()
                    .uri(indicatorUrl + "/api/indicators/calculate")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(IndicatorResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            String message = ex.getResponseBodyAsString();
            if (message == null || message.isBlank()) {
                message = "Indicator service is temporarily unavailable: " + ex.getStatusCode();
            }
            throw new DownstreamServiceUnavailableException(ex.getStatusCode().value(), message);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to indicator-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling indicator-service.");
        }
    }
}