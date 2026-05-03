package ai.jzhu.trading.marketdata.application.usecase;

import ai.jzhu.trading.common.dto.KlineResponse;
import ai.jzhu.trading.marketdata.domain.model.Kline;
import ai.jzhu.trading.marketdata.domain.port.KlineRepository;
import ai.jzhu.trading.marketdata.domain.port.MarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GetKlineUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetKlineUseCase.class);

    private final KlineRepository klineRepository;
    private final MarketDataProvider marketDataProvider;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public GetKlineUseCase(KlineRepository klineRepository, MarketDataProvider marketDataProvider) {
        this.klineRepository = klineRepository;
        this.marketDataProvider = marketDataProvider;
    }

    public List<KlineResponse> execute(
            String symbol,
            String market,
            String period,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String normalizedPeriod = period.toLowerCase();

        if ("daily".equals(normalizedPeriod)) {
            List<Kline> cachedDaily = klineRepository.findByRange("kline_daily", symbol, market, startDate, endDate);

            if (cachedDaily.isEmpty()) {
                log.info("Daily cache miss: fetching data from provider for symbol={}, market={}, period={}",
                        symbol, market, normalizedPeriod);
                List<Kline> apiDaily = marketDataProvider.fetchKlines(symbol, market, startDate, endDate);
                if (apiDaily.isEmpty()) {
                    return List.of();
                }
                klineRepository.saveAll("kline_daily", symbol, market, apiDaily);
                return toResponse(apiDaily);
            }

            log.info("Cache hit: read {} rows from DB for symbol={}, market={}, period={}",
                    cachedDaily.size(), symbol, market, normalizedPeriod);

            LocalDate targetEnd = endDate != null ? endDate : LocalDate.now(NEW_YORK);
            java.util.Optional<LocalDate> dbMaxOpt = klineRepository.findLatestDate("kline_daily", symbol, market);
            if (dbMaxOpt.isPresent()) {
                LocalDate dbMax = dbMaxOpt.get();
                if (dbMax.isBefore(targetEnd)) {
                    LocalDate fetchStart = dbMax.plusDays(1);
                    log.info("Stale cache: dbMax={} < targetEnd={} for symbol={}, market={}. Fetching missing range {}..{}",
                            dbMax, targetEnd, symbol, market, fetchStart, targetEnd);
                    List<Kline> missing = marketDataProvider.fetchKlines(symbol, market, fetchStart, targetEnd);
                    if (!missing.isEmpty()) {
                        klineRepository.saveAll("kline_daily", symbol, market, missing);
                    }
                    List<Kline> full = klineRepository.findByRange("kline_daily", symbol, market, startDate, endDate);
                    return toResponse(full);
                }
            }

            return toResponse(cachedDaily);
        }

        if (!"weekly".equals(normalizedPeriod) && !"monthly".equals(normalizedPeriod)) {
            throw new IllegalArgumentException("Unsupported period: " + period);
        }

        List<Kline> dailyRows = klineRepository.findByRange("kline_daily", symbol, market, startDate, endDate);
        if (dailyRows.isEmpty()) {
            log.info("Daily cache miss: fetching data from provider for symbol={}, market={}, period={}",
                    symbol, market, normalizedPeriod);
            dailyRows = marketDataProvider.fetchKlines(symbol, market, startDate, endDate);
            if (dailyRows.isEmpty()) {
                return List.of();
            }
            klineRepository.saveAll("kline_daily", symbol, market, dailyRows);
        } else {
            log.info("Daily cache hit: read {} rows from DB for symbol={}, market={}, period={}",
                    dailyRows.size(), symbol, market, normalizedPeriod);

            LocalDate targetEnd = endDate != null ? endDate : LocalDate.now(NEW_YORK);
            java.util.Optional<LocalDate> dbMaxOpt = klineRepository.findLatestDate("kline_daily", symbol, market);
            if (dbMaxOpt.isPresent()) {
                LocalDate dbMax = dbMaxOpt.get();
                if (dbMax.isBefore(targetEnd)) {
                    LocalDate fetchStart = dbMax.plusDays(1);
                    log.info("Stale daily rows for aggregation: dbMax={} < targetEnd={} for symbol={}, market={}. Fetching {}..{}",
                            dbMax, targetEnd, symbol, market, fetchStart, targetEnd);
                    List<Kline> missing = marketDataProvider.fetchKlines(symbol, market, fetchStart, targetEnd);
                    if (!missing.isEmpty()) {
                        klineRepository.saveAll("kline_daily", symbol, market, missing);
                    }
                    // refresh dailyRows used for aggregation
                    dailyRows = klineRepository.findByRange("kline_daily", symbol, market, startDate, endDate);
                }
            }
        }

        List<Kline> aggregated = aggregateKlines(dailyRows, normalizedPeriod);
        return toResponse(aggregated);
    }

    private List<Kline> aggregateKlines(List<Kline> dailyRows, String period) {
        Map<LocalDate, List<Kline>> grouped = new LinkedHashMap<>();
        for (Kline item : dailyRows) {
            LocalDate bucket = resolveBucketDate(item.date(), period);
            grouped.computeIfAbsent(bucket, k -> new ArrayList<>()).add(item);
        }

        List<Kline> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Kline>> entry : grouped.entrySet()) {
            List<Kline> bucketRows = entry.getValue();
            if (bucketRows.isEmpty()) {
                continue;
            }

            bucketRows.sort((a, b) -> a.date().compareTo(b.date()));
            Kline first = bucketRows.get(0);
            Kline last = bucketRows.get(bucketRows.size() - 1);

            double high = bucketRows.stream().mapToDouble(Kline::high).max().orElse(first.high());
            double low = bucketRows.stream().mapToDouble(Kline::low).min().orElse(first.low());
            long volume = bucketRows.stream().mapToLong(Kline::volume).sum();

            result.add(new Kline(
                    entry.getKey(),
                    first.open(),
                    high,
                    low,
                    last.close(),
                    volume
            ));
        }

        result.sort((a, b) -> a.date().compareTo(b.date()));
        return result;
    }

    private LocalDate resolveBucketDate(LocalDate date, String period) {
        return switch (period) {
            case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "monthly" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private String resolveTableName(String period) {
        return switch (period.toLowerCase()) {
            case "daily" -> "kline_daily";
            case "weekly" -> "kline_weekly";
            case "monthly" -> "kline_monthly";
            default -> throw new IllegalArgumentException("Unsupported period: " + period);
        };
    }

    private List<KlineResponse> toResponse(List<Kline> klines) {
        return klines.stream()
                .map(k -> new KlineResponse(
                        k.date().toString(),
                        k.open(),
                        k.high(),
                        k.low(),
                        k.close(),
                        k.volume()
                ))
                .toList();
    }
}
