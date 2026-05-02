package ai.jzhu.trading.backtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"ai.jzhu.trading.backtest", "ai.jzhu.strategy"})
public class BacktestServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BacktestServiceApplication.class, args);
    }
}
