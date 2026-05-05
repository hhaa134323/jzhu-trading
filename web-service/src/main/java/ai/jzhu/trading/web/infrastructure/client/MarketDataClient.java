package ai.jzhu.trading.web.infrastructure.client;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.web.domain.port.MarketDataPort;
import ai.jzhu.trading.web.presentation.exception.DownstreamServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Component
public class MarketDataClient implements MarketDataPort {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

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
            int statusCode = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            String message;
            if (body != null && !body.isBlank()) {
                // Try to extract message from downstream ErrorResponse JSON
                message = body;
                if (body.contains("\"message\"")) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                        String msg = root.path("message").asText(null);
                        if (msg != null && !msg.isBlank()) {
                            message = msg;
                        }
                    } catch (Exception ignored) {
                        // Use raw body as fallback
                    }
                }
            } else {
                message = "Market data service returned error: " + ex.getStatusCode();
            }
            log.warn("market-data-service returned error: status={}, body={}", statusCode, body);
            throw new DownstreamServiceUnavailableException(statusCode, message);
        } catch (WebClientRequestException ex) {
            log.error("Cannot connect to market-data-service: {}", ex.getMessage());
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to market-data-service. Please try again later.");
        } catch (RuntimeException ex) {
            log.error("Unexpected error when calling market-data-service: {}", ex.getMessage(), ex);
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling market-data-service.");
        }
    }
}
