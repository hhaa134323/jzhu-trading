package ai.jzhu.trading.backtest.infrastructure.client;

import ai.jzhu.trading.backtest.domain.port.MarketDataPort;
import ai.jzhu.trading.backtest.presentation.exception.DownstreamServiceUnavailableException;
import ai.jzhu.trading.common.dto.KlineResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Component
public class MarketDataClient implements MarketDataPort {

    private final WebClient webClient;
    private final String marketDataUrl;

    public MarketDataClient(WebClient webClient, @Value("${service.market-data.url}") String marketDataUrl) {
        this.webClient = webClient;
        this.marketDataUrl = marketDataUrl;
    }

    @Override
    public List<KlineResponse> getKline(String symbol, String market, String period, String startDate, String endDate) {
        try {
            return webClient.get()
                    .uri(marketDataUrl + "/api/market-data/kline", uriBuilder -> {
                        uriBuilder.queryParam("symbol", symbol)
                                .queryParam("market", market)
                                .queryParam("period", period);
                        if (startDate != null && !startDate.isBlank()) {
                            uriBuilder.queryParam("startDate", startDate);
                        }
                        if (endDate != null && !endDate.isBlank()) {
                            uriBuilder.queryParam("endDate", endDate);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToFlux(KlineResponse.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException ex) {
            String message = ex.getResponseBodyAsString();
            if (message == null || message.isBlank()) {
                message = "Market data service is temporarily unavailable: " + ex.getStatusCode();
            }
            throw new DownstreamServiceUnavailableException(ex.getStatusCode().value(), message);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to market-data-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling market-data-service.");
        }
    }
}
