package ai.jzhu.trading.marketdata.infrastructure.persistence;

import ai.jzhu.trading.marketdata.domain.model.Kline;
import ai.jzhu.trading.marketdata.domain.port.KlineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcKlineRepository implements KlineRepository {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final JdbcTemplate jdbcTemplate;

    public JdbcKlineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Kline> findByRange(String tableName, String symbol, String market, LocalDate startDate, LocalDate endDate) {
        StringBuilder sql = new StringBuilder("SELECT time, open, high, low, close, volume FROM ")
                .append(tableName)
                .append(" WHERE symbol = ? AND market = ?");

        List<Object> params = new ArrayList<>();
        params.add(symbol);
        params.add(market);

        if (startDate != null) {
            sql.append(" AND time >= ?");
            params.add(Timestamp.from(startDate.atStartOfDay(NEW_YORK).toInstant()));
        }
        if (endDate != null) {
            sql.append(" AND time < ?");
            params.add(Timestamp.from(endDate.plusDays(1).atStartOfDay(NEW_YORK).toInstant()));
        }

        sql.append(" ORDER BY time ASC");

        RowMapper<Kline> rowMapper = (rs, rowNum) -> new Kline(
                rs.getTimestamp("time").toInstant().atZone(NEW_YORK).toLocalDate(),
                rs.getDouble("open"),
                rs.getDouble("high"),
                rs.getDouble("low"),
                rs.getDouble("close"),
                rs.getLong("volume")
        );

        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    @Override
    public void saveAll(String tableName, String symbol, String market, List<Kline> klines) {
        if (klines == null || klines.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO " + tableName +
                " (time, symbol, market, open, high, low, close, volume) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

        jdbcTemplate.batchUpdate(sql, klines, klines.size(), (PreparedStatement ps, Kline kline) -> {
            ps.setTimestamp(1, Timestamp.from(kline.date().atStartOfDay(NEW_YORK).toInstant()));
            ps.setString(2, symbol);
            ps.setString(3, market);
            ps.setDouble(4, kline.open());
            ps.setDouble(5, kline.high());
            ps.setDouble(6, kline.low());
            ps.setDouble(7, kline.close());
            ps.setLong(8, kline.volume());
        });
    }
}
