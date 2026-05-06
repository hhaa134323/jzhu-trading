package ai.jzhu.trading.marketdata.infrastructure.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.jzhu.trading.marketdata.domain.model.Kline;
import ai.jzhu.trading.marketdata.domain.port.MarketDataProvider;
import ai.jzhu.trading.marketdata.infrastructure.external.dto.FmpHistoricalResponse;
import ai.jzhu.trading.marketdata.presentation.exception.ExternalApiException;
import ai.jzhu.trading.marketdata.presentation.exception.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class FmpMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(FmpMarketDataProvider.class);

    private static final String TENCENT_API_BASE = "https://web.ifzq.gtimg.cn";
    private static final ObjectMapper TENCENT_OBJECT_MAPPER = new ObjectMapper();

    private final WebClient fmpWebClient;
    private final WebClient tencentWebClient;
    private final String apiKey;

    public FmpMarketDataProvider(
            @Qualifier("fmpWebClient") WebClient fmpWebClient,
            @Value("${fmp.api-key}") String apiKey
    ) {
        this.fmpWebClient = fmpWebClient;
        this.tencentWebClient = WebClient.builder().baseUrl(TENCENT_API_BASE).build();
        this.apiKey = apiKey;
    }

    @Override
    public List<Kline> fetchKlines(String symbol, String market, LocalDate startDate, LocalDate endDate) {
        String normalizedMarket = market.trim().toLowerCase(Locale.ROOT);
        if ("hk".equals(normalizedMarket) || "cn".equals(normalizedMarket)) {
            return fetchKlinesFromTencent(symbol, normalizedMarket, startDate, endDate);
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalApiException("FMP API key is missing. Please set FMP_API_KEY in .env");
        }

        String normalizedSymbol = normalizeSymbolForMarket(symbol, normalizedMarket);

        try {
            String responseBody = fmpWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/historical-price-eod/full")
                            .queryParam("symbol", normalizedSymbol)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                log.info("FMP returned empty historical data for symbol={}, market={}, normalizedSymbol={}",
                        symbol, market, normalizedSymbol);
                return List.of();
            }

            // Using /historical-price-eod/full which returns real OHLC (open/high/low/close/volume).
            // Skip any row missing required OHLC fields — never write flattened data.
            JsonNode root;
            try {
                root = TENCENT_OBJECT_MAPPER.readTree(responseBody);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new ExternalApiException("Failed to parse FMP response", ex);
            }
            if (!root.isArray()) {
                log.info("FMP returned non-array response for symbol={}, normalizedSymbol={}", symbol, normalizedSymbol);
                return List.of();
            }

            List<Kline> klines = new ArrayList<>();
            for (JsonNode item : root) {
                if (item == null || item.isNull()) continue;
                String dateStr = item.path("date").asText(null);
                if (dateStr == null) continue;
                LocalDate date = LocalDate.parse(dateStr);
                if (startDate != null && date.isBefore(startDate)) continue;
                if (endDate != null && date.isAfter(endDate)) continue;

                double open = item.has("open") ? item.path("open").asDouble() : Double.NaN;
                double high = item.has("high") ? item.path("high").asDouble() : Double.NaN;
                double low = item.has("low") ? item.path("low").asDouble() : Double.NaN;
                double close = item.has("close") ? item.path("close").asDouble() : Double.NaN;
                long volume = item.has("volume") ? item.path("volume").asLong() : 0L;

                if (Double.isNaN(open) || Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(close)) {
                    log.warn("FMP response missing OHLC fields for symbol={}, date={}, open={}, high={}, low={}, close={}. Skipping row.",
                            normalizedSymbol, dateStr, open, high, low, close);
                    continue;
                }

                klines.add(new Kline(date, open, high, low, close, volume));
            }

            klines.sort(Comparator.comparing(Kline::date));

            // Sanity check: if >95% of rows have open==close, the FMP endpoint is likely wrong
            if (!klines.isEmpty()) {
                long flatCount = klines.stream().filter(k -> k.open() == k.close()).count();
                double ratio = (double) flatCount / klines.size();
                if (ratio > 0.95) {
                    log.warn("Suspicious flat OHLC from FMP, possible wrong endpoint: symbol={}, totalRows={}, flatRows={}, sampleRatio={}",
                            normalizedSymbol, klines.size(), flatCount, ratio);
                }
            }

            return klines;
        } catch (WebClientResponseException.Forbidden ex) {
            throw new ExternalApiException("FMP API key is forbidden for this endpoint. Please verify plan permissions or key scope.", ex);
        } catch (WebClientResponseException.TooManyRequests ex) {
            throw new RateLimitException("FMP API rate limit reached. Please retry later.");
        } catch (WebClientResponseException.NotFound ex) {
            log.info("FMP symbol not found: symbol={}, market={}, normalizedSymbol={}", symbol, market, normalizedSymbol);
            return List.of();
        } catch (WebClientRequestException ex) {
            throw new ExternalApiException("Cannot connect to FMP API endpoint.", ex);
        } catch (WebClientResponseException ex) {
            throw new ExternalApiException("FMP API error: " + ex.getStatusCode(), ex);
        } catch (RuntimeException ex) {
            throw new ExternalApiException("Failed to fetch market data from FMP", ex);
        }
    }

    private String normalizeSymbolForMarket(String symbol, String market) {
        String trimmedSymbol = symbol.trim().toUpperCase();

        return switch (market) {
            case "us" -> trimmedSymbol;
            case "hk" -> normalizeHongKongSymbol(trimmedSymbol);
            case "cn" -> normalizeChinaASymbol(trimmedSymbol);
            default -> throw new IllegalArgumentException("Unsupported market: " + market + ". Supported values: us, hk, cn");
        };
    }

    private String normalizeHongKongSymbol(String symbol) {
        if (symbol.endsWith(".HK")) {
            return symbol;
        }

        if (symbol.matches("\\d{1,5}")) {
            return String.format("%05d.HK", Integer.parseInt(symbol));
        }

        return symbol + ".HK";
    }

    private String normalizeChinaASymbol(String symbol) {
        if (symbol.endsWith(".SS") || symbol.endsWith(".SZ")) {
            return symbol;
        }

        if (symbol.matches("\\d{6}")) {
            if (symbol.startsWith("6") || symbol.startsWith("9")) {
                return symbol + ".SS";
            }
            return symbol + ".SZ";
        }

        return symbol;
    }

    private List<Kline> fetchKlinesFromTencent(String symbol, String market, LocalDate startDate, LocalDate endDate) {
        String tencentSymbol = toTencentSymbol(symbol, market);

        try {
            String responseBody = tencentWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/appstock/app/kline/kline")
                            .queryParam("param", tencentSymbol + ",day,,,2000")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return List.of();
            }

            JsonNode root = TENCENT_OBJECT_MAPPER.readTree(responseBody);
            JsonNode rows = root.path("data").path(tencentSymbol).path("day");
            if (!rows.isArray() || rows.isEmpty()) {
                return List.of();
            }

            List<Kline> klines = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 6) {
                    continue;
                }

                LocalDate date = LocalDate.parse(row.get(0).asText());
                if (startDate != null && date.isBefore(startDate)) {
                    continue;
                }
                if (endDate != null && date.isAfter(endDate)) {
                    continue;
                }

                double open = row.get(1).asDouble();
                double close = row.get(2).asDouble();
                double high = row.get(3).asDouble();
                double low = row.get(4).asDouble();
                long volume = Math.round(row.get(5).asDouble());

                klines.add(new Kline(date, open, high, low, close, volume));
            }

            klines.sort(Comparator.comparing(Kline::date));
            return klines;
        } catch (WebClientRequestException ex) {
            throw new ExternalApiException("Cannot connect to Tencent market data API.", ex);
        } catch (WebClientResponseException ex) {
            throw new ExternalApiException("Tencent market data API error: " + ex.getStatusCode(), ex);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ExternalApiException("Failed to parse Tencent market data response", ex);
        } catch (RuntimeException ex) {
            throw new ExternalApiException("Failed to fetch market data from Tencent API", ex);
        }
    }

    private String toTencentSymbol(String symbol, String market) {
        String raw = symbol.trim().toUpperCase(Locale.ROOT);

        return switch (market) {
            case "hk" -> {
                String digits = raw.replace(".HK", "");
                if (!digits.matches("\\d{1,5}")) {
                    throw new IllegalArgumentException("Unsupported HK symbol format: " + symbol + ". Example: 00700");
                }
                yield "hk" + String.format("%05d", Integer.parseInt(digits));
            }
            case "cn" -> {
                if (raw.endsWith(".SS")) {
                    yield "sh" + raw.substring(0, raw.length() - 3);
                }
                if (raw.endsWith(".SZ")) {
                    yield "sz" + raw.substring(0, raw.length() - 3);
                }
                if (!raw.matches("\\d{6}")) {
                    throw new IllegalArgumentException("Unsupported CN symbol format: " + symbol + ". Example: 600519");
                }
                if (raw.startsWith("6") || raw.startsWith("9")) {
                    yield "sh" + raw;
                }
                yield "sz" + raw;
            }
            default -> throw new IllegalArgumentException("Unsupported market for Tencent source: " + market);
        };
    }
}
