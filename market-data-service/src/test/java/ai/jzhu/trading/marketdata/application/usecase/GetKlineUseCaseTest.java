package ai.jzhu.trading.marketdata.application.usecase;

import ai.jzhu.trading.marketdata.domain.model.Kline;
import ai.jzhu.trading.marketdata.domain.port.KlineRepository;
import ai.jzhu.trading.marketdata.domain.port.MarketDataProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GetKlineUseCaseTest {

    @Test
    public void whenDbStale_fetchesMissingAndSaves() {
        KlineRepository repo = mock(KlineRepository.class);
        MarketDataProvider provider = mock(MarketDataProvider.class);

        GetKlineUseCase uc = new GetKlineUseCase(repo, provider);

        String symbol = "TSLA";
        String market = "us";
        LocalDate dbMax = LocalDate.of(2024,5,1);
        LocalDate start = LocalDate.of(2024,5,1);
        LocalDate end = LocalDate.of(2024,5,3);

        Kline existing = new Kline(dbMax, 1,2,3,4,100);
        Kline missing1 = new Kline(dbMax.plusDays(1), 2,3,4,5,200);
        Kline missing2 = new Kline(dbMax.plusDays(2), 3,4,5,6,300);

        when(repo.findByRange(eq("kline_daily"), eq(symbol), eq(market), eq(start), eq(end)))
                .thenReturn(List.of(existing))
                .thenReturn(List.of(existing, missing1, missing2));

        when(repo.findLatestDate(eq("kline_daily"), eq(symbol), eq(market)))
                .thenReturn(Optional.of(dbMax));

        when(provider.fetchKlines(eq(symbol), eq(market), eq(dbMax.plusDays(1)), eq(end)))
                .thenReturn(List.of(missing1, missing2));

        List<?> resp = uc.execute(symbol, market, "daily", start, end);

        assertEquals(3, resp.size());

        verify(provider, times(1)).fetchKlines(eq(symbol), eq(market), eq(dbMax.plusDays(1)), eq(end));
        verify(repo, atLeastOnce()).saveAll(eq("kline_daily"), eq(symbol), eq(market), any());
    }
}
