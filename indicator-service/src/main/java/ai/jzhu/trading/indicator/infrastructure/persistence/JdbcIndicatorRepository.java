package ai.jzhu.trading.indicator.infrastructure.persistence;

import ai.jzhu.trading.indicator.domain.model.IndicatorValues;
import ai.jzhu.trading.indicator.domain.port.IndicatorRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcIndicatorRepository implements IndicatorRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIndicatorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IndicatorValues> findCachedDaily(String symbol, String market, List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return Optional.empty();
        }

        LocalDate start = dates.get(0);
        LocalDate end = dates.get(dates.size() - 1);

        Map<LocalDate, Double[]> maMap = queryMa(symbol, market, start, end);
        Map<LocalDate, Double[]> macdMap = queryMacd(symbol, market, start, end);
        Map<LocalDate, Double[]> rsiMap = queryRsi(symbol, market, start, end);
        Map<LocalDate, Double[]> bollMap = queryBoll(symbol, market, start, end);

        List<Double> ma5 = new ArrayList<>(dates.size());
        List<Double> ma10 = new ArrayList<>(dates.size());
        List<Double> ma20 = new ArrayList<>(dates.size());
        List<Double> ma30 = new ArrayList<>(dates.size());
        List<Double> ma60 = new ArrayList<>(dates.size());

        List<Double> dif = new ArrayList<>(dates.size());
        List<Double> dea = new ArrayList<>(dates.size());
        List<Double> macd = new ArrayList<>(dates.size());

        List<Double> rsi6 = new ArrayList<>(dates.size());
        List<Double> rsi12 = new ArrayList<>(dates.size());
        List<Double> rsi24 = new ArrayList<>(dates.size());

        List<Double> upper = new ArrayList<>(dates.size());
        List<Double> middle = new ArrayList<>(dates.size());
        List<Double> lower = new ArrayList<>(dates.size());

        for (LocalDate date : dates) {
            Double[] maRow = maMap.get(date);
            Double[] macdRow = macdMap.get(date);
            Double[] rsiRow = rsiMap.get(date);
            Double[] bollRow = bollMap.get(date);

            if (maRow == null || macdRow == null || rsiRow == null || bollRow == null) {
                return Optional.empty();
            }

            ma5.add(maRow[0]);
            ma10.add(maRow[1]);
            ma20.add(maRow[2]);
            ma30.add(maRow[3]);
            ma60.add(maRow[4]);

            dif.add(macdRow[0]);
            dea.add(macdRow[1]);
            macd.add(macdRow[2]);

            rsi6.add(rsiRow[0]);
            rsi12.add(rsiRow[1]);
            rsi24.add(rsiRow[2]);

            upper.add(bollRow[0]);
            middle.add(bollRow[1]);
            lower.add(bollRow[2]);
        }

        return Optional.of(new IndicatorValues(
                dif, dea, macd,
                ma5, ma10, ma20, ma30, ma60,
                rsi6, rsi12, rsi24,
                upper, middle, lower
        ));
    }

    @Override
    public void saveDaily(String symbol, String market, List<LocalDate> dates, IndicatorValues values) {
        if (dates == null || dates.isEmpty()) {
            return;
        }

        int size = dates.size();
        assertSize(size, values);

        batchInsertMa(symbol, market, dates, values);
        batchInsertMacd(symbol, market, dates, values);
        batchInsertRsi(symbol, market, dates, values);
        batchInsertBoll(symbol, market, dates, values);
    }

    private void assertSize(int expectedSize, IndicatorValues values) {
        if (values.ma5List().size() != expectedSize
                || values.macdList().size() != expectedSize
                || values.rsi6List().size() != expectedSize
                || values.upperList().size() != expectedSize) {
            throw new IllegalArgumentException("Indicator array length must equal kline length");
        }
    }

    private Map<LocalDate, Double[]> queryMa(String symbol, String market, LocalDate start, LocalDate end) {
        String sql = """
                SELECT time::date AS d, ma5, ma10, ma20, ma30, ma60
                FROM ma_daily
                WHERE symbol = ? AND market = ? AND time::date BETWEEN ? AND ?
                ORDER BY d ASC
                """;
        return jdbcTemplate.query(sql,
                ps -> {
                    ps.setString(1, symbol);
                    ps.setString(2, market);
                    ps.setObject(3, start);
                    ps.setObject(4, end);
                },
                rs -> {
                    Map<LocalDate, Double[]> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(
                                rs.getObject("d", LocalDate.class),
                                new Double[]{
                                        (Double) rs.getObject("ma5"),
                                        (Double) rs.getObject("ma10"),
                                        (Double) rs.getObject("ma20"),
                                        (Double) rs.getObject("ma30"),
                                        (Double) rs.getObject("ma60")
                                }
                        );
                    }
                    return map;
                });
    }

    private Map<LocalDate, Double[]> queryMacd(String symbol, String market, LocalDate start, LocalDate end) {
        String sql = """
                SELECT time::date AS d, dif, dea, macd_hist
                FROM macd_daily
                WHERE symbol = ? AND market = ? AND time::date BETWEEN ? AND ?
                ORDER BY d ASC
                """;
        return jdbcTemplate.query(sql,
                ps -> {
                    ps.setString(1, symbol);
                    ps.setString(2, market);
                    ps.setObject(3, start);
                    ps.setObject(4, end);
                },
                rs -> {
                    Map<LocalDate, Double[]> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(
                                rs.getObject("d", LocalDate.class),
                                new Double[]{
                                        (Double) rs.getObject("dif"),
                                        (Double) rs.getObject("dea"),
                                        (Double) rs.getObject("macd_hist")
                                }
                        );
                    }
                    return map;
                });
    }

    private Map<LocalDate, Double[]> queryRsi(String symbol, String market, LocalDate start, LocalDate end) {
        String sql = """
                SELECT time::date AS d, rsi6, rsi12, rsi24
                FROM rsi_daily
                WHERE symbol = ? AND market = ? AND time::date BETWEEN ? AND ?
                ORDER BY d ASC
                """;
        return jdbcTemplate.query(sql,
                ps -> {
                    ps.setString(1, symbol);
                    ps.setString(2, market);
                    ps.setObject(3, start);
                    ps.setObject(4, end);
                },
                rs -> {
                    Map<LocalDate, Double[]> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(
                                rs.getObject("d", LocalDate.class),
                                new Double[]{
                                        (Double) rs.getObject("rsi6"),
                                        (Double) rs.getObject("rsi12"),
                                        (Double) rs.getObject("rsi24")
                                }
                        );
                    }
                    return map;
                });
    }

    private Map<LocalDate, Double[]> queryBoll(String symbol, String market, LocalDate start, LocalDate end) {
        String sql = """
                SELECT time::date AS d, upper_band, middle_band, lower_band
                FROM boll_daily
                WHERE symbol = ? AND market = ? AND time::date BETWEEN ? AND ?
                ORDER BY d ASC
                """;
        return jdbcTemplate.query(sql,
                ps -> {
                    ps.setString(1, symbol);
                    ps.setString(2, market);
                    ps.setObject(3, start);
                    ps.setObject(4, end);
                },
                rs -> {
                    Map<LocalDate, Double[]> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(
                                rs.getObject("d", LocalDate.class),
                                new Double[]{
                                        (Double) rs.getObject("upper_band"),
                                        (Double) rs.getObject("middle_band"),
                                        (Double) rs.getObject("lower_band")
                                }
                        );
                    }
                    return map;
                });
    }

    private void batchInsertMa(String symbol, String market, List<LocalDate> dates, IndicatorValues values) {
        String sql = """
                INSERT INTO ma_daily(time, symbol, market, ma5, ma10, ma20, ma30, ma60)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, market, time) DO NOTHING
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LocalDate date = dates.get(i);
                ps.setTimestamp(1, toTimestamp(date));
                ps.setString(2, symbol);
                ps.setString(3, market);
                setNullableDouble(ps, 4, values.ma5List().get(i));
                setNullableDouble(ps, 5, values.ma10List().get(i));
                setNullableDouble(ps, 6, values.ma20List().get(i));
                setNullableDouble(ps, 7, values.ma30List().get(i));
                setNullableDouble(ps, 8, values.ma60List().get(i));
            }

            @Override
            public int getBatchSize() {
                return dates.size();
            }
        });
    }

    private void batchInsertMacd(String symbol, String market, List<LocalDate> dates, IndicatorValues values) {
        String sql = """
                INSERT INTO macd_daily(time, symbol, market, dif, dea, macd_hist)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, market, time) DO NOTHING
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LocalDate date = dates.get(i);
                ps.setTimestamp(1, toTimestamp(date));
                ps.setString(2, symbol);
                ps.setString(3, market);
                setNullableDouble(ps, 4, values.difList().get(i));
                setNullableDouble(ps, 5, values.deaList().get(i));
                setNullableDouble(ps, 6, values.macdList().get(i));
            }

            @Override
            public int getBatchSize() {
                return dates.size();
            }
        });
    }

    private void batchInsertRsi(String symbol, String market, List<LocalDate> dates, IndicatorValues values) {
        String sql = """
                INSERT INTO rsi_daily(time, symbol, market, rsi6, rsi12, rsi24)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, market, time) DO NOTHING
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LocalDate date = dates.get(i);
                ps.setTimestamp(1, toTimestamp(date));
                ps.setString(2, symbol);
                ps.setString(3, market);
                setNullableDouble(ps, 4, values.rsi6List().get(i));
                setNullableDouble(ps, 5, values.rsi12List().get(i));
                setNullableDouble(ps, 6, values.rsi24List().get(i));
            }

            @Override
            public int getBatchSize() {
                return dates.size();
            }
        });
    }

    private void batchInsertBoll(String symbol, String market, List<LocalDate> dates, IndicatorValues values) {
        String sql = """
                INSERT INTO boll_daily(time, symbol, market, upper_band, middle_band, lower_band)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, market, time) DO NOTHING
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                LocalDate date = dates.get(i);
                ps.setTimestamp(1, toTimestamp(date));
                ps.setString(2, symbol);
                ps.setString(3, market);
                setNullableDouble(ps, 4, values.upperList().get(i));
                setNullableDouble(ps, 5, values.middleList().get(i));
                setNullableDouble(ps, 6, values.lowerList().get(i));
            }

            @Override
            public int getBatchSize() {
                return dates.size();
            }
        });
    }

    private Timestamp toTimestamp(LocalDate date) {
        return Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setObject(index, null);
            return;
        }
        ps.setDouble(index, value);
    }
}
