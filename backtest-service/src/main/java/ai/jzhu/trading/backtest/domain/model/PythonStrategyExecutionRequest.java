package ai.jzhu.trading.backtest.domain.model;

import java.util.Map;

/**
 * Input to the Python strategy runner.
 *
 * @param code       The Python source code containing the entrypoint function.
 * @param entrypoint The function name to call (default: "on_bar").
 * @param ctx        The context passed to the entrypoint:
 *                   params, indicators, position, bar
 */
public record PythonStrategyExecutionRequest(
        String code,
        String entrypoint,
        Map<String, Object> ctx
) {

    public PythonStrategyExecutionRequest {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            entrypoint = "on_bar";
        }
        if (ctx == null) {
            ctx = Map.of();
        }
    }

    public PythonStrategyExecutionRequest(String code, Map<String, Object> ctx) {
        this(code, "on_bar", ctx);
    }
}