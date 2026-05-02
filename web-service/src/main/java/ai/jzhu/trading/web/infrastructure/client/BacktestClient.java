package ai.jzhu.trading.web.infrastructure.client;

import ai.jzhu.trading.common.dto.backtest.BacktestRequest;
import ai.jzhu.trading.common.dto.backtest.SimpleBacktestResponse;
import ai.jzhu.trading.common.dto.backtest.StrategyInfoResponse;
import ai.jzhu.trading.common.dto.template.CloneStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.CreateStrategyTemplateRequest;
import ai.jzhu.trading.common.dto.template.SaveStrategyTemplateVersionRequest;
import ai.jzhu.trading.common.dto.template.StrategyTemplateDetailResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateSummaryResponse;
import ai.jzhu.trading.common.dto.template.StrategyTemplateVersionResponse;
import ai.jzhu.trading.web.domain.port.BacktestPort;
import ai.jzhu.trading.web.presentation.exception.DownstreamServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

@Component
public class BacktestClient implements BacktestPort {

    private final WebClient webClient;
    private final String backtestUrl;

    public BacktestClient(WebClient webClient, @Value("${service.backtest.url}") String backtestUrl) {
        this.webClient = webClient;
        this.backtestUrl = backtestUrl;
    }

    @Override
    public SimpleBacktestResponse runBacktest(BacktestRequest request) {
        try {
            return webClient.post()
                    .uri(backtestUrl + "/api/backtest/run")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(SimpleBacktestResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            String message = ex.getResponseBodyAsString();
            if (message == null || message.isBlank()) {
                message = "Backtest service is temporarily unavailable: " + ex.getStatusCode();
            }
            throw new DownstreamServiceUnavailableException(ex.getStatusCode().value(), message);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public List<StrategyInfoResponse> getStrategies() {
        try {
            return webClient.get()
                    .uri(backtestUrl + "/api/backtest/strategies")
                    .retrieve()
                    .bodyToFlux(StrategyInfoResponse.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException ex) {
            String message = ex.getResponseBodyAsString();
            if (message == null || message.isBlank()) {
                message = "Backtest service is temporarily unavailable: " + ex.getStatusCode();
            }
            throw new DownstreamServiceUnavailableException(ex.getStatusCode().value(), message);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public List<StrategyTemplateSummaryResponse> getStrategyTemplates() {
        try {
            return webClient.get()
                    .uri(backtestUrl + "/api/backtest/strategy-templates")
                    .retrieve()
                    .bodyToFlux(StrategyTemplateSummaryResponse.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public StrategyTemplateDetailResponse createStrategyTemplate(CreateStrategyTemplateRequest request) {
        try {
            return webClient.post()
                    .uri(backtestUrl + "/api/backtest/strategy-templates")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(StrategyTemplateDetailResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public StrategyTemplateDetailResponse getStrategyTemplate(String templateId) {
        try {
            return webClient.get()
                    .uri(backtestUrl + "/api/backtest/strategy-templates/" + templateId)
                    .retrieve()
                    .bodyToMono(StrategyTemplateDetailResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public List<StrategyTemplateVersionResponse> getStrategyTemplateVersions(String templateId) {
        try {
            return webClient.get()
                    .uri(backtestUrl + "/api/backtest/strategy-templates/" + templateId + "/versions")
                    .retrieve()
                    .bodyToFlux(StrategyTemplateVersionResponse.class)
                    .collectList()
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public StrategyTemplateDetailResponse saveStrategyTemplateVersion(String templateId, SaveStrategyTemplateVersionRequest request) {
        try {
            return webClient.post()
                    .uri(backtestUrl + "/api/backtest/strategy-templates/" + templateId + "/versions")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(StrategyTemplateDetailResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    @Override
    public StrategyTemplateDetailResponse cloneStrategyTemplate(String templateId, CloneStrategyTemplateRequest request) {
        try {
            return webClient.post()
                    .uri(backtestUrl + "/api/backtest/strategy-templates/" + templateId + "/clone")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(StrategyTemplateDetailResponse.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw mapException(ex);
        } catch (WebClientRequestException ex) {
            throw new DownstreamServiceUnavailableException(503, "Cannot connect to backtest-service. Please try again later.");
        } catch (RuntimeException ex) {
            throw new DownstreamServiceUnavailableException(503, "Unexpected error when calling backtest-service.");
        }
    }

    private DownstreamServiceUnavailableException mapException(WebClientResponseException ex) {
        String message = ex.getResponseBodyAsString();
        if (message == null || message.isBlank()) {
            message = "Backtest service is temporarily unavailable: " + ex.getStatusCode();
        }
        return new DownstreamServiceUnavailableException(ex.getStatusCode().value(), message);
    }
}
